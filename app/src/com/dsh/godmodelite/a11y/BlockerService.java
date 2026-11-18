package com.dsh.godmodelite.a11y;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import com.dsh.godmodelite.core.ConfigStore;
import com.dsh.godmodelite.core.Rule;

/**
 * 无障碍服务：整个 App 的核心。
 *
 * 职责（v2 / v3）：
 * 1. 作为单例给主界面 / 规则页调用：
 *    - startPicker(pkg/null)：进入「图层选择」模式（选中的元素按当前窗口包名存规则）；
 *    - setBallVisible(boolean)：显示 / 隐藏悬浮球；
 *    - applyBallSettings()（v3）：重新读取悬浮球样式 / 尺寸 / 透明度并立即刷新；
 *    - hideBallForAppExit()（v3）：仅本次运行隐藏悬浮球（不写配置），
 *      用于「关闭主界面后其他界面不显示球」；
 * 2. 监听前台窗口变化，延迟去抖后调用 MaskManager 按规则上蒙版；
 * 3. 管理悬浮球（FloatingBall）与图层选择层（LayerInspectorOverlay）的生命周期：
 *    图层选择期间隐藏悬浮球，选择结束（保存 / 取消）后恢复；
 * 4. 服务销毁 / 中断时清理全部悬浮窗与回调，避免窗口泄漏。
 *
 * 所有悬浮窗与无障碍操作都在主线程完成。
 */
