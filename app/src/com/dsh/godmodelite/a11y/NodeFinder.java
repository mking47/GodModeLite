package com.dsh.godmodelite.a11y;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.dsh.godmodelite.core.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点查找工具类（全静态）。
 *
 * 负责三件事：
 * 1. 按已保存的规则（viewId → path → cls+text）在无障碍节点树里找到目标节点；
 * 2. 按屏幕坐标找到用户点击到的最合适节点（拾取层使用）；
 * 3. 计算某个节点在树中的下标路径（形如 "0/1/3"）。
 *
 * 内存策略（v3）：
 * - `collectVisibleNodes` 只返回「屏幕矩形 + 标签 + 深度」的快照（{@link LayerNode}），
 *   不保存任何 AccessibilityNodeInfo 引用；遍历中由 getChild 得到的子节点在子树用完后
 *   立即 recycle（同一对象只 recycle 一次），根节点 / 活动根节点拿不准所以不 recycle；
 * - `findByRule` / `findNodeAt` / `pathOf` 会把节点交给调用方，因此不 recycle 返回值，
 *   只限制遍历规模（MAX_VISITED），由系统回收。
 */
public final class NodeFinder {

    private static final String TAG = "GodModeNodeFinder";

    /** 宽或高小于该像素的节点不认为可以被点击（过滤装饰性小节点） */
    private static final int MIN_TOUCH_SIZE = 8;

    /** 节点树最大遍历深度，防御异常深的树导致栈溢出 */
    private static final int MAX_DEPTH = 100;

    /** 单次遍历最多访问的节点数（v3 从 5000 降到 3000），防御超大节点树卡死主线程 */
    private static final int MAX_VISITED = 3000;

    /** 图层小标签最多显示几个字 */
    private static final int MAX_LABEL_CHARS = 12;

    private NodeFinder() {
        // 工具类，不允许实例化
    }

    // ==================== 对外 API ====================

