package com.dsh.godmodelite.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.util.Log;

/**
 * v4 自动备份：开启屏蔽时自动把规则备份到「软件默认目录」，只保留最近 2 份，新的覆盖旧的。
 *
 * 目录：应用外部私有目录 Android/data/com.dsh.godmodelite/files/backup/
 * （拿不到外部目录时退回内部 files/backup/）。
 * 文件名：rules-yyyyMMdd-HHmmss.json（与本作手动备份的 JSON 格式完全一致，可互相恢复）。
 */
public final class AutoBackupManager {

    private static final String TAG = "GodModeAutoBackup";

    /** 默认保留的备份份数。 */
    public static final int MAX_BACKUPS = 2;

    private static final String DIR_NAME = "backup";
    private static final String PREFIX = "rules-";
    private static final String SUFFIX = ".json";

    private AutoBackupManager() {
    }

    /** 自动备份目录（不存在会创建）。 */
    public static File backupDir(Context ctx) {
        if (ctx == null) {
            return null;
        }
        File dir = null;
        try {
            dir = ctx.getExternalFilesDir(DIR_NAME);
        } catch (Throwable t) {
            dir = null;
        }
        if (dir == null) {
            dir = new File(ctx.getFilesDir(), DIR_NAME);
        }
        if (!dir.exists()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
                // 创建失败时后续读写会走异常分支，交给调用方提示。
            }
        }
        return dir;
    }

    /** 目录展示路径（相对 Android/data 的简写，给 UI 用）。 */
    public static String dirDisplayPath(Context ctx) {
        File dir = backupDir(ctx);
        if (dir == null) {
            return "backup/";
        }
        String path = dir.getAbsolutePath();
        int idx = path.indexOf("/files/");
        if (idx >= 0) {
            return path.substring(idx + 1);
        }
        return path;
    }

    /**
     * 立即自动备份一次（调用方必须放子线程）。
     *
     * @return 生成的备份文件
     */
    public static File autoBackup(Context ctx) throws Exception {
        if (ctx == null) {
            throw new IllegalArgumentException("上下文为空");
        }
        List<Rule> rules = ConfigStore.allRules();
        boolean blocking = ConfigStore.isBlockingEnabled();
        File dir = backupDir(ctx);
        if (dir == null) {
            throw new IOException("无法创建备份目录");
        }
        String name = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + SUFFIX;
        File file = new File(dir, name);
        BackupManager.exportToFile(file, rules, blocking);
        prune(ctx, MAX_BACKUPS);
        Log.i(TAG, "自动备份完成：" + file.getAbsolutePath() + "，规则 " + rules.size() + " 条");
        return file;
    }

    /** 只保留最新的 max 份，其余按时间从旧到新删除。 */
    public static void prune(Context ctx, int max) {
        pruneDir(backupDir(ctx), max);
    }

    /** 对指定目录做保留 max 份的清理（纯文件逻辑，便于单元测试）。 */
    public static void pruneDir(File dir, int max) {
        File[] files = listDir(dir);
        if (files == null || files.length <= max) {
            return;
        }
        for (int i = max; i < files.length; i++) {
            try {
                files[i].delete();
            } catch (Throwable ignored) {
                // 删除失败不影响本次备份。
            }
        }
    }

    /** 备份文件列表，按时间从新到旧。 */
    public static File[] listBackups(Context ctx) {
        return listDir(backupDir(ctx));
    }

    /** 列出某个目录下的备份文件，按时间从新到旧（纯文件逻辑，便于单元测试）。 */
    public static File[] listDir(File dir) {
        if (dir == null) {
            return new File[0];
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        List<File> list = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f != null && f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(SUFFIX)) {
                list.add(f);
            }
        }
        File[] out = list.toArray(new File[0]);
        Arrays.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                // 文件名带时间戳，名字倒序即时间倒序；lastModified 兜底。
                int byName = b.getName().compareTo(a.getName());
                if (byName != 0) {
                    return byName;
                }
                return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
            }
        });
        return out;
    }

    /** 最近一份备份；没有返回 null。 */
    public static File latestBackup(Context ctx) {
        File[] files = listBackups(ctx);
        return files.length == 0 ? null : files[0];
    }

    /** 状态文案：`2 份 · 最近 08-18 12:03`。 */
    public static String summary(Context ctx) {
        File[] files = listBackups(ctx);
        if (files.length == 0) {
            return "暂无";
        }
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(files[0].lastModified()));
        return files.length + " 份 · 最近 " + time;
    }

    /** 今天是否已经有备份（用于启动时补一次备份，避免重复）。 */
    public static boolean hasBackupToday(Context ctx) {
        File latest = latestBackup(ctx);
        if (latest == null) {
            return false;
        }
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String fileDay = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(latest.lastModified()));
        return today.equals(fileDay);
    }

    /** 读取一份备份文件到内存（调用方在子线程执行）。 */
    public static byte[] readFile(File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IOException("备份文件不存在");
        }
        if (file.length() > 32L * 1024L * 1024L) {
            throw new IOException("备份文件过大");
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 忽略。
                }
            }
        }
    }
}
