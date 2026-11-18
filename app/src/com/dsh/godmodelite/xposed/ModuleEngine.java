package com.dsh.godmodelite.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块在目标应用进程内的逻辑中枢（全静态，按 Activity 分别维护状态）。
 *
 * 三件事：
 * 1. 应用规则：onResume 后延迟拉取规则，按 viewId → path → cls+text 匹配 decorView 里的 View；
 *    mode=gone 真删（setVisibility(GONE)），mode=mask 在 decorView 上盖一块同位置同大小的半透明白色 View。
 * 2. 悬浮球：每个 Activity 一个圆形球（加在 decorView 上，不需要悬浮窗权限，尺寸/样式/透明度可配），可拖、单击图层选择、长按菜单。
 * 3. 图层选择：在 decorView 上加全屏半透明层，把所有可见元素画框，点选后可真删/遮挡并写回规则。
 *
 * 约束：只用 android framework + org.json + Xposed API；全部 View 操作包 try/catch；
 * 不能 import 主应用任何类（这里跑在目标进程里，那些类根本加载不到）。
 */
public final class ModuleEngine {

    private static final String TAG = "[GodModeLite] ";

    private static final String OWN_PACKAGE = "com.dsh.godmodelite";
    private static final String MAIN_ACTIVITY = "com.dsh.godmodelite.MainActivity";

    /** 我们自己加的 View 统一打的 tag 前缀：遍历/清理时据此跳过。 */
    static final String INTERNAL_TAG = "gm_lite_internal";

    /** 遮挡层颜色：半透明白（与规格一致）。 */
    private static final int MASK_COLOR = 0xE6FFFFFF;
    /** 图层选择层的全屏淡黑背景。 */
    private static final int PICKER_SCRIM = 0x66000000;
    /** 长按菜单背后的更深一点的遮罩。 */
    private static final int MENU_SCRIM = 0x33000000;

    private static final int COLOR_CARD = 0xFF1E1E1E;
    /** v3 动作卡背景：#F22A2A2A（半透明深灰）。 */
    private static final int COLOR_CARD_TRANSLUCENT = 0xF22A2A2A;
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_TEXT_DIM = 0xFF9E9E9E;
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    private static final int COLOR_DANGER = 0xFFEF5350;
    private static final int COLOR_INFO = 0xFF64B5F6;
    /** 「取消」按钮深灰底。 */
    private static final int COLOR_BUTTON_GRAY = 0xFF2A2A2A;
    /** 选中高亮的外发光色（4dp 半透明绿）。 */
    private static final int COLOR_HIGHLIGHT_GLOW = 0x2200FF00;

    /** 悬浮球样式（与主应用设置里的样式编号保持一致）。 */
    static final int BALL_STYLE_EYE = 0;
    static final int BALL_STYLE_DOT = 1;
    static final int BALL_STYLE_SHIELD = 2;
    static final int BALL_STYLE_TEXT = 3;
    static final int BALL_STYLE_SLASH = 4;

    /** 当前生效的悬浮球设置（来自 provider 的 ball_style / ball_size / ball_alpha 列）。 */
    private static int sBallStyle = RuleClient.DEFAULT_BALL_STYLE;
    private static int sBallSizeDp = RuleClient.DEFAULT_BALL_SIZE_DP;
    private static int sBallAlpha = RuleClient.DEFAULT_BALL_ALPHA;
    /** v4：悬浮球是否开启（来自 provider 的 ball_visible 列；默认关，只能手动开）。 */
    private static boolean sBallVisible = false;
    /** v5：无障碍服务是否已连接（连接时全局已有唯一悬浮球，模块不再加球，避免两个球）。 */
    private static boolean sA11yConnected = false;

    /** 图层选择时边框颜色按深度循环。 */
    private static final int[] DEPTH_COLORS = {
            0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFFE91E63, 0xFF9C27B0, 0xFF00BCD4
    };

    private static final int MAX_PICK_TARGETS = 200;
    private static final int MAX_MASKS = 40;
    private static final long RULE_DELAY_FIRST = 250L;
    private static final long RULE_DELAY_SECOND = 800L;
    private static final long BALL_LONG_PRESS_MS = 480L;

    /** Activity → 进程内 UI 状态（key 是弱引用，onDestroy 会主动移除）。 */
    private static final WeakHashMap<Activity, ActivityState> STATES =
            new WeakHashMap<Activity, ActivityState>();

    /** 被我们真删（GONE）的 View 的原始可见性；每轮应用规则前先还原，再按当前规则重新隐藏。 */
    private static final WeakHashMap<View, Integer> HIDDEN_VIEWS =
            new WeakHashMap<View, Integer>();

    /** 各包悬浮球最后一次拖动位置（进程内记忆，避免切换 Activity 后跳回默认位置）。 */
    private static final HashMap<String, int[]> BALL_POSITIONS = new HashMap<String, int[]>();

    /** 用户主动隐藏过悬浮球的包（本进程内不再显示）。 */
    private static final HashSet<String> BALL_HIDDEN_PKGS = new HashSet<String>();

    /** 写回主应用失败的规则（provider 不可用时的内存兜底），保证本次运行内操作不丢。 */
    private static final HashMap<String, List<RuleClient.RuleSpec>> LOCAL_RULES =
            new HashMap<String, List<RuleClient.RuleSpec>>();

    private ModuleEngine() {
        // 工具类，不允许实例化
    }

    // ==================== 生命周期入口（由 GodModeModule 的 hook 调用） ====================

