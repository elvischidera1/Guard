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
    jvmArgs("-Xms1g", "-Xmx1g")
    args(layout.buildDirectory.file("bench.json").get().asFile.path)
}

