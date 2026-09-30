package com.statsig.androidsdk.sql

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Connections handed back by closed clients and reused by the next one. */
class PoolTest {
    private fun driverOf(db: StatsigDb): SqlDriver =
        StatsigDb::class.java.getDeclaredField("driver").apply { isAccessible = true }.get(db) as SqlDriver

    private fun tempTables(db: StatsigDb): List<String> {
        val rows = driverOf(db).query("SELECT name FROM sqlite_temp_schema WHERE type = 'table'", emptyList())
        return rows.map { it["name"] as String }.filter { it != "hash_memo" && it != "djb2_pow" }
    }

    @Test
    fun reset_clears_every_per_client_table() {
        val cleared = SqlScript.default.block("reset").statements
            .map { Regex("DELETE FROM (\\w+)").find(it.sql)!!.groupValues[1] }.toSet()
        val db = newDb()
        db.run("evaluator_views")
        db.run("sha256_schema")
        assertEquals(emptySet<String>(), tempTables(db).toSet() - cleared)
    }

    @Test
    fun a_closed_client_hands_its_connection_to_the_next_one_on_the_same_database() {
        val path = File(Files.createTempDirectory("statsig-sql").toFile(), "statsig.db").path
        val key = Any()
        val first = StatsigDb.open(key) { JdbcSqlDriver(path) }
        first.startSession()
        first.run("save_values", mapOf("payload" to """{"feature_gates":{},"dynamic_configs":{},"layer_configs":{},"has_updates":true,"time":1}""", "user" to """{"userID":"u1"}""", "scoped_key" to "u1:client-key"))
        first.run("log_event", mapOf("event_name" to "e", "value" to null, "metadata" to null, "statsig_metadata" to null))
        first.drainDeferred()
        val driver = driverOf(first)
        first.close()

        val second = StatsigDb.open(key) { error("the connection should be reused") }
        assertSame(driver, driverOf(second))
        for (table in tempTables(second)) assertEquals(table, 0L, second.scalar("SELECT count(*) FROM $table"))
        // durable state stays
        assertEquals(1L, second.scalar("SELECT count(*) FROM cached_values"))
        second.startSession()
        assertEquals(1L, second.scalar("SELECT count(*) FROM session"))
        second.close()

        // another database gets its own connection
        val other = StatsigDb.open(Any()) { JdbcSqlDriver(path) }
        assertNotSame(driver, driverOf(other))
        other.close()
    }
}
