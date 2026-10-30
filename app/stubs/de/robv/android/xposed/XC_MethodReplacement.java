package de.robv.android.xposed;

// compile-time stub; provided by LSPosed at runtime
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        param.setResult(replaceHookedMethod(param));
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
