package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Member;

// compile-time stub; provided by LSPosed at runtime
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String name, ClassLoader loader) { return null; }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {}

    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {}

    public static void setIntField(Object obj, String fieldName, int value) {}

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Object callMethod(Object receiver, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader loader, String methodName,
            Object... parameterTypesAndCallback) {
        return null;
    }
}
