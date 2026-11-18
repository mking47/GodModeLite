package de.robv.android.xposed;

/** 仅编译期使用：对应 Xposed 的 XposedBridge。 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }
}
