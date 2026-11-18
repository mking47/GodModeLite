package com.dsh.godmodelite.a11y;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.dsh.godmodelite.core.ConfigStore;
import com.dsh.godmodelite.core.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 蒙版管理器（全静态，只服务于唯一的无障碍服务实例）。
 *
 * 职责：把已启用规则命中的界面节点用白色小窗口盖住，达到“遮挡 + 无法点击”的效果。
 * - apply(service, pkg)：先清空旧蒙版，再按规则找节点、逐一 addView；
 * - 每个蒙版是一个带长按事件的 View，长按直接删除对应规则并刷新；
 * - 最多 MAX_MASKS 个蒙版，覆盖屏幕超过 MAX_COVER_PERCENT% 的元素跳过；
 * - 总开关关闭 / 规则停用时不出蒙版。
 *
 * v2 变化：蒙版不再区分规则模式（rule.mode）——
 * "mask"（遮挡）和 "gone"（真删）在这里都按遮挡显示。原因是无障碍服务跑在独立进程，
 * 无法跨进程修改目标应用 View 的可见性；真正 setVisibility(GONE) 只能由 LSPosed 模块
 * 在目标进程内完成。规则本身仍然带着 mode 字段保存，模块端会按 mode 处理。
 *
 * 所有方法都必须在主线程调用（无障碍回调和 UI 回调本身就在主线程）。
 */
public final class MaskManager {

    private static final String TAG = "GodModeMask";

    /** 蒙版颜色：白色不透明（v1 固定，后续可做成配置项） */
    public static final int MASK_COLOR = 0xFFFFFFFF;

    /** 同时存在的蒙版窗口上限，防止节点树异常时创建过多窗口 */
    private static final int MAX_MASKS = 30;

    /** 蒙版面积超过屏幕的 85% 视为“整页元素”，跳过以免整个屏幕被盖死 */
    private static final int MAX_COVER_PERCENT = 85;

    /** 当前已添加的蒙版 View（v3 不再额外保存规则列表，规则由每个 View 的监听器持有） */
    private static final List<View> sMaskViews = new ArrayList<View>();

    /** 添加蒙版时缓存的 WindowManager，用于 clear() 时移除窗口；没有蒙版时置空避免静态持有 */
    private static WindowManager sWindowManager;

    private MaskManager() {
        // 工具类，不允许实例化
    }

    /**
     * 按包名刷新蒙版：先移除全部旧蒙版，再给该包下所有启用规则的命中节点加蒙版。
     *
     * @param service 无障碍服务（同时作为 Context 和节点查询入口）
     * @param pkg     当前前台应用包名，可为 null（等于只清理）
     */
    public static void apply(BlockerService service, String pkg) {
        // 任何情况下都先清掉旧蒙版，避免残留
        clear();
        if (service == null) {
            return;
        }
        WindowManager windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        sWindowManager = windowManager;

        // 总开关关闭：不显示任何蒙版
        boolean blockingEnabled;
        try {
            blockingEnabled = ConfigStore.isBlockingEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "读取屏蔽总开关失败", t);
            return;
        }
        if (!blockingEnabled) {
            return;
        }
        if (pkg == null || pkg.length() == 0) {
            return;
        }

        List<Rule> rules;
        try {
            rules = ConfigStore.rulesFor(pkg);
        } catch (Throwable t) {
            Log.w(TAG, "读取规则失败", t);
            return;
        }
        if (rules == null || rules.isEmpty()) {
            return;
        }

