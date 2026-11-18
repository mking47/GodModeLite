import com.dsh.godmodelite.core.AutoBackupManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/** JVM 侧验证自动备份的「只保留最近 2 份、旧的按时间删除」逻辑（纯文件，不依赖 Android 运行时）。 */
public class TestAutoBackup {

    static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name + (detail == null ? "" : "  " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "gm_lite_autobackup_test");
        deleteDir(dir);
        check("目录创建", dir.mkdirs(), dir.getAbsolutePath());

        // 造 3 份备份：名字带时间戳，修改时间递增
        File a = create(dir, "rules-20260101-100000.json", 1000L);
        File b = create(dir, "rules-20260102-100000.json", 2000L);
        File c = create(dir, "rules-20260103-100000.json", 3000L);

        File[] listed = AutoBackupManager.listDir(dir);
        check("listDir 返回 3 份", listed.length == 3, String.valueOf(listed.length));
        check("listDir 按时间新→旧", listed[0].getName().equals("rules-20260103-100000.json"),
                listed[0].getName());

        AutoBackupManager.pruneDir(dir, 2);
        File[] after = AutoBackupManager.listDir(dir);
        check("只保留 2 份", after.length == 2, String.valueOf(after.length));
        check("保留的是最新两份", after[0].getName().equals("rules-20260103-100000.json")
                        && after[1].getName().equals("rules-20260102-100000.json"),
                Arrays.toString(after));
        check("最旧一份被删除", !a.exists(), a.getName());

        // 非备份文件不参与清理
        File other = new File(dir, "notes.txt");
        write(other, "keep me");
        AutoBackupManager.pruneDir(dir, 2);
        check("非备份文件不受影响", other.exists(), other.getName());

        deleteDir(dir);
        System.out.println(failed == 0 ? "\nALL TESTS PASSED" : "\n" + failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }

    static File create(File dir, String name, long mtime) throws Exception {
        File f = new File(dir, name);
        write(f, "{\"rules\":[],\"count\":0}");
        f.setLastModified(mtime);
        return f;
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(s.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                children[i].delete();
            }
        }
        dir.delete();
    }
}
