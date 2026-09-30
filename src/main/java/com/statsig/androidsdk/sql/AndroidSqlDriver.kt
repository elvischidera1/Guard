package com.statsig.androidsdk.sql

import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram

/**
 * [SqlDriver] over the framework's SQLite. The database is opened without write-ahead logging, so
 * the framework uses a single connection and TEMP tables persist between calls.
 *
 * The SQL needs SQLite 3.38+ (JSON functions and the -> / ->> operators), i.e. Android 14 (API 34)
 * and newer. On older devices a bundled SQLite (e.g. androidx.sqlite's BundledSQLiteDriver or
 * requery's sqlite-android) can be plugged in through StatsigClient.sqlDriverFactory.
 * CursorWindow(String, Long) and SQLiteBlobTooBigException need API 28, below the SQL's minimum.
 */
internal class AndroidSqlDriver(path: String) : SqlDriver {
    private companion object {
        const val LARGE_WINDOW_BYTES = 64L * 1024 * 1024
    }

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(path, null).apply {
        setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE)
    }

    override fun execute(sql: String, args: List<Any?>): Int =
        db.compileStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdateDelete()
        }

    override fun query(sql: String, args: List<Any?>): List<Row> = try {
        query(sql, args, null)
    } catch (e: SQLiteBlobTooBigException) {
        // A row larger than the default 2 MB cursor window (a big payload or event batch).
        query(sql, args, LARGE_WINDOW_BYTES)
    }

    private fun query(sql: String, args: List<Any?>, windowBytes: Long?): List<Row> {
        // rawQuery only binds strings; a cursor factory gets to bind typed values.
        val factory = SQLiteDatabase.CursorFactory { _, driver, table, query ->
            bind(query, args)
            SQLiteCursor(driver, table, query).apply {
                if (windowBytes != null) window = CursorWindow("statsig", windowBytes)
            }
        }
        return db.rawQueryWithFactory(factory, sql, null, null).use { cursor -> read(cursor) }
    }

    override fun beginTransaction() = db.beginTransactionNonExclusive()

    override fun commit() {
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    override fun rollback() = db.endTransaction()

    override fun close() = db.close()

    private fun bind(program: SQLiteProgram, args: List<Any?>) {
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> program.bindNull(i + 1)
                is Long -> program.bindLong(i + 1, arg)
                is Double -> program.bindDouble(i + 1, arg)
                is ByteArray -> program.bindBlob(i + 1, arg)
                else -> program.bindString(i + 1, arg.toString())
            }
        }
    }

    private fun read(cursor: Cursor): List<Row> {
        val names = cursor.columnNames
        val rows = ArrayList<Row>(cursor.count)
        while (cursor.moveToNext()) {
            val row = HashMap<String, Any?>(names.size * 2)
            for (i in names.indices) {
                row[names[i]] = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                    else -> null
                }
            }
            rows.add(row)
        }
        return rows
    }
}
