package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JVM test shim for android.util.Log — android.jar method bodies throw
 *  "Stub!", so plain-JVM regression tests resolve this recorder instead
 *  (tools/run-tests.sh puts this class dir ahead of android.jar on the
 *  runtime classpath). Warnings are captured for assertions. */
public final class Log {

    public static final List<String> WARNINGS =
            Collections.synchronizedList(new ArrayList<String>());

    private Log() {
    }

    public static int v(String tag, String msg) { return 0; }

    public static int d(String tag, String msg) { return 0; }

    public static int i(String tag, String msg) { return 0; }

    public static int w(String tag, String msg) {
        WARNINGS.add(tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg) { return 0; }

    public static int wtf(String tag, String msg) { return 0; }
}
