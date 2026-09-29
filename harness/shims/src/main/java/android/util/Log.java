package android.util;

/** JVM stand-in for android.util.Log (the real one is native). Quiet unless -Dstatsig.log=true. */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    private static final boolean ENABLED = Boolean.getBoolean("statsig.log");

    private static int print(String level, String tag, String msg, Throwable tr) {
        if (ENABLED) {
            System.err.println(level + "/" + tag + ": " + msg + (tr == null ? "" : " " + tr));
        }
        return 0;
    }

    public static int v(String tag, String msg) { return print("V", tag, msg, null); }
    public static int v(String tag, String msg, Throwable tr) { return print("V", tag, msg, tr); }
    public static int d(String tag, String msg) { return print("D", tag, msg, null); }
    public static int d(String tag, String msg, Throwable tr) { return print("D", tag, msg, tr); }
    public static int i(String tag, String msg) { return print("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable tr) { return print("I", tag, msg, tr); }
    public static int w(String tag, String msg) { return print("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return print("W", tag, msg, tr); }
    public static int w(String tag, Throwable tr) { return print("W", tag, "", tr); }
    public static int e(String tag, String msg) { return print("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return print("E", tag, msg, tr); }
    public static boolean isLoggable(String tag, int level) { return ENABLED; }
}