    /**
     * Activity.onResume（after）时调用：
     * 准备状态 → 装悬浮球 → 250ms / 800ms 各拉一次规则并应用（后者补晚加载的内容）。
     */
    public static void onResume(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            String pkg = activity.getPackageName();
            if (pkg == null || pkg.length() == 0 || OWN_PACKAGE.equals(pkg)) {
                return;
            }
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            View decorView = window.getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return;
            }
            ActivityState state = stateOf(activity);
            state.decor = (ViewGroup) decorView;
            state.pkg = pkg;
            state.destroyed = false;
            if (state.handler == null) {
                state.handler = new Handler(Looper.getMainLooper());
            }
            try {
                state.handler.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
                // 忽略
            }
            // 重新进入时清掉可能残留的菜单/选择层（基于旧布局，已失效）
            closeMenuInternal(state);
            closePickerInternal(state);
            ensureBall(activity, state);
            scheduleRuleApply(activity, state, RULE_DELAY_FIRST);
            scheduleRuleApply(activity, state, RULE_DELAY_SECOND);
        } catch (Throwable t) {
            log("onResume 处理失败");
            logThrowable(t);
        }
    }

    /** Activity.onDestroy（after）时调用：移除本 Activity 上所有我们加的 View，释放 handler。 */
    public static void onDestroy(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            ActivityState state;
            synchronized (STATES) {
                state = STATES.remove(activity);
            }
            if (state == null) {
                // 没走过 onResume（或状态已丢）：兜底按 tag 清一遍
                removeInternalViews(activity);
                return;
            }
            state.destroyed = true;
            if (state.handler != null) {
                try {
                    state.handler.removeCallbacksAndMessages(null);
                } catch (Throwable ignored) {
                    // 忽略
                }
                // v3 内存优化：销毁时释放 Handler 引用
                state.handler = null;
            }
            closeMenuInternal(state);
            closePickerInternal(state);
            clearMasks(state);
            removeView(state, state.ball);
            state.ball = null;
            state.decor = null;
            // 兜底：decor 里如果还有漏网的内部 View，一并移除
            removeInternalViews(activity);
        } catch (Throwable t) {
            log("onDestroy 清理失败");
            logThrowable(t);
        }
    }

    // ==================== 状态管理 ====================

    private static ActivityState stateOf(Activity activity) {
        synchronized (STATES) {
            ActivityState state = STATES.get(activity);
            if (state == null) {
                state = new ActivityState();
                STATES.put(activity, state);
            }
            return state;
        }
    }

    /** 单个 Activity 的进程内状态。故意不持有 Activity 强引用，避免 WeakHashMap 泄漏。 */
    static final class ActivityState {
        /** 主线程 Handler；onDestroy 时移除回调并置空（v3 内存优化）。 */
        Handler handler = new Handler(Looper.getMainLooper());
        ViewGroup decor;
        String pkg;
        View ball;
        boolean ballHidden;
        boolean destroyed;
        View menuOverlay;
        View pickerOverlay;
        /** 图层选择的自绘 View，关闭时清空其候选列表并置空引用（v3 内存优化）。 */
        PickerView pickerView;
        final List<View> maskViews = new ArrayList<View>();
    }

    // ==================== 规则应用 ====================

    private static void scheduleRuleApply(final Activity activity, final ActivityState state, long delayMs) {
        if (state == null || state.destroyed) {
            return;
        }
        final Handler handler = state.handler;
        if (handler == null) {
            return;
        }
        try {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fetchRulesAndApply(activity, state);
                }
            }, delayMs);
        } catch (Throwable t) {
            log("安排规则应用失败");
            logThrowable(t);
        }
    }

    /** 子线程查 provider（避免 provider 冷启动卡住目标应用主线程），回主线程应用。 */
    private static void fetchRulesAndApply(final Activity activity, final ActivityState state) {
        if (state == null || state.destroyed || state.decor == null) {
            return;
        }
        final String pkg = state.pkg;
        if (pkg == null) {
            return;
        }
        final Handler handler = state.handler;
        if (handler == null) {
            return;
        }
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    RuleClient.RuleData data = null;
                    try {
                        data = RuleClient.getRules(activity, pkg);
                    } catch (Throwable t) {
                        log("拉取规则失败 pkg=" + pkg);
                        logThrowable(t);
                        return;
                    }
                    if (data == null) {
                        // provider 不可用：保持现状，不清遮挡、不还原
                        return;
                    }
                    final RuleClient.RuleData finalData = data;
                    try {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (state.destroyed) {
                                    return;
                                }
                                applyRules(activity, state, finalData);
                            }
                        });
                    } catch (Throwable t) {
                        log("回主线程应用规则失败");
                        logThrowable(t);
                    }
                }
            }, "gm-lite-rules");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            log("启动规则线程失败");
            logThrowable(t);
        }
    }

    /** 主线程应用一轮规则：还原上一轮真删 → 清理旧遮挡 → 逐条重新应用。 */
    private static void applyRules(Activity activity, ActivityState state, RuleClient.RuleData data) {
        if (activity == null || state == null || data == null) {
            return;
        }
        try {
            // 0. 同步 provider 里的悬浮球设置（样式 / 尺寸 / 透明度），变更会即时作用到已存在的球
            updateBallSettings(data);
            if (sBallVisible) {
                ensureBall(activity, state);
            } else {
                removeOwnBall(state);
            }
            ViewGroup decor = state.decor;
            if (decor == null) {
                Window window = activity.getWindow();
                if (window == null) {
                    return;
                }
                View view = window.getDecorView();
                if (!(view instanceof ViewGroup)) {
                    return;
                }
                decor = (ViewGroup) view;
                state.decor = decor;
            }
            // 1. 先把上一轮被我们 GONE 掉的 View 还原回原始可见性（规则仍生效时本轮马上会再隐藏）
            restoreHiddenViews();
            // 2. 遮挡层每轮重建：规则被删除/停用后能自然消失，也不会重复叠加
            clearMasks(state);
            if (!data.blocking) {
                // 总开关关闭：只清理遮挡，不再隐藏新元素
                bringBallToFront(state);
                return;
            }
            // 3. 规则 = provider 返回的 + 内存兜底规则（去重）
            List<RuleClient.RuleSpec> rules = new ArrayList<RuleClient.RuleSpec>();
            if (data.rules != null) {
                rules.addAll(data.rules);
            }
            rules.addAll(localRules(state.pkg));
            Set<String> applied = new HashSet<String>();
            for (int i = 0; i < rules.size(); i++) {
                RuleClient.RuleSpec rule;
                try {
                    rule = rules.get(i);
                } catch (Throwable t) {
                    continue;
                }
                if (rule == null || !rule.enabled) {
                    continue;
                }
                if (rule.id == null || rule.id.length() == 0) {
                    rule.id = UUID.randomUUID().toString();
                }
                if (!applied.add(rule.id)) {
                    continue;
                }
                try {
                    applyOneRule(decor, state, rule);
                } catch (Throwable t) {
                    // 单条规则失败不影响其它规则
                    log("应用规则失败: " + rule.describe());
                    logThrowable(t);
                }
            }
            bringBallToFront(state);
        } catch (Throwable t) {
            log("应用规则失败");
            logThrowable(t);
        }
    }

    private static void applyOneRule(ViewGroup decor, ActivityState state, RuleClient.RuleSpec rule) {
        View target = findTargetView(decor, rule);
        if (target == null || target == decor || isInternal(target)) {
            return;
        }
        if ("gone".equals(rule.mode)) {
            hideNow(target);
        } else {
            addMaskView(decor, state, target, rule);
        }
    }

    /** mode=gone：真删。记录原始可见性，规则失效/关闭屏蔽时可还原。 */
    private static void hideNow(View view) {
        if (view == null) {
            return;
        }
        try {
            if (view.getVisibility() != View.GONE) {
                synchronized (HIDDEN_VIEWS) {
                    HIDDEN_VIEWS.put(view, Integer.valueOf(view.getVisibility()));
                }
                view.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            log("隐藏 View 失败");
            logThrowable(t);
        }
    }

    /** 把上一轮记录的真删还原（只还原仍是 GONE 的，避免覆盖应用自己的状态变化）。 */
    private static void restoreHiddenViews() {
        List<Map.Entry<View, Integer>> snapshot;
        synchronized (HIDDEN_VIEWS) {
            snapshot = new ArrayList<Map.Entry<View, Integer>>(HIDDEN_VIEWS.entrySet());
            HIDDEN_VIEWS.clear();
        }
        for (int i = 0; i < snapshot.size(); i++) {
            Map.Entry<View, Integer> entry = snapshot.get(i);
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            View view = entry.getKey();
            try {
                if (view.getVisibility() == View.GONE) {
                    view.setVisibility(entry.getValue().intValue());
                }
            } catch (Throwable ignored) {
                // 单个 View 还原失败不影响其它
            }
        }
    }

    private static List<RuleClient.RuleSpec> localRules(String pkg) {
        List<RuleClient.RuleSpec> result = new ArrayList<RuleClient.RuleSpec>();
        if (pkg == null) {
            return result;
        }
        synchronized (LOCAL_RULES) {
            List<RuleClient.RuleSpec> list = LOCAL_RULES.get(pkg);
            if (list != null) {
                result.addAll(list);
            }
        }
        return result;
    }

    private static void addLocalRule(String pkg, RuleClient.RuleSpec rule) {
        if (pkg == null || rule == null) {
            return;
        }
        synchronized (LOCAL_RULES) {
            List<RuleClient.RuleSpec> list = LOCAL_RULES.get(pkg);
            if (list == null) {
                list = new ArrayList<RuleClient.RuleSpec>();
                LOCAL_RULES.put(pkg, list);
            }
            if (list.size() >= 50) {
                list.remove(0);
            }
            list.add(rule);
        }
    }

    // ==================== 规则 → View 匹配 ====================

    /** 优先级：viewId（资源名）→ path（decorView 子节点下标路径）→ cls+text。 */
    private static View findTargetView(ViewGroup decor, RuleClient.RuleSpec rule) {
        String viewId = rule.viewId;
        if (viewId != null && viewId.length() > 0) {
            View found = findViewByResourceName(decor, viewId);
            if (found != null) {
                return found;
            }
        }
        String path = rule.path;
        if (path != null && path.length() > 0) {
            View found = findViewByPath(decor, path, rule.cls);
            if (found != null) {
                return found;
            }
        }
        if (rule.text != null && rule.text.length() > 0) {
            return findViewByClassText(decor, rule.cls, rule.text);
        }
        return null;
    }

    /** 按资源名匹配（如 com.foo:id/btn）。取名失败时视为不匹配。 */
    private static View findViewByResourceName(View view, String viewId) {
        if (view == null || isInternal(view)) {
            return null;
        }
        try {
            if (viewId.equals(resourceNameOf(view))) {
                return view;
            }
        } catch (Throwable ignored) {
            // 继续递归
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = 0;
            try {
                count = group.getChildCount();
            } catch (Throwable ignored) {
                return null;
            }
            for (int i = 0; i < count; i++) {
                View child;
                try {
                    child = group.getChildAt(i);
                } catch (Throwable t) {
                    continue;
                }
                View found = findViewByResourceName(child, viewId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 按子节点下标路径匹配（跳过我们的内部 View）；给了 cls 时还要类名一致，降低误伤。 */
    private static View findViewByPath(ViewGroup decor, String path, String cls) {
        try {
            String[] parts = path.split("/");
            View current = decor;
            for (int i = 0; i < parts.length; i++) {
                int index;
                try {
                    index = Integer.parseInt(parts[i].trim());
                } catch (Throwable t) {
                    return null;
                }
                if (index < 0 || !(current instanceof ViewGroup)) {
                    return null;
                }
                ViewGroup group = (ViewGroup) current;
                View next = null;
                int visibleIndex = 0;
                int count = group.getChildCount();
                for (int c = 0; c < count; c++) {
                    View child;
                    try {
                        child = group.getChildAt(c);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (child == null || isInternal(child)) {
                        continue;
                    }
                    if (visibleIndex == index) {
                        next = child;
                        break;
                    }
                    visibleIndex++;
                }
                if (next == null) {
                    return null;
                }
                current = next;
            }
            if (cls != null && cls.length() > 0) {
                if (!cls.equals(current.getClass().getName())) {
                    return null;
                }
            }
            return current;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按「类名 + 文字」匹配（必须有非空文字，否则太容易误伤）。 */
    private static View findViewByClassText(View view, String cls, String text) {
        if (view == null || isInternal(view) || text == null || text.length() == 0) {
            return null;
        }
        try {
            boolean classMatched = (cls == null || cls.length() == 0 || cls.equals(view.getClass().getName()));
            if (classMatched) {
                String current = textOfView(view);
                if (current != null && current.equals(text.trim())) {
                    return view;
                }
            }
        } catch (Throwable ignored) {
            // 继续递归
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = 0;
            try {
                count = group.getChildCount();
            } catch (Throwable ignored) {
                return null;
            }
            for (int i = 0; i < count; i++) {
                View child;
                try {
                    child = group.getChildAt(i);
                } catch (Throwable t) {
                    continue;
                }
                View found = findViewByClassText(child, cls, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ==================== 遮挡 View（mode=mask） ====================

    private static void addMaskView(ViewGroup decor, ActivityState state, View target, RuleClient.RuleSpec rule) {
        if (decor == null || state == null || target == null) {
            return;
        }
        if (!(decor instanceof FrameLayout)) {
            // 只有 FrameLayout 根（PhoneWindow.DecorView）才能按 margin 精确定位
            return;
        }
        if (state.maskViews.size() >= MAX_MASKS) {
            return;
        }
        try {
            if (target.getVisibility() != View.VISIBLE) {
                return;
            }
            int width = target.getWidth();
            int height = target.getHeight();
            if (width < 4 || height < 4) {
                return;
            }
            int[] targetLocation = new int[2];
            int[] decorLocation = new int[2];
            target.getLocationOnScreen(targetLocation);
            decor.getLocationOnScreen(decorLocation);
            int left = targetLocation[0] - decorLocation[0];
            int top = targetLocation[1] - decorLocation[1];
            int decorWidth = decor.getWidth();
            int decorHeight = decor.getHeight();
            if (decorWidth > 0 && decorHeight > 0) {
                if (left < 0) {
                    width += left;
                    left = 0;
                }
                if (top < 0) {
                    height += top;
                    top = 0;
                }
                if (left + width > decorWidth) {
                    width = decorWidth - left;
                }
                if (top + height > decorHeight) {
                    height = decorHeight - top;
                }
            }
            if (width < 4 || height < 4) {
                return;
            }
            Context context = decor.getContext();
            final View mask = new View(context);
            mask.setBackgroundColor(MASK_COLOR);
            mask.setClickable(true);
            mask.setFocusable(false);
            // 吞掉点击，避免用户点到被遮挡的元素
            mask.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 故意什么都不做：蒙版的作用就是拦截点击
                }
            });
            // tag 记规则 id，同时标记为内部 View（遍历时跳过）
            mask.setTag(INTERNAL_TAG + "#rule:" + rule.id);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = left;
            params.topMargin = top;
            decor.addView(mask, params);
            state.maskViews.add(mask);
        } catch (Throwable t) {
            // BadTokenException / 布局参数不匹配等：忽略这一条蒙版
            log("添加遮挡层失败");
            logThrowable(t);
        }
    }

    private static void clearMasks(ActivityState state) {
        if (state == null) {
            return;
        }
        for (int i = 0; i < state.maskViews.size(); i++) {
            removeView(state, state.maskViews.get(i));
        }
        state.maskViews.clear();
    }

    // ==================== 悬浮球 ====================

    private static void ensureBall(final Activity activity, final ActivityState state) {
        if (state == null || state.decor == null) {
            return;
        }
        if (!sBallVisible) {
            // v4：悬浮球未手动开启时，任何界面都不显示
            removeOwnBall(state);
            return;
        }
        if (state.ball != null) {
            // 回到前台时重新套一次设置（主应用可能刚改过样式/尺寸/透明度）
            applyBallSettings(state);
            bringBallToFront(state);
            return;
        }
        if (state.ballHidden) {
            return;
        }
        if (state.pkg != null && BALL_HIDDEN_PKGS.contains(state.pkg)) {
            state.ballHidden = true;
            return;
        }
        if (!(state.decor instanceof FrameLayout)) {
            return;
        }
        if (isSystemApp(activity)) {
            // 系统应用（SystemUI / 桌面 / 设置）里不加球，避免干扰系统界面；规则仍照常应用
            return;
        }
        try {
            int size = dp(activity, clampBallSizeDp(sBallSizeDp));
            final BallView ball = new BallView(activity);
            ball.setTag(INTERNAL_TAG);
            ball.setListener(new BallListener() {
                @Override
                public void onBallTap() {
                    openPicker(activity, state);
                }

                @Override
                public void onBallLongPress() {
                    showBallMenu(activity, state);
                }

                @Override
                public void onBallMoved(int left, int top) {
                    if (state.pkg == null) {
                        return;
                    }
                    int[] position = new int[2];
                    position[0] = left;
                    position[1] = top;
                    synchronized (BALL_POSITIONS) {
                        BALL_POSITIONS.put(state.pkg, position);
                    }
                }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            params.gravity = Gravity.TOP | Gravity.START;
            state.decor.addView(ball, params);
            state.ball = ball;
            placeBall(activity, state, params);
            applyBallSettings(state);
            bringBallToFront(state);
        } catch (Throwable t) {
            log("添加悬浮球失败");
            logThrowable(t);
        }
    }

    /** 计算悬浮球位置：优先上次拖动位置，否则右侧 45% 高度；四边限制在 decor 内。 */
    private static void placeBall(Activity activity, ActivityState state, FrameLayout.LayoutParams params) {
        if (state == null || params == null || state.decor == null) {
            return;
        }
        int size = params.width;
        int[] saved = null;
        if (state.pkg != null) {
            synchronized (BALL_POSITIONS) {
                int[] remembered = BALL_POSITIONS.get(state.pkg);
                if (remembered != null) {
                    saved = new int[]{remembered[0], remembered[1]};
                }
            }
        }
        int decorWidth = state.decor.getWidth();
        int decorHeight = state.decor.getHeight();
        int left;
        int top;
        if (saved != null) {
            left = saved[0];
            top = saved[1];
        } else if (decorWidth > 0 && decorHeight > 0) {
            left = decorWidth - size - dp(activity, 12);
            top = (int) (decorHeight * 0.45f);
        } else {
            left = dp(activity, 12);
            top = dp(activity, 120);
        }
        if (decorWidth > 0) {
            if (left > decorWidth - size) {
                left = decorWidth - size;
            }
            if (left < 0) {
                left = 0;
            }
        }
        if (decorHeight > 0) {
            if (top > decorHeight - size) {
                top = decorHeight - size;
            }
            if (top < 0) {
                top = 0;
            }
        }
        params.leftMargin = left;
        params.topMargin = top;
        if (state.ball != null) {
            try {
                state.ball.setLayoutParams(params);
            } catch (Throwable t) {
                log("设置悬浮球位置失败");
                logThrowable(t);
            }
        }
    }

    private static void bringBallToFront(ActivityState state) {
        if (state == null || state.ball == null) {
            return;
        }
        try {
            state.ball.bringToFront();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================== 悬浮球设置（v3：ball_style / ball_size / ball_alpha） ====================

    /** provider 返回新的悬浮球设置时，更新静态配置并作用到所有已存在的球。 */
    private static void updateBallSettings(RuleClient.RuleData data) {
        if (data == null) {
            return;
        }
        int style = data.ballStyle;
        int sizeDp = clampBallSizeDp(data.ballSizeDp);
        int alpha = clampBallAlpha(data.ballAlpha);
        if (style < BALL_STYLE_EYE || style > BALL_STYLE_SLASH) {
            style = RuleClient.DEFAULT_BALL_STYLE;
        }
        boolean visible = data.ballVisible && !data.a11yConnected;
        boolean visibilityChanged = visible != sBallVisible;
        sBallVisible = visible;
        sA11yConnected = data.a11yConnected;
        if (style == sBallStyle && sizeDp == sBallSizeDp && alpha == sBallAlpha && !visibilityChanged) {
            return;
        }
        sBallStyle = style;
        sBallSizeDp = sizeDp;
        sBallAlpha = alpha;
        List<ActivityState> states;
        synchronized (STATES) {
            states = new ArrayList<ActivityState>(STATES.values());
        }
        for (int i = 0; i < states.size(); i++) {
            ActivityState state = states.get(i);
            if (state == null) {
                continue;
            }
            if (sBallVisible) {
                applyBallSettings(state);
                bringBallToFront(state);
            } else {
                removeOwnBall(state);
            }
        }
    }

    /** 移除某个 Activity 上的模块悬浮球（可重复调用）。 */
    private static void removeOwnBall(ActivityState state) {
        if (state == null || state.ball == null) {
            return;
        }
        removeView(state, state.ball);
        state.ball = null;
    }

    /** 把当前悬浮球设置套到某个 Activity 的球上（尺寸变化会重新裁剪位置）。 */
    private static void applyBallSettings(ActivityState state) {
        if (state == null || state.destroyed || state.ball == null) {
            return;
        }
        if (!(state.ball instanceof BallView)) {
            return;
        }
        try {
            BallView ball = (BallView) state.ball;
            Context context = ball.getContext();
            int sizePx = dp(context, clampBallSizeDp(sBallSizeDp));
            ball.applySettings(sBallStyle, sizePx, clampBallAlpha(sBallAlpha));
        } catch (Throwable t) {
            log("应用悬浮球设置失败");
            logThrowable(t);
        }
    }

    private static int clampBallSizeDp(int sizeDp) {
        if (sizeDp < 32 || sizeDp > 80) {
            return RuleClient.DEFAULT_BALL_SIZE_DP;
        }
        return sizeDp;
    }

    private static int clampBallAlpha(int alpha) {
        if (alpha < 150 || alpha > 255) {
            return RuleClient.DEFAULT_BALL_ALPHA;
        }
        return alpha;
    }

    /** 系统应用（SystemUI / 桌面 / 设置等）不装悬浮球，但规则仍然生效。 */
    private static boolean isSystemApp(Activity activity) {
        try {
            ApplicationInfo info = activity.getApplicationInfo();
            if (info == null) {
                return false;
            }
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            return (info.flags & systemFlags) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 悬浮球长按菜单 ====================

    private static void showBallMenu(final Activity activity, final ActivityState state) {
        if (state == null || state.decor == null || state.menuOverlay != null) {
            return;
        }
        try {
            final Context context = activity;
            final FrameLayout overlay = new FrameLayout(context);
            overlay.setTag(INTERNAL_TAG + "#menu");
            overlay.setBackgroundColor(MENU_SCRIM);
            overlay.setClickable(true);
            overlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenu(state);
                }
            });

            LinearLayout card = buildCard(context, 12);
            TextView title = new TextView(context);
            title.setText("上帝模式 Lite");
            title.setTextColor(COLOR_TEXT_DIM);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 4));
            card.addView(title);

            card.addView(makeRow(context, "打开上帝模式 Lite", COLOR_TEXT, new Runnable() {
                @Override
                public void run() {
                    closeMenu(state);
                    openMainApp(context);
                }
            }));
            card.addView(makeRow(context, "刷新规则", COLOR_TEXT, new Runnable() {
                @Override
                public void run() {
                    closeMenu(state);
                    refreshRules(activity, state);
                    toast(activity, "规则已刷新");
                }
            }));
            card.addView(makeRow(context, "隐藏悬浮球", COLOR_TEXT, new Runnable() {
                @Override
                public void run() {
                    closeMenu(state);
                    hideBall(state);
                    toast(activity, "已隐藏悬浮球（重启应用后恢复）");
                }
            }));
            card.addView(makeRow(context, "取消", COLOR_TEXT_DIM, new Runnable() {
                @Override
                public void run() {
                    closeMenu(state);
                }
            }));

            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.gravity = Gravity.BOTTOM;
            cardParams.leftMargin = dp(context, 10);
            cardParams.rightMargin = dp(context, 10);
            cardParams.bottomMargin = dp(context, 8) + navBarHeight(context);
            overlay.addView(card, cardParams);

            FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            state.decor.addView(overlay, overlayParams);
            state.menuOverlay = overlay;
        } catch (Throwable t) {
            log("显示悬浮球菜单失败");
            logThrowable(t);
        }
    }

    private static void closeMenu(ActivityState state) {
        closeMenuInternal(state);
    }

    private static void closeMenuInternal(ActivityState state) {
        if (state == null) {
            return;
        }
        View overlay = state.menuOverlay;
        state.menuOverlay = null;
        removeView(state, overlay);
    }

    private static void hideBall(ActivityState state) {
        if (state == null) {
            return;
        }
        state.ballHidden = true;
        if (state.pkg != null) {
            BALL_HIDDEN_PKGS.add(state.pkg);
        }
        removeView(state, state.ball);
        state.ball = null;
    }

    private static void openMainApp(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(OWN_PACKAGE, MAIN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
        } catch (Throwable t) {
            log("打开主应用失败");
            logThrowable(t);
            toast(context, "打开上帝模式 Lite 失败");
        }
    }

    // ==================== 图层选择 ====================

    private static void openPicker(final Activity activity, final ActivityState state) {
        if (state == null || state.decor == null || state.pickerOverlay != null) {
            return;
        }
        try {
            final Context context = activity;
            final List<PickTarget> targets = new ArrayList<PickTarget>();
            collectTargets(state.decor, "", 0, targets);

            final FrameLayout root = new FrameLayout(context);
            root.setTag(INTERNAL_TAG + "#picker");
            root.setBackgroundColor(PICKER_SCRIM);
            root.setClickable(true);

            final PickerUi ui = new PickerUi();
            final PickerView pickerView = new PickerView(context, targets);
            pickerView.setPickListener(new PickListener() {
                @Override
                public void onPicked(PickTarget target) {
                    if (target == null || target.view == null) {
                        return;
                    }
                    ui.selected = target;
                    ui.title.setText(shortDesc(target.view));
                    ui.subtitle.setText(subtitleOf(target));
                    ui.card.setVisibility(View.VISIBLE);
                }
            });
            root.addView(pickerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 顶部提示条（v3）：图标位 + 主标题 15sp + 副标题「共 N 个图层 · 当前 <包名>」+ 取消胶囊
            LinearLayout bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xCC000000);
            bar.setPadding(dp(context, 12), dp(context, 8), dp(context, 10), dp(context, 8));

            TextView icon = new TextView(context);
            icon.setText("◈");
            icon.setTextColor(COLOR_ACCENT);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            icon.setPadding(0, 0, dp(context, 8), 0);
            bar.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout titleBox = new LinearLayout(context);
            titleBox.setOrientation(LinearLayout.VERTICAL);
            TextView tip = new TextView(context);
            tip.setText("图层选择：点选要处理的元素");
            tip.setTextColor(COLOR_TEXT);
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tip.setSingleLine(true);
            titleBox.addView(tip);
            TextView tipSub = new TextView(context);
            tipSub.setText("共 " + targets.size() + " 个图层 · 当前 "
                    + (state.pkg == null ? "" : state.pkg));
            tipSub.setTextColor(COLOR_TEXT_DIM);
            tipSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tipSub.setSingleLine(true);
            titleBox.addView(tipSub);
            bar.addView(titleBox, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView cancel = new TextView(context);
            cancel.setText("取消");
            cancel.setTextColor(COLOR_TEXT);
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(context, 16), dp(context, 7), dp(context, 16), dp(context, 7));
            GradientDrawable capsule = new GradientDrawable();
            capsule.setColor(COLOR_BUTTON_GRAY);
            capsule.setCornerRadius(dp(context, 999));
            cancel.setBackground(capsule);
            cancel.setClickable(true);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closePicker(activity, state);
                }
            });
            bar.addView(cancel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            barParams.gravity = Gravity.TOP;
            barParams.topMargin = statusBarHeight(context);
            root.addView(bar, barParams);

            // 底部动作卡（选中后才显示）
            ui.card = buildPickerCard(activity, state, ui);
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.gravity = Gravity.BOTTOM;
            cardParams.leftMargin = dp(context, 8);
            cardParams.rightMargin = dp(context, 8);
            cardParams.bottomMargin = dp(context, 8) + navBarHeight(context);
            root.addView(ui.card, cardParams);
            ui.card.setVisibility(View.GONE);

            FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            state.decor.addView(root, rootParams);
            state.pickerOverlay = root;
            state.pickerView = pickerView;
            if (state.ball != null) {
                try {
                    state.ball.setVisibility(View.GONE);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            toast(activity, "点选要处理的元素");
        } catch (Throwable t) {
            log("打开图层选择失败");
            logThrowable(t);
        }
    }

    /** 收集所有候选元素（VISIBLE、宽高 >= 4px、跳过内部 View），v3 上限 200 个（减少内存/绘制开销）。 */
    private static void collectTargets(ViewGroup parent, String parentPath, int depth, List<PickTarget> out) {
        if (parent == null || out == null || out.size() >= MAX_PICK_TARGETS) {
            return;
        }
        int count;
        try {
            count = parent.getChildCount();
        } catch (Throwable t) {
            return;
        }
        int index = 0;
        for (int i = 0; i < count; i++) {
            if (out.size() >= MAX_PICK_TARGETS) {
                return;
            }
            View child;
            try {
                child = parent.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null || isInternal(child)) {
                continue;
            }
            String path = parentPath.length() == 0 ? String.valueOf(index) : parentPath + "/" + index;
            index++;
            try {
                if (child.getVisibility() == View.VISIBLE
                        && child.isShown()
                        && child.getWidth() >= 4
                        && child.getHeight() >= 4) {
                    int[] location = new int[2];
                    child.getLocationOnScreen(location);
                    int left = location[0];
                    int top = location[1];
                    int right = left + child.getWidth();
                    int bottom = top + child.getHeight();
                    if (right > left && bottom > top) {
                        out.add(new PickTarget(child, left, top, right, bottom, depth, path, labelOf(child)));
                    }
                }
            } catch (Throwable ignored) {
                // 单个 View 取坐标失败不影响其它
            }
            if (child instanceof ViewGroup) {
                collectTargets((ViewGroup) child, path, depth + 1, out);
            }
        }
    }

    private static LinearLayout buildPickerCard(final Activity activity, final ActivityState state, final PickerUi ui) {
        // v3 动作卡：#F22A2A2A 背景 / 圆角 14dp / 内边距 12dp
        LinearLayout card = buildCard(activity, 14, COLOR_CARD_TRANSLUCENT);
        int padding = dp(activity, 12);
        card.setPadding(padding, dp(activity, 10), padding, padding);

        // 第 1 行：viewId 末段 或 文字（16sp 白色粗体）
        ui.title = new TextView(activity);
        ui.title.setText("未选择元素");
        ui.title.setTextColor(COLOR_TEXT);
        ui.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ui.title.setTypeface(Typeface.DEFAULT_BOLD);
        ui.title.setSingleLine(true);
        ui.title.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 2));
        card.addView(ui.title);

        // 第 2 行：类名 · 坐标(左,上,宽×高)（11sp 灰色）
        ui.subtitle = new TextView(activity);
        ui.subtitle.setText("");
        ui.subtitle.setTextColor(COLOR_TEXT_DIM);
        ui.subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ui.subtitle.setSingleLine(true);
        ui.subtitle.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 10));
        card.addView(ui.subtitle);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int buttonHeight = dp(activity, 52);
        // 真删=红实底白字；遮挡=绿实底白字；取消=深灰底
        TextView deleteButton = makeSolidButton(activity, "真删", COLOR_DANGER, 0xFFFFFFFF, buttonHeight);
        TextView maskButton = makeSolidButton(activity, "遮挡", COLOR_ACCENT, 0xFFFFFFFF, buttonHeight);
        TextView cancelButton = makeSolidButton(activity, "取消", COLOR_BUTTON_GRAY, COLOR_TEXT, buttonHeight);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, buttonHeight, 1f);
        deleteParams.rightMargin = dp(activity, 8);
        LinearLayout.LayoutParams maskParams = new LinearLayout.LayoutParams(0, buttonHeight, 1f);
        maskParams.rightMargin = dp(activity, 8);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, buttonHeight, 1f);
        row.addView(deleteButton, deleteParams);
        row.addView(maskButton, maskParams);
        row.addView(cancelButton, cancelParams);
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PickTarget target = ui.selected;
                if (target == null || target.view == null) {
                    return;
                }
                hideNow(target.view);
                RuleClient.RuleSpec rule = buildRuleSpec(activity, target, "gone");
                boolean saved = RuleClient.insertRule(activity, rule);
                if (!saved) {
                    addLocalRule(activity.getPackageName(), rule);
                }
                toast(activity, saved ? "已真删并保存规则" : "已真删（规则未能保存到主应用）");
                closePicker(activity, state);
                refreshRules(activity, state);
            }
        });

        maskButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PickTarget target = ui.selected;
                if (target == null || target.view == null) {
                    return;
                }
                RuleClient.RuleSpec rule = buildRuleSpec(activity, target, "mask");
                boolean saved = RuleClient.insertRule(activity, rule);
                if (!saved) {
                    addLocalRule(activity.getPackageName(), rule);
                }
                // 立即生效，不等下一轮刷新
                addMaskView(state.decor, state, target.view, rule);
                toast(activity, saved ? "已遮挡并保存规则" : "已遮挡（规则未能保存到主应用）");
                closePicker(activity, state);
                refreshRules(activity, state);
            }
        });

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePicker(activity, state);
            }
        });

        return card;
    }

    private static void closePicker(Activity activity, ActivityState state) {
        closePickerInternal(state);
    }

    private static void closePickerInternal(ActivityState state) {
        if (state == null) {
            return;
        }
        View overlay = state.pickerOverlay;
        state.pickerOverlay = null;
        // v3 内存优化：清空候选列表并置空引用，避免 200 个 View 引用残留
        PickerView pickerView = state.pickerView;
        state.pickerView = null;
        if (pickerView != null) {
            try {
                pickerView.clearTargets();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        removeView(state, overlay);
        if (state.ball != null) {
            try {
                state.ball.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 清缓存后马上（0ms / 300ms）重新拉规则，让增删改立刻生效。 */
    private static void refreshRules(Activity activity, ActivityState state) {
        try {
            RuleClient.invalidateCache();
        } catch (Throwable ignored) {
            // 忽略
        }
        scheduleRuleApply(activity, state, 0L);
        scheduleRuleApply(activity, state, 300L);
    }

    private static RuleClient.RuleSpec buildRuleSpec(Activity activity, PickTarget target, String mode) {
        RuleClient.RuleSpec rule = new RuleClient.RuleSpec();
        rule.id = UUID.randomUUID().toString();
        try {
            rule.pkg = activity.getPackageName();
        } catch (Throwable t) {
            rule.pkg = null;
        }
        try {
            rule.activity = activity.getClass().getName();
        } catch (Throwable t) {
            rule.activity = null;
        }
        rule.viewId = resourceNameOf(target.view);
        try {
            rule.cls = target.view.getClass().getName();
        } catch (Throwable t) {
            rule.cls = null;
        }
        rule.text = textOfView(target.view);
        rule.path = target.path;
        rule.l = target.l;
        rule.t = target.t;
        rule.r = target.r;
        rule.b = target.b;
        rule.enabled = true;
        rule.created = System.currentTimeMillis();
        rule.mode = mode;
        return rule;
    }

    // ==================== 通用工具 ====================

    /** 取 View 的资源名（如 com.foo:id/btn）；没有 id 或取名失败返回 null。 */
    private static String resourceNameOf(View view) {
        if (view == null) {
            return null;
        }
        try {
            int id = view.getId();
            if (id == View.NO_ID) {
                return null;
            }
            return view.getResources().getResourceName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 TextView 文字；不是 TextView 或文字为空返回 null。 */
    private static String textOfView(View view) {
        if (!(view instanceof TextView)) {
            return null;
        }
        try {
            CharSequence text = ((TextView) view).getText();
            if (text == null) {
                return null;
            }
            String value = text.toString();
            if (value == null || value.trim().length() == 0) {
                return null;
            }
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有内容描述/文字的 View 给个短标签，用于图层选择里的小标签。 */
    private static String labelOf(View view) {
        try {
            CharSequence text = null;
            if (view instanceof TextView) {
                text = ((TextView) view).getText();
            }
            if (text == null || text.toString().trim().length() == 0) {
                text = view.getContentDescription();
            }
            if (text == null) {
                return null;
            }
            String value = text.toString().trim().replace('\n', ' ');
            if (value.length() == 0) {
                return null;
            }
            if (value.length() > 12) {
                value = value.substring(0, 12) + "…";
            }
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 选中元素的一行描述：优先文字/内容描述，其次资源名末段，最后类名简名。 */
    private static String shortDesc(View view) {
        if (view == null) {
            return "元素";
        }
        String text = textOfView(view);
        if (text != null) {
            return trimTo(text, 40);
        }
        try {
            CharSequence description = view.getContentDescription();
            if (description != null && description.toString().trim().length() > 0) {
                return trimTo(description.toString().trim(), 40);
            }
        } catch (Throwable ignored) {
            // 继续兜底
        }
        String name = resourceNameOf(view);
        if (name != null) {
            return trimTo(lastSegment(name), 40);
        }
        try {
            return simpleName(view.getClass().getName());
        } catch (Throwable t) {
            return "元素";
        }
    }

    /** 动作卡第 2 行：类名 · 坐标(左,上,宽×高)。 */
    private static String subtitleOf(PickTarget target) {
        if (target == null || target.view == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try {
            builder.append(simpleName(target.view.getClass().getName()));
        } catch (Throwable t) {
            builder.append("View");
        }
        builder.append(" · 坐标(")
                .append(target.l).append(",").append(target.t).append(") ")
                .append(target.width()).append("×").append(target.height());
        return builder.toString();
    }

    /** 是否是我们自己加的 View（tag 前缀约定）。 */
    private static boolean isInternal(View view) {
        if (view == null) {
            return false;
        }
        try {
            Object tag = view.getTag();
            return tag instanceof String && ((String) tag).startsWith(INTERNAL_TAG);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void removeView(ActivityState state, View view) {
        if (view == null) {
            return;
        }
        try {
            ViewGroup parent = view.getParent() instanceof ViewGroup
                    ? (ViewGroup) view.getParent() : null;
            if (parent != null) {
                parent.removeView(view);
            }
        } catch (Throwable t) {
            log("移除 View 失败");
            logThrowable(t);
        }
    }

    /** onDestroy 兜底：按 tag 移除 decor 上所有内部 View。 */
    private static void removeInternalViews(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            View decorView = window.getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return;
            }
            ViewGroup group = (ViewGroup) decorView;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child;
                try {
                    child = group.getChildAt(i);
                } catch (Throwable t) {
                    continue;
                }
                if (isInternal(child)) {
                    try {
                        group.removeView(child);
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable t) {
            log("清理内部 View 失败");
            logThrowable(t);
        }
    }

    private static LinearLayout buildCard(Context context, int cornerDp) {
        return buildCard(context, cornerDp, COLOR_CARD);
    }

    private static LinearLayout buildCard(Context context, int cornerDp, int color) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, cornerDp));
        card.setBackground(background);
        int padding = dp(context, 6);
        card.setPadding(padding, padding, padding, padding);
        return card;
    }

    private static TextView makeRow(Context context, String text, int color, final Runnable action) {
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextColor(color);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setPadding(dp(context, 14), dp(context, 13), dp(context, 14), dp(context, 13));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action == null) {
                    return;
                }
                try {
                    action.run();
                } catch (Throwable t) {
                    log("菜单操作失败");
                    logThrowable(t);
                }
            }
        });
        return row;
    }

    /** v3 实底按钮：52dp 高 / 16sp 粗体 / 白字 / 圆角 10dp（真删红、遮挡绿、取消深灰）。 */
    private static TextView makeSolidButton(Context context, String text, int backgroundColor,
                                            int textColor, int heightPx) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(context, 10));
        button.setBackground(background);
        button.setClickable(true);
        button.setFocusable(true);
        // 用 View 的最小高度（API 1）兜底，实际高度由外部 LayoutParams 固定 52dp
        button.setMinimumHeight(heightPx);
        return button;
    }

    private static int statusBarHeight(Context context) {
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return context.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
            // 走默认值
        }
        return dp(context, 24);
    }

    private static int navBarHeight(Context context) {
        try {
            int id = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                return context.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
            // 走默认值
        }
        return 0;
    }

    private static int dp(Context context, float value) {
        try {
            return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return (int) value;
        }
    }

    private static String trimTo(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() > max) {
            return text.substring(0, max) + "…";
        }
        return text;
    }

    private static String lastSegment(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) {
            return name.substring(slash + 1);
        }
        return name;
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "View";
        }
        int dot = className.lastIndexOf('.');
        return dot >= 0 && dot + 1 < className.length() ? className.substring(dot + 1) : className;
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // Toast 失败不影响功能
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

    // ==================== 内部 View 类 ====================

    /** 悬浮球回调。 */
    interface BallListener {
        void onBallTap();

        void onBallLongPress();

        void onBallMoved(int left, int top);
    }

    /**
     * 悬浮球：可切换 5 种样式（v3）：0 眼睛 / 1 圆点 / 2 盾牌 / 3 文字「隐」 / 4 禁止斜杠。
     * 全部用 onDraw 几何绘制，不持有 Bitmap；尺寸/透明度由 provider 的 ball_* 列驱动。
     * 自己处理触摸：拖动改变 decor 内 margin；短按=单击；按住 480ms=长按。
     */
    static final class BallView extends View {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pupilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint slashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shieldPath = new Path();
        private final float density;
        private Handler handler = new Handler(Looper.getMainLooper());
        /** 当前样式：0 眼睛 / 1 圆点 / 2 盾牌 / 3 文字「隐」 / 4 禁止斜杠。 */
        private int style = BALL_STYLE_EYE;
        private final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                longPressFired = true;
                if (listener == null) {
                    return;
                }
                try {
                    listener.onBallLongPress();
                } catch (Throwable t) {
                    log("悬浮球长按回调失败");
                    logThrowable(t);
                }
            }
        };

        private BallListener listener;
        private boolean pressed;
        private boolean dragging;
        private boolean longPressFired;
        private float downRawX;
        private float downRawY;
        private int startLeft;
        private int startTop;

        BallView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            bgPaint.setStyle(Paint.Style.FILL);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2f * density);
            eyePaint.setStyle(Paint.Style.FILL);
            pupilPaint.setStyle(Paint.Style.FILL);
            shieldPaint.setStyle(Paint.Style.STROKE);
            shieldPaint.setStrokeWidth(2.2f * density);
            shieldPaint.setStrokeJoin(Paint.Join.ROUND);
            shieldPaint.setColor(COLOR_ACCENT);
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(COLOR_ACCENT);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            slashPaint.setStyle(Paint.Style.STROKE);
            slashPaint.setStrokeWidth(3f * density);
            slashPaint.setStrokeCap(Paint.Cap.ROUND);
            slashPaint.setColor(COLOR_ACCENT);
            setClickable(true);
            setFocusable(false);
            setWillNotDraw(false);
        }

        void setListener(BallListener ballListener) {
            this.listener = ballListener;
        }

        /** 应用样式 / 尺寸(px) / 透明度(150~255)：立即重绘，尺寸变化时重新裁剪位置。 */
        void applySettings(int newStyle, int newSizePx, int newAlpha) {
            style = newStyle;
            try {
                setAlpha(newAlpha / 255f);
            } catch (Throwable ignored) {
                // 忽略
            }
            ViewGroup.LayoutParams params = getLayoutParams();
            if (params instanceof FrameLayout.LayoutParams && newSizePx > 0) {
                FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
                if (frameParams.width != newSizePx || frameParams.height != newSizePx) {
                    frameParams.width = newSizePx;
                    frameParams.height = newSizePx;
                    int maxX = 0;
                    int maxY = 0;
                    try {
                        View parent = getParent() instanceof View ? (View) getParent() : null;
                        if (parent != null) {
                            maxX = Math.max(0, parent.getWidth() - newSizePx);
                            maxY = Math.max(0, parent.getHeight() - newSizePx);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    if (frameParams.leftMargin > maxX) {
                        frameParams.leftMargin = maxX;
                    }
                    if (frameParams.topMargin > maxY) {
                        frameParams.topMargin = maxY;
                    }
                    if (frameParams.leftMargin < 0) {
                        frameParams.leftMargin = 0;
                    }
                    if (frameParams.topMargin < 0) {
                        frameParams.topMargin = 0;
                    }
                    try {
                        setLayoutParams(frameParams);
                    } catch (Throwable t) {
                        log("更新悬浮球尺寸失败");
                        logThrowable(t);
                    }
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float centerX = width / 2f;
            float centerY = height / 2f;
            float radius = Math.min(width, height) / 2f - 2f * density;
            if (radius <= 0) {
                radius = Math.min(width, height) / 2f;
            }
            // 深底 + 绿色主色（v3 规格 #CC1E1E1E / #4CAF50），全部几何绘制不引 Bitmap
            bgPaint.setColor(pressed ? 0xF01E1E1E : 0xCC1E1E1E);
            canvas.drawCircle(centerX, centerY, radius, bgPaint);
            ringPaint.setColor(COLOR_ACCENT);
            canvas.drawCircle(centerX, centerY, radius - 1f * density, ringPaint);
            switch (style) {
                case BALL_STYLE_DOT:
                    drawDot(canvas, centerX, centerY, radius);
                    break;
                case BALL_STYLE_SHIELD:
                    drawShield(canvas, centerX, centerY, radius);
                    break;
                case BALL_STYLE_TEXT:
                    drawTextStyle(canvas, centerX, centerY, radius);
                    break;
                case BALL_STYLE_SLASH:
                    drawSlash(canvas, centerX, centerY, radius);
                    break;
                case BALL_STYLE_EYE:
                default:
                    drawEye(canvas, centerX, centerY, radius);
                    break;
            }
        }

        /** 样式 0：眼睛（v2 默认样式）。 */
        private void drawEye(Canvas canvas, float centerX, float centerY, float radius) {
            eyePaint.setColor(0xFFF2F2F2);
            RectF eye = new RectF(
                    centerX - radius * 0.46f, centerY - radius * 0.30f,
                    centerX + radius * 0.46f, centerY + radius * 0.30f);
            canvas.drawOval(eye, eyePaint);
            pupilPaint.setColor(0xFF121212);
            canvas.drawCircle(centerX, centerY, radius * 0.18f, pupilPaint);
        }

        /** 样式 1：实心圆点。 */
        private void drawDot(Canvas canvas, float centerX, float centerY, float radius) {
            eyePaint.setColor(COLOR_ACCENT);
            canvas.drawCircle(centerX, centerY, radius * 0.32f, eyePaint);
        }

        /** 样式 2：盾牌轮廓（Path 几何绘制）。 */
        private void drawShield(Canvas canvas, float centerX, float centerY, float radius) {
            shieldPath.reset();
            float topY = centerY - radius * 0.60f;
            float midY = centerY - radius * 0.30f;
            float bottomY = centerY + radius * 0.62f;
            float leftX = centerX - radius * 0.44f;
            float rightX = centerX + radius * 0.44f;
            shieldPath.moveTo(centerX, topY);
            shieldPath.lineTo(rightX, midY);
            shieldPath.quadTo(rightX, centerY + radius * 0.16f, centerX, bottomY);
            shieldPath.quadTo(leftX, centerY + radius * 0.16f, leftX, midY);
            shieldPath.close();
            canvas.drawPath(shieldPath, shieldPaint);
        }

        /** 样式 3：绿色文字「隐」。 */
        private void drawTextStyle(Canvas canvas, float centerX, float centerY, float radius) {
            textPaint.setTextSize(radius * 0.95f);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText("隐", centerX, baseline, textPaint);
        }

        /** 样式 4：禁止斜杠。 */
        private void drawSlash(Canvas canvas, float centerX, float centerY, float radius) {
            canvas.drawLine(centerX - radius * 0.50f, centerY + radius * 0.50f,
                    centerX + radius * 0.50f, centerY - radius * 0.50f, slashPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    dragging = false;
                    longPressFired = false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startLeft = marginLeft();
                    startTop = marginTop();
                    invalidate();
                    try {
                        if (handler != null) {
                            handler.postDelayed(longPressRunnable, BALL_LONG_PRESS_MS);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    float slop = 8f * density;
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true;
                        try {
                            if (handler != null) {
                                handler.removeCallbacks(longPressRunnable);
                            }
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    if (dragging) {
                        moveTo(startLeft + (int) dx, startTop + (int) dy);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    try {
                        if (handler != null) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    pressed = false;
                    invalidate();
                    if (dragging) {
                        if (listener != null) {
                            try {
                                listener.onBallMoved(marginLeft(), marginTop());
                            } catch (Throwable t) {
                                logThrowable(t);
                            }
                        }
                    } else if (!longPressFired && listener != null) {
                        try {
                            listener.onBallTap();
                        } catch (Throwable t) {
                            log("悬浮球点击回调失败");
                            logThrowable(t);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    try {
                        if (handler != null) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    pressed = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            try {
                if (handler != null) {
                    handler.removeCallbacks(longPressRunnable);
                    handler = null;
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            super.onDetachedFromWindow();
        }

        private int marginLeft() {
            ViewGroup.LayoutParams params = getLayoutParams();
            if (params instanceof FrameLayout.LayoutParams) {
                return ((FrameLayout.LayoutParams) params).leftMargin;
            }
            return 0;
        }

        private int marginTop() {
            ViewGroup.LayoutParams params = getLayoutParams();
            if (params instanceof FrameLayout.LayoutParams) {
                return ((FrameLayout.LayoutParams) params).topMargin;
            }
            return 0;
        }

        /** 拖动到指定 margin，四边限制在父容器（decorView）内。 */
        private void moveTo(int left, int top) {
            ViewGroup.LayoutParams params = getLayoutParams();
            if (!(params instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
            int maxX = 0;
            int maxY = 0;
            try {
                View parent = getParent() instanceof View ? (View) getParent() : null;
                if (parent != null) {
                    maxX = parent.getWidth() - getWidth();
                    maxY = parent.getHeight() - getHeight();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            if (maxX < 0) {
                maxX = 0;
            }
            if (maxY < 0) {
                maxY = 0;
            }
            if (left < 0) {
                left = 0;
            } else if (left > maxX) {
                left = maxX;
            }
            if (top < 0) {
                top = 0;
            } else if (top > maxY) {
                top = maxY;
            }
            frameParams.leftMargin = left;
            frameParams.topMargin = top;
            try {
                setLayoutParams(frameParams);
            } catch (Throwable t) {
                log("移动悬浮球失败");
                logThrowable(t);
            }
        }
    }

    /** 图层选择里的一个候选目标（屏幕坐标 + 深度 + 路径）。 */
    static final class PickTarget {
        final View view;
        final int l;
        final int t;
        final int r;
        final int b;
        final int depth;
        final String path;
        final String label;

        PickTarget(View view, int l, int t, int r, int b, int depth, String path, String label) {
            this.view = view;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.depth = depth;
            this.path = path;
            this.label = label;
        }

        boolean contains(int x, int y) {
            return x >= l && x < r && y >= t && y < b;
        }

        int width() {
            return r - l;
        }

        int height() {
            return b - t;
        }
    }

    /** 图层选择里选中元素后的回调。 */
    interface PickListener {
        void onPicked(PickTarget target);
    }

    /** 图层选择底部卡片的引用集合。 */
    static final class PickerUi {
        LinearLayout card;
        TextView title;
        TextView subtitle;
        PickTarget selected;
    }

    /**
     * 图层选择覆盖层里负责「画框 + 命中」的 View：
     * 把收集到的候选元素矩形画成彩色边框（按深度循环颜色），点击时取最深的命中 View。
     */
    static final class PickerView extends View {

        private final List<PickTarget> targets;
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlightStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlightGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int labelMinWidth;
        private final int labelMinHeight;
        private final int[] location = new int[2];

        private PickTarget selected;
        private PickListener listener;

        PickerView(Context context, List<PickTarget> targets) {
            super(context);
            this.targets = targets;
            this.density = context.getResources().getDisplayMetrics().density;
            this.labelMinWidth = dp(context, 120);
            this.labelMinHeight = dp(context, 18);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1.5f * density);

            labelBgPaint.setStyle(Paint.Style.FILL);
            labelBgPaint.setColor(0xCC000000);

            labelTextPaint.setColor(0xFFFFFFFF);
            labelTextPaint.setTextSize(9f * density);
            // v3：标签文字加粗
            labelTextPaint.setFakeBoldText(true);

            highlightFillPaint.setStyle(Paint.Style.FILL);
            highlightFillPaint.setColor(0x3300FF00);

            highlightStrokePaint.setStyle(Paint.Style.STROKE);
            highlightStrokePaint.setStrokeWidth(3f * density);
            highlightStrokePaint.setColor(COLOR_ACCENT);

            // v3：选中高亮的外发光（4dp 半透明绿）
            highlightGlowPaint.setStyle(Paint.Style.STROKE);
            highlightGlowPaint.setStrokeWidth(4f * density);
            highlightGlowPaint.setColor(COLOR_HIGHLIGHT_GLOW);

            setWillNotDraw(false);
        }

        void setPickListener(PickListener pickListener) {
            this.listener = pickListener;
        }

        /** v3 内存优化：关闭图层选择时清空候选列表、断开回调与选中引用。 */
        void clearTargets() {
            listener = null;
            selected = null;
            if (targets != null) {
                targets.clear();
            }
        }

        PickTarget getSelected() {
            return selected;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<PickTarget> list = targets;
            if (canvas == null || list == null || list.isEmpty()) {
                return;
            }
            try {
                getLocationOnScreen(location);
            } catch (Throwable t) {
                location[0] = 0;
                location[1] = 0;
            }
            int saveCount = canvas.save();
            try {
                canvas.translate(-location[0], -location[1]);
                // 先画所有边框（颜色按深度循环）
                for (int i = 0; i < list.size(); i++) {
                    PickTarget target = list.get(i);
                    if (target == null) {
                        continue;
                    }
                    borderPaint.setColor(DEPTH_COLORS[target.depth % DEPTH_COLORS.length]);
                    try {
                        canvas.drawRect(target.l, target.t, target.r, target.b, borderPaint);
                    } catch (Throwable ignored) {
                        // 单个矩形画失败不影响其它
                    }
                }
                // 再画有内容的标签（盖在边框上面）
                for (int i = 0; i < list.size(); i++) {
                    PickTarget target = list.get(i);
                    if (target == null || target.label == null) {
                        continue;
                    }
                    if (target.width() < labelMinWidth || target.height() < labelMinHeight) {
                        continue;
                    }
                    try {
                        float x = target.l + 2f * density;
                        float baseline = target.t + 11f * density;
                        float textWidth = labelTextPaint.measureText(target.label);
                        canvas.drawRect(target.l, target.t,
                                x + textWidth + 1f * density, target.t + 13f * density, labelBgPaint);
                        canvas.drawText(target.label, x, baseline, labelTextPaint);
                    } catch (Throwable ignored) {
                        // 单个标签画失败不影响其它
                    }
                }
                // 最后画选中高亮：4dp 外发光 + 淡绿填充 + 3dp 亮绿边框（v3）
                PickTarget chosen = selected;
                if (chosen != null) {
                    float glow = 2f * density;
                    canvas.drawRect(chosen.l - glow, chosen.t - glow,
                            chosen.r + glow, chosen.b + glow, highlightGlowPaint);
                    canvas.drawRect(chosen.l, chosen.t, chosen.r, chosen.b, highlightFillPaint);
                    canvas.drawRect(chosen.l, chosen.t, chosen.r, chosen.b, highlightStrokePaint);
                }
            } finally {
                canvas.restoreToCount(saveCount);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                try {
                    getLocationOnScreen(location);
                    int x = (int) (event.getX() + location[0]);
                    int y = (int) (event.getY() + location[1]);
                    PickTarget hit = findDeepest(x, y);
                    if (hit != null) {
                        selected = hit;
                        invalidate();
                        if (listener != null) {
                            listener.onPicked(hit);
                        }
                    }
                } catch (Throwable t) {
                    log("图层选择点击处理失败");
                    logThrowable(t);
                }
                return true;
            }
            return true;
        }

        /** 命中最深的 View（同深度取面积更小的），相当于手写版的 findViewAt。 */
        private PickTarget findDeepest(int x, int y) {
            List<PickTarget> list = targets;
            if (list == null) {
                return null;
            }
            PickTarget best = null;
            for (int i = 0; i < list.size(); i++) {
                PickTarget target = list.get(i);
                if (target == null || !target.contains(x, y)) {
                    continue;
                }
                if (best == null) {
                    best = target;
                    continue;
                }
                if (target.depth > best.depth) {
                    best = target;
                } else if (target.depth == best.depth) {
                    long targetArea = (long) target.width() * (long) target.height();
                    long bestArea = (long) best.width() * (long) best.height();
                    if (targetArea < bestArea) {
                        best = target;
                    }
                }
            }
            return best;
        }
    }
}
