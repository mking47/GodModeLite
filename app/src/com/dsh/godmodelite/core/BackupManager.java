package com.dsh.godmodelite.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentResolver;
import android.net.Uri;

/**
 * 规则备份/恢复（SAF Uri，不需要存储权限）。
 *
 * 导出格式（上帝模式 Lite 自定义 JSON）：
 * {"app":"GodModeLite","version":1,"exportedAt":毫秒,"blocking":bool,"count":n,"rules":[...]}
 *
 * v2：每条规则的 "mode" 字段（mask/gone）由 {@link Rule#toJson()} 自动写入、
 * {@link Rule#fromJson(JSONObject)} 读取；旧备份没有该字段时默认按遮挡（mask）处理，
 * 所以本类无需为 mode 做额外分支，版本号保持 1（旧版本读新备份会忽略未知字段）。
 *
 * 导入兼容两种格式：
 * 1) 上面的 JSON；
 * 2) 原版「上帝模式」的备份 —— 实际是 ZIP（后缀常为 .gzip），内含 manifest.json，
 *    结构 {"version":1,"packageName":"...","rules":[原版 ViewRule 数组]}，截图 webp 忽略。
 */
public final class BackupManager {

    /** 导出/导入的格式标识。 */
    public static final String FORMAT_LITE = "lite";
    public static final String FORMAT_ORIGINAL = "original";

    private static final String APP_TAG = "GodModeLite";
    private static final int VERSION = 1;
    /** 备份文件读取上限（防止误选超大文件导致 OOM）。 */
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;

    private BackupManager() {
    }

    /** 导入结果：规则 + 识别到的格式 + 备份里的屏蔽开关。 */
    public static class ImportResult {
        public List<Rule> rules = new ArrayList<Rule>();
        /** FORMAT_LITE 或 FORMAT_ORIGINAL。 */
        public String format = FORMAT_LITE;
        /** 备份里是否带 blocking 字段（原版没有）。 */
        public boolean hasBlocking;
        public boolean blocking = true;
        /** 给用户看的格式描述。 */
        public String formatLabel() {
            if (FORMAT_ORIGINAL.equals(format)) {
                return "检测到原版格式备份，共 " + rules.size() + " 条规则";
            }
            return "备份格式：上帝模式 Lite，共 " + rules.size() + " 条规则";
        }
    }

