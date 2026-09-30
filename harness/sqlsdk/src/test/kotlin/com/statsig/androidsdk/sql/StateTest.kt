package com.statsig.androidsdk.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage and logging rules that the public-API scenario does not reach. */
class StateTest {
    private fun payload(time: Long) =
        """{"feature_gates":{"g":{"name":"g","value":true,"rule_id":"r"}},"dynamic_configs":{
           "e":{"name":"e","value":{"v":$time},"rule_id":"r","is_experiment_active":true,"is_user_in_experiment":true}},
           "layer_configs":{},"has_updates":true,"time":$time,"hash_used":"none"}"""

    private fun switchTo(db: StatsigDb, i: Int) {
        val user = """{"userID":"u$i"}"""
        db.run("set_user", mapOf("user" to user, "scoped_key" to "u$i:client-key"))
        db.run("load_cache")
        db.run("save_values", mapOf("payload" to payload(i.toLong()), "user" to user, "scoped_key" to "u$i:client-key"))
    }

    @Test
    fun keeps_the_ten_most_recent_user_caches_and_their_sticky_values() {
        val db = newDb()
        db.startSession()
        for (i in 1..12) {
            switchTo(db, i)
            db.run("get_experiment", mapOf("name" to "e", "kind" to "config", "keep" to true))
            Thread.sleep(2) // distinct last_used_at
        }
        assertEquals(10L, db.scalar("SELECT count(*) FROM cache_key_map"))
        assertEquals(10L, db.scalar("SELECT count(*) FROM cached_values"))
        assertEquals(10L, db.scalar("SELECT count(*) FROM sticky_value"))
        assertEquals(0L, db.scalar("SELECT count(*) FROM cache_key_map WHERE scoped_key IN ('u1:client-key', 'u2:client-key')"))

        // a returning user gets its cached values back
        db.run("set_user", mapOf("user" to """{"userID":"u5"}""", "scoped_key" to "u5:client-key"))
        db.run("load_cache")
        val gate = db.one("get_value", mapOf("kind" to "gate", "name" to "g"))!!
        assertEquals("Cache", gate["source"])
        assertEquals(5L, gate["lcut"])
    }

    @Test
    fun sticky_value_survives_new_values_until_the_experiment_ends() {
        val db = newDb()
        db.startSession()
        val user = """{"userID":"u1"}"""
        db.run("save_values", mapOf("payload" to payload(1), "user" to user, "scoped_key" to "u1:client-key"))
        fun experiment(keep: Boolean) = db.one("get_experiment", mapOf("name" to "e", "kind" to "config", "keep" to keep))!!
        assertEquals("""{"v":1}""", experiment(true)["value"])
        db.run("save_values", mapOf("payload" to payload(2), "user" to user, "scoped_key" to "u1:client-key"))
        experiment(true).let {
            assertEquals("""{"v":1}""", it["value"])
            assertEquals("Sticky", it["reason"])
        }
        assertEquals("""{"v":2}""", experiment(false)["value"])
        assertEquals(0L, db.scalar("SELECT count(*) FROM sticky_value"))
    }

    @Test
    fun responses_for_a_user_the_session_left_are_ignored() {
        val db = newDb()
        db.startSession()
        db.run("set_user", mapOf("user" to """{"userID":"other"}""", "scoped_key" to "other:client-key"))
        val saved = db.one("save_values", mapOf("payload" to payload(1), "user" to """{"userID":"u1"}""", "scoped_key" to "u1:client-key"))!!
        assertEquals(0L, saved["applied"])
        assertEquals(0L, db.scalar("SELECT count(*) FROM cached_values"))
        assertEquals("Uninitialized", db.one("session_state")!!["source"])
    }

    @Test
    fun failed_log_batches_are_bounded_by_count_age_and_retries() {
        val db = newDb()
        val now = System.currentTimeMillis()
        fun save(createdAt: Long, retries: Int, body: String) = db.run(
            "failed_log_save",
            mapOf("sdk_key" to "k", "created_at" to createdAt, "body" to body, "retry_count" to retries, "event_count" to "1")
        )
        save(now - 4L * 24 * 3600 * 1000, 1, "too old")
        save(now, 3, "retried too often")
        for (i in 1..12) save(now + i, 1, "b$i")
        db.exec("INSERT INTO failed_log (sdk_key, created_at, body, retry_count) VALUES ('other', $now, 'other key', 1)")
        val taken = db.run("failed_logs_take", mapOf("sdk_key" to "k"))
        assertEquals((3..12).map { "b$it" }, taken.map { it["body"] })
        assertEquals(1L, db.scalar("SELECT count(*) FROM failed_log"))
    }

