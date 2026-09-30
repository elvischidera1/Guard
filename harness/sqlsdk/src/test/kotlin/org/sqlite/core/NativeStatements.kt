package org.sqlite.core

import java.sql.SQLException

/**
 * SQLite's statement API (prepare / bind / step / column / reset) as sqlite-jdbc's native layer
 * exposes it, without the JDBC wrapper around it. Lives in org.sqlite.core because those native
 * methods are package-private. Used by the harness's SqlDriver.
 */
class NativeStatements(private val db: NativeDB) {
    companion object {
        const val ROW = 100
        const val DONE = 101
        const val INTEGER = 1
        const val FLOAT = 2
        const val TEXT = 3
        const val BLOB = 4
    }

    fun prepare(sql: String): Long {
        val pointer = db.prepare_utf8(sql.toByteArray(Charsets.UTF_8))
        if (pointer == 0L) throw SQLException("${db.errmsg()} in: $sql")
        return pointer
    }

    fun bind(statement: Long, index: Int, value: Any?) {
        val rc = when (value) {
            null -> db.bind_null(statement, index)
            is Long -> db.bind_long(statement, index, value)
            is Double -> db.bind_double(statement, index, value)
            is ByteArray -> db.bind_blob(statement, index, value)
            else -> db.bind_text_utf8(statement, index, value.toString().toByteArray(Charsets.UTF_8))
        }
        if (rc != 0) throw SQLException(db.errmsg())
    }

    /** Steps; returns true for a row, false when done. Resets the statement on errors. */
    fun step(statement: Long, sql: String): Boolean = when (val rc = db.step(statement)) {
        ROW -> true
        DONE -> false
        else -> {
            val message = db.errmsg()
            db.reset(statement)
            db.clear_bindings(statement)
            throw SQLException("[$rc] $message in: $sql")
        }
    }

    /** Resets for the next run (which binds every parameter again, so bindings need no clearing). */
    fun done(statement: Long) {
        db.reset(statement)
    }

    fun columnCount(statement: Long) = db.column_count(statement)
    fun columnName(statement: Long, i: Int): String = db.column_name(statement, i)
    fun column(statement: Long, i: Int): Any? = when (db.column_type(statement, i)) {
        INTEGER -> db.column_long(statement, i)
        FLOAT -> db.column_double(statement, i)
        TEXT -> db.column_text(statement, i)
        BLOB -> db.column_blob(statement, i)
        else -> null
    }

    fun changes(): Int = db.changes().toInt()
    fun finalize(statement: Long) = db.finalize(statement)
}
