package com.dsh.godmodelite.xposed;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块侧的规则同步客户端（运行在目标应用进程里）。
 *
 * 只通过主应用导出的 ContentProvider 交换数据：
 * - 读：content://com.dsh.godmodelite.rules/rules?pkg=&lt;包名&gt; → 一行，列 json（规则数组字符串）+ blocking（0/1）；
 * - 写：content://com.dsh.godmodelite.rules/rules，ContentValues 里放 "json" = 单条规则 JSON。
 *
 * 这里不 import 主应用的任何类（规则模型、配置、界面类都不行），
 * 规则字段名与主应用的规则 JSON 保持一致，用 org.json 自行拼装与解析。
 * 所有异常一律静默降级：主应用被卸载 / provider 不存在 / 无权限时，模块只当没有规则，绝不崩溃目标应用。
 */
public final class RuleClient {

    private static final String TAG = "[GodModeLite] ";

    /** 主应用 RulesProvider 的 authority + 路径。 */
    private static final String BASE_URI = "content://com.dsh.godmodelite.rules";
    private static final String RULES_URI = BASE_URI + "/rules";

    /** 规则内存缓存时长：2 秒内同一个包只查一次 provider。 */
    private static final long CACHE_TTL_MS = 2000L;

    /** 悬浮球默认设置（provider 缺列 / 空值时的兜底）：样式 0=眼睛、尺寸 48dp、透明度 235。 */
    public static final int DEFAULT_BALL_STYLE = 0;
    public static final int DEFAULT_BALL_SIZE_DP = 48;
    public static final int DEFAULT_BALL_ALPHA = 235;

    /** 单包缓存（模块进程里每个包都是独立进程，单条足够）。 */
    private static RuleData sCache;
    private static String sCachePkg;
    private static long sCacheTime;

    private RuleClient() {
        // 工具类，不允许实例化
    }

    // ==================== 读规则 ====================

    /**
     * 查询某个包的规则快照（带 2 秒内存缓存）。可能阻塞，建议在子线程调用。
     *
     * @return 查询成功返回数据（没有规则时 rules 为空、blocking 默认 true）；
     *         provider 不可用等异常返回 null（调用方应保留现有状态，不要清理遮挡层）。
     */
    public static RuleData getRules(Context context, String pkg) {
        if (context == null || pkg == null || pkg.length() == 0) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (RuleClient.class) {
            if (sCache != null && pkg.equals(sCachePkg) && (now - sCacheTime) < CACHE_TTL_MS) {
                return sCache;
            }
        }
        RuleData data = queryRules(context, pkg);
        if (data != null) {
            synchronized (RuleClient.class) {
                sCache = data;
                sCachePkg = pkg;
                sCacheTime = SystemClock.elapsedRealtime();
            }
        }
        return data;
    }

    /** 清空内存缓存（主应用里刚增删规则、或用户点「刷新规则」时调用）。 */
    public static void invalidateCache() {
        synchronized (RuleClient.class) {
            sCache = null;
            sCachePkg = null;
            sCacheTime = 0L;
        }
    }

