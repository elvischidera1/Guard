// Compiles the unmodified upstream SDK (the repository's first commit) for the JVM so it can be
// benchmarked and used to record golden scenario transcripts.
plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

val baselineCommit = providers.gradleProperty("baselineCommit")
    .orElse("3e1c1bff43fd057a8f603c93329e7c5ac4524b03")
val baselineSrc = layout.buildDirectory.dir("baseline-src")

// The only edit made to upstream code: the DataStore-backed storage class is replaced by a stub,
// because androidx.datastore is only published to Google Maven. The SDK's default storage path
// (SharedPreferences, "LEGACY") is untouched and is what the benchmark exercises.
val extractBaseline by tasks.registering {
    inputs.property("commit", baselineCommit)
    outputs.dir(baselineSrc)
    val repoDir = rootDir.parentFile
    val outDir = baselineSrc.get().asFile
    val commit = baselineCommit.get()
    doLast {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val archive = ProcessBuilder("git", "-C", repoDir.path, "archive", commit, "src/main/java")
            .redirectErrorStream(false).start()
        val tar = ProcessBuilder("tar", "-x", "-C", outDir.path).start()
        archive.inputStream.copyTo(tar.outputStream)
        tar.outputStream.close()
        check(archive.waitFor() == 0 && tar.waitFor() == 0) { "git archive of $commit failed" }
        val kvs = File(outDir, "src/main/java/com/statsig/androidsdk/KeyValueStorage.kt")
        val text = kvs.readText()
        val cut = text.indexOf("/**\n * [KeyValueStorage] with each substore backed by a [Preferences] [DataStore].")
        check(cut > 0) { "KeyValueStorage.kt layout changed" }
        kvs.writeText(
            text.substring(0, cut).lines().filterNot { it.startsWith("import androidx.datastore") }
                .joinToString("\n") +
                """
                |class PreferencesDataStoreKeyValueStorage(
                |    val application: Application,
                |    val coroutineScope: CoroutineScope
                |) : KeyValueStorage<String> by LegacyKeyValueStorage(application) {
                |    companion object { fun resetForTesting() {} }
                |}
                |""".trimMargin()
        )
    }
}

val buildConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    val version = rootProject.extra["sdkVersion"] as String
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/statsig/androidsdk/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.statsig.androidsdk\n\nobject BuildConfig { const val VERSION_NAME = \"$version\" }\n")
    }
}

sourceSets {
    main { kotlin.srcDir(extractBaseline.map { baselineSrc.get().dir("src/main/java") }) ; kotlin.srcDir(buildConfig) }
    test { kotlin.srcDir("../scenario/src") }
}

dependencies {
    compileOnly(rootProject.extra["androidAll"] as String)
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // Order matters: the shims must shadow the native-backed classes in android-all.
    testImplementation(project(":shims"))
    testImplementation(rootProject.extra["androidAll"] as String)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
}

apply(from = "../scenario/tasks.gradle.kts")
