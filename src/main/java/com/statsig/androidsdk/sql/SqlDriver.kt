package com.statsig.androidsdk.sql

import java.io.Closeable

/** One result row: column name -> Long, Double, String, ByteArray or null. */
typealias Row = Map<String, Any?>

/**
 * The only thing the SDK needs from a platform's SQLite binding (SQLite 3.38+ with its built-in
 * JSON functions). Implementations are used from one thread at a time and must keep a single
 * connection open, since per-client state lives in TEMP tables. Arguments are Long, Double,
 * String or null. See [com.statsig.androidsdk.StatsigClient.sqlDriverFactory].
 */
interface SqlDriver : Closeable {
    fun execute(sql: String, args: List<Any?>)

    fun query(sql: String, args: List<Any?>): List<Row>

    fun beginTransaction()

    fun commit()

    fun rollback()
}
