package com.dsh.godmodelite.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * root 相关工具：检测 root、执行 su 命令、一键自动配置（无障碍服务 + 悬浮窗授权）。
 * 所有方法都会阻塞（su 最多 10 秒），必须放在子线程调用。
 */
public final class RootHelper {

    /** 无障碍服务的完整类名（与 manifest 中声明的服务一致）。 */
    private static final String A11Y_SERVICE_CLASS = "com.dsh.godmodelite.a11y.BlockerService";
    /** 普通命令超时：10 秒。 */
    private static final long EXEC_TIMEOUT_MS = 10000L;
    /** root 检测超时：5 秒。 */
    private static final long ROOT_TIMEOUT_MS = 5000L;
    /** 自动配置脚本执行完成标记。 */
    private static final String MARK_OK = "GM_OK";

    private RootHelper() {
    }

    /** 检测 root：su -c id 的输出里包含 uid=0 即视为有 root。 */
    public static boolean isRoot() {
        String out = execWithTimeout("id", ROOT_TIMEOUT_MS);
        if (out == null) {
            return false;
        }
        return out.indexOf("uid=0") >= 0;
    }

    /** 以 root 执行 shell 命令，返回 stdout+stderr（已合并）；失败时返回错误文本。 */
    public static String exec(String shellCommand) {
        return execWithTimeout(shellCommand, EXEC_TIMEOUT_MS);
    }

    /** 无障碍服务的组件名，如 "com.dsh.godmodelite/com.sh...BlockerService"。 */
    public static String a11yComponent(Context ctx) {
        String pkg = "com.dsh.godmodelite";
        if (ctx != null && ctx.getPackageName() != null) {
            pkg = ctx.getPackageName();
        }
        return pkg + "/" + A11Y_SERVICE_CLASS;
    }

    /** 无障碍服务当前是否已开启：Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 中包含我们的组件。 */
    public static boolean isA11yEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            ContentResolver cr = ctx.getApplicationContext().getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.length() == 0) {
                return false;
            }
            String pkg = ctx.getPackageName();
            String target = a11yComponent(ctx);
            String[] items = list.split(":");
            for (int i = 0; i < items.length; i++) {
                String norm = normalizeComponent(items[i], pkg);
                if (target.equalsIgnoreCase(norm)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /**
     * 规范化无障碍组件名：系统里可能存完整形式 "pkg/com.dsh...BlockerService"，
     * 也可能存缩写形式 "pkg/.a11y.BlockerService"。后者按 Android 的缩写规则展开成完整形式。
     */
    static String normalizeComponent(String item, String pkg) {
        if (item == null) {
            return null;
        }
        String s = item.trim();
        if (s.length() == 0 || pkg == null || pkg.length() == 0) {
            return s;
        }
        String shortPrefix = pkg + "/.";
        if (s.startsWith(shortPrefix)) {
            // "pkg/.a11y.BlockerService" -> "pkg/com.dsh.godmodelite.a11y.BlockerService"
            return pkg + "/" + pkg + s.substring(pkg.length() + 1);
        }
        return s;
    }

    /** 在命令输出里（逐行、按 ':' 拆分后规范化）查找目标组件。 */
    private static boolean outputHasComponent(String out, String comp, String pkg) {
        if (out == null || comp == null) {
            return false;
        }
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            String[] items = line.split(":");
            for (int j = 0; j < items.length; j++) {
                String norm = normalizeComponent(items[j], pkg);
                if (norm != null && comp.equalsIgnoreCase(norm)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 一键自动配置（需要 root）：
     * 1) enabled_accessibility_services 追加我们的组件（已存在则跳过，用 shell case 判重）；
     * 2) accessibility_enabled = 1；
     * 3) appops 允许 SYSTEM_ALERT_WINDOW。
     * 返回 true 表示脚本执行成功且设置后的服务列表里确实包含我们的组件。
     */
    public static boolean autoConfigure(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String pkg = ctx.getPackageName();
        String comp = a11yComponent(ctx);
        // 单行 sh；用 case 判重避免重复追加，避免复杂引号转义。
        String script = "CUR=$(settings get secure enabled_accessibility_services); "
                + "CMP=" + comp + "; "
                + "case \":$CUR:\" in "
                + "*\":$CMP:\"*) ;; "
                + "*) if [ -z \"$CUR\" ] || [ \"$CUR\" = \"null\" ]; then "
                + "settings put secure enabled_accessibility_services \"$CMP\"; "
                + "else settings put secure enabled_accessibility_services \"$CUR:$CMP\"; fi ;; "
                + "esac; "
                + "settings put secure accessibility_enabled 1; "
                + "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; "
                + "echo " + MARK_OK + "; "
                + "settings get secure enabled_accessibility_services";
        String out = exec(script);
        if (out == null) {
            return false;
        }
        int mark = out.indexOf(MARK_OK);
        if (mark < 0) {
            return false;
        }
        // 只看 MARK_OK 之后的输出（即设置后的服务列表），按规范化组件名比较。
        String tail = out.substring(mark + MARK_OK.length());
        return outputHasComponent(tail, comp, pkg);
    }

    // ==================== 系统布局边界（root，全局显示所有控件边框） ====================

    /**
     * 读取系统属性 debug.layout 是否开启（root 执行 getprop）。
     * 开启后系统会为所有应用绘制控件布局边框，方便「看见所有图层」。
     */
    public static boolean isLayoutBoundsEnabled() {
        String out = exec("getprop debug.layout");
        if (out == null) {
            return false;
        }
        return out.contains("true") || out.contains("1");
    }

    /**
     * 开关系统布局边界（root）：setprop debug.layout true/false。
     * 注意是全局属性：影响所有应用，重启手机后自动恢复关闭；不需要时请关闭。
     */
    public static boolean setLayoutBounds(boolean enabled) {
        String value = enabled ? "true" : "false";
        String out = exec("setprop debug.layout " + value + "; getprop debug.layout");
        if (out == null) {
            return false;
        }
        if (enabled) {
            return out.contains("true") || out.contains("1");
        }
        return !out.contains("true") || out.contains("0");
    }

    /** 实际执行 su -c <command>，带超时与输出收集。 */
    private static String execWithTimeout(final String command, long timeoutMs) {
        if (command == null || command.length() == 0) {
            return "[命令为空]";
        }
        final StringBuilder sb = new StringBuilder();
        final Object lock = new Object();
        Process proc;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            pb.redirectErrorStream(true);
            proc = pb.start();
        } catch (Exception e) {
            return "[无法执行 su] " + describeException(e);
        }
        final Process p = proc;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    char[] buf = new char[2048];
                    int n;
                    while ((n = br.read(buf)) > 0) {
                        synchronized (lock) {
                            sb.append(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                    // 进程被 destroy 时会抛，忽略即可。
                } finally {
                    closeQuietly(br);
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        final int[] exitCode = {-1};
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    exitCode[0] = p.waitFor();
                } catch (Exception ignored) {
                    // 忽略。
                }
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        boolean finished;
        try {
            reader.join(timeoutMs);
            finished = !reader.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            try {
                p.destroy();
            } catch (Exception ignored) {
                // 忽略。
            }
            synchronized (lock) {
                return sb.toString() + "\n[命令超时]";
            }
        }
        try {
            waiter.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String out;
        synchronized (lock) {
            out = sb.toString();
        }
        if (exitCode[0] > 0) {
            out = out + "\n[exit=" + exitCode[0] + "]";
        }
        return out;
    }

    private static String describeException(Exception e) {
        if (e == null) {
            return "未知错误";
        }
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 忽略。
        }
    }
}
