package com.statsig.androidsdk.sql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** [SqlDriver] for the JVM (xerial sqlite-jdbc), used by the harness in place of Android's SQLite. */
internal class JdbcSqlDriver(path: String) : SqlDriver {
    // get_generated_keys=false: otherwise sqlite-jdbc runs "SELECT last_insert_rowid()" after
    // every INSERT, which the SDK never needs.
    private val connection: Connection =
        DriverManager.getConnection("jdbc:sqlite:$path?jdbc.get_generated_keys=false")
    private val statements = HashMap<String, PreparedStatement>()
    private val columns = HashMap<String, List<String>>()

    override fun execute(sql: String, args: List<Any?>): Int = prepare(sql, args).executeUpdate()

    override fun query(sql: String, args: List<Any?>): List<Row> {
        prepare(sql, args).executeQuery().use { rs ->
            val names = columns.getOrPut(sql) { rs.metaData.let { m -> (1..m.columnCount).map { m.getColumnLabel(it) } } }
            val rows = ArrayList<Row>()
            while (rs.next()) {
                val row = HashMap<String, Any?>(names.size * 2)
                names.forEachIndexed { i, name ->
                    row[name] = when (val v = rs.getObject(i + 1)) {
                        is Int -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v
                    }
                }
                rows.add(row)
            }
            return rows
        }
    }

    // Plain SQL (cached statements) rather than setAutoCommit(), which re-prepares each time.
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
        statements.values.forEach { it.close() }
        connection.close()
    }

    private fun prepare(sql: String, args: List<Any?>): PreparedStatement {
        val statement = statements.getOrPut(sql) { connection.prepareStatement(sql) }
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> statement.setNull(i + 1, Types.NULL)
                is Long -> statement.setLong(i + 1, arg)
                is Double -> statement.setDouble(i + 1, arg)
                is ByteArray -> statement.setBytes(i + 1, arg)
                else -> statement.setString(i + 1, arg.toString())
            }
        }
        return statement
    }
}
