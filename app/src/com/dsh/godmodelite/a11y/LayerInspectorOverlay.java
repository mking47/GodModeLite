package com.dsh.godmodelite.a11y;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.godmodelite.core.ConfigStore;
import com.dsh.godmodelite.core.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 图层选择层（v2 新增，v3 清晰度 & 内存优化）。
 *
 * 与 v1「盲点」式拾取不同，这里先把目标应用所有可见节点的矩形一起画出来，
 * 用户能直观看到每个图层元素的边界，再点选要处理的元素。
 *
 * 流程（v3）：
 * 1. 用 NodeFinder.collectVisibleNodes 遍历 getWindows() 里所有应用窗口的节点树
 *    （跳过 TYPE_ACCESSIBILITY_OVERLAY），取「可见 + 宽高 >= 4px」的节点，最多 300 个；
 *    快照里只有矩形/标签/深度，**不持有 AccessibilityNodeInfo**；
 * 2. 全屏覆盖层（背景 0x66000000）+ 顶部信息条：图标位 + 标题 15sp +
 *    副标题「共 N 个图层 · 当前 <包名>」+ 右侧胶囊「取消」；
 * 3. **一个自定义 View 的 onDraw(Canvas)** 画出全部矩形与小标签（不再每个元素 addView），
 *    边框 1.5dp 按深度循环 6 色；有标签且尺寸够大时在左上角画 9sp 白色粗体小标签；
 * 4. 点击 → NodeFinder.findNodeAt 现场取一次节点 → 选中高亮（3dp 亮绿边框 +
 *    0x2200FF00 外发光层，在同一个画布里叠加绘制）→ 底部动作卡片：
 *    第 1 行 viewId 末段或文字（16sp 白）+ 第 2 行「类名 · 坐标(左,上,宽×高)」（11sp 灰），
 *    卡片 #F22A2A2A 圆角 14dp，三按钮高 52dp / 16sp 粗体：遮挡（绿实底白字）/
 *    真删（红实底白字）/ 取消（深灰底）；
 * 5. 遮挡 → 保存 mode=mask 规则；真删 → 先 Toast 说明需 LSPosed，再保存 mode=gone 规则；
 *    保存后 ConfigStore.addRule → 关闭选择层 → 回调 Listener（由 BlockerService 立即上蒙版）；
 * 6. 关闭时清空图层列表、把画布/根布局引用置空，不再持有任何节点。
 *
 * 所有窗口操作都包 try/catch；addView 失败时按 v1 的降级逻辑换窗口类型重试。
 * 所有方法都在主线程调用（悬浮窗操作只能在主线程）。
 */
public class LayerInspectorOverlay {

    /** 图层选择回调（由 BlockerService 实现） */
    public interface Listener {
        /** 用户保存了一条规则（此时选择层已关闭） */
        void onLayerSaved(Rule rule);

        /** 用户取消了图层选择（此时选择层已关闭） */
        void onLayerCancelled();
    }

    private static final String TAG = "GodModeLayers";

    /** 全屏覆盖层背景：淡黑，让用户知道处在选择模式 */
    private static final int COLOR_SCRIM = 0x66000000;
    /** 顶部信息条背景 */
    private static final int COLOR_HINT_BG = 0xE6000000;
    /** 提示主文字颜色 */
    private static final int COLOR_HINT_TEXT = 0xFFFFFFFF;
    /** 副标题颜色 */
    private static final int COLOR_HINT_SUB = 0xFF9E9E9E;
    /** 强调色（图标位 / 取消胶囊） */
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    /** 动作卡片背景：#F22A2A2A */
    private static final int COLOR_CARD_BG = 0xF22A2A2A;
    /** 卡片主文字色 */
    private static final int COLOR_CARD_TEXT = 0xFFF2F2F2;
    /** 卡片次要文字色 */
    private static final int COLOR_CARD_SUB_TEXT = 0xFF9E9E9E;
    /** 遮挡按钮：绿色实底 */
    private static final int COLOR_MASK_BUTTON = 0xFF4CAF50;
    /** 真删按钮：红色实底 */
    private static final int COLOR_GONE_BUTTON = 0xFFEF5350;
    /** 取消按钮：深灰底 */
    private static final int COLOR_CANCEL_BUTTON = 0xFF3A3A3A;
    /** 按钮文字色（实底按钮用白字） */
    private static final int COLOR_BUTTON_TEXT = 0xFFFFFFFF;
    /** 选中高亮：3dp 亮绿边框 */
    private static final int COLOR_HIGHLIGHT_STROKE = 0xFF00FF00;
    /** 选中高亮：4dp 外发光层（叠加的半透明边框） */
    private static final int COLOR_HIGHLIGHT_GLOW = 0x2200FF00;
    /** 选中高亮：极淡填充，让选中区域更醒目 */
    private static final int COLOR_HIGHLIGHT_FILL = 0x1500FF00;

