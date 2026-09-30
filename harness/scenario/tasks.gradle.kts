// Tasks shared by :baseline and :sqlsdk. Both compile the same public-API-only sources in
// scenario/src, so the transcript and benchmark exercise exactly the same calls.
val testSourceSet = the<SourceSetContainer>()["test"]

tasks.register<JavaExec>("scenario") {
    group = "harness"
    description = "Runs the public-API scenario and writes a JSON transcript."
    classpath = testSourceSet.runtimeClasspath
    mainClass.set("harness.ScenarioKt")
    args(layout.buildDirectory.file("scenario.json").get().asFile.path)
}

tasks.register<JavaExec>("bench") {
    group = "harness"
    description = "Runs the public-API benchmark."
    classpath = testSourceSet.runtimeClasspath
    mainClass.set("harness.BenchmarkKt")
    // Pre-touch the heap: otherwise first-touch page faults on fresh heap pages make
    // allocation-heavy loops several times slower until the heap has been cycled once.
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch")
    // e.g. -PbenchJvmArgs=-XX:StartFlightRecording=filename=bench.jfr to profile
    providers.gradleProperty("benchJvmArgs").orNull?.let { jvmArgs(it.split(' ')) }
    args(layout.buildDirectory.file("bench.json").get().asFile.path)
}



tasks.register<JavaExec>("probe") {
    group = "harness"
    description = "Runs -Pmain=<class> from the test classpath (ad-hoc measurements)."
    classpath = testSourceSet.runtimeClasspath
    mainClass.set(providers.gradleProperty("main"))
    jvmArgs("-Xms1g", "-Xmx1g")
    providers.gradleProperty("benchJvmArgs").orNull?.let { jvmArgs(it.split(' ')) }
}
