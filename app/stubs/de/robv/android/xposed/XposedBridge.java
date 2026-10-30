package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

// compile-time stub; provided by LSPosed at runtime
public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> clazz, String methodName, XC_MethodHook callback) {
        return null;
    }
}
