package com.dsh.godmodelite.xposed;

import android.app.Activity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口（assets/xposed_init 指向本类）。
 *
 * 职责很薄：决定给哪些进程装 hook，然后把 Activity 生命周期事件转交给 {@link ModuleEngine}。
 * 所有逻辑都包 try/catch —— 模块代码跑在别人的进程里，任何未捕获异常都可能让目标应用崩溃。
 *
 * hook 点：
 * - android.app.Activity.onResume（after）→ ModuleEngine.onResume(activity)
 * - android.app.Activity.onDestroy（after）→ ModuleEngine.onDestroy(activity)
 */
public class GodModeModule implements IXposedHookLoadPackage {

    private static final String TAG = "[GodModeLite] ";

    /** 自己的包名：模块绝不能注入自己（会死循环也给本应用加悬浮球）。 */
    private static final String OWN_PACKAGE = "com.dsh.godmodelite";

    /** 系统核心包：没有正常的 Activity/资源体系，跳过。 */
    private static final String ANDROID_PACKAGE = "android";

    /** 同一进程可能被重复回调（理论上不会），防止重复 hook。 */
    private static final Set<String> HOOKED_PACKAGES =
            Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = null;
        try {
            if (lpparam == null) {
                return;
            }
            pkg = lpparam.packageName;
            if (pkg == null || pkg.length() == 0) {
                return;
            }
            // 1. 跳过自己与 android 系统包
            if (OWN_PACKAGE.equals(pkg) || ANDROID_PACKAGE.equals(pkg)) {
                return;
            }
            // 2. v5.1：不再要求 isFirstApplication——部分应用的界面跑在 :ui 等子进程；
            //    hook 的是 Activity 类，没有界面的进程不会触发，开销可忽略。
            // 3. 防御性去重
            if (!HOOKED_PACKAGES.add(pkg)) {
                return;
            }
            hookActivityMethods();
            log("已注入进程: " + pkg + " process=" + lpparam.processName
                    + " first=" + lpparam.isFirstApplication);
        } catch (Throwable t) {
            log("handleLoadPackage 失败: " + pkg);
            logThrowable(t);
        }
    }

    /** hook Activity 的 onResume / onDestroy；两个 hook 各自独立 try/catch，互不影响。 */
    private static void hookActivityMethods() {
        // onResume（after）：每次回到前台都重新套用规则 + 保证悬浮球在位
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object target = param == null ? null : param.thisObject;
                        if (target instanceof Activity) {
                            ModuleEngine.onResume((Activity) target);
                        }
                    } catch (Throwable t) {
                        log("onResume hook 失败");
                        logThrowable(t);
                    }
                }
            });
        } catch (Throwable t) {
            log("hook Activity.onResume 失败");
            logThrowable(t);
        }

        // onPostResume（after）：onResume 之后再补一次，提升重启后首次打开的命中率
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object target = param == null ? null : param.thisObject;
                        if (target instanceof Activity) {
                            ModuleEngine.onPostResume((Activity) target);
                        }
                    } catch (Throwable t) {
                        log("onPostResume hook 失败");
                        logThrowable(t);
                    }
                }
            });
        } catch (Throwable t) {
            log("hook Activity.onPostResume 失败");
            logThrowable(t);
        }

        // onDestroy（after）：清理我们加进这个 Activity 的所有 View，避免泄漏/残留
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object target = param == null ? null : param.thisObject;
                        if (target instanceof Activity) {
                            ModuleEngine.onDestroy((Activity) target);
                        }
                    } catch (Throwable t) {
                        log("onDestroy hook 失败");
                        logThrowable(t);
                    }
                }
            });
        } catch (Throwable t) {
            log("hook Activity.onDestroy 失败");
            logThrowable(t);
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // 日志失败不能影响目标应用
        }
    }

    private static void logThrowable(Throwable t) {
        try {
            XposedBridge.log(t);
        } catch (Throwable ignored) {
            // 日志失败不能影响目标应用
        }
    }
}
