package androidx.annotation

// Compile-time stand-in for androidx.annotation (Google Maven is unreachable from the harness).
@Retention(AnnotationRetention.BINARY)
annotation class VisibleForTesting(val otherwise: Int = PRIVATE) {
    companion object {
        const val PRIVATE = 2
        const val PACKAGE_PRIVATE = 3
        const val PROTECTED = 4
        const val NONE = 5
    }
}