    private fun logGate(db: StatsigDb, name: String) = db.one(
        "log_gate_exposure",
        mapOf("name" to name, "value" to true, "rule_id" to "r", "secondary" to "[]", "reason" to "Network:Recognized",
            "lcut" to 1L, "received_at" to 2L, "manual" to false)
    )!!

    @Test
    fun exposures_are_deduplicated_for_ten_minutes_and_per_user() {
        val db = newDb()
        db.startSession()
        logGate(db, "g")
        logGate(db, "g")
        assertEquals(1L, db.scalar("SELECT count(*) FROM event_queue"))
        db.exec("UPDATE exposure_seen SET logged_at = logged_at - 600001")
        logGate(db, "g")
        assertEquals(2L, db.scalar("SELECT count(*) FROM event_queue"))
        db.run("set_user", mapOf("user" to """{"userID":"u2"}""", "scoped_key" to "u2"))
        logGate(db, "g")
        assertEquals(3L, db.scalar("SELECT count(*) FROM event_queue"))
    }

    @Test
    fun queue_asks_for_a_flush_at_50_and_keeps_the_newest_1000() {
        val db = newDb()
        db.startSession()
        fun log(i: Int) = db.one("log_event", mapOf("event_name" to "e$i", "value" to null, "metadata" to null, "statsig_metadata" to null))!!
        for (i in 1..48) log(i)
        assertEquals(0L, log(49)["should_flush"])
        assertEquals(1L, log(50)["should_flush"])
        for (i in 51..1005) log(i)
        assertEquals(1000L, db.scalar("SELECT count(*) FROM event_queue"))
        assertTrue((db.scalar("SELECT min(event) FROM event_queue WHERE json_extract(event, '$.eventName') = 'e6'") as String?) != null)
        assertNull(db.scalar("SELECT event FROM event_queue WHERE json_extract(event, '$.eventName') = 'e5'"))

        val batch = db.one("take_batch", mapOf("metadata" to "{}", "logging_enabled" to true))!!
        assertEquals(1000L, batch["n"])
        assertEquals(0L, db.scalar("SELECT count(*) FROM event_queue"))
    }

    @Test
    fun fallback_urls_rotate_expire_and_respect_the_apps_list() {
        val db = newDb()
        fun active(fallbackUrls: String? = null, custom: Boolean = false) =
            db.one("fallback_url", mapOf("endpoint" to "initialize", "fallback_urls" to fallbackUrls, "has_custom_url" to custom))?.get("url")
        fun pick(vararg urls: String) =
            db.one("fallback_url_pick", mapOf("endpoint" to "initialize", "candidates" to urls.joinToString(",", "[", "]") { "\"$it\"" }))!!["updated"]

        assertEquals(1L, pick("https://a.org/", "https://b.org"))
        assertEquals("https://a.org", active())
        assertEquals(1L, pick("https://a.org", "https://b.org"))
        assertEquals("https://b.org", active())
        assertEquals(0L, pick("https://a.org", "https://b.org"))
        // a custom api without fallback urls never uses fallbacks (and keeps the entry)
        assertNull(active(custom = true))
        assertEquals("https://b.org", active())
        // an app list that no longer contains the url drops it
        assertNull(active(fallbackUrls = """["https://c.org"]"""))
        assertNull(active())
        // expiry
        pick("https://d.org")
        db.exec("UPDATE fallback_url SET expires_at = 0")
        assertNull(active())
    }

    @Test
    fun dns_candidates_come_from_matching_txt_records_in_order() {
        val db = newDb()
        val urls = db.one(
            "fallback_url_candidates",
            mapOf("records" to """["i=one.org/","e=logs.org","i=two.org=extra","ii=bad.org"]""", "dns_key" to "i", "path" to "/v1/initialize")
        )!!["urls"]
        assertEquals("""["https://one.org/v1/initialize","https://two.org/v1/initialize"]""", urls)
        assertEquals(1L, db.one("dns_query_allowed", mapOf("endpoint" to "initialize"))!!["allowed"])
        assertEquals(0L, db.one("dns_query_allowed", mapOf("endpoint" to "initialize"))!!["allowed"])
    }
}
