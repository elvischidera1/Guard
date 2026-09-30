// Compiles this repository's SDK (../../src/main) for the JVM, with a JDBC SqlDriver for tests.
plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

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
    main {
        kotlin.srcDir("../../src/main/java")
        kotlin.srcDir(buildConfig)
        resources.srcDir("../../src/main/resources")
        // androidx.annotation stand-in (Google Maven is unreachable from the harness)
        kotlin.srcDir("../baseline/src/main/kotlin/androidx/annotation")
    }
    test { kotlin.srcDir("../scenario/src") }
}

dependencies {
    compileOnly(rootProject.extra["androidAll"] as String)
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    testImplementation(project(":shims"))
    testImplementation(rootProject.extra["androidAll"] as String)
    testImplementation(providers.gradleProperty("sqliteJdbc").getOrElse("org.xerial:sqlite-jdbc:3.50.3.0"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

apply(from = "../scenario/tasks.gradle.kts")
