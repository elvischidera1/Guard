package com.statsig.androidsdk.sql

import java.io.Closeable

/** One result row: column name -> Long, Double, String, ByteArray or null. */
internal typealias Row = Map<String, Any?>

/**
 * The only thing the SDK needs from a platform's SQLite binding. Implementations are used from one
 * thread at a time (StatsigDb serializes access). Arguments are Long, Double, String or null.
 */
internal interface SqlDriver : Closeable {
    fun execute(sql: String, args: List<Any?>)

    fun query(sql: String, args: List<Any?>): List<Row>

    fun beginTransaction()

    fun commit()

    fun rollback()
}
