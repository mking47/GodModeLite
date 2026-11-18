package com.dsh.godmodelite.core;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.graphics.Rect;

/**
 * 一条「遮挡规则」：描述目标应用里的某个界面元素。
 * 既是内存模型，也负责与备份 JSON 互转。
 */
public class Rule {

    /** 处理方式：遮挡（用白色蒙版盖住，无障碍/root 模式可用）。 */
    public static final String MODE_MASK = "mask";
    /** 处理方式：真正删除（在 LSPosed 模块进程里 setVisibility(GONE)，需模块启用）。 */
    public static final String MODE_GONE = "gone";

    /** UUID，规则唯一标识。 */
    public String id;
    /** 目标应用包名。 */
    public String pkg;
    /** 抓取时所在窗口的类名（可空，仅参考）。 */
    public String activity;
    /** 形如 "com.foo:id/btn" 的 View id（可空）。 */
    public String viewId;
    /** 控件类名，如 android.widget.ImageView。 */
    public String cls;
    /** 控件文字快照（可空）。 */
    public String text;
    /** 从根节点起的子节点下标路径，如 "0/1/3"。 */
    public String path;
    /** 抓取时屏幕坐标（左上右下）。 */
    public int l;
    public int t;
    public int r;
    public int b;
    /** 规则是否启用。 */
    public boolean enabled;
    /** 处理方式："mask"（遮挡，默认）或 "gone"（真正删除）。旧数据/缺失时默认 mask。 */
    public String mode;
    /** 创建时间（毫秒）。 */
    public long created;

