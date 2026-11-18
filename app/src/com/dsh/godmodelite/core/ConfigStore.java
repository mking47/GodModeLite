package com.dsh.godmodelite.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 规则与全局开关的本地存储（SharedPreferences 文件 "gm_lite"）。
 * 全部方法 static、synchronized；每次修改立即写盘（持久化）。
 * 注意：使用前必须调用一次 {@link #init(Context)}（Activity.onCreate / 无障碍服务 onServiceConnected 里各调一次即可，幂等）。
 */
public final class ConfigStore {

    private static final String PREF_NAME = "gm_lite";
    private static final String KEY_RULES = "rules";
    private static final String KEY_BLOCKING = "blocking";
    private static final String KEY_BALL_VISIBLE = "ball_visible";
    /** v4：开启屏蔽时自动备份规则开关。 */
    private static final String KEY_AUTO_BACKUP = "auto_backup";
    /** v5：应用列表布局（0 列表 / 1 瀑布流）。 */
    private static final String KEY_LAYOUT = "layout_mode";
    /** v5.1：LSPosed 模块心跳（最近一次模块在目标进程生效的包名/时间）与日志。 */
    private static final String KEY_MODULE_PKG = "module_last_pkg";
    private static final String KEY_MODULE_TIME = "module_last_time";
    private static final String KEY_MODULE_LOG = "module_log";
    private static final String KEY_BALL_X = "ball_x";
    private static final String KEY_BALL_Y = "ball_y";
    // ===== v3 新增键 =====
    private static final String KEY_BALL_STYLE = "ball_style";
    private static final String KEY_BALL_SIZE_DP = "ball_size_dp";
    private static final String KEY_BALL_ALPHA = "ball_alpha";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_FILTER_MODE = "filter_mode";

    /** 悬浮球位置未设置时的哨兵值（由 a11y 层按屏幕尺寸取默认位置）。 */
    public static final int BALL_POS_UNSET = -1;

    // ===== 悬浮球样式（v3，SPEC-v3 §1） =====
    /** 眼睛（默认，v2 样式）。 */
    public static final int BALL_STYLE_EYE = 0;
    /** 实心圆点。 */
    public static final int BALL_STYLE_DOT = 1;
    /** 盾牌轮廓。 */
    public static final int BALL_STYLE_SHIELD = 2;
    /** 文字「隐」。 */
    public static final int BALL_STYLE_TEXT = 3;
    /** 禁止斜杠。 */
    public static final int BALL_STYLE_SLASH = 4;

    // ===== 悬浮球尺寸（dp）/ 透明度（0~255）（v3） =====
    public static final int BALL_SIZE_SMALL = 40;
    public static final int BALL_SIZE_MEDIUM = 48;
    public static final int BALL_SIZE_LARGE = 56;
    public static final int BALL_ALPHA_LOW = 150;
    public static final int BALL_ALPHA_MEDIUM = 200;
    public static final int BALL_ALPHA_HIGH = 235;

    // ===== 应用列表排序 / 筛选（v3，SPEC-v3 §4） =====
    /** 智能：有规则的置顶，再按名称。 */
    public static final int SORT_SMART = 0;
    /** 名称。 */
    public static final int SORT_NAME = 1;
    /** 规则数从多到少。 */
    public static final int SORT_RULES = 2;

    /** 筛选：全部应用。 */
    public static final int FILTER_ALL = 0;
    /** 筛选：仅显示有规则的应用。 */
    public static final int FILTER_WITH_RULES = 1;

    // ===== 应用列表布局（v5） =====

    /** 列表布局：单列。 */
    public static final int LAYOUT_LIST = 0;
    /** 瀑布流布局：双列卡片。 */
    public static final int LAYOUT_WATERFALL = 1;

    private static final Object LOCK = new Object();
    private static SharedPreferences sPrefs;
    private static final List<Rule> sRules = new ArrayList<Rule>();
    private static boolean sBlocking = true;
    /** 悬浮球开关（默认显示）。 */
    private static boolean sBallVisible = false;
    /** v4：开启屏蔽时是否自动备份（默认开）。 */
    private static boolean sAutoBackup = true;
    /** v5：应用列表布局模式（0 列表 / 1 瀑布流）。 */
    private static int sLayoutMode = 0;
    /** v5：无障碍服务当前是否已连接（运行期状态，不持久化；给跨进程模块判断是否已有悬浮球）。 */
    private static boolean sA11yConnected = false;
    /** v5.1：模块心跳（跨进程写入）与最近日志。 */
    private static String sModuleLastPkg = null;
    private static long sModuleLastTime = 0L;
    private static String sModuleLog = null;
    /** 悬浮球上次拖动后的屏幕坐标；BALL_POS_UNSET 表示还没拖过。 */
    private static int sBallX = BALL_POS_UNSET;
    private static int sBallY = BALL_POS_UNSET;
    /** 悬浮球样式（v3，默认眼睛）。 */
    private static int sBallStyle = BALL_STYLE_EYE;
    /** 悬浮球尺寸 dp（v3，默认 48）。 */
    private static int sBallSizeDp = BALL_SIZE_MEDIUM;
    /** 悬浮球透明度 150~255（v3，默认 235）。 */
    private static int sBallAlpha = BALL_ALPHA_HIGH;
    /** 应用列表排序方式（v3，默认智能）。 */
    private static int sSortMode = SORT_SMART;
    /** 应用列表筛选方式（v3，默认全部）。 */
    private static int sFilterMode = FILTER_ALL;

    private ConfigStore() {
    }

    /** 初始化：读取已有规则到内存。重复调用无副作用。 */
    public static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        synchronized (LOCK) {
            if (sPrefs != null) {
                return;
            }
            sPrefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            loadLocked();
        }
    }

    /** 从 SharedPreferences 读入规则与开关；坏数据直接当空处理，不崩溃。 */
    private static void loadLocked() {
        sRules.clear();
        sBlocking = true;
        sBallVisible = false;
        sAutoBackup = true;
        sLayoutMode = LAYOUT_LIST;
        sA11yConnected = false;
        sModuleLastPkg = null;
        sModuleLastTime = 0L;
        sModuleLog = null;
        sBallX = BALL_POS_UNSET;
        sBallY = BALL_POS_UNSET;
        sBallStyle = BALL_STYLE_EYE;
        sBallSizeDp = BALL_SIZE_MEDIUM;
        sBallAlpha = BALL_ALPHA_HIGH;
        sSortMode = SORT_SMART;
        sFilterMode = FILTER_ALL;
        if (sPrefs == null) {
            return;
        }
        sBlocking = sPrefs.getBoolean(KEY_BLOCKING, true);
        sBallVisible = sPrefs.getBoolean(KEY_BALL_VISIBLE, false);
        sAutoBackup = sPrefs.getBoolean(KEY_AUTO_BACKUP, true);
        sLayoutMode = sPrefs.getInt(KEY_LAYOUT, LAYOUT_LIST);
        sModuleLastPkg = sPrefs.getString(KEY_MODULE_PKG, null);
        sModuleLastTime = sPrefs.getLong(KEY_MODULE_TIME, 0L);
        sModuleLog = sPrefs.getString(KEY_MODULE_LOG, null);
        sBallX = sPrefs.getInt(KEY_BALL_X, BALL_POS_UNSET);
        sBallY = sPrefs.getInt(KEY_BALL_Y, BALL_POS_UNSET);
        sBallStyle = normalizeStyle(sPrefs.getInt(KEY_BALL_STYLE, BALL_STYLE_EYE));
        sBallSizeDp = clampSize(sPrefs.getInt(KEY_BALL_SIZE_DP, BALL_SIZE_MEDIUM));
        sBallAlpha = clampAlpha(sPrefs.getInt(KEY_BALL_ALPHA, BALL_ALPHA_HIGH));
        sSortMode = normalizeSort(sPrefs.getInt(KEY_SORT_MODE, SORT_SMART));
        sFilterMode = normalizeFilter(sPrefs.getInt(KEY_FILTER_MODE, FILTER_ALL));
        String raw = sPrefs.getString(KEY_RULES, null);
        if (raw == null || raw.length() == 0) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Rule r = Rule.fromJson(o);
                if (r.pkg == null || r.pkg.length() == 0) {
                    continue;
                }
                sRules.add(r);
            }
        } catch (Exception ignored) {
            // 数据损坏：清空规则，避免影响使用。
            sRules.clear();
        }
    }

    /** 把内存中的规则写回 SharedPreferences。 */
    private static void persistLocked() {
        if (sPrefs == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < sRules.size(); i++) {
                arr.put(sRules.get(i).toJson());
            }
            sPrefs.edit().putString(KEY_RULES, arr.toString()).apply();
        } catch (Exception ignored) {
            // 理论上不会发生；写盘失败也不影响当前内存状态。
        }
    }

    /** 全部规则（返回拷贝，外部修改不影响内部）。 */
    public static List<Rule> allRules() {
        synchronized (LOCK) {
            List<Rule> out = new ArrayList<Rule>(sRules.size());
            for (int i = 0; i < sRules.size(); i++) {
                out.add(sRules.get(i).copy());
            }
            return out;
        }
    }

    /** 某个应用的规则。 */
    public static List<Rule> rulesFor(String pkg) {
        List<Rule> out = new ArrayList<Rule>();
        if (pkg == null) {
            return out;
        }
        synchronized (LOCK) {
            for (int i = 0; i < sRules.size(); i++) {
                Rule r = sRules.get(i);
                if (pkg.equals(r.pkg)) {
                    out.add(r.copy());
                }
            }
        }
        return out;
    }

    /** 某个应用的规则条数。 */
    public static int ruleCount(String pkg) {
        if (pkg == null) {
            return 0;
        }
        int n = 0;
        synchronized (LOCK) {
            for (int i = 0; i < sRules.size(); i++) {
                if (pkg.equals(sRules.get(i).pkg)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 有规则的全部包名（按首次出现顺序）。 */
    public static Set<String> packagesWithRules() {
        Set<String> out = new LinkedHashSet<String>();
        synchronized (LOCK) {
            for (int i = 0; i < sRules.size(); i++) {
                String pkg = sRules.get(i).pkg;
                if (pkg != null && pkg.length() > 0) {
                    out.add(pkg);
                }
            }
        }
        return out;
    }

    /** 新增一条规则（同 id 覆盖），立即持久化。 */
    public static void addRule(Rule r) {
        if (r == null || r.pkg == null || r.pkg.length() == 0) {
            return;
        }
        synchronized (LOCK) {
            if (r.id == null || r.id.length() == 0) {
                r.id = UUID.randomUUID().toString();
            }
            if (r.created <= 0L) {
                r.created = System.currentTimeMillis();
            }
            for (int i = sRules.size() - 1; i >= 0; i--) {
                if (r.id.equals(sRules.get(i).id)) {
                    sRules.remove(i);
                }
            }
            sRules.add(r.copy());
            persistLocked();
        }
    }

    /** 按 id 删除规则。 */
    public static void deleteRule(String id) {
        if (id == null) {
            return;
        }
        synchronized (LOCK) {
            for (int i = sRules.size() - 1; i >= 0; i--) {
                if (id.equals(sRules.get(i).id)) {
                    sRules.remove(i);
                }
            }
            persistLocked();
        }
    }

    /** 清空某个应用的全部规则。 */
    public static void clearRules(String pkg) {
        if (pkg == null) {
            return;
        }
        synchronized (LOCK) {
            for (int i = sRules.size() - 1; i >= 0; i--) {
                if (pkg.equals(sRules.get(i).pkg)) {
                    sRules.remove(i);
                }
            }
            persistLocked();
        }
    }

    /** 启用/停用某条规则。 */
    public static void setRuleEnabled(String id, boolean enabled) {
        if (id == null) {
            return;
        }
        synchronized (LOCK) {
            for (int i = 0; i < sRules.size(); i++) {
                Rule r = sRules.get(i);
                if (id.equals(r.id)) {
                    r.enabled = enabled;
                    persistLocked();
                    return;
                }
            }
        }
    }

    /** 全局屏蔽开关。 */
    public static boolean isBlockingEnabled() {
        synchronized (LOCK) {
            return sBlocking;
        }
    }

    /** 设置全局屏蔽开关并持久化。 */
    public static void setBlockingEnabled(boolean on) {
        synchronized (LOCK) {
            sBlocking = on;
            if (sPrefs != null) {
                sPrefs.edit().putBoolean(KEY_BLOCKING, on).apply();
            }
        }
    }

    // ===================== 悬浮球设置（v2） =====================

    /** 悬浮球是否显示（默认 true）。无障碍服务连接时按此 / 用户开关显示。 */
    public static boolean isBallVisible() {
        synchronized (LOCK) {
            return sBallVisible;
        }
    }

    /** 设置悬浮球显示/隐藏并持久化；服务未连接时只保存，连接后生效。 */
    public static void setBallVisible(boolean on) {
        synchronized (LOCK) {
            sBallVisible = on;
            if (sPrefs != null) {
                sPrefs.edit().putBoolean(KEY_BALL_VISIBLE, on).apply();
            }
        }
    }

    /** 悬浮球上次拖动后的 x 坐标（px）；未设置返回 {@link #BALL_POS_UNSET}。 */
    public static int getBallX() {
        synchronized (LOCK) {
            return sBallX;
        }
    }

    /** 悬浮球上次拖动后的 y 坐标（px）；未设置返回 {@link #BALL_POS_UNSET}。 */
    public static int getBallY() {
        synchronized (LOCK) {
            return sBallY;
        }
    }

    /** 保存悬浮球拖动后的位置（px），拖动结束时调用。 */
    public static void setBallPos(int x, int y) {
        synchronized (LOCK) {
            sBallX = x;
            sBallY = y;
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_BALL_X, x).putInt(KEY_BALL_Y, y).apply();
            }
        }
    }

    // ===================== 悬浮球样式 / 尺寸 / 透明度（v3） =====================

    /** 悬浮球样式：0 眼睛 / 1 圆点 / 2 盾牌 / 3 文字「隐」/ 4 禁止斜杠（默认 0）。 */
    public static int getBallStyle() {
        synchronized (LOCK) {
            return normalizeStyle(sBallStyle);
        }
    }

    /** 设置悬浮球样式并持久化；服务在线时由调用方再调 applyBallSettings() 立即刷新。 */
    public static void setBallStyle(int style) {
        synchronized (LOCK) {
            sBallStyle = normalizeStyle(style);
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_BALL_STYLE, sBallStyle).apply();
            }
        }
    }

    /** 悬浮球尺寸 dp（40/48/56，默认 48）。 */
    public static int getBallSizeDp() {
        synchronized (LOCK) {
            return clampSize(sBallSizeDp);
        }
    }

    /** 设置悬浮球尺寸 dp 并持久化（越界值夹到 40~56）。 */
    public static void setBallSizeDp(int sizeDp) {
        synchronized (LOCK) {
            sBallSizeDp = clampSize(sizeDp);
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_BALL_SIZE_DP, sBallSizeDp).apply();
            }
        }
    }

    /** 悬浮球透明度 150~255（默认 235）。 */
    public static int getBallAlpha() {
        synchronized (LOCK) {
            return clampAlpha(sBallAlpha);
        }
    }

    /** 设置悬浮球透明度并持久化（夹到 150~255）。 */
    public static void setBallAlpha(int alpha) {
        synchronized (LOCK) {
            sBallAlpha = clampAlpha(alpha);
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_BALL_ALPHA, sBallAlpha).apply();
            }
        }
    }

    /** 样式的中文名（主界面状态卡 / 设置弹窗用）。 */
    public static String ballStyleName(int style) {
        switch (normalizeStyle(style)) {
            case BALL_STYLE_DOT:
                return "圆点";
            case BALL_STYLE_SHIELD:
                return "盾牌";
            case BALL_STYLE_TEXT:
                return "文字「隐」";
            case BALL_STYLE_SLASH:
                return "禁止斜杠";
            case BALL_STYLE_EYE:
            default:
                return "眼睛";
        }
    }

    /** 规范化样式：只接受 0~4，其它一律回落到眼睛。 */
    static int normalizeStyle(int style) {
        if (style < BALL_STYLE_EYE || style > BALL_STYLE_SLASH) {
            return BALL_STYLE_EYE;
        }
        return style;
    }

    /** 尺寸夹取：低于 40 取 40，高于 56 取 56。 */
    static int clampSize(int sizeDp) {
        if (sizeDp < BALL_SIZE_SMALL) {
            return BALL_SIZE_SMALL;
        }
        if (sizeDp > BALL_SIZE_LARGE) {
            return BALL_SIZE_LARGE;
        }
        return sizeDp;
    }

    /** 透明度夹取：低于 150 取 150，高于 255 取 255。 */
    static int clampAlpha(int alpha) {
        if (alpha < BALL_ALPHA_LOW) {
            return BALL_ALPHA_LOW;
        }
        if (alpha > 255) {
            return 255;
        }
        return alpha;
    }

    // ===================== 应用列表排序 / 筛选（v3） =====================

    /** 排序方式：0 智能 / 1 名称 / 2 规则数（默认 0）。 */
    public static int getSortMode() {
        synchronized (LOCK) {
            return normalizeSort(sSortMode);
        }
    }

    /** 设置排序方式并持久化。 */
    public static void setSortMode(int mode) {
        synchronized (LOCK) {
            sSortMode = normalizeSort(mode);
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_SORT_MODE, sSortMode).apply();
            }
        }
    }

    /** 筛选方式：0 全部 / 1 仅有规则（默认 0）。 */
    public static int getFilterMode() {
        synchronized (LOCK) {
            return normalizeFilter(sFilterMode);
        }
    }

    /** 设置筛选方式并持久化。 */
    public static void setFilterMode(int mode) {
        synchronized (LOCK) {
            sFilterMode = normalizeFilter(mode);
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_FILTER_MODE, sFilterMode).apply();
            }
        }
    }

    /** 排序方式的中文名。 */
    public static String sortModeName(int mode) {
        switch (normalizeSort(mode)) {
            case SORT_NAME:
                return "名称";
            case SORT_RULES:
                return "规则数";
            case SORT_SMART:
            default:
                return "智能";
        }
    }

    /** 筛选方式的中文名。 */
    public static String filterModeName(int mode) {
        return normalizeFilter(mode) == FILTER_WITH_RULES ? "有规则" : "全部";
    }

    static int normalizeSort(int mode) {
        if (mode < SORT_SMART || mode > SORT_RULES) {
            return SORT_SMART;
        }
        return mode;
    }

    static int normalizeFilter(int mode) {
        if (mode < FILTER_ALL || mode > FILTER_WITH_RULES) {
            return FILTER_ALL;
        }
        return mode;
    }

    // ===================== LSPosed 模块心跳（v5.1） =====================

    /** 模块在目标进程生效时写入心跳：包名 + 时间（毫秒）。 */
    public static void setModuleHeartbeat(String pkg, long time) {
        synchronized (LOCK) {
            sModuleLastPkg = pkg;
            sModuleLastTime = time;
            if (sPrefs != null) {
                sPrefs.edit().putString(KEY_MODULE_PKG, pkg).putLong(KEY_MODULE_TIME, time).apply();
            }
        }
    }

    /** 最近一次模块心跳的包名；没有返回 null。 */
    public static String getModuleLastPkg() {
        synchronized (LOCK) {
            return sModuleLastPkg;
        }
    }

    /** 最近一次模块心跳时间（毫秒）；没有返回 0。 */
    public static long getModuleLastTime() {
        synchronized (LOCK) {
            return sModuleLastTime;
        }
    }

    /** 追加一行模块日志（最多保留约 4KB，供 App 内诊断展示）。 */
    public static void appendModuleLog(String line) {
        if (line == null || line.length() == 0) {
            return;
        }
        synchronized (LOCK) {
            String old = sModuleLog == null ? "" : sModuleLog;
            String merged = line + "\n" + old;
            if (merged.length() > 4096) {
                merged = merged.substring(0, 4096);
            }
            sModuleLog = merged;
            if (sPrefs != null) {
                sPrefs.edit().putString(KEY_MODULE_LOG, merged).apply();
            }
        }
    }

    /** 最近模块日志（新的在前）；没有返回空串。 */
    public static String getModuleLog() {
        synchronized (LOCK) {
            return sModuleLog == null ? "" : sModuleLog;
        }
    }

    // ===================== 应用列表布局（v5） =====================

    /** 应用列表布局：0 列表 / 1 瀑布流。 */
    public static int getLayoutMode() {
        synchronized (LOCK) {
            return sLayoutMode;
        }
    }

    /** 设置应用列表布局并持久化。 */
    public static void setLayoutMode(int mode) {
        synchronized (LOCK) {
            sLayoutMode = (mode == LAYOUT_WATERFALL) ? LAYOUT_WATERFALL : LAYOUT_LIST;
            if (sPrefs != null) {
                sPrefs.edit().putInt(KEY_LAYOUT, sLayoutMode).apply();
            }
        }
    }

    /** 布局名称（给按钮/菜单显示）。 */
    public static String layoutModeName(int mode) {
        return mode == LAYOUT_WATERFALL ? "瀑布流" : "列表";
    }

    // ===================== 无障碍连接状态（v5，运行期） =====================

    /** 无障碍服务是否已连接（运行期状态，重启进程后为 false）。 */
    public static boolean isA11yConnected() {
        synchronized (LOCK) {
            return sA11yConnected;
        }
    }

    /** 无障碍服务连接/断开时由 BlockerService 更新（不持久化）。 */
    public static void setA11yConnected(boolean connected) {
        synchronized (LOCK) {
            sA11yConnected = connected;
        }
    }

    // ===================== 自动备份（v4） =====================

    /** 开启屏蔽时是否自动备份规则（默认 true）。 */
    public static boolean isAutoBackupEnabled() {
        synchronized (LOCK) {
            return sAutoBackup;
        }
    }

    /** 设置开启屏蔽时自动备份规则并持久化。 */
    public static void setAutoBackupEnabled(boolean on) {
        synchronized (LOCK) {
            sAutoBackup = on;
            if (sPrefs != null) {
                sPrefs.edit().putBoolean(KEY_AUTO_BACKUP, on).apply();
            }
        }
    }

    // ===================== 规则统计（v5 优化：不拷贝规则列表） =====================

    /** 一次遍历统计：返回 [总数, 遮挡数, 真删数]，不创建规则拷贝。 */
    public static int[] stats() {
        synchronized (LOCK) {
            int mask = 0;
            int gone = 0;
            for (int i = 0; i < sRules.size(); i++) {
                Rule r = sRules.get(i);
                if (r == null) {
                    continue;
                }
                if (r.isGone()) {
                    gone++;
                } else {
                    mask++;
                }
            }
            return new int[]{mask + gone, mask, gone};
        }
    }

    /** 每个包的规则统计：包名 -> [遮挡数, 真删数]，不创建规则拷贝。 */
    public static java.util.Map<String, int[]> packageStats() {
        java.util.Map<String, int[]> out = new java.util.HashMap<String, int[]>();
        synchronized (LOCK) {
            for (int i = 0; i < sRules.size(); i++) {
                Rule r = sRules.get(i);
                if (r == null || r.pkg == null || r.pkg.length() == 0) {
                    continue;
                }
                int[] c = out.get(r.pkg);
                if (c == null) {
                    c = new int[2];
                    out.put(r.pkg, c);
                }
                if (r.isGone()) {
                    c[1]++;
                } else {
                    c[0]++;
                }
            }
        }
        return out;
    }

    // ===================== 规则统计（v3 状态卡用） =====================

    /** 「遮挡」规则条数（mode != gone）。 */
    public static int countMask() {
        synchronized (LOCK) {
            int n = 0;
            for (int i = 0; i < sRules.size(); i++) {
                if (!sRules.get(i).isGone()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 「真删」规则条数（mode == gone）。 */
    public static int countGone() {
        synchronized (LOCK) {
            int n = 0;
            for (int i = 0; i < sRules.size(); i++) {
                if (sRules.get(i).isGone()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 恢复备份：整体替换规则并设置屏蔽开关。 */
    public static void replaceAll(List<Rule> rules, boolean blocking) {
        synchronized (LOCK) {
            sRules.clear();
            if (rules != null) {
                for (int i = 0; i < rules.size(); i++) {
                    Rule r = rules.get(i);
                    if (r == null || r.pkg == null || r.pkg.length() == 0) {
                        continue;
                    }
                    if (r.id == null || r.id.length() == 0) {
                        r.id = UUID.randomUUID().toString();
                    }
                    if (r.created <= 0L) {
                        r.created = System.currentTimeMillis();
                    }
                    sRules.add(r.copy());
                }
            }
            sBlocking = blocking;
            if (sPrefs != null) {
                sPrefs.edit().putBoolean(KEY_BLOCKING, blocking).apply();
            }
            persistLocked();
        }
    }
}