    /** 边框颜色按深度循环 */
    private static final int[] DEPTH_COLORS = new int[] {
            0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFFE91E63, 0xFF9C27B0, 0xFF00BCD4
    };

    /** 最多画多少个矩形/标签（v3 从 400 降到 300，防卡且省内存） */
    private static final int MAX_NODES = 300;
    /** 节点的最小宽高（像素），更小的装饰性节点不画 */
    private static final int MIN_NODE_SIZE = 4;
    /** 画标签的最小宽度（dp） */
    private static final int MIN_LABEL_WIDTH_DP = 120;
    /** 画标签的最小高度（dp） */
    private static final int MIN_LABEL_HEIGHT_DP = 18;

    /** 「真删」前的说明文案 */
    private static final String TIP_GONE =
            "需在 LSPosed 中启用本模块才会真正删除；未启用时按遮挡处理，规则仍会保存";

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Listener listener;

    /** 进入图层选择时指定的目标包名（规则页传入），可为 null（此时用当前窗口包名） */
    private String targetPkg;
    /** 顶部副标题里显示的当前包名 */
    private String currentPkg;

    /** 全屏覆盖层根布局 */
    private FrameLayout rootView;
    /** 画布：一次性画全部矩形 + 标签，同时收触摸 */
    private LayerBoardView boardView;
    /** 底部动作卡片 */
    private LinearLayout actionCard;

    /** 本次快照画出来的所有图层矩形（屏幕坐标，不含节点引用） */
    private final List<LayerRect> layers = new ArrayList<LayerRect>();

    private boolean showing;

    public LayerInspectorOverlay(AccessibilityService service, WindowManager windowManager,
                                 Listener listener, String targetPkg) {
        this.service = service;
        this.windowManager = windowManager;
        this.listener = listener;
        this.targetPkg = targetPkg;
    }

    /** 选择层是否正在显示 */
    public boolean isShowing() {
        return showing;
    }

    // ==================== 显示 / 关闭 ====================

    /** 开始图层选择（重复调用会先清掉旧界面） */
    public void start() {
        stop();
        if (windowManager == null || service == null) {
            toast("无法显示图层选择（窗口服务不可用）");
            return;
        }
        // 先收集快照，再 addView：避免把本应用即将添加的覆盖层算进节点树
        currentPkg = resolveCurrentPkg();
        collectLayers();

        FrameLayout root = new FrameLayout(service);
        root.setBackgroundColor(COLOR_SCRIM);

        // 画布：所有节点矩形 + 标签 + 选中高亮，一次 onDraw 画完，同时负责接收点击
        LayerBoardView board = new LayerBoardView(service);
        board.setLayers(layers);
        root.addView(board, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 顶部信息条（后加，盖在画布之上）
        LinearLayout topBar = buildTopBar();
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;
        root.addView(topBar, topParams);

        if (!addOverlayWindow(root)) {
            toast("无法显示图层选择（悬浮窗可能被系统限制）");
            layers.clear();
            board.setLayers(null);
            return;
        }
        rootView = root;
        boardView = board;
        showing = true;
    }

    /**
     * 关闭选择层，移除窗口并清空全部临时引用（可重复调用）。
     * v3：图层列表清空、画布/根布局置空，避免关闭后还持有位图/节点/视图。
     */
    public void stop() {
        showing = false;
        removeActionCard();
        if (boardView != null) {
            boardView.setLayers(null);
            boardView.clearSelection();
        }
        if (rootView != null && windowManager != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Throwable t) {
                // 窗口可能已经被系统移除，忽略
            }
        }
        rootView = null;
        boardView = null;
        currentPkg = null;
        layers.clear();
    }

