package com.statsig.androidsdk.sql

import java.sql.Connection
import java.sql.DriverManager
import org.sqlite.SQLiteConnection
import org.sqlite.core.NativeDB
import org.sqlite.core.NativeStatements

/**
 * [SqlDriver] for the JVM, used by the harness in place of Android's SQLite. It opens the
 * database with xerial sqlite-jdbc (which bundles SQLite) but runs statements through SQLite's
 * own statement API (prepare once, then bind / step / read columns / reset), like Android's
 * framework binding does, rather than through the JDBC wrapper, whose per-call bookkeeping
 * costs several microseconds per statement, parameter and column.
 */
internal class JdbcSqlDriver(path: String) : SqlDriver {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val native = NativeStatements((connection as SQLiteConnection).database as NativeDB)
    private val statements = HashMap<String, Long>()
    private val columns = HashMap<Long, Array<String>>()

    override fun execute(sql: String, args: List<Any?>): Int {
        val statement = prepare(sql, args)
        try {
            while (native.step(statement, sql)) Unit
            return native.changes()
        } finally {
            native.done(statement)
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Row> {
        val statement = prepare(sql, args)
        try {
            val names = columns.getOrPut(statement) {
                Array(native.columnCount(statement)) { native.columnName(statement, it) }
            }
            val rows = ArrayList<Row>()
            while (native.step(statement, sql)) {
                val row = HashMap<String, Any?>(names.size * 2)
                for (i in names.indices) row[names[i]] = native.column(statement, i)
                rows.add(row)
            }
            return rows
        } finally {
            native.done(statement)
        }
    }

    override fun beginTransaction() {
        execute("BEGIN", emptyList())
    }

    override fun commit() {
        execute("COMMIT", emptyList())
    }

    override fun rollback() {
        execute("ROLLBACK", emptyList())
    }

    override fun close() {
        statements.values.forEach { native.finalize(it) }
        statements.clear()
        connection.close()
    }

    private fun prepare(sql: String, args: List<Any?>): Long {
        val statement = statements.getOrPut(sql) { native.prepare(sql) }
        args.forEachIndexed { i, arg -> native.bind(statement, i + 1, arg) }
        return statement
    }
}
