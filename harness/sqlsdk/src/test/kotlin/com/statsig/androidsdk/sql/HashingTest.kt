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
        return listOf("", "a", "a".repeat(55), "a".repeat(56), "a".repeat(64), "a".repeat(200)) +
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
        val size = db.scalar("SELECT count(*) FROM hash_memo") as Long
        assert(size in 1..2304) { "memo size $size" }
        assertEquals(djb2("n2999"), db.scalar("SELECT output FROM hash_memo WHERE input = 'n2999'"))
    }
}
