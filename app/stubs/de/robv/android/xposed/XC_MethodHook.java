package de.robv.android.xposed;

import java.lang.reflect.Member;

// compile-time stub; provided by LSPosed at runtime
public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;
        public boolean returnEarly = false;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { this.throwable = t; this.returnEarly = true; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
}
