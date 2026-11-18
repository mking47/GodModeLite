package com.dsh.godmodelite.core;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * 规则 ContentProvider：给 LSPosed 模块进程同步规则用（跨进程契约）。
 *
 * 权限与声明在 manifest（Lead 负责）：authorities="com.dsh.godmodelite.rules"，exported="true"、无权限。
 *
 * 约定：
 * - query: content://com.dsh.godmodelite.rules/rules?pkg=<包名>
 *     固定返回 1 行，列 json = 该包规则 JSON **数组字符串**（无规则时 "[]"），blocking = 0/1。
 *     v3 增加列 ball_style / ball_size / ball_alpha（悬浮球样式设置，模块端消费）。
 *     不带 pkg 时返回全部规则（每行一条，json 为单条规则 JSON）。
 * - insert: ContentValues 里 "json" = 单条规则 JSON 字符串；返回 content://.../rules/<id>。
 * - delete: selection "_id=?" 按规则 id 删除；selection "pkg=?" 清空该包。也支持 Uri 末段 id。
 *
 * 所有异常一律吞掉（返回 null / 0），保证模块或主应用任何一侧出问题都不会 crash 对方进程。
 */
public class RulesProvider extends ContentProvider {

    private static final String TAG = "GodModeRules";

    /** Provider authority（与 manifest 一致）。 */
    public static final String AUTHORITY = "com.dsh.godmodelite.rules";
    /** 规则集合 Uri：content://com.dsh.godmodelite.rules/rules */
    public static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY + "/rules");

    /** 列名：规则 id（字符串 UUID）。 */
    public static final String COL_ID = "_id";
    /** 列名：目标包名。 */
    public static final String COL_PKG = "pkg";
    /** 列名：规则 JSON（单条或数组，见类注释）。 */
    public static final String COL_JSON = "json";
    /** 列名：全局屏蔽开关（0/1）。 */
    public static final String COL_BLOCKING = "blocking";
    /** 列名：悬浮球样式（v3，0 眼睛 / 1 圆点 / 2 盾牌 / 3 文字「隐」/ 4 禁止斜杠）。 */
    public static final String COL_BALL_STYLE = "ball_style";
    /** 列名：悬浮球尺寸 dp（v3，40/48/56）。 */
    public static final String COL_BALL_SIZE = "ball_size";
    /** 列名：悬浮球透明度（v3，150~255）。 */
    public static final String COL_BALL_ALPHA = "ball_alpha";
    /** 列名：悬浮球是否开启（v4，0/1；手动开关才为 1）。 */
    public static final String COL_BALL_VISIBLE = "ball_visible";
    /** 列名：无障碍服务是否已连接（v5，0/1；连接时全局已有唯一悬浮球，模块不再加球）。 */
    public static final String COL_A11Y_CONNECTED = "a11y_connected";

    private static final String[] COLUMNS = {COL_ID, COL_PKG, COL_JSON, COL_BLOCKING,
            COL_BALL_STYLE, COL_BALL_SIZE, COL_BALL_ALPHA, COL_BALL_VISIBLE, COL_A11Y_CONNECTED};

    @Override
    public boolean onCreate() {
        initStore();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        MatrixCursor cursor = null;
        try {
            initStore();
            cursor = new MatrixCursor(COLUMNS);
            int blocking = ConfigStore.isBlockingEnabled() ? 1 : 0;
            // v3：悬浮球样式设置随规则一起下发，模块端按同样参数画球。
            int ballStyle = ConfigStore.getBallStyle();
            int ballSize = ConfigStore.getBallSizeDp();
            int ballAlpha = ConfigStore.getBallAlpha();

            String pkg = null;
            if (uri != null) {
                try {
                    pkg = uri.getQueryParameter(COL_PKG);
                } catch (Throwable ignored) {
                    pkg = null;
                }
            }
            if (pkg == null || pkg.length() == 0) {
                pkg = valueFromSelection(selection, selectionArgs, COL_PKG);
            }

            if (pkg != null && pkg.length() > 0) {
                // 单包查询：恒返回 1 行，json 为数组字符串（模块端按包名解析）。
                List<Rule> rules = ConfigStore.rulesFor(pkg);
                cursor.addRow(new Object[]{0, pkg, rulesToJsonArray(rules), blocking,
                        ballStyle, ballSize, ballAlpha, ConfigStore.isBallVisible() ? 1 : 0,
                        ConfigStore.isA11yConnected() ? 1 : 0});
                return cursor;
            }

            // 全部规则：一行一条（json 为单条规则 JSON），供调试/统计使用。
            List<Rule> all = ConfigStore.allRules();
            for (int i = 0; i < all.size(); i++) {
                Rule r = all.get(i);
                String json;
                try {
                    json = r.toJson().toString();
                } catch (Throwable t) {
                    continue;
                }
                cursor.addRow(new Object[]{i + 1, r.pkg, json, blocking,
                        ballStyle, ballSize, ballAlpha, ConfigStore.isBallVisible() ? 1 : 0,
                        ConfigStore.isA11yConnected() ? 1 : 0});
            }
            return cursor;
        } catch (Throwable t) {
            Log.w(TAG, "query 失败", t);
            // 出错也要给一个带表头的空 cursor，调用方遍历时不会 NPE。
            return cursor != null ? cursor : new MatrixCursor(COLUMNS);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            initStore();
            if (values == null) {
                return null;
            }
            String json = values.getAsString(COL_JSON);
            if (json == null || json.trim().length() == 0) {
                return null;
            }
            Rule rule;
            try {
                rule = Rule.fromJson(new JSONObject(json));
            } catch (Throwable t) {
                Log.w(TAG, "insert 的 json 无法解析", t);
                return null;
            }
            if (rule.pkg == null || rule.pkg.length() == 0) {
                return null;
            }
            ConfigStore.addRule(rule);
            String id = rule.id == null ? "" : rule.id;
            return Uri.withAppendedPath(BASE_URI, Uri.encode(id));
        } catch (Throwable t) {
            Log.w(TAG, "insert 失败", t);
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            initStore();
            String id = valueFromSelection(selection, selectionArgs, COL_ID);
            if (id == null || id.length() == 0) {
                id = lastPathId(uri);
            }
            if (id != null && id.length() > 0) {
                ConfigStore.deleteRule(id);
                return 1;
            }
            String pkg = valueFromSelection(selection, selectionArgs, COL_PKG);
            if ((pkg == null || pkg.length() == 0) && uri != null) {
                try {
                    pkg = uri.getQueryParameter(COL_PKG);
                } catch (Throwable ignored) {
                    pkg = null;
                }
            }
            if (pkg != null && pkg.length() > 0) {
                int n = ConfigStore.ruleCount(pkg);
                ConfigStore.clearRules(pkg);
                return n;
            }
            return 0;
        } catch (Throwable t) {
            Log.w(TAG, "delete 失败", t);
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // 规则修改统一走 add/delete（insert 同 id 即覆盖），不支持单独 update。
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.dsh.godmodelite.rules";
    }

    // ===================== 内部工具 =====================

    /** 幂等初始化存储；Provider 可能在主应用未启动时被模块进程唤起。 */
    private void initStore() {
        try {
            Context ctx = getContext();
            if (ctx != null) {
                ConfigStore.init(ctx);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ConfigStore 初始化失败", t);
        }
    }

    /** List<Rule> -> JSON 数组字符串；规则序列化失败时跳过该条，保证不返回 null。 */
    private static String rulesToJsonArray(List<Rule> rules) {
        JSONArray arr = new JSONArray();
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                Rule r = rules.get(i);
                if (r == null) {
                    continue;
                }
                try {
                    arr.put(r.toJson());
                } catch (Throwable t) {
                    // 单条失败不影响整包规则。
                }
            }
        }
        return arr.toString();
    }

    /**
     * 从 selection 里取某列的值：支持 "_id=?"（配 selectionArgs）、"_id='abc'"、"_id=abc"。
     * 列名不匹配 / 解析不出来时返回 null。
     */
    private static String valueFromSelection(String selection, String[] selectionArgs, String column) {
        if (selection == null || column == null) {
            return null;
        }
        String s = selection.trim();
        if (s.indexOf('=') < 0) {
            return null;
        }
        // 只处理最简单的单条件（模块端契约就是单条件）。
        String[] parts = s.split("=", 2);
        if (parts.length != 2) {
            return null;
        }
        String key = stripQuotes(parts[0].trim());
        if (!column.equals(key)) {
            return null;
        }
        String value = parts[1].trim();
        if (value.length() == 0) {
            return null;
        }
        if ("?".equals(value)) {
            return (selectionArgs != null && selectionArgs.length > 0) ? selectionArgs[0] : null;
        }
        return stripQuotes(value);
    }

    /** 去掉 SQL 里常见的引号包裹（'x' / "x" / `x`）。 */
    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) {
            return s;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '\'' && last == '\'')
                || (first == '"' && last == '"')
                || (first == '`' && last == '`')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 取 Uri 末段作为规则 id（content://.../rules/<id>）；空/集合 Uri 返回 null。 */
    private static String lastPathId(Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.size() < 2) {
                return null;
            }
            String last = segments.get(segments.size() - 1);
            if ("rules".equals(last)) {
                return null;
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }
}
