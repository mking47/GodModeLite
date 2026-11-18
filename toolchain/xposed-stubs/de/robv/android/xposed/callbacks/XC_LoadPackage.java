package de.robv.android.xposed.callbacks;

/** 仅编译期使用：对应 Xposed 的 de.robv.android.xposed.callbacks.XC_LoadPackage。 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
