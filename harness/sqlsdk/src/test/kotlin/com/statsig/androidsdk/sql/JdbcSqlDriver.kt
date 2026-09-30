package com.statsig.androidsdk.sql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** [SqlDriver] for the JVM (xerial sqlite-jdbc), used by the harness in place of Android's SQLite. */
internal class JdbcSqlDriver(path: String) : SqlDriver {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val statements = HashMap<String, PreparedStatement>()

    override fun execute(sql: String, args: List<Any?>) {
        prepare(sql, args).execute()
    }

    override fun query(sql: String, args: List<Any?>): List<Row> {
        prepare(sql, args).executeQuery().use { rs ->
            val meta = rs.metaData
            val names = (1..meta.columnCount).map { meta.getColumnLabel(it) }
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

    override fun beginTransaction() {
        connection.autoCommit = false
    }

    override fun commit() {
        connection.commit()
        connection.autoCommit = true
    }

    override fun rollback() {
        connection.rollback()
        connection.autoCommit = true
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
