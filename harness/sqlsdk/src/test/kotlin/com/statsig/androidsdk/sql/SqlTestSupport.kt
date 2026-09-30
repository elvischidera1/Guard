package com.statsig.androidsdk.sql

import java.nio.file.Files

/** A fresh database (file-backed, like on a device) with the schema applied. */
internal fun newDb(dir: java.io.File = Files.createTempDirectory("statsig-sql").toFile()): StatsigDb =
    StatsigDb(JdbcSqlDriver(java.io.File(dir, "statsig.db").path))

internal fun StatsigDb.startSession(user: String = """{"userID":"u1"}""", scopedKey: String = "u1:client-key") =
    run("session_start", mapOf("sdk_key" to "client-key", "user" to user, "scoped_key" to scopedKey, "options" to "{}"))


internal fun StatsigDb.exec(sql: String, vararg args: Any?) {
    val driverField = StatsigDb::class.java.getDeclaredField("driver").apply { isAccessible = true }
    (driverField.get(this) as SqlDriver).execute(sql, args.toList())
}

/** Drops the host's cached results (for tests that change tables behind the blocks' backs). */
internal fun StatsigDb.forgetCaches() {
    val field = StatsigDb::class.java.getDeclaredField("cache").apply { isAccessible = true }
    (field.get(this) as MutableMap<*, *>).clear()
    val gens = StatsigDb::class.java.getDeclaredField("generations").apply { isAccessible = true }
        .get(this) as java.util.concurrent.atomic.AtomicLongArray
    for (i in 0 until gens.length()) gens.incrementAndGet(i)
}

internal fun StatsigDb.scalar(sql: String, vararg args: Any?): Any? {
    val driverField = StatsigDb::class.java.getDeclaredField("driver").apply { isAccessible = true }
    return (driverField.get(this) as SqlDriver).query(sql, args.toList()).firstOrNull()?.values?.firstOrNull()
}