    /**
     * 按规则查找节点。
     * 优先级：1) viewId 相等且可见；2) path 路径逐级 getChild 且类名匹配；3) cls + text 相等。
     *
     * @return 找到的节点；找不到返回 null（调用方不要 recycle，本类也未释放）
     */
    public static AccessibilityNodeInfo findByRule(AccessibilityService svc, Rule rule) {
        if (svc == null || rule == null) {
            return null;
        }
        List<AccessibilityNodeInfo> roots = collectRoots(svc);
        if (roots.isEmpty()) {
            return null;
        }

        // 1) viewId 优先（最稳定，不受布局顺序影响）
        String wantedViewId = rule.viewId;
        if (wantedViewId != null && wantedViewId.length() > 0) {
            AccessibilityNodeInfo hit = findByViewId(roots, wantedViewId);
            if (hit != null) {
                return hit;
            }
        }

        // 2) path 路径（同一页面重新打开时通常还能命中）
        String path = rule.path;
        if (path != null && path.length() > 0) {
            for (int i = 0; i < roots.size(); i++) {
                AccessibilityNodeInfo hit = findByPath(roots.get(i), path, rule.cls);
                if (hit != null) {
                    return hit;
                }
            }
        }

        // 3) cls + text（最后兜底，text 必须非空）
        String text = rule.text;
        if (text != null && text.length() > 0) {
            AccessibilityNodeInfo hit = findByText(roots, text, rule.cls);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 查找屏幕坐标 (x, y) 下最合适的节点。
     * 规则：节点可见、bounds 包含坐标、宽高不小于 MIN_TOUCH_SIZE；
     * 在候选节点中选“最深 + 有 viewId/text/clickable”的那个。
     */
    public static AccessibilityNodeInfo findNodeAt(AccessibilityService svc, int x, int y) {
        if (svc == null) {
            return null;
        }
        List<AccessibilityNodeInfo> roots = collectRoots(svc);
        if (roots.isEmpty()) {
            return null;
        }
        final int px = x;
        final int py = y;
        final BestNode best = new BestNode();
        Visitor visitor = new Visitor() {
            @Override
            public boolean visit(AccessibilityNodeInfo node, int depth) {
                if (!safeVisible(node)) {
                    return false;
                }
                Rect rect = new Rect();
                if (!safeBounds(node, rect)) {
                    return false;
                }
                // 太小的节点（分隔线、图标角标等）不适合被拾取
                if (rect.width() < MIN_TOUCH_SIZE || rect.height() < MIN_TOUCH_SIZE) {
                    return false;
                }
                if (!rect.contains(px, py)) {
                    return false;
                }
                String text = safeText(node);
                boolean informative = safeViewId(node) != null
                        || (text != null && text.length() > 0)
                        || safeClickable(node);
                best.offer(node, depth, informative);
                // 继续往深处找更精确的节点
                return false;
            }
        };
        for (int i = 0; i < roots.size(); i++) {
            traverse(roots.get(i), visitor);
        }
        return best.node;
    }

    /**
     * 图层选择用的节点快照（v3）：只保留屏幕矩形、小标签和深度。
     *
     * 说明：这里**不持有** AccessibilityNodeInfo 引用，节点在遍历结束时就已释放，
     * 避免一次图层选择把整棵节点树钉在内存里；选中后需要节点属性时由
     * LayerInspectorOverlay 再用 findNodeAt 重新取一次。
     */
    public static final class LayerNode {
        public final Rect bounds;
        public final String label;
        public final int depth;

        LayerNode(Rect bounds, String label, int depth) {
            this.bounds = bounds;
            this.label = label;
            this.depth = depth;
        }
    }

    /**
     * 收集所有应用窗口里「可见 + bounds 有效（宽高都 >= minSize）」的节点快照，
     * 供图层选择层（LayerInspectorOverlay）一次画出来。
     *
     * 要点（v3 内存优化）：
     * - 复用 collectRoots：会跳过无障碍 overlay 窗口，避免把本应用自己的悬浮球/选择层算进去；
     * - 深度优先顺序返回，最多 maxCount 个，防止超大节点树把主线程拖死；
     * - **返回的 LayerNode 不含节点引用**，遍历中的每个 getChild 结果在其子树处理完后 recycle；
     * - 根节点（窗口根 / 活动根）不 recycle，因为可能被无障碍框架复用，拿不准就不动。
     *
     * @param svc      无障碍服务
     * @param minSize  节点宽高下限（像素），小于该值的装饰性节点不收集
     * @param maxCount 最多收集多少个节点
     */
    public static List<LayerNode> collectVisibleNodes(AccessibilityService svc, final int minSize, final int maxCount) {
        List<LayerNode> out = new ArrayList<LayerNode>();
        if (svc == null || maxCount <= 0) {
            return out;
        }
        List<AccessibilityNodeInfo> roots = collectRoots(svc);
        int[] visited = new int[] { 0 };
        for (int i = 0; i < roots.size(); i++) {
            if (out.size() >= maxCount || visited[0] >= MAX_VISITED) {
                break;
            }
            AccessibilityNodeInfo root = roots.get(i);
            if (root == null) {
                continue;
            }
            try {
                collectInternal(root, 0, minSize, maxCount, out, visited);
            } catch (Throwable t) {
                Log.w(TAG, "收集图层节点异常", t);
            }
        }
        // 根节点不 recycle，只清掉列表引用
        roots.clear();
        return out;
    }

    /**
     * 收集快照的递归实现（v3）：只把 rect/label/depth 存进 out，不保存 node。
     * 子节点在子树遍历结束后立即 recycle，避免长时间持有整棵节点树。
     */
    private static void collectInternal(AccessibilityNodeInfo node, int depth, final int minSize,
                                       final int maxCount, final List<LayerNode> out, final int[] visited) {
        if (node == null) {
            return;
        }
        if (visited[0] >= MAX_VISITED || out.size() >= maxCount) {
            return;
        }
        visited[0] = visited[0] + 1;
        // 收集当前节点：用户可见 + 尺寸达标；只保存快照字段，不保存 node 引用
        if (safeVisible(node)) {
            Rect rect = new Rect();
            if (safeBounds(node, rect) && rect.width() >= minSize && rect.height() >= minSize) {
                out.add(new LayerNode(rect, buildLayerLabel(node), depth));
            }
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        int count = safeChildCount(node);
        for (int i = 0; i < count; i++) {
            if (visited[0] >= MAX_VISITED || out.size() >= maxCount) {
                return;
            }
            AccessibilityNodeInfo child = safeChild(node, i);
            if (child == null) {
                continue;
            }
            try {
                collectInternal(child, depth + 1, minSize, maxCount, out, visited);
            } catch (Throwable t) {
                Log.w(TAG, "收集图层子节点异常", t);
            } finally {
                // 子树已经处理完，子节点不再被使用：立刻释放（每个对象只 recycle 一次）
                recycleQuietly(child);
            }
        }
    }

    /** 生成图层小标签：优先 text，其次 viewId 末段，最多 12 字；都没有返回 null */
    private static String buildLayerLabel(AccessibilityNodeInfo node) {
        String text = safeText(node);
        if (text != null) {
            text = text.trim();
            if (text.length() > 0) {
                return trimLabel(text);
            }
        }
        String viewId = safeViewId(node);
        if (viewId != null && viewId.length() > 0) {
            int slash = viewId.lastIndexOf('/');
            String name = slash >= 0 ? viewId.substring(slash + 1) : viewId;
            if (name.length() > 0) {
                return trimLabel(name);
            }
        }
        return null;
    }

    private static String trimLabel(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_LABEL_CHARS) {
            return value;
        }
        return value.substring(0, MAX_LABEL_CHARS);
    }

    /** 安静地 recycle 一个节点：已释放 / 系统异常都不影响功能 */
    private static void recycleQuietly(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable t) {
            // 可能已被系统回收：吞掉，绝不能因此崩溃
        }
    }

    /**
     * 计算 target 在 root 节点树里的下标路径，如 "0/1/3"；找不到返回 null。
     * 若 target 就是 root 本身，返回空字符串。
     */
    public static String pathOf(AccessibilityNodeInfo root, AccessibilityNodeInfo target) {
        if (root == null || target == null) {
            return null;
        }
        if (root == target || target.equals(root)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (findPath(root, target, builder, 0)) {
            return builder.toString();
        }
        return null;
    }

    // ==================== 按规则查找的三种实现 ====================

    /** 按 viewId 查找：优先返回可见节点，否则返回 bounds 有效的第一个同类节点 */
    private static AccessibilityNodeInfo findByViewId(List<AccessibilityNodeInfo> roots, String wantedId) {
        final String wanted = wantedId;
        final AccessibilityNodeInfo[] visibleHit = new AccessibilityNodeInfo[1];
        final AccessibilityNodeInfo[] anyHit = new AccessibilityNodeInfo[1];
        Visitor visitor = new Visitor() {
            @Override
            public boolean visit(AccessibilityNodeInfo node, int depth) {
                String viewId = safeViewId(node);
                if (viewId == null || !wanted.equals(viewId)) {
                    return false;
                }
                if (safeVisible(node)) {
                    visibleHit[0] = node;
                    return true;
                }
                if (anyHit[0] == null && hasUsefulBounds(node)) {
                    anyHit[0] = node;
                }
                return false;
            }
        };
        for (int i = 0; i < roots.size(); i++) {
            traverse(roots.get(i), visitor);
            if (visibleHit[0] != null) {
                return visibleHit[0];
            }
        }
        return anyHit[0];
    }

    /** 按 path 查找：逐级 getChild(index)，最后校验类名（若规则里记录了类名） */
    private static AccessibilityNodeInfo findByPath(AccessibilityNodeInfo root, String path, String cls) {
        if (root == null || path == null || path.length() == 0) {
            return null;
        }
        String[] parts = path.split("/");
        AccessibilityNodeInfo current = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part == null || part.trim().length() == 0) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 0) {
                return null;
            }
            AccessibilityNodeInfo next;
            try {
                next = current.getChild(index);
            } catch (Throwable t) {
                next = null;
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        if (cls != null && cls.length() > 0) {
            String currentCls = safeClassName(current);
            if (currentCls == null || !cls.equals(currentCls)) {
                return null;
            }
        }
        return current;
    }

    /** 按 cls + text 查找（text 必须非空且完全相等） */
    private static AccessibilityNodeInfo findByText(List<AccessibilityNodeInfo> roots, String wantedText, final String wantedCls) {
        final String wanted = wantedText;
        final AccessibilityNodeInfo[] visibleHit = new AccessibilityNodeInfo[1];
        final AccessibilityNodeInfo[] anyHit = new AccessibilityNodeInfo[1];
        Visitor visitor = new Visitor() {
            @Override
            public boolean visit(AccessibilityNodeInfo node, int depth) {
                String text = safeText(node);
                if (text == null || !wanted.equals(text)) {
                    return false;
                }
                if (wantedCls != null && wantedCls.length() > 0) {
                    String cls = safeClassName(node);
                    if (cls == null || !wantedCls.equals(cls)) {
                        return false;
                    }
                }
                if (safeVisible(node)) {
                    visibleHit[0] = node;
                    return true;
                }
                if (anyHit[0] == null && hasUsefulBounds(node)) {
                    anyHit[0] = node;
                }
                return false;
            }
        };
        for (int i = 0; i < roots.size(); i++) {
            traverse(roots.get(i), visitor);
            if (visibleHit[0] != null) {
                return visibleHit[0];
            }
        }
        return anyHit[0];
    }

    // ==================== 节点树遍历 ====================

    /** 遍历回调：返回 true 表示停止本次遍历 */
    private interface Visitor {
        boolean visit(AccessibilityNodeInfo node, int depth);
    }

    /** 从某个根节点开始深度优先遍历 */
    private static void traverse(AccessibilityNodeInfo root, Visitor visitor) {
        if (root == null || visitor == null) {
            return;
        }
        int[] counter = new int[] { 0 };
        traverseInternal(root, 0, visitor, counter);
    }

    /**
     * 递归遍历实现。
     * 注意：不 recycle 节点，避免 double recycle；counter 用于限制总节点数。
     */
    private static boolean traverseInternal(AccessibilityNodeInfo node, int depth, Visitor visitor, int[] counter) {
        if (node == null || depth > MAX_DEPTH) {
            return false;
        }
        counter[0] = counter[0] + 1;
        if (counter[0] > MAX_VISITED) {
            return true;
        }
        boolean stop;
        try {
            stop = visitor.visit(node, depth);
        } catch (Throwable t) {
            Log.w(TAG, "节点遍历回调异常", t);
            stop = false;
        }
        if (stop) {
            return true;
        }
        int count = safeChildCount(node);
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = safeChild(node, i);
            if (child == null) {
                continue;
            }
            if (traverseInternal(child, depth + 1, visitor, counter)) {
                return true;
            }
        }
        return false;
    }

