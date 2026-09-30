package com.statsig.androidsdk.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** The host-side cache and deferred calls StatsigDb derives from the block directives. */
class DeferAndCacheTest {
    private val payload =
        """{"feature_gates":{"g":{"value":true,"rule_id":"r"}},"dynamic_configs":{
           "e":{"value":{"v":1},"rule_id":"r","is_experiment_active":true,"is_user_in_experiment":true}},
           "has_updates":true,"time":1,"hash_used":"none"}"""

    private fun db() = newDb().apply {
        startSession()
        run("save_values", mapOf("payload" to payload, "user" to """{"userID":"u1"}""", "scoped_key" to "u1:client-key"))
    }

    @Test
    fun cached_reads_are_reused_until_what_they_read_changes() {
        val db = db()
        val read = { db.read("get_value", mapOf("kind" to "gate", "name" to "g")) { Any() } }
        val first = read()
        assertSame(first, read())
        db.run("override_set", mapOf("kind" to "gate", "name" to "g", "value" to "false"))
        val afterOverride = read()
        assertNotSame(first, afterOverride)
        // event writes do not touch values
        db.run("log_event", mapOf("event_name" to "e", "value" to null, "metadata" to null, "statsig_metadata" to null))
        db.drainDeferred()
        assertSame(afterOverride, read())
    }

    @Test
    fun a_sticky_read_that_writes_is_not_cached_but_its_repeats_are() {
        val db = db()
        val read = { db.read("get_experiment", mapOf("kind" to "config", "name" to "e", "keep" to true)) { Any() } }
        val first = read() // keeps the value: a write
        val second = read() // nothing left to write: cacheable
        assertNotSame(first, second)
        assertSame(second, read())
        assertEquals(1L, db.scalar("SELECT count(*) FROM sticky_value"))
    }

    @Test
    fun identical_deferred_calls_are_coalesced_and_counted() {
        val db = db()
        repeat(5) { db.run("count_non_exposed", mapOf("name" to "a")) }
        db.run("count_non_exposed", mapOf("name" to "b"))
        db.drainDeferred()
        assertEquals(5L, db.scalar("SELECT n FROM non_exposed WHERE name = 'a'"))
        assertEquals(1L, db.scalar("SELECT n FROM non_exposed WHERE name = 'b'"))
    }

    @Test
    fun reading_events_runs_queued_calls_first_in_order() {
        val db = db()
        for (i in 1..3) db.run("log_event", mapOf("event_name" to "e$i", "value" to null, "metadata" to null, "statsig_metadata" to null))
        val body = db.one("take_batch", mapOf("metadata" to "{}", "logging_enabled" to true))!!["body"] as String
        assertEquals(listOf("e1", "e2", "e3"), Regex("\"eventName\":\"(e\\d)\"").findAll(body).map { it.groupValues[1] }.toList())
    }

    @Test
    fun quiet_calls_skip_sqlite_until_what_they_read_changes() {
        val db = db()
        val exposure = mapOf("name" to "g", "value" to true, "rule_id" to "r", "secondary" to "[]",
            "reason" to "Network:Recognized", "lcut" to 1L, "received_at" to 2L, "manual" to false)
        db.run("log_gate_exposure", exposure)
        db.drainDeferred()
        assertEquals(1L, db.scalar("SELECT count(*) FROM event_queue"))
        // Forgetting the exposure in SQL shows whether later calls reach SQLite at all.
        db.exec("DELETE FROM exposure_seen")
        db.run("log_gate_exposure", exposure)
        db.drainDeferred()
        assertEquals(1L, db.scalar("SELECT count(*) FROM event_queue"))
        // A different call is not skipped.
        db.run("log_gate_exposure", exposure + ("manual" to true))
        db.drainDeferred()
        assertEquals(2L, db.scalar("SELECT count(*) FROM event_queue"))
        // Writing what the block reads (values) ends the quiet period.
        db.run("override_set", mapOf("kind" to "gate", "name" to "x", "value" to "true"))
        db.exec("DELETE FROM exposure_seen")
        db.run("log_gate_exposure", exposure)
        db.drainDeferred()
        assertEquals(3L, db.scalar("SELECT count(*) FROM event_queue"))
    }

    @Test
    fun events_are_valid_json_without_null_members() {
        val db = db()
        for (manual in listOf(false, true)) {
            // (manual exposures dedupe with automatic ones: distinct names per round)
            db.run("log_gate_exposure", mapOf("name" to "g\"q$manual", "value" to true, "rule_id" to null,
                "secondary" to null, "reason" to "Network:Recognized", "lcut" to null,
                "received_at" to 2L, "manual" to manual))
            for (passed in listOf(null, true, false)) {
                db.run("log_config_exposure", mapOf("name" to "c$manual", "rule_id" to "r$passed",
                    "secondary" to "[]", "reason" to "Network:Recognized", "lcut" to 1L,
                    "received_at" to 2L, "rule_passed" to passed, "manual" to manual))
            }
            db.run("log_layer_exposure", mapOf("name" to "l$manual", "rule_id" to "r", "parameter" to "p",
                "explicit" to "[\"p\"]", "allocated" to null, "secondary" to "[]",
                "undelegated" to null, "reason" to "Network:Recognized", "received_at" to null,
                "manual" to manual))
        }
        db.run("log_event", mapOf("event_name" to "e", "value" to null, "metadata" to """{"k":"v"}""",
            "statsig_metadata" to null))
        db.drainDeferred()
        assertEquals(11L, db.scalar("SELECT count(*) FROM event_queue"))
        assertEquals(0L, db.scalar("SELECT count(*) FROM event_queue WHERE NOT json_valid(event)"))
        assertEquals(0L, db.scalar(
            "SELECT count(*) FROM event_queue, json_tree(event) WHERE json_tree.type = 'null' " +
                "AND json_tree.fullkey NOT LIKE '%ruleID' AND json_tree.fullkey NOT LIKE '%lcut'"))
        assertEquals("g\"qfalse", db.scalar("SELECT event ->> '$.metadata.gate' FROM event_queue ORDER BY id LIMIT 1"))
        assertEquals(null, db.scalar("SELECT event -> '$.metadata.rulePassed' FROM event_queue WHERE event ->> '$.metadata.ruleID' = 'rnull' LIMIT 1"))
        assertEquals("false", db.scalar("SELECT event ->> '$.metadata.rulePassed' FROM event_queue WHERE event ->> '$.metadata.ruleID' = 'rfalse' LIMIT 1"))
        assertEquals(5L, db.scalar("SELECT count(*) FROM event_queue WHERE event ->> '$.metadata.isManualExposure' = 'true'"))
    }
}