        // 屏幕尺寸：优先用 WindowManager 的真实屏幕尺寸，失败再退回资源里的尺寸
        int screenWidth = 0;
        int screenHeight = 0;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        } catch (Throwable t) {
            screenWidth = 0;
            screenHeight = 0;
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            DisplayMetrics resMetrics = service.getResources().getDisplayMetrics();
            screenWidth = resMetrics.widthPixels;
            screenHeight = resMetrics.heightPixels;
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        long screenArea = (long) screenWidth * (long) screenHeight;

        int added = 0;
        for (int i = 0; i < rules.size() && added < MAX_MASKS; i++) {
            Rule rule = rules.get(i);
            // 过滤条件只看「规则启用」，刻意不看 rule.mode：
            // mask / gone 两种情况在无障碍模式下都按遮挡显示（见类注释说明）。
            // 总开关 ConfigStore.isBlockingEnabled() 已在上面统一判断过了。
            if (rule == null || rule.id == null || !rule.enabled) {
                continue;
            }
            AccessibilityNodeInfo node;
            try {
                node = NodeFinder.findByRule(service, rule);
            } catch (Throwable t) {
                node = null;
            }
            if (node == null) {
                continue;
            }
            Rect rect = new Rect();
            try {
                node.getBoundsInScreen(rect);
            } catch (Throwable t) {
                continue;
            }
            if (rect.width() <= 0 || rect.height() <= 0) {
                continue;
            }
            // 裁剪到屏幕范围内（元素可能有一部分在屏幕外）
            if (!rect.intersect(0, 0, screenWidth, screenHeight)) {
                continue;
            }
            if (rect.width() <= 0 || rect.height() <= 0) {
                continue;
            }
            // 覆盖面积过大（超过屏幕 85%）跳过，防止把整个界面盖死
            long area = (long) rect.width() * (long) rect.height();
            if (area * 100L > screenArea * (long) MAX_COVER_PERCENT) {
                continue;
            }
            View maskView = createMaskView(service, rule);
            WindowManager.LayoutParams params = createMaskParams(rect);
            try {
                windowManager.addView(maskView, params);
                sMaskViews.add(maskView);
                added++;
            } catch (Throwable t) {
                // BadTokenException / 权限被限制等：忽略这一条，不影响其它蒙版
                Log.w(TAG, "添加蒙版窗口失败", t);
            }
        }
        if (sMaskViews.isEmpty()) {
            // 没有蒙版时不要静态持有 WindowManager，避免常驻引用
            sWindowManager = null;
        }
    }

    /** 移除全部蒙版窗口 */
    public static void clear() {
        WindowManager windowManager = sWindowManager;
        if (windowManager == null) {
            BlockerService service = BlockerService.get();
            if (service != null) {
                try {
                    windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
                } catch (Throwable t) {
                    windowManager = null;
                }
            }
        }
        for (int i = 0; i < sMaskViews.size(); i++) {
            View view = sMaskViews.get(i);
            if (view == null || windowManager == null) {
                continue;
            }
            try {
                windowManager.removeView(view);
            } catch (Throwable t) {
                // 窗口可能已被系统移除，忽略
            }
        }
        sMaskViews.clear();
        // clear 后不再持有 WindowManager 静态引用（下一次 apply 会重新取）
        sWindowManager = null;
    }

    /** 当前蒙版数量（调试用） */
    public static int maskCount() {
        return sMaskViews.size();
    }

    // ==================== 蒙版 View 与窗口参数 ====================

    /**
     * 创建单个蒙版 View：白色背景 + 吞掉点击 + 长按删除规则。
     * 注意：必须可点击，否则触摸会穿透到下面的应用。
     */
    private static View createMaskView(final BlockerService service, final Rule rule) {
        View maskView = new View(service);
        maskView.setBackgroundColor(MASK_COLOR);
        maskView.setClickable(true);
        maskView.setFocusable(false);
        // 单击直接吞掉，不传给下面的应用
        maskView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 蒙版的作用就是拦截点击，这里什么都不做
            }
        });
        // 长按：删除这条规则并刷新蒙版
        maskView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                handleMaskLongClick(service, rule);
                return true;
            }
        });
        return maskView;
    }

    /** 长按蒙版：删除规则 + Toast 提示 + 重新刷新蒙版 */
    private static void handleMaskLongClick(final BlockerService service, final Rule rule) {
        if (rule == null || rule.id == null) {
            return;
        }
        String description = describeSafely(rule);
        try {
            ConfigStore.deleteRule(rule.id);
        } catch (Throwable t) {
            Log.w(TAG, "删除规则失败", t);
            return;
        }
        try {
            Toast.makeText(service, "已删除规则：" + description, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // Toast 失败无所谓
        }
        final String pkg = rule.pkg != null && rule.pkg.length() > 0
                ? rule.pkg
                : service.getForegroundPackage();
        // 等本次触摸事件处理完再刷新窗口，避免在事件分发过程中 removeView
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                apply(service, pkg);
            }
        });
    }

    /** 蒙版窗口参数：绝对坐标定位到节点 bounds 的位置 */
    private static WindowManager.LayoutParams createMaskParams(Rect rect) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = BlockerService.overlayWindowType();
        params.format = PixelFormat.TRANSLUCENT;
        // 不获取焦点；允许接收触摸（不要 FLAG_NOT_TOUCHABLE，否则无法遮挡点击）；
        // NOT_TOUCH_MODAL 让蒙版范围之外的触摸继续交给下面的应用
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = rect.left;
        params.y = rect.top;
        params.width = rect.width();
        params.height = rect.height();
        try {
            params.setTitle("gm-mask");
        } catch (Throwable t) {
            // 老系统没有 setTitle 也不影响功能
        }
        return params;
    }

    /** 规则描述兜底：describe() 异常时用 text / viewId / 类名 */
    private static String describeSafely(Rule rule) {
        if (rule == null) {
            return "元素";
        }
        try {
            String text = rule.describe();
            if (text != null && text.length() > 0) {
                return text;
            }
        } catch (Throwable t) {
            // 忽略，走兜底
        }
        if (rule.text != null && rule.text.length() > 0) {
            return rule.text;
        }
        if (rule.viewId != null && rule.viewId.length() > 0) {
            int index = rule.viewId.indexOf('/');
            if (index >= 0 && index + 1 < rule.viewId.length()) {
                return rule.viewId.substring(index + 1);
            }
            return rule.viewId;
        }
        if (rule.cls != null && rule.cls.length() > 0) {
            int index = rule.cls.lastIndexOf('.');
            if (index >= 0 && index + 1 < rule.cls.length()) {
                return rule.cls.substring(index + 1);
            }
            return rule.cls;
        }
        return "元素";
    }
}
