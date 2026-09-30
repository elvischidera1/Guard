package com.statsig.androidsdk.sql

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class ConcurrencyTest {
    @Test
    fun one_database_serves_many_threads() {
        val db = newDb()
        db.startSession()
        db.run(
            "save_values",
            mapOf(
                "payload" to """{"feature_gates":{"g":{"value":true,"rule_id":"r"}},"has_updates":true,"time":1,"hash_used":"none"}""",
                "user" to """{"userID":"u1"}""", "scoped_key" to "u1:client-key"
            )
        )
        val pool = Executors.newFixedThreadPool(8)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        repeat(8) { t ->
            pool.submit {
                try {
                    repeat(500) { i ->
                        check(db.one("get_value", mapOf("kind" to "gate", "name" to "g"))!!["value"] == "true")
                        db.run("count_non_exposed", mapOf("name" to "t$t"))
                        db.run("log_event", mapOf("event_name" to "e", "value" to null, "metadata" to """{"i":$i}""", "statsig_metadata" to null))
                        if (i % 100 == 0) db.run("take_batch", mapOf("metadata" to "{}", "logging_enabled" to true))
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    failures.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)
        assertEquals(0, failures.get())
    }
}