    /** 空构造：默认生成 id、启用、遮挡模式、记录创建时间。 */
    public Rule() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
        this.mode = MODE_MASK;
        this.created = System.currentTimeMillis();
    }

    /** 常用构造：用 Rect 表示抓取范围。 */
    public Rule(String pkg, String activity, String viewId, String cls, String text, String path, Rect bounds) {
        this(pkg, activity, viewId, cls, text, path,
                bounds == null ? 0 : bounds.left,
                bounds == null ? 0 : bounds.top,
                bounds == null ? 0 : bounds.right,
                bounds == null ? 0 : bounds.bottom);
    }

    /** 常用构造：用四个整数表示抓取范围。 */
    public Rule(String pkg, String activity, String viewId, String cls, String text, String path,
                int l, int t, int r, int b) {
        this();
        this.pkg = pkg;
        this.activity = activity;
        this.viewId = viewId;
        this.cls = cls;
        this.text = text;
        this.path = path;
        this.l = l;
        this.t = t;
        this.r = r;
        this.b = b;
    }

    /** 给列表/确认卡片显示的一行描述：优先文字，其次 View id 末段，最后类名简名。 */
    public String describe() {
        if (text != null && text.trim().length() > 0) {
            return trim(text.trim());
        }
        if (viewId != null && viewId.length() > 0) {
            int slash = viewId.lastIndexOf('/');
            String name = slash >= 0 ? viewId.substring(slash + 1) : viewId;
            if (name.length() > 0) {
                return trim(name);
            }
        }
        if (cls != null && cls.length() > 0) {
            int dot = cls.lastIndexOf('.');
            String name = dot >= 0 ? cls.substring(dot + 1) : cls;
            if (name.length() > 0) {
                return trim(name);
            }
        }
        return "未命名元素";
    }

    private static String trim(String s) {
        if (s.length() > 40) {
            return s.substring(0, 40) + "…";
        }
        return s;
    }

    /** 复制一份（列表返回拷贝，避免外部改内部对象）。 */
    public Rule copy() {
        Rule c = new Rule();
        c.id = id;
        c.pkg = pkg;
        c.activity = activity;
        c.viewId = viewId;
        c.cls = cls;
        c.text = text;
        c.path = path;
        c.l = l;
        c.t = t;
        c.r = r;
        c.b = b;
        c.enabled = enabled;
        c.mode = normalizeMode(mode);
        c.created = created;
        return c;
    }

    /** 是否为「真删」规则（mode 缺失/非法一律按遮挡处理）。 */
    public boolean isGone() {
        return MODE_GONE.equals(normalizeMode(mode));
    }

    /** 规范化 mode：只认 "gone"，其余（null/缺失/大小写/脏数据）都返回 "mask"。 */
    public static String normalizeMode(String mode) {
        if (mode != null && MODE_GONE.equalsIgnoreCase(mode.trim())) {
            return MODE_GONE;
        }
        return MODE_MASK;
    }

    /** 序列化为备份用的 JSON 对象。空字符串/空值字段不写入，保持文件精简。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            if (id != null) {
                o.put("id", id);
            }
            if (pkg != null) {
                o.put("pkg", pkg);
            }
            if (activity != null) {
                o.put("activity", activity);
            }
            if (viewId != null) {
                o.put("viewId", viewId);
            }
            if (cls != null) {
                o.put("cls", cls);
            }
            if (text != null) {
                o.put("text", text);
            }
            if (path != null) {
                o.put("path", path);
            }
            o.put("l", l);
            o.put("t", t);
            o.put("r", r);
            o.put("b", b);
            o.put("enabled", enabled);
            // mode 始终写出（默认 "mask"），保证备份/Provider 里的数据自描述。
            o.put("mode", normalizeMode(mode));
            o.put("created", created);

            // 同时写入原版「上帝模式」备份的键名，方便与原版备份互转。
            if (pkg != null) {
                o.put("package_name", pkg);
            }
            if (activity != null) {
                o.put("act_class", activity);
            }
            if (viewId != null) {
                o.put("res_name", viewId);
            }
            if (cls != null) {
                o.put("view_class", cls);
            }
            o.put("depth", depthArray(path));
            o.put("x", l);
            o.put("y", t);
            o.put("width", Math.max(0, r - l));
            o.put("height", Math.max(0, b - t));
        } catch (JSONException e) {
            // JSONObject.put(String, Object) 理论不会抛；这里吞掉以保证备份流程不断。
        }
        return o;
    }

    /** 从 JSON 还原；字段缺失全部容错，只有 pkg 可能为空。 */
    public static Rule fromJson(JSONObject o) {
        Rule rule = new Rule();
        if (o == null) {
            return rule;
        }
        String id = str(o, "id");
        rule.id = (id != null && id.length() > 0) ? id : UUID.randomUUID().toString();
        rule.pkg = str(o, "pkg");
        rule.activity = str(o, "activity");
        rule.viewId = str(o, "viewId");
        rule.cls = str(o, "cls");
        rule.text = str(o, "text");
        rule.path = str(o, "path");
        rule.l = o.optInt("l", 0);
        rule.t = o.optInt("t", 0);
        rule.r = o.optInt("r", 0);
        rule.b = o.optInt("b", 0);
        rule.enabled = o.optBoolean("enabled", true);
        // 旧版备份/旧数据没有 mode 字段：默认遮挡，保证向后兼容。
        rule.mode = normalizeMode(str(o, "mode"));
        rule.created = o.optLong("created", 0L);
        if (rule.created <= 0L) {
            rule.created = o.optLong("timestamp", 0L);
        }
        if (rule.created <= 0L) {
            rule.created = System.currentTimeMillis();
        }
        // 兼容原版「上帝模式」备份的键名（package_name / res_name / view_class / depth / x,y,width,height）。
        if (rule.pkg == null) {
            rule.pkg = str(o, "package_name");
        }
        if (rule.activity == null) {
            rule.activity = str(o, "act_class");
        }
        if (rule.viewId == null) {
            rule.viewId = str(o, "res_name");
        }
        if (rule.cls == null) {
            rule.cls = str(o, "view_class");
        }
        if (rule.path == null) {
            rule.path = pathFromDepth(o.optJSONArray("depth"));
        }
        if (!o.has("l") && !o.has("r")) {
            int x = o.optInt("x", 0);
            int y = o.optInt("y", 0);
            int w = o.optInt("width", 0);
            int h = o.optInt("height", 0);
            rule.l = x;
            rule.t = y;
            rule.r = x + Math.max(0, w);
            rule.b = y + Math.max(0, h);
        }
        return rule;
    }

    /** "0/1/3" -> [0,1,3]；为空/非法则返回空数组。 */
    private static JSONArray depthArray(String path) {
        JSONArray arr = new JSONArray();
        if (path == null || path.length() == 0) {
            return arr;
        }
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i] == null ? "" : parts[i].trim();
            if (s.length() == 0) {
                continue;
            }
            try {
                arr.put(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                // 非法段直接跳过。
            }
        }
        return arr;
    }

    /** 原版 depth 整数数组 -> "0/1/3"；为空返回 null。 */
    private static String pathFromDepth(JSONArray depth) {
        if (depth == null || depth.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth.length(); i++) {
            int v = depth.optInt(i, -1);
            if (v < 0) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(v);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 读取可空字符串字段，缺省或 "null" 文本都当作 null。 */
    private static String str(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        String v;
        try {
            v = o.getString(key);
        } catch (JSONException e) {
            return null;
        }
        if (v == null || v.length() == 0 || "null".equals(v)) {
            return null;
        }
        return v;
    }

    @Override
    public String toString() {
        return "Rule{" + pkg + " " + describe() + " enabled=" + enabled + "}";
    }
}
