plugins { java }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    compileOnly(rootProject.extra["androidAll"] as String)
}