    /** DFS 查找 target 的路径，路径暂存在 builder 里，回溯时截断 */
    private static boolean findPath(AccessibilityNodeInfo node, AccessibilityNodeInfo target, StringBuilder builder, int depth) {
        if (node == null || target == null || depth > MAX_DEPTH) {
            return false;
        }
        int count = safeChildCount(node);
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = safeChild(node, i);
            if (child == null) {
                continue;
            }
            int lengthBefore = builder.length();
            if (lengthBefore > 0) {
                builder.append('/');
            }
            builder.append(i);
            if (child == target || target.equals(child)) {
                return true;
            }
            if (findPath(child, target, builder, depth + 1)) {
                return true;
            }
            builder.setLength(lengthBefore);
        }
        return false;
    }

    // ==================== 根节点收集 ====================

    /**
     * 收集所有可用的窗口根节点。
     * 跳过无障碍悬浮窗（包括本应用自己的拾取层和蒙版），避免拾取到自己的控件。
     */
    private static List<AccessibilityNodeInfo> collectRoots(AccessibilityService svc) {
        List<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            if (windows != null) {
                for (int i = 0; i < windows.size(); i++) {
                    AccessibilityWindowInfo window = windows.get(i);
                    if (window == null) {
                        continue;
                    }
                    int type = AccessibilityWindowInfo.TYPE_APPLICATION;
                    try {
                        type = window.getType();
                    } catch (Throwable t) {
                        type = AccessibilityWindowInfo.TYPE_APPLICATION;
                    }
                    if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        // 跳过无障碍悬浮窗，否则会拾取到自己的提示条/蒙版
                        continue;
                    }
                    AccessibilityNodeInfo root;
                    try {
                        root = window.getRoot();
                    } catch (Throwable t) {
                        root = null;
                    }
                    if (root != null) {
                        roots.add(root);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取窗口列表失败", t);
        }
        // 兜底：加上当前活动窗口根节点（部分 ROM 的 getWindows 不完整）
        AccessibilityNodeInfo activeRoot;
        try {
            activeRoot = svc.getRootInActiveWindow();
        } catch (Throwable t) {
            activeRoot = null;
        }
        if (activeRoot != null && !roots.contains(activeRoot)) {
            roots.add(activeRoot);
        }
        return roots;
    }

    // ==================== 安全取值小工具 ====================

    private static boolean safeVisible(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            return node.isVisibleToUser();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeBounds(AccessibilityNodeInfo node, Rect out) {
        if (node == null || out == null) {
            return false;
        }
        try {
            node.getBoundsInScreen(out);
            return true;
        } catch (Throwable t) {
            return false;
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

    private static boolean safeClickable(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            return node.isClickable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int safeChildCount(AccessibilityNodeInfo node) {
        if (node == null) {
            return 0;
        }
        try {
            return node.getChildCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static AccessibilityNodeInfo safeChild(AccessibilityNodeInfo node, int index) {
        if (node == null) {
            return null;
        }
        try {
            return node.getChild(index);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasUsefulBounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        if (!safeBounds(node, rect)) {
            return false;
        }
        return rect.width() > 0 && rect.height() > 0;
    }

    /** 候选节点比较：informative 优先，其次深度更深（更精确）的优先 */
    private static final class BestNode {
        AccessibilityNodeInfo node;
        int depth = -1;
        boolean informative;

        void offer(AccessibilityNodeInfo candidate, int candidateDepth, boolean candidateInformative) {
            if (node == null) {
                node = candidate;
                depth = candidateDepth;
                informative = candidateInformative;
                return;
            }
            if (candidateInformative && !informative) {
                node = candidate;
                depth = candidateDepth;
                informative = true;
                return;
            }
            if (!candidateInformative && informative) {
                return;
            }
            // 同为 informative（或同为非 informative）时，取更深的节点
            if (candidateDepth >= depth) {
                node = candidate;
                depth = candidateDepth;
            }
        }
    }
}
