package com.statsig.androidsdk.sql

/**
 * Runs the named SQL blocks for one client. A block is atomic (a transaction when it writes more
 * than once); its result is the rows of its last row-returning statement. Access is serialized: the SDK is called from many
 * threads and a SQLite connection must not be.
 */
internal class StatsigDb(
    private val driver: SqlDriver,
    private val script: SqlScript = SqlScript.default
) {

    init {
        run("connect")
        run("schema")
    }

    @Volatile
    private var closed = false

    fun run(block: String, params: Map<String, Any?> = emptyMap()): List<Row> {
        val statements = script.block(block)
        synchronized(this) {
            check(!closed) { "Statsig database is closed" }
            // A block with at most one write is atomic by itself; only others need a transaction.
            if (statements.count { !it.returnsRows } <= 1) {
                var result: List<Row> = emptyList()
                for (statement in statements) exec(statement, params)?.let { result = it }
                return result
            }
            driver.beginTransaction()
            try {
                var result: List<Row> = emptyList()
                for (statement in statements) {
                    exec(statement, params)?.let { result = it }
                }
                driver.commit()
                return result
            } catch (e: Throwable) {
                runCatching { driver.rollback() }.exceptionOrNull()?.let { e.addSuppressed(it) }
                throw e
            }
        }
    }

    fun one(block: String, params: Map<String, Any?> = emptyMap()): Row? =
        run(block, params).firstOrNull()

    fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            driver.close()
        }
    }

    private fun exec(statement: SqlScript.Statement, params: Map<String, Any?>): List<Row>? {
        val args = statement.params.map { name ->
            when (val value = params[name]) {
                is Boolean -> if (value) 1L else 0L
                is Int -> value.toLong()
                is Float -> value.toDouble()
                else -> value
            }
        }
        return if (statement.returnsRows) {
            driver.query(statement.sql, args)
        } else {
            driver.execute(statement.sql, args)
            null
        }
    }
}
