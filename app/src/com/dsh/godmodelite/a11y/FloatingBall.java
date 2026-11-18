package com.dsh.godmodelite.a11y;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.godmodelite.core.ConfigStore;

/**
 * 可拖动的悬浮球（v2 新增，v3 支持自定义样式 / 尺寸 / 透明度）。
 *
 * 外观（v3）：由 ConfigStore 决定，全部在 onDraw 里用几何绘制，不持有 Bitmap：
 * 0 眼睛（默认，v2 样式）/ 1 圆点 / 2 盾牌轮廓 / 3 文字「隐」/ 4 禁止斜杠；
 * 尺寸 40 / 48 / 56 dp，透明度 150~235；底色 #CC1E1E1E + 绿色 #4CAF50 主色。
 * 交互：
 * 1. 拖动：位移超过 10px 视为拖动，跟随手指移动，松手时把位置写回 ConfigStore；
 * 2. 单击：整段手势位移 < 10px 且时长 < 250ms → 回调 Listener#onBallClick（进入图层选择）；
 * 3. 长按（500ms 且没有拖动）→ 底部小卡片菜单：打开上帝模式 Lite / 关闭悬浮球 / 取消。
 *
 * 窗口：TYPE_ACCESSIBILITY_OVERLAY（API < 22 用 TYPE_SYSTEM_ALERT），
 * flags 为 FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL，不需要悬浮窗权限。
 * 悬浮球是否显示由 BlockerService 决定（show/hide 只操作窗口，不写配置）。
 *
 * 内存：没有任何 static Context / Bitmap，Paint 只在 BallView 里创建一份。
 * 所有方法都必须在主线程调用（WindowManager.addView/updateViewLayout 只能在主线程）。
 */
public class FloatingBall {

    /** 悬浮球回调（由 BlockerService 实现） */
    public interface Listener {
        /** 单击悬浮球：进入图层选择模式 */
        void onBallClick();

        /** 长按菜单里点了「关闭悬浮球」：持久关闭（写 ConfigStore + 隐藏） */
        void onBallHideRequested();
    }

    private static final String TAG = "GodModeBall";

    /** 与 ConfigStore.BALL_STYLE_* 一致的样式值（0 眼睛 / 1 圆点 / 2 盾牌 / 3 文字隐 / 4 禁止斜杠） */
    private static final int STYLE_EYE = ConfigStore.BALL_STYLE_EYE;
    private static final int STYLE_DOT = ConfigStore.BALL_STYLE_DOT;
    private static final int STYLE_SHIELD = ConfigStore.BALL_STYLE_SHIELD;
    private static final int STYLE_TEXT = ConfigStore.BALL_STYLE_TEXT;
    private static final int STYLE_SLASH = ConfigStore.BALL_STYLE_SLASH;
    private static final int STYLE_COUNT = 5;

    /** 球的默认直径（dp），实际值从 ConfigStore.getBallSizeDp() 读取 */
    private static final int DEFAULT_SIZE_DP = ConfigStore.BALL_SIZE_MEDIUM;
    /** 允许的直径范围（dp）：配置脏数据时兜底，避免球消失或大得离谱 */
    private static final int MIN_SIZE_DP = 32;
    private static final int MAX_SIZE_DP = 96;

    /** 默认透明度，实际值从 ConfigStore.getBallAlpha() 读取 */
    private static final int DEFAULT_ALPHA = ConfigStore.BALL_ALPHA_HIGH;
    /** 透明度兜底范围：太低会看不见球，太高失去半透明效果 */
    private static final int MIN_ALPHA = 60;
    private static final int MAX_ALPHA = 255;

    /** 默认位置离屏幕右边缘的距离（dp） */
    private static final int EDGE_MARGIN_DP = 8;

    /** 手势位移超过该像素数就视为拖动 */
    private static final int TOUCH_SLOP_PX = 10;
    /** 按下到抬起短于该毫秒数才算单击 */
    private static final long CLICK_MAX_MS = 250L;
    /** 长按判定时间 */
    private static final long LONG_PRESS_MS = 500L;

