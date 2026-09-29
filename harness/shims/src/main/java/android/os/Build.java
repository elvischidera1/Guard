package android.os;

/** JVM stand-in for android.os.Build (the real one reads native system properties). */
public class Build {
    public static final String MANUFACTURER = "jvm";
    public static final String MODEL = "harness";
    public static final String BRAND = "jvm";
    public static final String DEVICE = "harness";

    public static class VERSION {
        public static final int SDK_INT = 34;
        public static final String RELEASE = "14";
    }

    public static class VERSION_CODES {
        public static final int M = 23;
        public static final int N = 24;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int UPSIDE_DOWN_CAKE = 34;
    }
}