    /** 导出规则到 Uri（调用方负责在子线程执行）。 */
    public static void export(ContentResolver cr, Uri uri, List<Rule> rules, boolean blocking) throws Exception {
        if (cr == null || uri == null) {
            throw new IllegalArgumentException("没有可用的输出文件");
        }
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            if (rules != null) {
                for (int i = 0; i < rules.size(); i++) {
                    Rule r = rules.get(i);
                    if (r == null) {
                        continue;
                    }
                    arr.put(r.toJson());
                }
            }
            root.put("app", APP_TAG);
            root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("blocking", blocking);
            root.put("count", arr.length());
            root.put("rules", arr);
        } catch (JSONException e) {
            throw new Exception("生成备份内容失败", e);
        }
        byte[] data = root.toString().getBytes("UTF-8");
        OutputStream os = null;
        try {
            os = cr.openOutputStream(uri);
            if (os == null) {
                throw new IOException("无法打开备份文件写入流");
            }
            os.write(data);
            os.flush();
        } finally {
            closeQuietly(os);
        }
    }

    /** 导入规则（兼容 Lite JSON 与原版 ZIP 备份）。坏文件抛异常。 */
    public static List<Rule> importRules(ContentResolver cr, Uri uri) throws Exception {
        return importBackup(cr, uri).rules;
    }

    /** 导入并返回完整信息（格式、开关等），供恢复确认对话框使用。 */
    public static ImportResult importBackup(ContentResolver cr, Uri uri) throws Exception {
        byte[] data = readAll(cr, uri);
        if (data.length == 0) {
            throw new Exception("备份文件是空的");
        }
        if (isZip(data)) {
            return parseOriginalZip(data);
        }
        return parseLiteJson(new String(data, "UTF-8"));
    }

    /** 读取 Uri 全部内容（有大小上限）。 */
    private static byte[] readAll(ContentResolver cr, Uri uri) throws Exception {
        if (cr == null || uri == null) {
            throw new IllegalArgumentException("没有可用的备份文件");
        }
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) {
                throw new IOException("无法打开备份文件读取流");
            }
            return readAllLimited(in, MAX_FILE_BYTES);
        } finally {
            closeQuietly(in);
        }
    }

    /** 从流里读全部字节，超过上限抛异常。 */
    private static byte[] readAllLimited(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                throw new Exception("备份文件过大，无法导入");
            }
        }
        return out.toByteArray();
    }

    /** 是否为 ZIP（前两字节 "PK"）。不依赖文件后缀。 */
    private static boolean isZip(byte[] data) {
        return data.length >= 2 && data[0] == 0x50 && data[1] == 0x4B;
    }

    /** 解析我们自己的 JSON 备份格式。 */
    private static ImportResult parseLiteJson(String text) throws Exception {
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new Exception("不是有效的备份文件（JSON 解析失败）", e);
        }
        int version = root.optInt("version", VERSION);
        if (version > VERSION) {
            throw new Exception("不支持的备份版本：" + version);
        }
        JSONArray arr = root.optJSONArray("rules");
        if (arr == null) {
            throw new Exception("备份文件缺少 rules 字段");
        }
        ImportResult res = new ImportResult();
        res.format = FORMAT_LITE;
        res.hasBlocking = root.has("blocking");
        res.blocking = root.optBoolean("blocking", true);
        res.rules = parseRules(arr);
        return res;
    }

    /** 解析原版「上帝模式」的 ZIP 备份：只取 manifest.json，忽略截图。 */
    private static ImportResult parseOriginalZip(byte[] data) throws Exception {
        JSONObject manifest = findManifest(data);
        ImportResult res = new ImportResult();
        res.format = FORMAT_ORIGINAL;
        res.hasBlocking = false;
        res.blocking = true;
        JSONArray arr = manifest.optJSONArray("rules");
        // 原版可能没有 rules（空备份）：按空列表处理，不报错。
        res.rules = parseRules(arr);
        return res;
    }

    /** 在 ZIP 里找到 manifest.json 并解析。 */
    private static JSONObject findManifest(byte[] data) throws Exception {
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data));
        byte[] manifestBytes = null;
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null) {
                    continue;
                }
                if (name.toLowerCase(Locale.US).endsWith("manifest.json")) {
                    manifestBytes = readAllLimited(zis, MAX_MANIFEST_BYTES);
                    break;
                }
            }
        } catch (IOException e) {
            throw new Exception("不是有效的备份文件", e);
        } finally {
            closeQuietly(zis);
        }
        if (manifestBytes == null) {
            throw new Exception("备份文件缺少 manifest.json");
        }
        if (manifestBytes.length == 0) {
            throw new Exception("备份文件中的 manifest.json 是空的");
        }
        try {
            return new JSONObject(new String(manifestBytes, "UTF-8"));
        } catch (JSONException e) {
            throw new Exception("备份文件损坏：manifest.json 解析失败", e);
        } catch (UnsupportedEncodingException e) {
            throw new Exception("备份文件损坏：manifest.json 编码异常", e);
        }
    }

    /** 解析 rules 数组：原版键与本项目键都由 Rule.fromJson 兼容处理。 */
    private static List<Rule> parseRules(JSONArray arr) {
        List<Rule> out = new ArrayList<Rule>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Rule r;
            try {
                r = Rule.fromJson(o);
            } catch (Exception ignored) {
                continue;
            }
            if (r.pkg == null || r.pkg.length() == 0) {
                continue;
            }
            if (r.id == null || r.id.length() == 0) {
                r.id = UUID.randomUUID().toString();
            }
            out.add(r);
        }
        return out;
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
