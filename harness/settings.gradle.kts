// JVM-only harness. Google Maven (AGP, androidx) is not needed: the SDK compiles against the real
// Android API jar published to Maven Central by Robolectric, and runs against the small shims in
// :shims for the few framework classes that are native on a device.
rootProject.name = "statsig-jvm-harness"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(":shims", ":baseline")
