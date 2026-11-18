package com.dsh.godmodelite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 主界面的应用列表适配器（v3 深色）。
 *
 * 两种行：
 * - 分组标题行（TYPE_HEADER）：「已配置的应用（N）」/「其他应用（M）」，不可点击；
 * - 应用行（TYPE_APP）：40dp 图标 + 应用名 16sp + 包名 11sp + 右侧「遮挡 N」「真删 M」徽章。
 *
 * 内存优化（SPEC-v3 §7）：
 * - 图标用 {@link LruCache}（上限 {@link #ICON_CACHE_SIZE} 个 Drawable）缓存；
 * - 图标在后台线程（固定 2 线程池）通过 PackageManager 加载，结果回主线程；
 * - getView 里不做任何 PackageManager 查询，只用缓存 / 占位图；
 * - 行复用 + ImageView tag 校验，避免异步结果落到已被复用的行上；
 * - 规则数由 MainActivity 一次遍历填进 AppEntry，getView 不读 ConfigStore。
 * 纯代码构建行布局，无 XML。
 */
public class AppListAdapter extends BaseAdapter {

    /** 深色主题色值（与 SPEC-v2/v3 一致）。 */
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_SUB = 0xFF9E9E9E;
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    private static final int COLOR_DANGER = 0xFFEF5350;
    private static final int COLOR_PRESSED = 0xFF2A2A2A;
    private static final int COLOR_PLACEHOLDER = 0xFF2A2A2A;

    /** 图标缓存上限：48 个 Drawable（约 2MB）。 */
    public static final int ICON_CACHE_SIZE = 48;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    /** 一行应用数据：包名 + 应用名 + 规则统计（由 MainActivity 统计后填入）。 */
    public static class AppEntry {
        public final String pkg;
        public final String label;
        /** 遮挡规则条数（v3，主线程统计写入）。 */
        public int maskCount;
        /** 真删规则条数（v3）。 */
        public int goneCount;

        public AppEntry(String pkg, String label) {
            this.pkg = pkg;
            this.label = (label == null || label.length() == 0) ? pkg : label;
        }

        /** 该应用规则总数。 */
        public int totalCount() {
            return maskCount + goneCount;
        }
    }

    /** 列表里的一行：标题行或应用行（二选一）。 */
    public static class ListRow {
        /** 是否为分组标题行。 */
        public final boolean header;
        /** 标题文字（header 时有效）。 */
        public final String title;
        /** 应用数据（应用行时有效）。 */
        public final AppEntry entry;

        private ListRow(boolean header, String title, AppEntry entry) {
            this.header = header;
            this.title = title;
            this.entry = entry;
        }

        /** 构造一个不可点击的分组标题行。 */
        public static ListRow header(String title) {
            return new ListRow(true, title == null ? "" : title, null);
        }

        /** 构造一个应用行。 */
        public static ListRow app(AppEntry entry) {
            return new ListRow(false, null, entry);
        }
    }

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ListRow> rows = new ArrayList<ListRow>();
    /** 图标缓存：包名 -> Drawable。 */
    private final LruCache<String, Drawable> iconCache;
    /** 正在后台加载的包名，避免重复入队。 */
    private final Set<String> loadingIcons = new HashSet<String>();
    /** 图标加载线程池（固定 2 线程，避免滚动时线程爆炸）。 */
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(2);
    /** 无图标时的占位方块（所有行共享同一个 Drawable 实例，只读不改）。 */
    private final Drawable placeholder;

    public AppListAdapter(Context ctx) {
        this.ctx = ctx;
        this.iconCache = new LruCache<String, Drawable>(ICON_CACHE_SIZE);
        this.placeholder = rounded(COLOR_PLACEHOLDER, 9);
    }

    /** 替换数据（主线程调用）。 */
    public void setRows(List<ListRow> list) {
        rows.clear();
        if (list != null) {
            rows.addAll(list);
        }
        notifyDataSetChanged();
    }

    /** 兼容旧调用：只放应用行，不分组。 */
    public void setItems(List<AppEntry> list) {
        List<ListRow> converted = new ArrayList<ListRow>();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                converted.add(ListRow.app(list.get(i)));
            }
        }
        setRows(converted);
    }

    /** 当前的全部行（拷贝）。 */
    public List<ListRow> getRows() {
        return new ArrayList<ListRow>(rows);
    }

    /** 取某行的应用数据；标题行或越界返回 null。 */
    public AppEntry getAppEntry(int position) {
        if (position < 0 || position >= rows.size()) {
            return null;
        }
        ListRow row = rows.get(position);
        return row == null ? null : row.entry;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return getAppEntry(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= rows.size()) {
            return TYPE_APP;
        }
        ListRow row = rows.get(position);
        return (row != null && row.header) ? TYPE_HEADER : TYPE_APP;
    }

    /** 分组标题行不可点击（ListView 不会为它触发 onItemClick）。 */
    @Override
    public boolean isEnabled(int position) {
        return getItemViewType(position) == TYPE_APP;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (position < 0 || position >= rows.size()) {
            return convertView;
        }
        ListRow row = rows.get(position);
        if (row == null) {
            return convertView;
        }
        if (row.header) {
            return getHeaderView(row, convertView);
        }
        return getAppView(row.entry, convertView);
    }

    // ===================== 分组标题行 =====================

    private View getHeaderView(ListRow row, View convertView) {
        TextView tv;
        if (convertView instanceof TextView) {
            tv = (TextView) convertView;
        } else {
            tv = new TextView(ctx);
            tv.setTextSize(12f);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setTextColor(COLOR_SUB);
            tv.setPadding(dp(16), dp(12), dp(16), dp(6));
            tv.setSingleLine(true);
        }
        tv.setText(row.title == null ? "" : row.title);
        return tv;
    }

    // ===================== 应用行 =====================

    private View getAppView(AppEntry e, View convertView) {
        Holder holder;
        if (convertView == null || !(convertView.getTag() instanceof Holder)) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(9), dp(14), dp(9));
            // 按下高亮 / 默认透明；ListView 的 selector 已关掉，避免叠加成浅色块。
            row.setBackground(rowBackground());

            ImageView icon = new ImageView(ctx);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconLp.rightMargin = dp(10);
            row.addView(icon, iconLp);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView tvLabel = new TextView(ctx);
            tvLabel.setTextSize(16f);
            tvLabel.setTextColor(COLOR_TEXT);
            tvLabel.setSingleLine(true);
            tvLabel.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(tvLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvSub = new TextView(ctx);
            tvSub.setTextSize(11f);
            tvSub.setTextColor(COLOR_SUB);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setPadding(0, dp(2), 0, 0);
            texts.addView(tvSub, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout badges = new LinearLayout(ctx);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams badgesLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgesLp.leftMargin = dp(6);
            row.addView(badges, badgesLp);

            TextView maskBadge = newBadge(COLOR_ACCENT);
            TextView goneBadge = newBadge(COLOR_DANGER);
            badges.addView(maskBadge, badgeLp());
            badges.addView(goneBadge, badgeLp());

            holder = new Holder();
            holder.icon = icon;
            holder.label = tvLabel;
            holder.sub = tvSub;
            holder.maskBadge = maskBadge;
            holder.goneBadge = goneBadge;
            row.setTag(holder);
            convertView = row;
        } else {
            holder = (Holder) convertView.getTag();
        }

        if (e == null) {
            return convertView;
        }
        holder.label.setText(e.label);
        String sub = e.pkg;
        if (e.totalCount() > 0) {
            sub = e.pkg + " · " + e.totalCount() + " 条规则";
        }
        holder.sub.setText(sub);

        if (e.maskCount > 0) {
            holder.maskBadge.setVisibility(View.VISIBLE);
            holder.maskBadge.setText("遮挡 " + e.maskCount);
        } else {
            holder.maskBadge.setVisibility(View.GONE);
        }
        if (e.goneCount > 0) {
            holder.goneBadge.setVisibility(View.VISIBLE);
            holder.goneBadge.setText("真删 " + e.goneCount);
        } else {
            holder.goneBadge.setVisibility(View.GONE);
        }

        // 图标：先查缓存（getView 里绝不做 PackageManager 查询），没有再异步加载。
        holder.icon.setTag(e.pkg);
        Drawable cached = iconCache.get(e.pkg);
        if (cached != null) {
            holder.icon.setImageDrawable(cached);
        } else {
            holder.icon.setImageDrawable(placeholder);
            requestIcon(e.pkg, holder.icon);
        }
        return convertView;
    }

    /** 后台线程加载图标；结果只写回仍绑定同一包名的 ImageView。 */
    private void requestIcon(final String pkg, final ImageView target) {
        if (pkg == null || pkg.length() == 0 || target == null) {
            return;
        }
        if (loadingIcons.contains(pkg)) {
            return;
        }
        loadingIcons.add(pkg);
        try {
            iconLoader.execute(new Runnable() {
                @Override
                public void run() {
                    final Drawable d = loadIconSync(pkg);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            loadingIcons.remove(pkg);
                            if (d == null) {
                                return;
                            }
                            iconCache.put(pkg, d);
                            if (pkg.equals(target.getTag())) {
                                target.setImageDrawable(d);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            // 线程池已经关闭（页面正在销毁）：忽略这次加载，保持占位图。
            loadingIcons.remove(pkg);
        }
    }

    /** 子线程：PackageManager 取应用图标，失败返回 null（保持占位块）。 */
    private Drawable loadIconSync(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 释放缓存与线程池（Activity.onDestroy 调用，避免泄漏）。 */
    public void release() {
        try {
            iconLoader.shutdownNow();
        } catch (Throwable ignored) {
            // 已经关闭时忽略。
        }
        iconCache.evictAll();
        loadingIcons.clear();
    }

    // ===================== 小工具 =====================

    /** 徽章：描边小圆角标签（透明底 + 1dp 彩色描边）。 */
    private TextView newBadge(int color) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(10f);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setPadding(dp(5), dp(1), dp(5), dp(1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setStroke(dp(1), color);
        bg.setCornerRadius(dp(4));
        tv.setBackground(bg);
        return tv;
    }

    private LinearLayout.LayoutParams badgeLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(3);
        return lp;
    }

    /** 行背景：按下 #2A2A2A 圆角 8dp，默认透明。 */
    private Drawable rowBackground() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, rounded(COLOR_PRESSED, 8));
        sl.addState(new int[]{}, new GradientDrawable());
        return sl;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 复用行内控件。 */
    private static class Holder {
        ImageView icon;
        TextView label;
        TextView sub;
        TextView maskBadge;
        TextView goneBadge;
    }
}
