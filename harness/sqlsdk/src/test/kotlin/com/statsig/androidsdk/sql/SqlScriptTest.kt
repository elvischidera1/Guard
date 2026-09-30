package com.statsig.androidsdk.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlScriptTest {
    @Test
    fun parses_blocks_statements_triggers_and_parameters() {
        val script = SqlScript.parse(
            listOf(
                """
                -- header comment
                -- name: one
                -- leading comment
                SELECT ':not_a_param', "col:x", :a || ':' || :b -- trailing :comment
                FROM t;
                INSERT INTO t VALUES (:a);

                -- name: two
                CREATE TEMP TRIGGER x AFTER INSERT ON t
                BEGIN
                  DELETE FROM t WHERE a = NEW.a;
                  SELECT 1;
                END;
                UPDATE t SET a = :c;
                """.trimIndent()
            )
        )
        val one = script.block("one")
        assertEquals(2, one.size)
        assertEquals(listOf("a", "b"), one[0].params)
        assertTrue(one[0].returnsRows)
        assertTrue(one[0].sql.contains("':not_a_param'"))
        assertFalse(one[0].sql.contains("comment"))
        assertFalse(one[1].returnsRows)
        val two = script.block("two")
        assertEquals(2, two.size)
        assertTrue(two[0].sql.startsWith("CREATE TEMP TRIGGER") && two[0].sql.contains("SELECT 1;"))
        assertEquals(listOf("c"), two[1].params)
    }

    @Test
    fun every_shipped_block_prepares() {
        // Parsing plus running the schema proves every statement in the schema compiles; the
        // other blocks are prepared here against that schema.
        val db = newDb()
        val driver = StatsigDb::class.java.getDeclaredField("driver").apply { isAccessible = true }.get(db) as JdbcSqlDriver
        val prepare = JdbcSqlDriver::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(driver) as java.sql.Connection
        for ((name, statements) in SqlScript.default.blocks) {
            if (name == "schema") continue
            for (statement in statements) prepare.prepareStatement(statement.sql).close()
        }
    }
}
