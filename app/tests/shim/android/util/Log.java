package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JVM test shim for android.util.Log — android.jar method bodies throw
 *  "Stub!", so plain-JVM regression tests resolve this recorder instead
 *  (tools/run-tests.sh puts this class dir ahead of android.jar on the
 *  runtime classpath). Warnings are captured for assertions.
 *
 *  The surface mirrors the full public android.util.Log API on purpose:
 *  tests compile against android.jar's Log (the real signatures) and run
 *  against this class, so any signature missing here would surface as a
 *  NoSuchMethodError at runtime instead of a compile error. With the full
 *  set below, a new logging call in tested code either records (w) or is
 *  a silent no-op — it can never crash the suite. */
public final class Log {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public static final List<String> WARNINGS =
            Collections.synchronizedList(new ArrayList<String>());

    private Log() {
    }

    public static int v(String tag, String msg) { return 0; }

    public static int v(String tag, String msg, Throwable tr) { return 0; }

    public static int d(String tag, String msg) { return 0; }

    public static int d(String tag, String msg, Throwable tr) { return 0; }

    public static int i(String tag, String msg) { return 0; }

    public static int i(String tag, String msg, Throwable tr) { return 0; }

    public static int w(String tag, String msg) {
        WARNINGS.add(tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        WARNINGS.add(tag + ": " + msg + ": " + tr);
        return 0;
    }

    public static int e(String tag, String msg) { return 0; }

    public static int e(String tag, String msg, Throwable tr) { return 0; }

    public static int wtf(String tag, String msg) { return 0; }

    public static int wtf(String tag, String msg, Throwable tr) { return 0; }

    public static boolean isLoggable(String tag, int level) { return false; }

    public static int println(int priority, String tag, String msg) { return 0; }

    public static String getStackTraceString(Throwable tr) {
        return tr == null ? "" : tr.toString();
    }
}
