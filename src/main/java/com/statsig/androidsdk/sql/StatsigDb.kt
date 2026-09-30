package com.statsig.androidsdk.sql

import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the named SQL blocks for one client. A block is atomic (a transaction when it writes more
 * than once); its result is the rows of its last row-returning statement. Access is serialized:
 * the SDK is called from many threads and a SQLite connection must not be.
 *
 * The block directives (see [SqlScript.Block]) let this class avoid SQLite on hot paths without
 * knowing anything about what the blocks do:
 * - results of `@cache` blocks are kept until a block that writes what they read runs;
 * - calls to `@defer` blocks are queued (identical `@coalesce` calls merged) and run in bulk when
 *   [DEFER_LIMIT] calls are waiting, or before any block that depends on them;
 * - calls to `@quiet` blocks that are known to change nothing are skipped.
 */
internal class StatsigDb(
    private val driver: SqlDriver,
    private val script: SqlScript = SqlScript.default
) {
    private companion object {
        const val DEFER_LIMIT = 50
        const val CACHE_LIMIT = 16384
    }

    private class Key(val block: String, val params: Map<String, Any?>, val tag: Class<*>?) {
        private val hash = (block.hashCode() * 31 + params.hashCode()) * 31 + tag.hashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && other.hash == hash &&
            other.block == block && other.tag == tag && other.params == params
    }

    private class Deferred(val block: SqlScript.Block, val params: MutableMap<String, Any?>)

    /** Cached for a `@quiet` block: identical calls before [until] change nothing. */
    private class Quiet(val until: Long)

    /** Cached results by the data they were read from. */
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<Key, Any>>()

    /** Queued calls in call order, and the coalescable ones by (block, params). */
    private val deferred = ArrayList<Deferred>()
    private val coalesced = HashMap<Key, Deferred>()

    /** Called when a bulk run of deferred blocks reports `should_flush`. */
    @Volatile
    var onShouldFlush: (() -> Unit)? = null

    @Volatile
    private var closed = false

    init {
        run("connect")
        run("schema")
    }

    fun run(block: String, params: Map<String, Any?> = emptyMap()): List<Row> =
        read(block, params, null) { it }

    fun one(block: String, params: Map<String, Any?> = emptyMap()): Row? =
        run(block, params).firstOrNull()

    /**
     * Runs [block] and returns `transform(rows)`. For a `@cache` block the transformed value is
     * kept (per call site: [transform]'s class) and returned until what the block reads changes.
     */
    fun <T : Any> read(
        block: String,
        params: Map<String, Any?> = emptyMap(),
        transform: (List<Row>) -> T
    ): T = read(block, params, transform.javaClass, transform)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> read(
        name: String,
        params: Map<String, Any?>,
        tag: Class<*>?,
        transform: (List<Row>) -> T
    ): T {
        val block = script.block(name)
        if (block.defer) {
            defer(block, params)
            return transform(emptyList())
        }
        val key = if (block.cache) Key(name, params, tag) else null
        if (key != null) {
            for (domain in block.reads) cache[domain]?.get(key)?.let { return it as T }
        }
        var flush = false
        val result = synchronized(this) {
            check(!closed) { "Statsig database is closed" }
            if (deferred.isNotEmpty() && dependsOnDeferred(block)) flush = drain()
            val rows = execute(block, params)
            val cacheable = key != null && rows.firstOrNull()?.get("_cacheable").let {
                it == null || it != 0L
            }
            if (cacheable) {
                val value = transform(rows)
                for (domain in block.reads) put(domain, key!!, value)
                value
            } else {
                invalidate(block.writes)
                transform(rows)
            }
        }
        if (flush) onShouldFlush?.invoke()
        return result
    }

    /** Runs every queued call now. */
    fun drainDeferred() {
        val flush = synchronized(this) { !closed && deferred.isNotEmpty() && drain() }
        if (flush) onShouldFlush?.invoke()
    }

    fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            driver.close()
        }
    }

    private fun defer(block: SqlScript.Block, params: Map<String, Any?>) {
        val key = if (block.coalesce || block.quietMs != null) {
            Key(block.name, params, Quiet::class.java)
        } else {
            null
        }
        if (key != null && block.quietMs != null) {
            val now = System.currentTimeMillis()
            for (domain in block.reads) {
                val quiet = cache[domain]?.get(key) as Quiet?
                if (quiet != null && now < quiet.until) return
            }
        }
        val flush = synchronized(this) {
            check(!closed) { "Statsig database is closed" }
            val existing = if (block.coalesce) coalesced[key!!] else null
            if (existing != null) {
                existing.params["repeat"] = (existing.params["repeat"] as Int) + 1
            } else {
                val call = HashMap<String, Any?>(params.size + 2)
                call.putAll(params)
                call["time"] = System.currentTimeMillis()
                call["repeat"] = 1
                val entry = Deferred(block, call)
                deferred.add(entry)
                if (block.coalesce) coalesced[key!!] = entry
            }
            deferred.size >= DEFER_LIMIT && drain()
        }
        if (flush) onShouldFlush?.invoke()
    }

    /** Whether [block] reads what queued calls write, or writes what they read or write. */
    private fun dependsOnDeferred(block: SqlScript.Block): Boolean = deferred.any { d ->
        block.reads.any { it in d.block.writes } ||
            block.writes.any { it in d.block.reads || it in d.block.writes }
    }

    /**
     * Runs the queued calls in order, in one transaction. The row-returning statements of a block
     * run once, after its last queued call. Returns whether any reported `should_flush`.
     * A failing call does not stop the others (SQLite undoes just the failed statement); the
     * first failure is rethrown once the rest are committed.
     */
    private fun drain(): Boolean {
        val calls = ArrayList(deferred)
        deferred.clear()
        coalesced.clear()
        val last = HashMap<SqlScript.Block, Deferred>()
        val quiet = ArrayList<Deferred>()
        var failure: Throwable? = null
        driver.beginTransaction()
        try {
            for (call in calls) {
                try {
                    var changed = 0
                    for (statement in call.block.statements) {
                        if (!statement.returnsRows) changed += update(statement, call.params)
                    }
                    if (changed > 0 && call.block.quietMs != null) quiet.add(call)
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
                last[call.block] = call
            }
            var flush = false
            for ((block, call) in last) {
                for (statement in block.statements) {
                    if (statement.returnsRows &&
                        exec(statement, call.params)?.firstOrNull()?.get("should_flush") == 1L
                    ) {
                        flush = true
                    }
                }
            }
            driver.commit()
            for (block in last.keys) invalidate(block.writes)
            for (call in quiet) {
                val block = call.block
                val params = call.params.filterKeys { it != "time" && it != "repeat" }
                val until = Quiet(call.params["time"] as Long + block.quietMs!!)
                for (domain in block.reads) {
                    put(
                        domain,
                        Key(block.name, params, Quiet::class.java),
                        until
                    )
                }
            }
            failure?.let { throw it }
            return flush
        } catch (e: Throwable) {
            if (e !== failure) {
                runCatching { driver.rollback() }.exceptionOrNull()?.let { e.addSuppressed(it) }
            }
            throw e
        }
    }

    private fun put(domain: String, key: Key, value: Any) {
        val entries = cache.getOrPut(domain) { ConcurrentHashMap() }
        if (entries.size >= CACHE_LIMIT) entries.clear()
        entries[key] = value
    }

    private fun invalidate(domains: Set<String>) {
        for (domain in domains) cache[domain]?.clear()
    }

    private fun execute(block: SqlScript.Block, params: Map<String, Any?>): List<Row> {
        val statements = block.statements
        // A block with at most one write is atomic by itself; only others need a transaction.
        if (statements.count { !it.returnsRows } <= 1) {
            var result: List<Row> = emptyList()
            for (statement in statements) exec(statement, params)?.let { result = it }
            return result
        }
        driver.beginTransaction()
        try {
            var result: List<Row> = emptyList()
            for (statement in statements) exec(statement, params)?.let { result = it }
            driver.commit()
            return result
        } catch (e: Throwable) {
            runCatching { driver.rollback() }.exceptionOrNull()?.let { e.addSuppressed(it) }
            throw e
        }
    }

    private fun exec(statement: SqlScript.Statement, params: Map<String, Any?>): List<Row>? =
        if (statement.returnsRows) {
            driver.query(statement.sql, args(statement, params))
        } else {
            driver.execute(statement.sql, args(statement, params))
            null
        }

    private fun update(statement: SqlScript.Statement, params: Map<String, Any?>): Int =
        driver.execute(statement.sql, args(statement, params))

    private fun args(statement: SqlScript.Statement, params: Map<String, Any?>): List<Any?> =
        statement.params.map { name ->
            when (val value = params[name]) {
                is Boolean -> if (value) 1L else 0L
                is Int -> value.toLong()
                is Float -> value.toDouble()
                else -> value
            }
        }
}