    private static RuleData queryRules(Context context, String pkg) {
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                return null;
            }
            Uri uri = Uri.parse(RULES_URI + "?pkg=" + Uri.encode(pkg));
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor == null) {
                // provider 没给 cursor：当作「没有规则、屏蔽开启」，不清理也不新增
                return new RuleData(new ArrayList<RuleSpec>(), true);
            }
            String json = null;
            boolean blocking = true;
            int ballStyle = DEFAULT_BALL_STYLE;
            int ballSizeDp = DEFAULT_BALL_SIZE_DP;
            int ballAlpha = DEFAULT_BALL_ALPHA;
            if (cursor.moveToFirst()) {
                int jsonIndex = cursor.getColumnIndex("json");
                int blockingIndex = cursor.getColumnIndex("blocking");
                if (jsonIndex >= 0) {
                    json = cursor.getString(jsonIndex);
                }
                if (blockingIndex >= 0) {
                    blocking = cursor.getInt(blockingIndex) != 0;
                }
                // v3 新增列：悬浮球样式 / 尺寸(dp) / 透明度；缺列或空值走默认值，保证老版本主应用也能用
                int styleIndex = cursor.getColumnIndex("ball_style");
                if (styleIndex >= 0 && !cursor.isNull(styleIndex)) {
                    ballStyle = cursor.getInt(styleIndex);
                }
                int sizeIndex = cursor.getColumnIndex("ball_size");
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    ballSizeDp = cursor.getInt(sizeIndex);
                }
                int alphaIndex = cursor.getColumnIndex("ball_alpha");
                if (alphaIndex >= 0 && !cursor.isNull(alphaIndex)) {
                    ballAlpha = cursor.getInt(alphaIndex);
                }
            }
            // 空值 / 非法值兜底：0 / 48 / 235
            if (ballStyle < 0 || ballStyle > 4) {
                ballStyle = DEFAULT_BALL_STYLE;
            }
            if (ballSizeDp <= 0) {
                ballSizeDp = DEFAULT_BALL_SIZE_DP;
            }
            if (ballAlpha <= 0) {
                ballAlpha = DEFAULT_BALL_ALPHA;
            }
            return new RuleData(parseRules(json, pkg), blocking, ballStyle, ballSizeDp, ballAlpha);
        } catch (Throwable t) {
            log("查询规则失败 pkg=" + pkg + " : " + t);
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // 关闭失败无所谓
                }
            }
        }
    }

    /** 解析规则数组 JSON；坏条目跳过，不抛异常。 */
    private static List<RuleSpec> parseRules(String json, String pkg) {
        List<RuleSpec> result = new ArrayList<RuleSpec>();
        if (json == null) {
            return result;
        }
        String text = json.trim();
        if (text.length() == 0) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object;
                try {
                    object = array.optJSONObject(i);
                } catch (Throwable t) {
                    continue;
                }
                if (object == null) {
                    continue;
                }
                try {
                    RuleSpec rule = RuleSpec.fromJson(object);
                    if (rule.pkg == null) {
                        rule.pkg = pkg;
                    }
                    // 防御：万一 provider 返回了全部规则（忽略 pkg 参数），过滤掉别包规则，避免误删。
                    if (pkg != null && rule.pkg != null && !pkg.equals(rule.pkg)) {
                        continue;
                    }
                    result.add(rule);
                } catch (Throwable t) {
                    log("解析单条规则失败: " + t);
                }
            }
        } catch (Throwable t) {
            log("解析规则数组失败: " + t);
        }
        return result;
    }

    // ==================== 写规则 ====================

    /**
     * 把一条规则写回主应用（ContentValues "json" = 规则 JSON 字符串）。
     * 只用于「图层选择」里点真删/遮挡时保存规则；失败返回 false，由调用方降级为内存规则。
     */
    public static boolean insertRule(Context context, RuleSpec rule) {
        if (context == null || rule == null) {
            return false;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("json", rule.toJson().toString());
            Uri result = resolver.insert(Uri.parse(RULES_URI), values);
            return result != null;
        } catch (Throwable t) {
            log("写入规则失败: " + t);
            return false;
        }
    }

    // ==================== 数据结构 ====================

    /** 一次查询的结果：规则列表 + 屏蔽总开关 + 悬浮球设置（v3）。 */
    public static final class RuleData {
        public final List<RuleSpec> rules;
        public final boolean blocking;
        /** 悬浮球样式：0 眼睛 / 1 圆点 / 2 盾牌 / 3 文字「隐」 / 4 禁止斜杠。 */
        public final int ballStyle;
        /** 悬浮球尺寸（dp）。 */
        public final int ballSizeDp;
        /** 悬浮球透明度 150~255。 */
        public final int ballAlpha;

        public RuleData(List<RuleSpec> rules, boolean blocking) {
            this(rules, blocking, DEFAULT_BALL_STYLE, DEFAULT_BALL_SIZE_DP, DEFAULT_BALL_ALPHA);
        }

        public RuleData(List<RuleSpec> rules, boolean blocking,
                        int ballStyle, int ballSizeDp, int ballAlpha) {
            this.rules = rules == null ? new ArrayList<RuleSpec>() : rules;
            this.blocking = blocking;
            this.ballStyle = ballStyle;
            this.ballSizeDp = ballSizeDp;
            this.ballAlpha = ballAlpha;
        }
    }

    /**
     * 模块内的规则模型：字段名与主应用规则 JSON 完全一致。
     * 不引用主应用的规则类（运行在目标进程，主应用的类根本加载不到）。
     */
    public static final class RuleSpec {

        public String id;
        public String pkg;
        public String activity;
        public String viewId;
        public String cls;
        public String text;
        public String path;
        public int l;
        public int t;
        public int r;
        public int b;
        public boolean enabled = true;
        public long created;
        /** "gone" = 真删（setVisibility(GONE)）；其它（含缺省）= "mask" = 遮挡。 */
        public String mode = "mask";

        public RuleSpec() {
        }

        /** 给日志/卡片用的一行描述：优先文字，其次 viewId 末段，最后类名简名。 */
        public String describe() {
            if (text != null && text.trim().length() > 0) {
                return trim(text.trim(), 40);
            }
            if (viewId != null && viewId.length() > 0) {
                int slash = viewId.lastIndexOf('/');
                String name = slash >= 0 ? viewId.substring(slash + 1) : viewId;
                if (name.length() > 0) {
                    return trim(name, 40);
                }
            }
            if (cls != null && cls.length() > 0) {
                int dot = cls.lastIndexOf('.');
                String name = dot >= 0 ? cls.substring(dot + 1) : cls;
                if (name.length() > 0) {
                    return trim(name, 40);
                }
            }
            return "未命名元素";
        }

        /** 序列化为 provider 写入用的 JSON。 */
        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                if (id != null) {
                    object.put("id", id);
                }
                if (pkg != null) {
                    object.put("pkg", pkg);
                }
                if (activity != null) {
                    object.put("activity", activity);
                }
                if (viewId != null) {
                    object.put("viewId", viewId);
                }
                if (cls != null) {
                    object.put("cls", cls);
                }
                if (text != null) {
                    object.put("text", text);
                }
                if (path != null) {
                    object.put("path", path);
                }
                object.put("l", l);
                object.put("t", t);
                object.put("r", r);
                object.put("b", b);
                object.put("enabled", enabled);
                object.put("created", created);
                object.put("mode", mode);
            } catch (Throwable t) {
                log("拼规则 JSON 失败: " + t);
            }
            return object;
        }

        /** 从 provider 返回的 JSON 解析；字段缺失全部容错。 */
        public static RuleSpec fromJson(JSONObject object) {
            RuleSpec rule = new RuleSpec();
            if (object == null) {
                return rule;
            }
            rule.id = optString(object, "id");
            rule.pkg = optString(object, "pkg");
            if (rule.pkg == null) {
                rule.pkg = optString(object, "package_name");
            }
            rule.activity = optString(object, "activity");
            if (rule.activity == null) {
                rule.activity = optString(object, "act_class");
            }
            rule.viewId = optString(object, "viewId");
            if (rule.viewId == null) {
                rule.viewId = optString(object, "res_name");
            }
            rule.cls = optString(object, "cls");
            if (rule.cls == null) {
                rule.cls = optString(object, "view_class");
            }
            rule.text = optString(object, "text");
            rule.path = optString(object, "path");
            if (rule.path == null) {
                rule.path = pathFromDepth(object.optJSONArray("depth"));
            }
            rule.l = object.optInt("l", 0);
            rule.t = object.optInt("t", 0);
            rule.r = object.optInt("r", 0);
            rule.b = object.optInt("b", 0);
            if (!object.has("l") && !object.has("r")) {
                int x = object.optInt("x", 0);
                int y = object.optInt("y", 0);
                int width = object.optInt("width", 0);
                int height = object.optInt("height", 0);
                rule.l = x;
                rule.t = y;
                rule.r = x + Math.max(0, width);
                rule.b = y + Math.max(0, height);
            }
            rule.enabled = object.optBoolean("enabled", true);
            rule.created = object.optLong("created", 0L);
            if (rule.created <= 0L) {
                rule.created = object.optLong("timestamp", 0L);
            }
            if (rule.created <= 0L) {
                rule.created = System.currentTimeMillis();
            }
            String mode = optString(object, "mode");
            rule.mode = "gone".equalsIgnoreCase(mode) ? "gone" : "mask";
            if (rule.id == null || rule.id.length() == 0) {
                rule.id = UUID.randomUUID().toString();
            }
            return rule;
        }

        private static String pathFromDepth(JSONArray depth) {
            if (depth == null || depth.length() == 0) {
                return null;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < depth.length(); i++) {
                int value = depth.optInt(i, -1);
                if (value < 0) {
                    return null;
                }
                if (builder.length() > 0) {
                    builder.append('/');
                }
                builder.append(value);
            }
            return builder.length() == 0 ? null : builder.toString();
        }
    }

    // ==================== 工具方法 ====================

    /** 读取可空字符串：缺失 / 空串 / "null" 文本都当 null。 */
    private static String optString(JSONObject object, String key) {
        try {
            if (object == null || !object.has(key) || object.isNull(key)) {
                return null;
            }
            String value = object.getString(key);
            if (value == null || value.length() == 0 || "null".equals(value)) {
                return null;
            }
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() > max) {
            return text.substring(0, max) + "…";
        }
        return text;
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // 日志失败不能影响目标应用
        }
    }
}
