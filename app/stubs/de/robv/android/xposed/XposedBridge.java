package de.robv.android.xposed;

import java.lang.reflect.Member;

// compile-time stub; provided by LSPosed at runtime
public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return null;
    }
}