    // ==================== 顶部信息条 ====================

    /**
     * 顶部信息条：左侧图标位 + 主标题 15sp + 副标题「共 N 个图层 · 当前 <包名>」，
     * 右侧「取消」做成高对比胶囊按钮。
     */
    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(service);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_HINT_BG);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(12), dp(10));

        // 左侧图标位（纯文本符号，不依赖资源）
        TextView icon = new TextView(service);
        icon.setText("◎");
        icon.setTextSize(18f);
        icon.setTextColor(COLOR_ACCENT);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(26),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        iconParams.rightMargin = dp(8);
        bar.addView(icon, iconParams);

        // 中间两行：标题 + 副标题
        LinearLayout textBox = new LinearLayout(service);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textBoxParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(textBox, textBoxParams);

        TextView title = new TextView(service);
        title.setText("图层选择：点选要处理的元素");
        title.setTextColor(COLOR_HINT_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        textBox.addView(title);

        TextView subtitle = new TextView(service);
        subtitle.setText("共 " + layers.size() + " 个图层 · 当前 " + currentPkg);
        subtitle.setTextColor(COLOR_HINT_SUB);
        subtitle.setTextSize(11f);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(2), 0, 0);
        textBox.addView(subtitle);

        // 右侧「取消」胶囊按钮（高对比）
        TextView cancel = new TextView(service);
        cancel.setText("取消");
        cancel.setTextColor(COLOR_BUTTON_TEXT);
        cancel.setTextSize(14f);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setGravity(Gravity.CENTER);
        cancel.setSingleLine(true);
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(COLOR_ACCENT);
        capsule.setCornerRadius(dp(100));
        cancel.setBackground(capsule);
        cancel.setPadding(dp(16), dp(7), dp(16), dp(7));
        cancel.setClickable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelAll();
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.leftMargin = dp(10);
        bar.addView(cancel, cancelParams);
        return bar;
    }

    /** 用户点顶部「取消」：关闭选择层并回调 */
    private void cancelAll() {
        stop();
        if (listener != null) {
            listener.onLayerCancelled();
        }
    }

    // ==================== 收集图层节点 ====================

    /** 当前包名：优先规则页传入的目标包，其次活动窗口根节点的包名 */
    private String resolveCurrentPkg() {
        if (targetPkg != null && targetPkg.length() > 0) {
            return targetPkg;
        }
        String pkg = null;
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root != null) {
                CharSequence cs = root.getPackageName();
                pkg = cs == null ? null : cs.toString();
            }
        } catch (Throwable t) {
            pkg = null;
        }
        // active root 不 recycle（与 NodeFinder 约定一致），交给系统
        if (pkg == null || pkg.length() == 0) {
            return "未知";
        }
        return pkg;
    }

    /**
     * 收集所有应用窗口的可见节点，转成「矩形 + 颜色 + 标签」的快照。
     * v3：NodeFinder 返回的 LayerNode 不含节点引用，这里也不再保存节点；
     * 选中时由 findNodeAt 现场重新取一次节点。
     */
    private void collectLayers() {
        layers.clear();
        List<NodeFinder.LayerNode> nodes;
        try {
            nodes = NodeFinder.collectVisibleNodes(service, MIN_NODE_SIZE, MAX_NODES);
        } catch (Throwable t) {
            Log.w(TAG, "收集图层节点失败", t);
            return;
        }
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        int minLabelWidth = dp(MIN_LABEL_WIDTH_DP);
        int minLabelHeight = dp(MIN_LABEL_HEIGHT_DP);
        for (int i = 0; i < nodes.size() && layers.size() < MAX_NODES; i++) {
            NodeFinder.LayerNode layerNode = nodes.get(i);
            if (layerNode == null || layerNode.bounds == null) {
                continue;
            }
            Rect bounds = layerNode.bounds;
            if (bounds.width() < MIN_NODE_SIZE || bounds.height() < MIN_NODE_SIZE) {
                continue;
            }
            int depth = layerNode.depth < 0 ? 0 : layerNode.depth;
            int color = DEPTH_COLORS[depth % DEPTH_COLORS.length];
            String label = null;
            if (bounds.width() >= minLabelWidth && bounds.height() >= minLabelHeight) {
                label = layerNode.label;
            }
            layers.add(new LayerRect(new Rect(bounds), color, label));
        }
        // nodes 是局部变量，循环后即可回收；快照里没有节点引用
    }

    // ==================== 点击选中 ====================

    /** 处理画布上的点击（坐标为屏幕绝对坐标） */
    private void handleTap(float rawX, float rawY) {
        if (!showing || actionCard != null) {
            // 已有动作卡片时不重复选，先让用户处理当前选中的元素
            return;
        }
        AccessibilityNodeInfo node;
        try {
            node = NodeFinder.findNodeAt(service, (int) rawX, (int) rawY);
        } catch (Throwable t) {
            node = null;
        }
        if (node == null) {
            toast("没识别到元素");
            return;
        }
        Rule rule = buildRule(node);
        if (rule == null) {
            toast("没识别到元素");
            return;
        }
        if (boardView != null) {
            boardView.setSelectedRect(new Rect(rule.l, rule.t, rule.r, rule.b));
        }
        showActionCard(rule);
    }

    /** 把选中节点转成一条规则（此时还未保存，mode 默认 mask） */
    private Rule buildRule(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        String pkg = null;
        try {
            CharSequence cs = node.getPackageName();
            pkg = cs == null ? null : cs.toString();
        } catch (Throwable t) {
            pkg = null;
        }
        if (pkg == null || pkg.length() == 0) {
            pkg = targetPkg;
        }
        if (pkg == null || pkg.length() == 0) {
            // 拿不到包名就没法存规则
            return null;
        }
        Rule rule = new Rule();
        rule.id = UUID.randomUUID().toString();
        rule.pkg = pkg;
        // 节点树里拿不到 Activity 类名，这个字段只作参考，留空
        rule.activity = null;
        rule.viewId = safeViewId(node);
        rule.cls = safeClassName(node);
        String text = safeText(node);
        rule.text = (text != null && text.trim().length() > 0) ? text.trim() : null;
        Rect rect = new Rect();
        try {
            node.getBoundsInScreen(rect);
        } catch (Throwable t) {
            rect.setEmpty();
        }
        rule.l = rect.left;
        rule.t = rect.top;
        rule.r = rect.right;
        rule.b = rect.bottom;
        rule.path = pathForNode(node);
        rule.enabled = true;
        // 默认遮挡；点「真删」时改成 Rule.MODE_GONE
        rule.mode = Rule.MODE_MASK;
        rule.created = System.currentTimeMillis();
        return rule;
    }

    /** 计算节点路径：优先活动窗口根节点，失败再按窗口 id 找根节点 */
    private String pathForNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
        } catch (Throwable t) {
            root = null;
        }
        if (root == null) {
            root = rootForWindow(node);
        }
        if (root == null) {
            return null;
        }
        try {
            return NodeFinder.pathOf(root, node);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按节点所在窗口 id 找到窗口根节点（兼容多窗口场景） */
    private AccessibilityNodeInfo rootForWindow(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        int windowId = -1;
        try {
            windowId = node.getWindowId();
        } catch (Throwable t) {
            windowId = -1;
        }
        if (windowId < 0) {
            return null;
        }
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) {
                return null;
            }
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo window = windows.get(i);
                if (window == null) {
                    continue;
                }
                if (window.getId() == windowId) {
                    return window.getRoot();
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    // ==================== 动作卡片 ====================

    /**
     * 底部动作卡片（v3）：#F22A2A2A、圆角 14dp；两行信息 + 三个 52dp 高按钮。
     * 第 1 行 viewId 末段或文字（16sp 白），第 2 行「类名 · 坐标(左,上,宽×高)」（11sp 灰）。
     */
    private void showActionCard(final Rule rule) {
        removeActionCard();
        if (rootView == null || rule == null) {
            return;
        }
        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_CARD_BG);
        background.setCornerRadius(dp(14));
        card.setBackground(background);
        card.setPadding(dp(16), dp(12), dp(16), dp(14));

        // 第 1 行：viewId 末段 或 文字（16sp 白）
        TextView title = new TextView(service);
        title.setText(primaryInfo(rule));
        title.setTextColor(COLOR_CARD_TEXT);
        title.setTextSize(16f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        // 第 2 行：类名 · 坐标(左,上,宽×高)（11sp 灰）
        TextView subtitle = new TextView(service);
        subtitle.setText(secondaryInfo(rule));
        subtitle.setTextColor(COLOR_CARD_SUB_TEXT);
        subtitle.setTextSize(11f);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(3), 0, dp(12));
        card.addView(subtitle);

        LinearLayout buttonRow = new LinearLayout(service);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        // 「遮挡」：绿色实底白字（默认模式）
        TextView maskButton = createCardButton("遮挡", COLOR_BUTTON_TEXT, COLOR_MASK_BUTTON);
        maskButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveRule(rule, Rule.MODE_MASK);
            }
        });
        // 「真删」：红色实底白字，需 LSPosed 模块；无障碍模式下仍按遮挡显示
        TextView goneButton = createCardButton("真删", COLOR_BUTTON_TEXT, COLOR_GONE_BUTTON);
        goneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 先说明真删依赖 LSPosed 模块，不做二次确认弹窗
                toast(TIP_GONE);
                saveRule(rule, Rule.MODE_GONE);
            }
        });
        // 「取消」：深灰底，不处理这个元素，收起卡片继续选
        TextView cancelButton = createCardButton("取消", COLOR_CARD_TEXT, COLOR_CANCEL_BUTTON);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeActionCard();
                if (boardView != null) {
                    boardView.clearSelection();
                }
            }
        });

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        secondParams.leftMargin = dp(8);
        LinearLayout.LayoutParams thirdParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        thirdParams.leftMargin = dp(8);
        buttonRow.addView(maskButton, firstParams);
        buttonRow.addView(goneButton, secondParams);
        buttonRow.addView(cancelButton, thirdParams);
        card.addView(buttonRow);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.BOTTOM;
        cardParams.leftMargin = dp(12);
        cardParams.rightMargin = dp(12);
        cardParams.bottomMargin = dp(14);
        cardParams.topMargin = dp(8);
        try {
            rootView.addView(card, cardParams);
        } catch (Throwable t) {
            return;
        }
        actionCard = card;
    }

    /** 第 1 行信息：viewId 末段 → 文字 → 类名简名 */
    private static String primaryInfo(Rule rule) {
        if (rule == null) {
            return "元素";
        }
        String viewId = rule.viewId;
        if (viewId != null && viewId.length() > 0) {
            String name = lastSegment(viewId);
            if (name.length() > 0) {
                return name;
            }
        }
        if (rule.text != null && rule.text.trim().length() > 0) {
            return rule.text.trim();
        }
        String cls = simpleClassName(rule.cls);
        if (cls.length() > 0) {
            return cls;
        }
        return "未命名元素";
    }

    /** 第 2 行信息：类名 · 坐标(左,上,宽×高) */
    private static String secondaryInfo(Rule rule) {
        if (rule == null) {
            return "";
        }
        String cls = simpleClassName(rule.cls);
        if (cls.length() == 0) {
            cls = "未知类";
        }
        int width = Math.max(0, rule.r - rule.l);
        int height = Math.max(0, rule.b - rule.t);
        return cls + " · 坐标(" + rule.l + "," + rule.t + "," + width + "×" + height + ")";
    }

    /** viewId 末段（"com.foo:id/btn" → "btn"） */
    private static String lastSegment(String viewId) {
        if (viewId == null || viewId.length() == 0) {
            return "";
        }
        int slash = viewId.lastIndexOf('/');
        return slash >= 0 ? viewId.substring(slash + 1) : viewId;
    }

    /** 类名简名（"android.widget.TextView" → "TextView"） */
    private static String simpleClassName(String cls) {
        if (cls == null || cls.length() == 0) {
            return "";
        }
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }

    /** 保存规则：mode 由按钮决定；写 ConfigStore 后关闭选择层并回调 */
    private void saveRule(Rule rule, String mode) {
        if (rule == null) {
            return;
        }
        rule.mode = mode;
        try {
            ConfigStore.addRule(rule);
        } catch (Throwable t) {
            Log.w(TAG, "保存规则失败", t);
            toast("保存失败：" + t);
            return;
        }
        toast("已保存");
        // 先关掉选择层（会移除所有窗口），再通知 service 立即上蒙版
        stop();
        if (listener != null) {
            listener.onLayerSaved(rule);
        }
    }

    private TextView createCardButton(String text, int textColor, int backgroundColor) {
        TextView button = new TextView(service);
        button.setText(text);
        button.setTextSize(16f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setClickable(true);
        return button;
    }

    private void removeActionCard() {
        if (actionCard != null && rootView != null) {
            try {
                rootView.removeView(actionCard);
            } catch (Throwable t) {
                // 忽略
            }
        }
        actionCard = null;
    }

    // ==================== 窗口参数与降级重试 ====================

    /** 添加全屏选择层；首选无障碍 overlay，失败按 v1 降级逻辑换类型再试 */
    private boolean addOverlayWindow(FrameLayout root) {
        int primaryType = BlockerService.overlayWindowType();
        if (tryAddWindow(root, primaryType)) {
            return true;
        }
        int fallbackType = fallbackOverlayType(primaryType);
        if (fallbackType != 0 && tryAddWindow(root, fallbackType)) {
            return true;
        }
        return false;
    }

    private boolean tryAddWindow(FrameLayout root, int overlayType) {
        WindowManager.LayoutParams params = createOverlayParams(overlayType);
        try {
            windowManager.addView(root, params);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "添加图层选择层失败，type=" + overlayType, t);
            return false;
        }
    }

    /**
     * 备用悬浮窗类型（与 v1 一致）：
     * - API 26+：TYPE_APPLICATION_OVERLAY（值 2038，编译用 API 23 的 android.jar 所以写数值）；
     * - 更低版本：TYPE_SYSTEM_ALERT（2002）。
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

    /** 选择层窗口参数：全屏、绝对定位、不获取焦点、可接收触摸 */
    private WindowManager.LayoutParams createOverlayParams(int overlayType) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = overlayType;
        params.format = PixelFormat.TRANSLUCENT;
        // 不加 FLAG_NOT_TOUCHABLE：选择层要收全部触摸
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        try {
            params.setTitle("gm-layers");
        } catch (Throwable t) {
            // 老系统没有 setTitle 也不影响功能
        }
        return params;
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

    private static String safeViewId(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            return node.getViewIdResourceName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeClassName(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            CharSequence cs = node.getClassName();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeText(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            CharSequence cs = node.getText();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 内部数据与画布 ====================

    /** 一个图层矩形：屏幕矩形 + 边框颜色 + 标签（可为 null）；不含节点引用 */
    private static final class LayerRect {
        final Rect rect;
        final int color;
        final String label;

        LayerRect(Rect rect, int color, String label) {
            this.rect = rect;
            this.color = color;
            this.label = label;
        }
    }

    /**
     * 画布（v3）：用一个 View 的 onDraw 画出所有图层矩形、小标签和选中高亮，
     * 不再为每个元素 addView。子控件（顶部信息条、动作卡片）会先消费点击，
     * 其余位置的触摸由这里吞掉并当作选中操作。
     */
    private final class LayerBoardView extends View {

        private final Paint strokePaint = new Paint();
        private final Paint glowPaint = new Paint();
        private final Paint highlightBorderPaint = new Paint();
        private final Paint highlightFillPaint = new Paint();
        private final Paint labelTextPaint = new Paint();
        private final Paint labelBgPaint = new Paint();
        private final int[] location = new int[2];
        private final Rect selectedRect = new Rect();
        private final RectF tempRect = new RectF();
        private boolean hasSelection;
        private List<LayerRect> items;

        LayerBoardView(Context context) {
            super(context);
            // 边框：1.5dp、不填充
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setAntiAlias(true);
            strokePaint.setStrokeWidth(dp(1.5f));
            // 外发光：4dp 半透明绿描边
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setAntiAlias(true);
            glowPaint.setStrokeWidth(dp(4f));
            glowPaint.setColor(COLOR_HIGHLIGHT_GLOW);
            // 选中边框：3dp 亮绿
            highlightBorderPaint.setStyle(Paint.Style.STROKE);
            highlightBorderPaint.setAntiAlias(true);
            highlightBorderPaint.setStrokeWidth(dp(3f));
            highlightBorderPaint.setColor(COLOR_HIGHLIGHT_STROKE);
            // 选中填充：极淡绿
            highlightFillPaint.setStyle(Paint.Style.FILL);
            highlightFillPaint.setAntiAlias(true);
            highlightFillPaint.setColor(COLOR_HIGHLIGHT_FILL);
            // 标签文字：9sp 白色粗体
            labelTextPaint.setAntiAlias(true);
            labelTextPaint.setColor(0xFFFFFFFF);
            labelTextPaint.setTextSize(dp(9f));
            labelTextPaint.setFakeBoldText(true);
            // 标签背景：0xCC000000
            labelBgPaint.setStyle(Paint.Style.FILL);
            labelBgPaint.setColor(0xCC000000);
        }

        /** 设置要画的图层列表（null = 清空） */
        void setLayers(List<LayerRect> list) {
            items = list;
            invalidate();
        }

        /** 设置选中矩形（屏幕坐标）；传 null 清除选中 */
        void setSelectedRect(Rect screenRect) {
            if (screenRect == null) {
                clearSelection();
                return;
            }
            selectedRect.set(screenRect);
            hasSelection = true;
            invalidate();
        }

        /** 清除选中高亮 */
        void clearSelection() {
            hasSelection = false;
            selectedRect.setEmpty();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (canvas == null) {
                return;
            }
            int offsetX = 0;
            int offsetY = 0;
            try {
                getLocationOnScreen(location);
                offsetX = location[0];
                offsetY = location[1];
            } catch (Throwable t) {
                offsetX = 0;
                offsetY = 0;
            }
            List<LayerRect> list = items;
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    LayerRect item = list.get(i);
                    if (item == null || item.rect == null) {
                        continue;
                    }
                    Rect rect = item.rect;
                    int left = rect.left - offsetX;
                    int top = rect.top - offsetY;
                    int right = rect.right - offsetX;
                    int bottom = rect.bottom - offsetY;
                    if (right <= left || bottom <= top) {
                        continue;
                    }
                    strokePaint.setColor(item.color);
                    canvas.drawRect(left, top, right, bottom, strokePaint);
                    if (item.label != null && item.label.length() > 0) {
                        drawLabel(canvas, item.label, left, top);
                    }
                }
            }
            if (hasSelection) {
                drawSelection(canvas, offsetX, offsetY);
            }
        }

        /** 选中高亮：3dp 亮绿边框 + 4dp 0x2200FF00 外发光层 + 极淡填充 */
        private void drawSelection(Canvas canvas, int offsetX, int offsetY) {
            float left = selectedRect.left - offsetX;
            float top = selectedRect.top - offsetY;
            float right = selectedRect.right - offsetX;
            float bottom = selectedRect.bottom - offsetY;
            if (right <= left || bottom <= top) {
                return;
            }
            tempRect.set(left, top, right, bottom);
            canvas.drawRect(tempRect, highlightFillPaint);
            canvas.drawRect(tempRect, glowPaint);
            canvas.drawRect(tempRect, highlightBorderPaint);
        }

        /** 在矩形左上角画小标签：深色底 + 9sp 白色粗体，超出屏幕时往屏幕内挤 */
        private void drawLabel(Canvas canvas, String label, int left, int top) {
            Paint.FontMetrics metrics = labelTextPaint.getFontMetrics();
            float textHeight = metrics.descent - metrics.ascent;
            float padding = dp(2f);
            float boxWidth = labelTextPaint.measureText(label) + padding * 2f;
            float boxHeight = textHeight + padding * 2f;
            float boxLeft = left < 0 ? 0f : left;
            float boxTop = top < 0 ? 0f : top;
            if (boxLeft + boxWidth > getWidth()) {
                boxLeft = Math.max(0f, getWidth() - boxWidth);
            }
            if (boxTop + boxHeight > getHeight()) {
                boxTop = Math.max(0f, getHeight() - boxHeight);
            }
            tempRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight);
            canvas.drawRect(tempRect, labelBgPaint);
            canvas.drawText(label, boxLeft + padding, boxTop + padding - metrics.ascent,
                    labelTextPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                handleTap(event.getRawX(), event.getRawY());
            }
            // 返回 true：选择模式期间所有触摸都自己吞掉，不传给目标应用
            return true;
        }
    }
}
