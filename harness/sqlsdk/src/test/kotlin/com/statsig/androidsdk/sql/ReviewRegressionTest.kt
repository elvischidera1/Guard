package com.statsig.androidsdk.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases found in review, kept as regression tests. */
class ReviewRegressionTest {
    private fun evaluate(db: StatsigDb, name: String, user: String = """{"userID":"u1","custom":{"n":"7."}}"""): Row {
        val p = mapOf("kind" to "gate", "name" to name, "user" to user)
        for (r in db.run("eval_begin", p)) {
            val result = try { if (Regex(r["pattern"] as String).containsMatchIn(r["value"] as String)) 1L else 0L } catch (e: IllegalArgumentException) { -1L }
            db.run("regex_result", r + ("result" to result))
        }
        db.run("eval_conditions")
        var last = -1L
        while (true) {
            val next = db.one("eval_step")!!["progress"] as Long
            if (next == last) break
            last = next
        }
        return db.one("eval_result", p)!!
    }

    private fun cond(type: String, op: String?, target: String, field: String? = null) =
        """{"type":"$type","operator":${op?.let { "\"$it\"" }},"targetValue":$target,"field":${field?.let { "\"$it\"" }},"idType":"userID"}"""

    private fun gate(name: String, vararg rules: String) =
        """{"name":"$name","type":"feature_gate","isActive":true,"salt":"s","defaultValue":false,"enabled":true,"idType":"userID","entity":"feature_gate","rules":[${rules.joinToString(",")}]}"""

    private fun rule(id: String, vararg conditions: String, extra: String = "\"passPercentage\":100,\"salt\":\"rs\",") =
        """{"name":"$id","id":"$id",$extra"returnValue":true,"idType":"userID","groupName":"g","conditions":[${conditions.joinToString(",")}]}"""

    private fun load(db: StatsigDb, vararg gates: String) = db.run(
        "dcs_load",
        mapOf("payload" to """{"feature_gates":[${gates.joinToString(",")}],"dynamic_configs":[],"layer_configs":[],"time":1}""", "received_at" to 1L)
    )

    @Test
    fun numbers_in_strings_are_read_like_kotlin() {
        val db = newDb()
        db.startSession()
        load(
            db,
            gate("gt", rule("r", cond("user_field", "gt", "1", "n"))),
            gate("hex", rule("r", cond("user_field", "lt", "5", "n")))
        )
        for ((value, gt1) in listOf("7." to 1L, "+5" to 1L, " 3e2 " to 1L, "0x1F" to 0L, "1.2.3" to 0L, "NaN" to 0L, "abc" to 0L)) {
            val user = """{"userID":"u1","custom":{"n":"$value"}}"""
            assertEquals(value, gt1, evaluate(db, "gt", user)["bool"])
        }
        assertEquals(0L, evaluate(db, "hex", """{"userID":"u1","custom":{"n":"0x1F"}}""")["bool"])
    }

    @Test
    fun malformed_rules_fall_back_like_the_original() {
        val db = newDb()
        db.startSession()
        load(
            db,
            gate("no_percentage", rule("r", cond("public", null, "null"), extra = "")),
            gate("upper_segment", rule("r", cond("pass_gate", null, "\"Segment:x\""))),
            gate("Segment:x", rule("s", cond("public", null, "null")))
        )
        evaluate(db, "no_percentage").let {
            assertEquals(0L, it["bool"])
            assertEquals("r", it["rule_id"])
        }
        // only a lower-case "segment:" prefix hides the exposure
        assertTrue((evaluate(db, "upper_segment")["secondary"] as String).contains("Segment:x"))
    }

    @Test
    fun a_spec_with_thousands_of_rules_evaluates() {
        val db = newDb()
        db.startSession()
        val rules = listOf(rule("r0", cond("public", null, "null"))) +
            (1..3000).map { rule("r$it", cond("user_field", "eq", "\"nope\"", "email")) }
        load(db, gate("big", *rules.toTypedArray()))
        assertEquals(1L, evaluate(db, "big")["bool"])
    }

    @Test
    fun large_init_v2_payloads_apply_quickly_and_accept_numeric_ids() {
        val db = newDb()
        db.startSession()
        val n = 1500
        val gates = (0 until n).joinToString(",") { """"g$it":{"v":true,"r":"r$it","s":["${it % 50}",${it % 50}]}""" }
        val configs = (0 until n).joinToString(",") { """"c$it":{"v":${it % 100},"r":"r$it","s":["${it % 50}"],"us":["${it % 50}"]}""" }
        val values = (0 until 100).joinToString(",") { """"$it":{"k":$it}""" }
        val exposures = (0 until 50).joinToString(",") { """"$it":{"gate":"e$it","gateValue":"true","ruleID":"x"}""" }
        val payload = """{"response_format":"init-v2","feature_gates":{$gates},"dynamic_configs":{$configs},"layer_configs":{},
            "values":{$values},"exposures":{$exposures},"has_updates":true,"time":1,"hash_used":"none"}"""
        val start = System.nanoTime()
        db.run("bootstrap", mapOf("values" to payload))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("bootstrap took $ms ms", ms < 3000)
        val gate = db.one("get_value", mapOf("kind" to "gate", "name" to "g7"))!!
        assertEquals("""[{"gate":"e7","gateValue":"true","ruleID":"x"},{"gate":"e7","gateValue":"true","ruleID":"x"}]""", gate["secondary_exposures"])
        assertEquals("""{"k":7}""", db.one("get_value", mapOf("kind" to "config", "name" to "c7"))!!["value"])
    }

    @Test
    fun legacy_shared_preferences_data_is_imported_once() {
        val db = newDb()
        db.startSession()
        db.run(
            "legacy_import",
            mapOf(
                "stable_id" to "old-stable-id",
                "overrides" to """{"gates":{"g":true},"configs":{"c":{"a":1}},"layers":{}}""",
                "device_sticky" to """{"123":{"name":"123","value":{"x":1},"is_experiment_active":true}}"""
            )
        )
        assertEquals("old-stable-id", db.one("stable_id")!!["stable_id"])
        assertEquals(setOf("true", """{"a":1}"""), db.run("overrides").map { it["value"] as String }.toSet())
        assertEquals(0L, db.one("legacy_import_pending")!!["pending"])
    }
}
