plugins {
    kotlin("jvm") version "1.9.25" apply false
}

// Shared by :baseline and :sqlsdk.
extra["androidAll"] = "org.robolectric:android-all:14-robolectric-10818077"
extra["sdkVersion"] = "5.1.5"
