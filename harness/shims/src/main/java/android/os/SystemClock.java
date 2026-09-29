package android.os;

/** JVM stand-in for android.os.SystemClock (the real one is native). */
public final class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000L; }
    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
