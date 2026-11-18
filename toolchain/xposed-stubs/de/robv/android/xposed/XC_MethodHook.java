package de.robv.android.xposed;

/** 仅编译期使用：对应 Xposed 的 XC_MethodHook。 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 对应 Xposed 的 XC_MethodHook.MethodHookParam（编译期签名保持一致）。 */
    public static class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            throw new UnsupportedOperationException("stub");
        }

        public void setResult(Object result) {
            throw new UnsupportedOperationException("stub");
        }

        public Throwable getThrowable() {
            throw new UnsupportedOperationException("stub");
        }

        public void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("stub");
        }

        public boolean hasThrowable() {
            throw new UnsupportedOperationException("stub");
        }
    }

    /** 对应 Xposed 的 XC_MethodHook.Unhook（只用于保持返回类型签名一致）。 */
    public class Unhook {
    }
}