    /** 球底颜色：半透明深色 */
    private static final int COLOR_BALL_BG = 0xCC1E1E1E;
    /** 绿色圆环颜色 */
    private static final int COLOR_RING = 0xFF4CAF50;
    /** 眼睛颜色 */
    private static final int COLOR_EYE = 0xFFFFFFFF;
    /** 菜单卡片背景 */
    private static final int COLOR_MENU_BG = 0xF21E1E1E;
    /** 菜单文字颜色 */
    private static final int COLOR_MENU_TEXT = 0xFFF2F2F2;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 球窗口（一个 BallView） */
    private BallView ballView;
    private WindowManager.LayoutParams ballParams;
    /** 长按弹出的底部菜单窗口 */
    private FrameLayout menuRoot;

    /** 球直径 / 屏幕尺寸（像素） */
    private int ballSizePx;
    private int screenWidth;
    private int screenHeight;

    /** 当前生效的样式 / 直径（dp）/ 透明度（0~255），来自 ConfigStore */
    private int style = STYLE_EYE;
    private int ballSizeDp = DEFAULT_SIZE_DP;
    private int ballAlpha = DEFAULT_ALPHA;

    /** 手势起点：屏幕绝对坐标 + 窗口偏移 */
    private float downRawX;
    private float downRawY;
    private int downWinX;
    private int downWinY;
    private long downTime;
    private boolean dragging;
    private boolean longPressFired;

    /** 球当前是否已经 addView 成功 */
    private boolean showing;

