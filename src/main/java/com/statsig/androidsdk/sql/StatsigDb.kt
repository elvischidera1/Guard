package com.statsig.androidsdk.sql

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

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
 *
 * A result row may ask for a block to run first: its `_then` column names a block that is run
 * with the same parameters, after which the block is run once more.
 */
internal class StatsigDb(
    private val driver: SqlDriver,
    private val script: SqlScript = SqlScript.default
) {
    companion object {
        private const val DEFER_LIMIT = 50
        private const val CACHE_LIMIT = 16384
        private const val RAN = -1

        /**
         * A map's hash for the cache keys. Map.hashCode() sums key ^ value per entry, which
         * collides a lot for parameter maps that differ in correlated strings (name, rule id);
         * each entry is mixed first here. Order-independent, like map equality.
         */
        private fun contentHash(map: Map<String, Any?>): Int {
            if (map is Params) return map.contentHash
            var h = 0
            for ((k, v) in map) {
                var x = k.hashCode() * 0x9E3779B1.toInt() + (v?.hashCode() ?: 0)
                x = (x xor (x ushr 16)) * 0x85EBCA6B.toInt()
                x = (x xor (x ushr 13)) * 0xC2B2AE35.toInt()
                h += x xor (x ushr 16)
            }
            return h
        }
    }

    private class Key(val block: String, val params: Map<String, Any?>, val tag: Class<*>?) {
        private val hash =
            (block.hashCode() * 31 + contentHash(params)) * 31 + System.identityHashCode(tag)
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && other.hash == hash &&
            other.block == block && other.tag == tag &&
            (other.params === params || other.params == params)
    }

    /**
     * Parameters that are passed many times: the map's hash is computed once (maps are hashed
     * and compared for the cache, coalescing and @quiet).
     */
    class Params(private val map: Map<String, Any?>) : Map<String, Any?> by map {
        private val hash = map.hashCode()
        internal val contentHash = contentHash(map)

        /** The last cached result (or @quiet state) for these parameters: skips the maps. */
        @Volatile
        internal var slot: Slot? = null
        override fun hashCode() = hash
        override fun equals(other: Any?) = other === this || map == other
        override fun toString() = map.toString()
    }

    /** [Params] per name, built once (bounded): for call sites that pass the same shape often. */
    class ParamsMemo(private val make: (String) -> Map<String, Any?>) {
        private val memo = ConcurrentHashMap<String, Params>()

        operator fun get(name: String): Params = memo[name] ?: Params(make(name)).also {
            if (memo.size >= CACHE_LIMIT) memo.clear()
            memo[name] = it
        }
    }

    /**
     * A queued call: its parameters (plus time/repeat), and its key if coalesced or quiet.
     * [repeat] counts merged calls until the call runs, then reads [RAN].
     */
    private class Deferred(
        val block: SqlScript.Block,
        val key: Key?,
        val params: MutableMap<String, Any?>
    ) {
        val repeat = AtomicInteger(1)

        /** Merges one more identical call; false if this call already ran. */
        fun merge(): Boolean {
            while (true) {
                val n = repeat.get()
                if (n == RAN) return false
                if (repeat.compareAndSet(n, n + 1)) return true
            }
        }
    }

    /** Cached for a `@quiet` block: identical calls before [until] change nothing. */
    private class Quiet(val until: Long)

    /** A cached value for (block, tag), valid while the block's read domains are at [gens]. */
    internal class Slot(
        val block: SqlScript.Block,
        val tag: Class<*>?,
        val gens: LongArray,
        val value: Any
    )

    /** Per domain, bumped whenever its cached results are dropped. */
    private val generations = AtomicLongArray(script.domains.size)

    private fun gens(block: SqlScript.Block) =
        LongArray(block.readIds.size) { generations.get(block.readIds[it]) }

    private fun Slot.valid(block: SqlScript.Block, tag: Class<*>?): Boolean {
        if (this.block !== block || this.tag !== tag) return false
        val ids = block.readIds
        for (i in ids.indices) if (generations.get(ids[i]) != gens[i]) return false
        return true
    }

    /** Cached results by the data they were read from. */
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<Key, Any>>()

    /** Queued calls in call order, and the coalescable ones by (block, params). */
    private val deferred = ArrayList<Deferred>()
    private val deferredBlocks = HashSet<SqlScript.Block>()
    private val coalesced = ConcurrentHashMap<Key, Deferred>()

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
        if (block.cache && params is Params) {
            params.slot?.let { if (it.valid(block, tag)) return it.value as T }
        }
        val key = if (block.cache) Key(name, params, tag) else null
        if (key != null) {
            for (domain in block.readList) cache[domain]?.get(key)?.let { return it as T }
        }
        var flush = false
        val result = synchronized(this) {
            check(!closed) { "Statsig database is closed" }
            if (deferred.isNotEmpty() && dependsOnDeferred(block)) flush = drain()
            val gens = if (key != null) gens(block) else null
            var rows = execute(block, params)
            val then = rows.firstOrNull()?.get("_then") as String?
            if (then != null) {
                execute(script.block(then), params)
                rows = execute(block, params)
            }
            val cacheable = key != null && rows.firstOrNull()?.get("_cacheable").let {
                it == null || it != 0L
            }
            if (cacheable) {
                val value = transform(rows)
                for (domain in block.readList) put(domain, key!!, value)
                if (params is Params) params.slot = Slot(block, tag, gens!!, value)
                value
            } else {
                invalidate(block)
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
        if (block.quietMs != null && params is Params) {
            val slot = params.slot
            if (slot != null && slot.valid(block, Quiet::class.java) &&
                System.currentTimeMillis() < (slot.value as Quiet).until
            ) {
                return
            }
        }
        if (key != null && block.quietMs != null) {
            val now = System.currentTimeMillis()
            for (domain in block.readList) {
                val quiet = cache[domain]?.get(key) as Quiet?
                if (quiet != null && now < quiet.until) return
            }
        }
        // An identical queued call absorbs this one without the lock.
        if (block.coalesce && coalesced[key!!]?.merge() == true) return
        val flush = synchronized(this) {
            check(!closed) { "Statsig database is closed" }
            val existing = if (block.coalesce) coalesced[key!!] else null
            if (existing == null || !existing.merge()) {
                val call = HashMap<String, Any?>(params.size + 2)
                call.putAll(params)
                call["time"] = System.currentTimeMillis()
                val entry = Deferred(block, key, call)
                deferred.add(entry)
                deferredBlocks.add(block)
                if (block.coalesce) coalesced[key!!] = entry
            }
            deferred.size >= DEFER_LIMIT && drain()
        }
        if (flush) onShouldFlush?.invoke()
    }

    /** Whether [block] reads what queued calls write, or writes what they read or write. */
    private fun dependsOnDeferred(block: SqlScript.Block): Boolean = deferredBlocks.any { d ->
        block.reads.any { it in d.writes } || block.writes.any { it in d.reads || it in d.writes }
    }

    /**
     * Runs the queued calls in order, in one transaction: a block's first statement once per call,
     * its other statements once after each run of consecutive calls of the block (with the last
     * call's parameters). Returns whether any reported `should_flush`. A failing call does not
     * stop the others (SQLite undoes just the failed statement); the first failure is rethrown
     * once the rest are committed.
     */
    private fun drain(): Boolean {
        val calls = ArrayList(deferred)
        deferred.clear()
        deferredBlocks.clear()
        coalesced.clear()
        val quiet = ArrayList<Deferred>()
        var failure: Throwable? = null
        var flush = false
        driver.beginTransaction()
        try {
            for ((i, call) in calls.withIndex()) {
                call.params["repeat"] = call.repeat.getAndSet(RAN)
                val statements = call.block.statements
                try {
                    val changed = update(statements[0], call.params)
                    if (changed > 0 && call.block.quietMs != null) quiet.add(call)
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
                if (i + 1 < calls.size && calls[i + 1].block === call.block) continue
                for (statement in statements.subList(1, statements.size)) {
                    if (statement.returnsRows) {
                        val row = exec(statement, call.params)?.firstOrNull()
                        if (row?.get("should_flush") == 1L) flush = true
                    } else {
                        update(statement, call.params)
                    }
                }
            }
            driver.commit()
        } catch (e: Throwable) {
            runCatching { driver.rollback() }.exceptionOrNull()?.let { e.addSuppressed(it) }
            throw e
        }
        for (block in calls.mapTo(HashSet()) { it.block }) invalidate(block)
        for (call in quiet) {
            val until = Quiet(call.params["time"] as Long + call.block.quietMs!!)
            for (domain in call.block.readList) put(domain, call.key!!, until)
            (call.key!!.params as? Params)?.slot =
                Slot(call.block, Quiet::class.java, gens(call.block), until)
        }
        failure?.let { throw it }
        return flush
    }

    private fun put(domain: String, key: Key, value: Any) {
        val entries = cache.getOrPut(domain) { ConcurrentHashMap() }
        if (entries.size >= CACHE_LIMIT) entries.clear()
        entries[key] = value
    }

    private fun invalidate(block: SqlScript.Block) {
        for (id in block.writeIds) generations.incrementAndGet(id)
        for (domain in block.writes) cache[domain]?.clear()
    }

    /** Blocks named by `@needs` that already ran on this connection. */
    private val prepared = HashSet<String>()

    private fun execute(block: SqlScript.Block, params: Map<String, Any?>): List<Row> {
        for (need in block.needs) if (prepared.add(need)) execute(script.block(need), emptyMap())
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
