package com.statsig.androidsdk.sql

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {
    private fun djb2(input: String): String {
        var hash = 0
        for (c in input) {
            hash = (hash shl 5) - hash + c.code
            hash = hash and hash
        }
        return hash.toUInt().toString()
    }

    private fun sha256(input: String) = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())

    private fun inputs(): List<String> {
        val random = Random(42)
        val alphabet = "abcXYZ019 _-.:\"'\\/é漢😀\u007f"
        return listOf("", "a", "a".repeat(55), "a".repeat(56), "a".repeat(64), "a".repeat(200),
            "b".repeat(512), "b".repeat(513), "é".repeat(700)) +
            (1..60).map { n ->
                // whole code points (a lone surrogate is not a valid string anywhere)
                val codePoints = alphabet.codePoints().toArray()
                (1..random.nextInt(0, n + 3)).joinToString("") { Character.toString(codePoints[random.nextInt(codePoints.size)]) }
            }
    }


    @Test
    fun djb2_sha256_and_buckets_match_the_platform_implementations() {
        val db = newDb()
        for (input in inputs()) {
            for (algo in listOf("djb2", "sha256", "bucket")) db.exec("INSERT INTO hash_input (algo, input) VALUES (?, ?)", algo, input)
            val digest = sha256(input)
            assertEquals(input.map { it.code }.toString(), djb2(input), db.scalar("SELECT output FROM hash_memo WHERE algo = 'djb2' AND input = ?", input))
            assertEquals(input, Base64.getEncoder().encodeToString(digest), db.scalar("SELECT output FROM hash_memo WHERE algo = 'sha256' AND input = ?", input))
            assertEquals(input, (ByteBuffer.wrap(digest).long.toULong() % 10000UL).toString(), db.scalar("SELECT output FROM hash_memo WHERE algo = 'bucket' AND input = ?", input))
        }
        assertEquals(0L, db.scalar("SELECT count(*) FROM hash_input"))
    }

    @Test
    fun memo_stays_bounded_and_keeps_recent_entries() {
        val db = newDb()
        for (i in 0 until 3000) db.exec("INSERT INTO hash_input (algo, input) VALUES ('djb2', 'n$i')")
        assertEquals(3000L, db.scalar("SELECT count(*) FROM hash_memo"))
        db.run("trim_hash_memo")
        assertEquals(2048L, db.scalar("SELECT count(*) FROM hash_memo"))
        assertEquals(djb2("n2999"), db.scalar("SELECT output FROM hash_memo WHERE input = 'n2999'"))
    }

    @Test
    fun get_value_finds_djb2_hashed_names_of_any_length_and_alphabet() {
        val db = newDb()
        db.startSession()
        val names = inputs().filter { it.isNotEmpty() }.distinct()
        val gates = names.joinToString(",") { name ->
            val hash = djb2(name)
            "\"$hash\":{\"name\":\"$hash\",\"value\":true,\"rule_id\":\"r\"}"
        }
        db.run("save_values", mapOf(
            "payload" to """{"feature_gates":{$gates},"dynamic_configs":{},"layer_configs":{},"has_updates":true,"time":1,"hash_used":"djb2"}""",
            "user" to """{"userID":"u1"}""", "scoped_key" to "u1:client-key"))
        for (name in names) {
            assertEquals(name, 1L, db.one("get_value", mapOf("kind" to "gate", "name" to name))!!["found"])
        }
        assertEquals(0L, db.one("get_value", mapOf("kind" to "gate", "name" to "not a gate"))!!["found"])
    }

    @Test
    fun get_value_finds_sha256_hashed_names_declared_or_implied() {
        for (hashUsed in listOf(""","hash_used":"sha256"""", "")) {
            val db = newDb()
            db.startSession()
            val names = inputs().filter { it.isNotEmpty() }.distinct().take(20)
            val gates = names.joinToString(",") { name ->
                val hash = Base64.getEncoder().encodeToString(sha256(name))
                "\"$hash\":{\"name\":\"$hash\",\"value\":true,\"rule_id\":\"r\"}"
            }
            db.run("save_values", mapOf(
                "payload" to """{"feature_gates":{$gates},"dynamic_configs":{},"layer_configs":{},"has_updates":true,"time":1$hashUsed}""",
                "user" to """{"userID":"u1"}""", "scoped_key" to "u1:client-key"))
            for (name in names) {
                assertEquals(name, 1L, db.one("get_value", mapOf("kind" to "gate", "name" to name))!!["found"])
            }
            assertEquals(0L, db.one("get_value", mapOf("kind" to "gate", "name" to "not a gate"))!!["found"])
        }
    }
}