    /** 长按定时任务：到点且没拖动就弹菜单 */
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!showing || dragging || longPressFired) {
                return;
            }
            longPressFired = true;
            showMenu();
        }
    };

    public FloatingBall(AccessibilityService service, WindowManager windowManager, Listener listener) {
        this.service = service;
        this.windowManager = windowManager;
        this.listener = listener;
        updateScreenMetrics();
        // 创建时先读一次配置（样式 / 尺寸 / 透明度），保证第一次显示就是用户设置的样子
        loadSettings();
        ballSizePx = Math.max(dp(ballSizeDp), 1);
    }

    /** 球是否正在显示 */
    public boolean isShowing() {
        return showing;
    }

    // ==================== 样式 / 尺寸 / 透明度（v3） ====================

    /**
     * 重新读取 ConfigStore 里的悬浮球设置并立即生效（v3）：
     * - 球未显示：只更新字段，下次 show() 按新设置创建；
     * - 球显示中：尺寸变化时保持球心位置（越界裁剪），然后 updateViewLayout + 重绘。
     *
     * 由 BlockerService.applyBallSettings() 在主线程调用。
     */
    public void applyStyle() {
        loadSettings();
        int newSizePx = Math.max(dp(ballSizeDp), 1);
        if (ballView == null || ballParams == null || windowManager == null) {
            // 球窗口不存在：只记下新设置，下次显示时生效
            ballSizePx = newSizePx;
            return;
        }
        updateScreenMetrics();
        int oldSize = ballSizePx;
        // 尺寸变化时保持球心不动：先算出旧球心，再按新半径反推左上角
        int centerX = ballParams.x + oldSize / 2;
        int centerY = ballParams.y + oldSize / 2;
        ballSizePx = newSizePx;
        int newX = clampX(centerX - newSizePx / 2);
        int newY = clampY(centerY - newSizePx / 2);
        ballParams.width = newSizePx;
        ballParams.height = newSizePx;
        ballParams.x = newX;
        ballParams.y = newY;
        ballView.applyStyle(style, ballAlpha);
        try {
            windowManager.updateViewLayout(ballView, ballParams);
        } catch (Throwable t) {
            Log.w(TAG, "刷新悬浮球样式失败", t);
        }
    }

    /** 从 ConfigStore 读取样式 / 直径 / 透明度（带兜底与裁剪，异常不抛） */
    private void loadSettings() {
        int newStyle = STYLE_EYE;
        int newSizeDp = DEFAULT_SIZE_DP;
        int newAlpha = DEFAULT_ALPHA;
        try {
            newStyle = ConfigStore.getBallStyle();
            newSizeDp = ConfigStore.getBallSizeDp();
            newAlpha = ConfigStore.getBallAlpha();
        } catch (Throwable t) {
            // 存储还没初始化 / 旧版本没有这些字段：用默认值
            newStyle = STYLE_EYE;
            newSizeDp = DEFAULT_SIZE_DP;
            newAlpha = DEFAULT_ALPHA;
        }
        if (newStyle < 0 || newStyle >= STYLE_COUNT) {
            newStyle = STYLE_EYE;
        }
        if (newSizeDp < MIN_SIZE_DP) {
            newSizeDp = MIN_SIZE_DP;
        } else if (newSizeDp > MAX_SIZE_DP) {
            newSizeDp = MAX_SIZE_DP;
        }
        if (newAlpha < MIN_ALPHA) {
            newAlpha = MIN_ALPHA;
        } else if (newAlpha > MAX_ALPHA) {
            newAlpha = MAX_ALPHA;
        }
        style = newStyle;
        ballSizeDp = newSizeDp;
        ballAlpha = newAlpha;
    }

    // ==================== 窗口显示 / 隐藏 ====================

    /** 显示悬浮球（幂等：已显示时只刷新位置，不重复 addView） */
    public void show() {
        if (windowManager == null || service == null) {
            return;
        }
        if (showing) {
            // 已显示：按配置重新摆一次位置（例如从隐藏恢复、屏幕旋转后）
            int[] pos = resolvePosition();
            moveTo(pos[0], pos[1], false);
            return;
        }
        updateScreenMetrics();
        // 每次显示都重新读一次设置：球隐藏期间用户可能改过样式 / 尺寸 / 透明度
        loadSettings();
        ballSizePx = Math.max(dp(ballSizeDp), 1);
        int[] pos = resolvePosition();
        BallView view = new BallView(service, style, ballAlpha);
        WindowManager.LayoutParams params = createBallParams(pos[0], pos[1]);
        if (!addOverlayView(view, params)) {
            toast("悬浮球显示失败（悬浮窗可能被系统限制）");
            return;
        }
        ballView = view;
        ballParams = params;
        showing = true;
    }

    /** 隐藏悬浮球（移除窗口；不影响 ConfigStore 里的开关） */
    public void hide() {
        handler.removeCallbacks(longPressRunnable);
        dismissMenu();
        if (ballView != null && windowManager != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Throwable t) {
                // 窗口可能已被系统移除，忽略
            }
        }
        ballView = null;
        ballParams = null;
        showing = false;
        dragging = false;
        longPressFired = false;
    }

    /** 服务销毁时调用：移除球和菜单，清掉所有待执行任务 */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        hide();
    }

    // ==================== 位置 ====================

    /**
     * 计算球的初始位置：优先用 ConfigStore 里存的位置，没有则放在屏幕右侧 45% 高度处。
     * 返回值已按屏幕范围裁剪。
     */
    private int[] resolvePosition() {
        int x = -1;
        int y = -1;
        try {
            x = ConfigStore.getBallX();
            y = ConfigStore.getBallY();
        } catch (Throwable t) {
            // 存储还没初始化等异常：用默认位置
            x = -1;
            y = -1;
        }
        if (x < 0 || y < 0) {
            // 默认：屏幕右侧 45% 高度
            x = screenWidth - ballSizePx - dp(EDGE_MARGIN_DP);
            y = (int) (screenHeight * 0.45f) - ballSizePx / 2;
        }
        return new int[] { clampX(x), clampY(y) };
    }

    private int clampX(int x) {
        int max = screenWidth - ballSizePx;
        if (max < 0) {
            max = 0;
        }
        if (x < 0) {
            return 0;
        }
        return x > max ? max : x;
    }

    private int clampY(int y) {
        int max = screenHeight - ballSizePx;
        if (max < 0) {
            max = 0;
        }
        if (y < 0) {
            return 0;
        }
        return y > max ? max : y;
    }

    /** 移动球窗口到 (x, y)（会裁剪在屏幕内）；save 为 true 时顺带把位置写回配置 */
    private void moveTo(int x, int y, boolean save) {
        if (ballParams == null || ballView == null || windowManager == null) {
            return;
        }
        int newX = clampX(x);
        int newY = clampY(y);
        ballParams.x = newX;
        ballParams.y = newY;
        try {
            windowManager.updateViewLayout(ballView, ballParams);
        } catch (Throwable t) {
            Log.w(TAG, "移动悬浮球失败", t);
            return;
        }
        if (save) {
            try {
                ConfigStore.setBallPos(newX, newY);
            } catch (Throwable t) {
                Log.w(TAG, "保存悬浮球位置失败", t);
            }
        }
    }

    private void updateScreenMetrics() {
        int w = 0;
        int h = 0;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            w = metrics.widthPixels;
            h = metrics.heightPixels;
        } catch (Throwable t) {
            w = 0;
            h = 0;
        }
        if (w <= 0 || h <= 0) {
            DisplayMetrics resMetrics = service.getResources().getDisplayMetrics();
            w = resMetrics.widthPixels;
            h = resMetrics.heightPixels;
        }
        screenWidth = w;
        screenHeight = h;
    }

    // ==================== 触摸：拖动 / 单击 / 长按 ====================

    private void handleTouchDown(MotionEvent event) {
        downRawX = event.getRawX();
        downRawY = event.getRawY();
        downWinX = ballParams == null ? 0 : ballParams.x;
        downWinY = ballParams == null ? 0 : ballParams.y;
        downTime = SystemClock.uptimeMillis();
        dragging = false;
        longPressFired = false;
        handler.removeCallbacks(longPressRunnable);
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
    }

    private void handleTouchMove(MotionEvent event) {
        if (ballParams == null) {
            return;
        }
        float dx = event.getRawX() - downRawX;
        float dy = event.getRawY() - downRawY;
        if (!dragging && (Math.abs(dx) > TOUCH_SLOP_PX || Math.abs(dy) > TOUCH_SLOP_PX)) {
            // 超过 10px：判定为拖动，取消长按
            dragging = true;
            handler.removeCallbacks(longPressRunnable);
        }
        if (dragging) {
            moveTo(downWinX + (int) dx, downWinY + (int) dy, false);
        }
    }

    private void handleTouchUp(MotionEvent event) {
        handler.removeCallbacks(longPressRunnable);
        if (ballParams == null) {
            return;
        }
        float dx = event.getRawX() - downRawX;
        float dy = event.getRawY() - downRawY;
        boolean moved = Math.abs(dx) > TOUCH_SLOP_PX || Math.abs(dy) > TOUCH_SLOP_PX;
        long duration = SystemClock.uptimeMillis() - downTime;
        if (dragging) {
            // 拖动结束：记住新位置
            dragging = false;
            moveTo(ballParams.x, ballParams.y, true);
            return;
        }
        if (!moved && !longPressFired && duration < CLICK_MAX_MS) {
            // 单击：进入图层选择
            handleClick();
        }
    }

    private void handleClick() {
        dismissMenu();
        if (listener != null) {
            listener.onBallClick();
        }
    }

    // ==================== 长按菜单（底部小卡片） ====================

    /** 弹出底部菜单：打开上帝模式 Lite / 关闭悬浮球 / 取消 */
    private void showMenu() {
        if (!showing || menuRoot != null || windowManager == null) {
            return;
        }
        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_MENU_BG);
        background.setCornerRadius(dp(12));
        card.setBackground(background);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        card.addView(createMenuItem("打开上帝模式 Lite", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissMenu();
                openMainApp();
            }
        }));
        card.addView(createMenuItem("关闭悬浮球", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissMenu();
                // 持久关闭：由 BlockerService 写 ConfigStore.setBallVisible(false) 并隐藏
                if (listener != null) {
                    listener.onBallHideRequested();
                }
            }
        }));
        card.addView(createMenuItem("取消", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissMenu();
            }
        }));

        FrameLayout root = new FrameLayout(service);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.leftMargin = dp(16);
        cardParams.rightMargin = dp(16);
        cardParams.bottomMargin = dp(20);
        cardParams.gravity = Gravity.BOTTOM;
        root.addView(card, cardParams);

        WindowManager.LayoutParams params = createMenuParams();
        if (!addOverlayView(root, params)) {
            toast("菜单显示失败");
            return;
        }
        menuRoot = root;
    }

    /** 关闭底部菜单（幂等） */
    private void dismissMenu() {
        if (menuRoot != null && windowManager != null) {
            try {
                windowManager.removeView(menuRoot);
            } catch (Throwable t) {
                // 窗口可能已不在，忽略
            }
        }
        menuRoot = null;
    }

    /** 菜单项：居中文字 + 点击回调（整行都是点击区） */
    private TextView createMenuItem(String text, View.OnClickListener clickListener) {
        TextView item = new TextView(service);
        item.setText(text);
        item.setTextSize(15f);
        item.setTextColor(COLOR_MENU_TEXT);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(12), dp(12), dp(12), dp(12));
        item.setClickable(true);
        item.setOnClickListener(clickListener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        item.setLayoutParams(params);
        return item;
    }

    /** 打开主界面（本应用的 MainActivity） */
    private void openMainApp() {
        try {
            Intent intent = new Intent();
            intent.setClassName(service.getPackageName(), "com.dsh.godmodelite.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "打开主界面失败", t);
            toast("打开上帝模式 Lite 失败");
        }
    }

    // ==================== 窗口参数与降级重试 ====================

    private WindowManager.LayoutParams createBallParams(int x, int y) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = BlockerService.overlayWindowType();
        params.format = PixelFormat.TRANSLUCENT;
        // 不抢焦点（不弹输入法）；NOT_TOUCH_MODAL 让球范围外的触摸继续交给下面的应用
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = x;
        params.y = y;
        params.width = ballSizePx;
        params.height = ballSizePx;
        try {
            params.setTitle("gm-ball");
        } catch (Throwable t) {
            // 老系统没有 setTitle 也不影响功能
        }
        return params;
    }

    private WindowManager.LayoutParams createMenuParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = BlockerService.overlayWindowType();
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        params.x = 0;
        params.y = 0;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        try {
            params.setTitle("gm-ball-menu");
        } catch (Throwable t) {
            // 忽略
        }
        return params;
    }

    /**
     * 添加悬浮窗：先试无障碍 overlay 类型，失败再按 v1 的降级逻辑换类型重试一次。
     * 所有异常都吞掉，不能让悬浮球把服务带崩。
     */
    private boolean addOverlayView(View view, WindowManager.LayoutParams params) {
        if (windowManager == null) {
            return false;
        }
        int primaryType = BlockerService.overlayWindowType();
        if (tryAddOverlay(view, params, primaryType)) {
            return true;
        }
        int fallbackType = fallbackOverlayType(primaryType);
        if (fallbackType != 0 && tryAddOverlay(view, params, fallbackType)) {
            return true;
        }
        return false;
    }

    private boolean tryAddOverlay(View view, WindowManager.LayoutParams params, int type) {
        params.type = type;
        try {
            windowManager.addView(view, params);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "添加悬浮窗失败，type=" + type, t);
            return false;
        }
    }

    /**
     * 备用窗口类型（与 v1 PickerOverlay 一致）：
     * - API 26+：TYPE_APPLICATION_OVERLAY（值 2038，编译用 API 23 的 android.jar 所以写数值），
     *   需要「显示在其他应用上层」权限；
     * - 更低版本：TYPE_SYSTEM_ALERT（2002）。
     * 返回 0 表示没有可用的备用类型。
     */
    private int fallbackOverlayType(int failedType) {
        if (Build.VERSION.SDK_INT >= 26) {
            final int typeApplicationOverlay = 2038;
            if (failedType != typeApplicationOverlay && canDrawOverlays()) {
                return typeApplicationOverlay;
            }
            return 0;
        }
        if (failedType != WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) {
            return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }
        return 0;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(service);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 小工具 ====================

    private int dp(float value) {
        float density = service.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private void toast(String message) {
        try {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // Toast 失败不影响功能
        }
    }

    /**
     * 悬浮球本体（v3）：全部用 onDraw 几何绘制 5 种样式，不持有任何 Bitmap。
     * 0 眼睛：圆底 + 绿环 + 白色眼睛椭圆 + 瞳孔；
     * 1 圆点：圆底 + 绿环 + 实心绿点；
     * 2 盾牌：圆底 + 绿环 + 盾牌轮廓；
     * 3 文字：圆底 + 绿环 + 绿色粗体「隐」；
     * 4 禁止：圆底 + 绿环 + 绿色斜杠。
     * 透明度通过 Paint.setAlpha 叠加到每个颜色自身的 alpha 上（配置 150~235）。
     * 触摸回调直接交给 FloatingBall 处理，方便统一管理拖动/单击/长按状态。
     */
    private final class BallView extends View {

        private final Paint fillPaint = new Paint();
        private final Paint ringPaint = new Paint();
        private final Paint eyePaint = new Paint();
        private final Paint pupilPaint = new Paint();
        private final Paint dotPaint = new Paint();
        private final Paint slashPaint = new Paint();
        private final Paint textPaint = new Paint();
        private final Path shieldPath = new Path();
        private final RectF ovalRect = new RectF();

        private int drawStyle;
        private int drawAlpha;

        BallView(android.content.Context context, int style, int alpha) {
            super(context);
            drawStyle = style;
            drawAlpha = alpha;
            // 圆底：实心 + 抗锯齿
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setAntiAlias(true);
            fillPaint.setColor(COLOR_BALL_BG);
            // 绿色圆环
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setAntiAlias(true);
            ringPaint.setColor(COLOR_RING);
            ringPaint.setStrokeWidth(dp(2f));
            // 白色眼睛：椭圆描边
            eyePaint.setStyle(Paint.Style.STROKE);
            eyePaint.setAntiAlias(true);
            eyePaint.setColor(COLOR_EYE);
            eyePaint.setStrokeWidth(dp(1.6f));
            // 瞳孔：实心圆
            pupilPaint.setStyle(Paint.Style.FILL);
            pupilPaint.setAntiAlias(true);
            pupilPaint.setColor(COLOR_EYE);
            // 圆点 / 盾牌：绿色实心
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setAntiAlias(true);
            dotPaint.setColor(COLOR_RING);
            // 禁止斜杠：绿色圆头线
            slashPaint.setStyle(Paint.Style.STROKE);
            slashPaint.setAntiAlias(true);
            slashPaint.setColor(COLOR_RING);
            slashPaint.setStrokeWidth(dp(2.5f));
            slashPaint.setStrokeCap(Paint.Cap.ROUND);
            // 文字「隐」：绿色粗体
            textPaint.setAntiAlias(true);
            textPaint.setColor(COLOR_RING);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
            setClickable(true);
        }

        /** 样式 / 透明度变化后立即重绘（尺寸由窗口 LayoutParams 控制） */
        void applyStyle(int style, int alpha) {
            drawStyle = style;
            drawAlpha = alpha;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (canvas == null) {
                return;
            }
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float centerX = width / 2f;
            float centerY = height / 2f;
            float radius = Math.min(width, height) / 2f;
            int alpha = drawAlpha;
            if (alpha < 0) {
                alpha = 0;
            } else if (alpha > 255) {
                alpha = 255;
            }
            // 1) 半透明深色圆底（颜色本身是 0xCC，再乘上配置透明度）
            float circleRadius = radius - ringPaint.getStrokeWidth();
            if (circleRadius <= 0f) {
                return;
            }
            setPaintAlpha(fillPaint, COLOR_BALL_BG, alpha);
            canvas.drawCircle(centerX, centerY, circleRadius, fillPaint);
            // 2) 绿色圆环（所有样式共用）
            setPaintAlpha(ringPaint, COLOR_RING, alpha);
            canvas.drawCircle(centerX, centerY, circleRadius, ringPaint);
            // 3) 中央图形
            switch (drawStyle) {
                case STYLE_DOT:
                    drawDot(canvas, centerX, centerY, circleRadius, alpha);
                    break;
                case STYLE_SHIELD:
                    drawShield(canvas, centerX, centerY, circleRadius, alpha);
                    break;
                case STYLE_TEXT:
                    drawTextGlyph(canvas, centerX, centerY, circleRadius, alpha);
                    break;
                case STYLE_SLASH:
                    drawSlash(canvas, centerX, centerY, circleRadius, alpha);
                    break;
                case STYLE_EYE:
                default:
                    drawEye(canvas, centerX, centerY, circleRadius, alpha);
                    break;
            }
        }

        /** 样式 0：眼睛（v2 默认样式）：椭圆轮廓 + 实心瞳孔 */
        private void drawEye(Canvas canvas, float centerX, float centerY, float circleRadius, int alpha) {
            float eyeHalfWidth = circleRadius * 0.48f;
            float eyeHalfHeight = circleRadius * 0.30f;
            ovalRect.set(centerX - eyeHalfWidth, centerY - eyeHalfHeight,
                    centerX + eyeHalfWidth, centerY + eyeHalfHeight);
            setPaintAlpha(eyePaint, COLOR_EYE, alpha);
            canvas.drawOval(ovalRect, eyePaint);
            setPaintAlpha(pupilPaint, COLOR_EYE, alpha);
            canvas.drawCircle(centerX, centerY, circleRadius * 0.20f, pupilPaint);
        }

        /** 样式 1：实心圆点 */
        private void drawDot(Canvas canvas, float centerX, float centerY, float circleRadius, int alpha) {
            setPaintAlpha(dotPaint, COLOR_RING, alpha);
            canvas.drawCircle(centerX, centerY, circleRadius * 0.34f, dotPaint);
        }

        /** 样式 2：盾牌轮廓（上宽下尖的简化盾形） */
        private void drawShield(Canvas canvas, float centerX, float centerY, float circleRadius, int alpha) {
            float halfTop = circleRadius * 0.52f;
            float shoulderY = centerY - circleRadius * 0.48f;
            float waistY = centerY + circleRadius * 0.10f;
            float bottomY = centerY + circleRadius * 0.62f;
            shieldPath.reset();
            shieldPath.moveTo(centerX - halfTop, shoulderY);
            shieldPath.lineTo(centerX + halfTop, shoulderY);
            shieldPath.lineTo(centerX + circleRadius * 0.46f, waistY);
            shieldPath.lineTo(centerX, bottomY);
            shieldPath.lineTo(centerX - circleRadius * 0.46f, waistY);
            shieldPath.close();
            setPaintAlpha(ringPaint, COLOR_RING, alpha);
            canvas.drawPath(shieldPath, ringPaint);
        }

        /** 样式 3：文字「隐」（绿色粗体，居中） */
        private void drawTextGlyph(Canvas canvas, float centerX, float centerY, float circleRadius, int alpha) {
            textPaint.setTextSize(circleRadius * 1.05f);
            setPaintAlpha(textPaint, COLOR_RING, alpha);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText("隐", centerX, baseline, textPaint);
        }

        /** 样式 4：禁止斜杠（圆环 + 一条斜线） */
        private void drawSlash(Canvas canvas, float centerX, float centerY, float circleRadius, int alpha) {
            float dx = circleRadius * 0.62f;
            setPaintAlpha(slashPaint, COLOR_RING, alpha);
            canvas.drawLine(centerX - dx, centerY + dx, centerX + dx, centerY - dx, slashPaint);
        }

        /** 颜色 + 配置透明度叠加：最终 alpha = 颜色自身 alpha * 配置 alpha / 255 */
        private void setPaintAlpha(Paint paint, int color, int alpha) {
            paint.setColor(color);
            int baseAlpha = (color >>> 24) & 0xFF;
            int effective = baseAlpha * alpha / 255;
            if (effective < 0) {
                effective = 0;
            } else if (effective > 255) {
                effective = 255;
            }
            paint.setAlpha(effective);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handleTouchDown(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    handleTouchMove(event);
                    return true;
                case MotionEvent.ACTION_UP:
                    handleTouchUp(event);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    dragging = false;
                    longPressFired = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