public class BlockerService extends AccessibilityService
        implements FloatingBall.Listener, LayerInspectorOverlay.Listener {

    private static final String TAG = "GodModeBlocker";

    /** 前台窗口变化后延迟多久刷新蒙版（等待新页面布局稳定） */
    private static final long DELAY_WINDOW_CHANGED = 150L;

    /** 内容变化 / 滚动后延迟多久刷新蒙版（去抖，避免频繁 addView） */
    private static final long DELAY_CONTENT_CHANGED = 120L;

    private static BlockerService sInstance;

    private Handler handler;
    private WindowManager windowManager;

    /** 图层选择层（v2 取代 v1 的 PickerOverlay） */
    private LayerInspectorOverlay layerOverlay;
    /** 悬浮球（可空） */
    private FloatingBall floatingBall;

    /** 悬浮球开关的会话状态（与 ConfigStore 同步；选择期间球窗口会被临时移除但该值不变） */
    private boolean ballVisible = true;

    /**
     * v3：会话级「临时隐藏」标记。由 hideBallForAppExit() 置位（关闭主界面时只隐藏本次运行），
     * 不写 ConfigStore；MainActivity 回到前台调用 setBallVisible(true) 时清除并重新显示。
     */
    private boolean sessionHidden;

    /** 当前前台应用包名 / Activity 类名（用于决定给哪个应用上蒙版） */
    private String foregroundPackage;
    private String foregroundActivity;

    /** 去抖后的蒙版刷新任务：重发前先 removeCallbacks */
    private final Runnable refreshMasksTask = new Runnable() {
        @Override
        public void run() {
            refreshMasksNow();
        }
    };

    // ==================== 单例与生命周期 ====================

    /** 获取当前已连接的无障碍服务实例；未开启时返回 null */
    public static BlockerService get() {
        return sInstance;
    }

    /** 当前前台包名（图层选择保存规则时兜底使用） */
    public String getForegroundPackage() {
        return foregroundPackage;
    }

    /** 当前前台 Activity 类名（规则里只做参考记录） */
    public String getForegroundActivity() {
        return foregroundActivity;
    }

    /**
     * 悬浮窗类型：API 22+ 用无障碍专用的 TYPE_ACCESSIBILITY_OVERLAY（无需额外权限），
     * 更低版本退回 TYPE_SYSTEM_ALERT（需要悬浮窗权限）。
     */
    static int overlayWindowType() {
        if (Build.VERSION.SDK_INT >= 22) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        ensureMainHandler();
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        // 服务可能在进程重建后重连：先清掉上一次可能残留的窗口
        cleanupAll();
        // 保证存储已初始化
        try {
            ConfigStore.init(this);
        } catch (Throwable t) {
            Log.w(TAG, "ConfigStore 初始化失败", t);
        }
        foregroundPackage = null;
        foregroundActivity = null;
        // v3：服务刚连接时清掉会话级临时隐藏，按配置恢复显示
        sessionHidden = false;
        // 按配置恢复悬浮球（默认显示）
        try {
            ballVisible = ConfigStore.isBallVisible();
        } catch (Throwable t) {
            ballVisible = true;
        }
        applyBallVisibility();
        Log.i(TAG, "无障碍服务已连接，悬浮球=" + ballVisible);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int eventType;
        try {
            eventType = event.getEventType();
        } catch (Throwable t) {
            return;
        }
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowStateChanged(event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                handleContentChanged(event);
                break;
            default:
                break;
        }
    }

    @Override
    public void onInterrupt() {
        // 服务被系统中断：清掉所有悬浮窗，防止残留
        cleanupAll();
    }

    @Override
    public void onDestroy() {
        cleanupAll();
        if (sInstance == this) {
            sInstance = null;
        }
        // v3：服务销毁后释放 Handler / WindowManager / 前台包名等引用，避免静态单例残留
        windowManager = null;
        foregroundPackage = null;
        foregroundActivity = null;
        sessionHidden = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        super.onDestroy();
    }

    // ==================== 悬浮球开关（主界面调用） ====================

    /**
     * 显示 / 隐藏悬浮球，并写入 ConfigStore（下次服务连接时生效）。
     * 可以在任意线程调用，内部会切到主线程。
     */
    public void setBallVisible(final boolean visible) {
        ensureMainHandler();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    setBallVisible(visible);
                }
            });
            return;
        }
        ballVisible = visible;
        // 显式设置（主界面开关 / 回到前台恢复）会清掉「关闭主界面」造成的会话级隐藏
        sessionHidden = false;
        try {
            ConfigStore.setBallVisible(visible);
        } catch (Throwable t) {
            Log.w(TAG, "保存悬浮球开关失败", t);
        }
        applyBallVisibility();
    }

    /** 当前悬浮球开关状态（会话值，最准） */
    public boolean isBallVisible() {
        return ballVisible;
    }

    // ==================== 悬浮球设置 / 关闭主界面隐藏（v3） ====================

    /**
     * 重新读取 ConfigStore 里的悬浮球样式 / 尺寸 / 透明度并立即刷新（v3）。
     * 主界面改完设置后调用；悬浮球不存在（未显示 / 选择中）时忽略。
     */
    public void applyBallSettings() {
        ensureMainHandler();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    applyBallSettings();
                }
            });
            return;
        }
        if (floatingBall == null) {
            // 球窗口还没创建：下次显示时会重新读取设置，这里无需处理
            return;
        }
        try {
            floatingBall.applyStyle();
        } catch (Throwable t) {
            Log.w(TAG, "刷新悬浮球设置失败", t);
        }
    }

    /**
     * 仅本次运行隐藏悬浮球（v3）：主界面 isFinishing 时调用，不写 ConfigStore。
     * 下次打开 App（MainActivity.onResume → setBallVisible(true)）会重新显示；
     * 按 Home 键（onStop，不 destroy）不会走到这里。
     */
    public void hideBallForAppExit() {
        ensureMainHandler();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    hideBallForAppExit();
                }
            });
            return;
        }
        sessionHidden = true;
        if (floatingBall != null) {
            try {
                floatingBall.hide();
            } catch (Throwable t) {
                Log.w(TAG, "隐藏悬浮球失败", t);
            }
        }
    }

    /**
     * 控制球窗口的实际显示：只有「开关为开」且「没有被关闭主界面逻辑临时隐藏」且
     * 「图层选择层没开着」时才显示。只操作窗口，不写 ConfigStore。
     */
    private void applyBallVisibility() {
        boolean selectionOpen = layerOverlay != null && layerOverlay.isShowing();
        setBallWindowVisible(ballVisible && !sessionHidden && !selectionOpen);
    }

    /** 底层：添加 / 移除悬浮球窗口（幂等） */
    private void setBallWindowVisible(boolean visible) {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (windowManager == null) {
            return;
        }
        if (visible) {
            if (floatingBall == null) {
                floatingBall = new FloatingBall(this, windowManager, this);
            }
            floatingBall.show();
        } else {
            if (floatingBall != null) {
                floatingBall.hide();
            }
        }
    }

    // ==================== 图层选择入口 ====================

    /**
     * 进入图层选择模式（主界面 / 规则页 / 悬浮球单击都会调）。
     *
     * @param targetPkg 目标应用包名，可为 null（此时用选中节点的包名兜底）
     */
    public void startPicker(final String targetPkg) {
        ensureMainHandler();
        // 保证在主线程操作悬浮窗
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    startPicker(targetPkg);
                }
            });
            return;
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (windowManager == null) {
            toast("无法获取窗口服务，请重试");
            return;
        }
        try {
            ConfigStore.init(this);
        } catch (Throwable t) {
            Log.w(TAG, "ConfigStore 初始化失败", t);
        }
        // 图层选择前先撤掉蒙版，避免蒙版盖住要选中的元素、干扰点击；并停掉去抖刷新
        MaskManager.clear();
        handler.removeCallbacks(refreshMasksTask);
        stopLayerOverlayInternal(false);
        // 选择期间隐藏悬浮球（避免挡住图层），退出后由 applyBallVisibility 恢复
        setBallWindowVisible(false);

        LayerInspectorOverlay overlay = new LayerInspectorOverlay(this, windowManager, this, targetPkg);
        overlay.start();
        if (!overlay.isShowing()) {
            // addView 失败（被系统限制等），不要留下半成品对象，恢复悬浮球
            try {
                overlay.stop();
            } catch (Throwable t) {
                Log.w(TAG, "清理失败的选择层异常", t);
            }
            applyBallVisibility();
            return;
        }
        layerOverlay = overlay;
    }

    /** 退出图层选择模式（主界面离开时也可以调用） */
    public void stopPicker() {
        ensureMainHandler();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    stopPicker();
                }
            });
            return;
        }
        stopLayerOverlayInternal(true);
        refreshMasksNow();
    }

    /** 关闭并丢弃图层选择层；restoreBall 为 true 时顺带恢复悬浮球 */
    private void stopLayerOverlayInternal(boolean restoreBall) {
        if (layerOverlay != null) {
            try {
                layerOverlay.stop();
            } catch (Throwable t) {
                Log.w(TAG, "关闭图层选择层失败", t);
            }
            layerOverlay = null;
        }
        if (restoreBall) {
            applyBallVisibility();
        }
    }

    /** 图层选择层是否开着（开着时不上蒙版、不刷蒙版） */
    private boolean isSelectionOpen() {
        return layerOverlay != null && layerOverlay.isShowing();
    }

    // ==================== 悬浮球回调（FloatingBall.Listener） ====================

    @Override
    public void onBallClick() {
        // 单击悬浮球：进入图层选择（目标包名用当前前台包名，可为 null）。
        // 这里 post 一下，让本次触摸事件先派发完再移除球窗口、添加选择层，避免在事件分发中改窗口。
        if (isSelectionOpen()) {
            return;
        }
        ensureMainHandler();
        final String pkg = foregroundPackage;
        handler.post(new Runnable() {
            @Override
            public void run() {
                startPicker(pkg);
            }
        });
    }

    @Override
    public void onBallHideRequested() {
        // 长按菜单里的「关闭悬浮球」：持久关闭——写 ConfigStore.setBallVisible(false)
        // 并立刻隐藏（下次服务连接也不会自动出来，主界面开关同步为关）。
        // 延后到本次触摸事件之后执行，避免在菜单自己的点击回调里移除正在派发事件的窗口。
        ensureMainHandler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                setBallVisible(false);
            }
        });
    }

    // ==================== 图层选择回调（LayerInspectorOverlay.Listener） ====================

    @Override
    public void onLayerSaved(Rule rule) {
        // 选择层已经自己关闭，这里负责恢复悬浮球并立即按新规则上蒙版
        stopLayerOverlayInternal(true);
        String pkg = foregroundPackage;
        if (rule != null && rule.pkg != null && rule.pkg.length() > 0) {
            pkg = rule.pkg;
            // 同步前台包名，避免后续去抖刷新用旧包名把刚上的蒙版清掉
            foregroundPackage = rule.pkg;
            foregroundActivity = rule.activity;
        }
        // mask / gone 规则在无障碍模式下都按遮挡显示（真删靠 LSPosed 模块）
        applyMasksNow(pkg);
        // Toast 已在 LayerInspectorOverlay 里提示过「已保存」
    }

    @Override
    public void onLayerCancelled() {
        stopLayerOverlayInternal(true);
        refreshMasksNow();
    }

    // ==================== 事件处理与去抖刷新 ====================

    /** 窗口切换：记录前台包名，延迟 150ms 刷新蒙版 */
    private void handleWindowStateChanged(AccessibilityEvent event) {
        String pkg = null;
        String cls = null;
        try {
            CharSequence pkgCs = event.getPackageName();
            pkg = pkgCs == null ? null : pkgCs.toString();
            CharSequence clsCs = event.getClassName();
            cls = clsCs == null ? null : clsCs.toString();
        } catch (Throwable t) {
            return;
        }
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        boolean isOwnApp = pkg.equals(getPackageName());
        if (isOwnApp) {
            // 回到本应用：无障碍蒙版窗口在系统所有窗口之上，必须立刻撤掉，
            // 否则会继续盖在我们自己的界面上。
            foregroundPackage = null;
            foregroundActivity = null;
            if (isSelectionOpen()) {
                // 图层选择进行中：不动蒙版（本来就没有）
                return;
            }
            try {
                MaskManager.clear();
            } catch (Throwable t) {
                Log.w(TAG, "清理蒙版失败", t);
            }
            return;
        }
        foregroundPackage = pkg;
        foregroundActivity = cls;
        if (isSelectionOpen()) {
            // 选择期间不刷蒙版（选择层盖在目标应用之上）
            return;
        }
        scheduleRefresh(DELAY_WINDOW_CHANGED);
    }

    /** 内容变化 / 滚动：当前包有规则时延迟 120ms 重刷蒙版 */
    private void handleContentChanged(AccessibilityEvent event) {
        String eventPkg = null;
        try {
            CharSequence pkgCs = event.getPackageName();
            eventPkg = pkgCs == null ? null : pkgCs.toString();
        } catch (Throwable t) {
            eventPkg = null;
        }
        if (eventPkg != null && eventPkg.equals(getPackageName())) {
            // 自己应用的事件（Toast / 悬浮窗 / 界面动画）不处理，避免自激刷新。
            // 只有在确认已切回本应用（foregroundPackage 已被窗口事件清空）时才清蒙版；
            // 目标应用在前台时本应用弹的 Toast 不能把目标应用的蒙版误清掉。
            if (foregroundPackage == null && !isSelectionOpen()) {
                try {
                    MaskManager.clear();
                } catch (Throwable t) {
                    Log.w(TAG, "清理蒙版失败", t);
                }
            }
            return;
        }
        if (foregroundPackage == null && eventPkg != null) {
            foregroundPackage = eventPkg;
        }
        if (foregroundPackage == null) {
            return;
        }
        if (isSelectionOpen()) {
            // 选择中不刷蒙版
            return;
        }
        int count = 0;
        try {
            count = ConfigStore.ruleCount(foregroundPackage);
        } catch (Throwable t) {
            count = 0;
        }
        if (count > 0) {
            scheduleRefresh(DELAY_CONTENT_CHANGED);
        }
    }

    /** 去抖：先移除上一次的刷新任务，再延迟执行 */
    private void scheduleRefresh(long delayMs) {
        ensureMainHandler();
        handler.removeCallbacks(refreshMasksTask);
        handler.postDelayed(refreshMasksTask, delayMs);
    }

    /** 立即按当前前台包刷新蒙版 */
    private void refreshMasksNow() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (isSelectionOpen()) {
            return;
        }
        applyMasksNow(foregroundPackage);
    }

    /** 按指定包名立即刷新蒙版（图层选择保存后也会调用） */
    private void applyMasksNow(String pkg) {
        if (isSelectionOpen()) {
            return;
        }
        if (pkg == null || pkg.length() == 0) {
            MaskManager.clear();
            return;
        }
        try {
            MaskManager.apply(this, pkg);
        } catch (Throwable t) {
            Log.w(TAG, "刷新蒙版失败", t);
        }
    }

    // ==================== 清理与小工具 ====================

    /** 清理所有悬浮窗、回调和选择状态（幂等） */
    private void cleanupAll() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        stopLayerOverlayInternal(false);
        if (floatingBall != null) {
            try {
                floatingBall.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "移除悬浮球失败", t);
            }
            floatingBall = null;
        }
        try {
            MaskManager.clear();
        } catch (Throwable t) {
            Log.w(TAG, "清理蒙版失败", t);
        }
    }

    private void ensureMainHandler() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // Toast 失败不影响功能
        }
    }
}
