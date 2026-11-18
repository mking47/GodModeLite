import com.dsh.godmodelite.core.Rule;
import com.dsh.godmodelite.core.BackupManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** JVM 侧验证：备份 JSON 序列化/反序列化 + 原版 ZIP 备份解析（不依赖 Android 运行时）。 */
public class TestBackup {

    static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name + (detail == null ? "" : "  " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. Rule 自身 JSON 往返 ----------
        Rule r = new Rule("com.foo.app", "com.foo.app.MainActivity", "com.foo.app:id/banner",
                "android.widget.ImageView", "广告位", "0/1/3", 10, 20, 110, 220);
        JSONObject o = r.toJson();
        check("toJson 含原版键 package_name", "com.foo.app".equals(o.getString("package_name")), null);
        check("toJson 含原版键 res_name", "com.foo.app:id/banner".equals(o.getString("res_name")), null);
        check("toJson depth=[0,1,3]", o.getJSONArray("depth").length() == 3 && o.getJSONArray("depth").getInt(2) == 3, null);
        check("toJson width=100/height=200", o.getInt("width") == 100 && o.getInt("height") == 200, null);

        Rule back = Rule.fromJson(o);
        check("fromJson pkg/viewId 往返", "com.foo.app".equals(back.pkg) && "com.foo.app:id/banner".equals(back.viewId), null);
        check("fromJson path 往返", "0/1/3".equals(back.path), null);
        check("fromJson bounds 往返", back.l == 10 && back.t == 20 && back.r == 110 && back.b == 220, null);
        check("fromJson cls/text 往返", "android.widget.ImageView".equals(back.cls) && "广告位".equals(back.text), null);

        // ---------- 2. 原版上帝模式 JSON（只有原版键）----------
        JSONObject orig = new JSONObject();
        orig.put("package_name", "com.bar.app");
        orig.put("act_class", "com.bar.app.A");
        orig.put("res_name", "com.bar.app:id/ad");
        orig.put("view_class", "android.widget.FrameLayout");
        orig.put("text", "推广");
        orig.put("depth", new JSONArray("[0,2]"));
        orig.put("x", 5);
        orig.put("y", 6);
        orig.put("width", 50);
        orig.put("height", 60);
        orig.put("timestamp", 123456789L);
        Rule mapped = Rule.fromJson(orig);
        check("原版键映射 pkg", "com.bar.app".equals(mapped.pkg), null);
        check("原版键映射 viewId", "com.bar.app:id/ad".equals(mapped.viewId), null);
        check("原版键映射 path", "0/2".equals(mapped.path), null);
        check("原版键映射 bounds", mapped.l == 5 && mapped.t == 6 && mapped.r == 55 && mapped.b == 66, null);
        check("原版键映射 created", mapped.created == 123456789L, null);

        // ---------- 3. BackupManager 原版 ZIP 解析（反射调用私有方法）----------
        byte[] zip = buildOriginalZip(mapped);
        Method isZip = BackupManager.class.getDeclaredMethod("isZip", byte[].class);
        isZip.setAccessible(true);
        check("BackupManager.isZip 识别 PK", (Boolean) isZip.invoke(null, (Object) zip), null);

        Method parseZip = BackupManager.class.getDeclaredMethod("parseOriginalZip", byte[].class);
        parseZip.setAccessible(true);
        Object result = parseZip.invoke(null, (Object) zip);
        List<Rule> rules = (List<Rule>) result.getClass().getField("rules").get(result);
        String format = (String) result.getClass().getField("format").get(result);
        check("原版 ZIP 解析出 1 条规则", rules != null && rules.size() == 1, rules == null ? "null" : String.valueOf(rules.size()));
        check("原版 ZIP 格式识别", BackupManager.FORMAT_ORIGINAL.equals(format), format);
        if (rules != null && rules.size() == 1) {
            check("原版 ZIP 规则内容正确", "com.bar.app:id/ad".equals(rules.get(0).viewId)
                    && "0/2".equals(rules.get(0).path), null);
        }

        // ---------- 4. BackupManager 自己的 JSON 解析（反射）----------
        JSONObject lite = new JSONObject();
        lite.put("app", "GodModeLite");
        lite.put("version", 1);
        lite.put("blocking", false);
        JSONArray arr = new JSONArray();
        arr.put(r.toJson());
        arr.put(mapped.toJson());
        lite.put("rules", arr);
        Method parseLite = BackupManager.class.getDeclaredMethod("parseLiteJson", String.class);
        parseLite.setAccessible(true);
        Object liteRes = parseLite.invoke(null, lite.toString());
        List<Rule> liteRules = (List<Rule>) liteRes.getClass().getField("rules").get(liteRes);
        boolean blocking = (Boolean) liteRes.getClass().getField("blocking").get(liteRes);
        check("Lite JSON 解析出 2 条规则", liteRules != null && liteRules.size() == 2, liteRules == null ? "null" : String.valueOf(liteRules.size()));
        check("Lite JSON blocking=false 生效", !blocking, null);

        // ---------- 5. 坏数据不崩溃 ----------
        boolean threw = false;
        try {
            JSONObject bad = new JSONObject();
            bad.put("version", 99);
            bad.put("rules", arr);
            parseLite.invoke(null, bad.toString());
        } catch (Exception e) {
            threw = true;
        }
        check("高版本备份被拒绝", threw, null);

        System.out.println(failed == 0 ? "\nALL TESTS PASSED" : "\n" + failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }

    /** 构造一个原版格式的 ZIP（manifest.json + 占位 webp，后缀无关）。 */
    static byte[] buildOriginalZip(Rule rule) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("version", 1);
        manifest.put("packageName", rule.pkg);
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("package_name", rule.pkg);
        item.put("act_class", rule.activity == null ? "" : rule.activity);
        item.put("res_name", rule.viewId);
        item.put("view_class", rule.cls);
        item.put("text", rule.text);
        item.put("depth", new JSONArray("[0,2]"));
        item.put("x", rule.l);
        item.put("y", rule.t);
        item.put("width", rule.r - rule.l);
        item.put("height", rule.b - rule.t);
        item.put("timestamp", rule.created);
        arr.put(item);
        manifest.put("rules", arr);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.putNextEntry(new ZipEntry("manifest.json"));
        zos.write(manifest.toString().getBytes("UTF-8"));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("1700000000000.webp"));
        zos.write(new byte[] {1, 2, 3, 4});
        zos.closeEntry();
        zos.close();
        return bos.toByteArray();
    }
}
