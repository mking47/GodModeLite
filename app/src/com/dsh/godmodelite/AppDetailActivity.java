package com.dsh.godmodelite;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.godmodelite.a11y.BlockerService;
import com.dsh.godmodelite.core.ConfigStore;
import com.dsh.godmodelite.core.Rule;

/**
 * 单个应用的规则页（v2 深色）。
 *
 * 结构：自绘标题栏（返回 + 「规则 · 应用名」）→ 规则列表（每行：启用勾选 + describe() +
 * 「遮挡/真删」标签 + viewId/类名）→ 底部按钮（图层选择 / 清空 / 返回）。
 * 纯代码构建 UI；启动目标应用与 startPicker 在主线程，规则读写走 ConfigStore。
 */
public class AppDetailActivity extends Activity {

    public static final String EXTRA_PKG = "extra_pkg";
    public static final String EXTRA_LABEL = "extra_label";

    /** 深色主题色值（SPEC-v2）。 */
    private static final int COLOR_BG = 0xFF121212;
    private static final int COLOR_CARD = 0xFF1E1E1E;
    private static final int COLOR_CARD2 = 0xFF2A2A2A;
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_SUB = 0xFF9E9E9E;
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    private static final int COLOR_DANGER = 0xFFEF5350;
    private static final int COLOR_PRESSED = 0xFF2A2A2A;

    /** 规则创建时间格式（SPEC-v3 §3：yyyy-MM-dd HH:mm）。 */
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    private String pkg;
    private String label;

    private ListView lvRules;
    private TextView tvEmpty;
    private TextView tvSubtitle;
    private RuleAdapter adapter;
    private final List<Rule> rules = new ArrayList<Rule>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConfigStore.init(this);
        pkg = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null || pkg.length() == 0) {
            Toast.makeText(this, "缺少应用信息", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (label == null || label.length() == 0) {
            label = pkg;
        }
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadRules();
    }

    // ===================== UI 构建 =====================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // ---- 自绘标题栏（64dp）：返回 + 「规则 · 应用名」+ 副标题（包名·条数） ----
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(6), 0, dp(16), 0);

        TextView btnBackArrow = new TextView(this);
        btnBackArrow.setText("←");
        btnBackArrow.setTextSize(22f);
        btnBackArrow.setTextColor(COLOR_TEXT);
        btnBackArrow.setGravity(Gravity.CENTER);
        btnBackArrow.setPadding(dp(10), dp(8), dp(10), dp(8));
        titleBar.addView(btnBackArrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(4), 0, 0, 0);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("规则 · " + label);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(COLOR_TEXT);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSubtitle = new TextView(this);
        tvSubtitle.setText(pkg);
        tvSubtitle.setTextSize(12f);
        tvSubtitle.setTextColor(COLOR_SUB);
        tvSubtitle.setSingleLine(true);
        tvSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        tvSubtitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvSubtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleBar.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        // ---- 规则列表（行是圆角卡片，用背景色分隔条当 6dp 间距） ----
        FrameLayout listWrap = new FrameLayout(this);
        lvRules = new ListView(this);
        lvRules.setBackgroundColor(COLOR_BG);
        lvRules.setDivider(new ColorDrawable(COLOR_BG));
        lvRules.setDividerHeight(dp(6));
        lvRules.setSelector(new ColorDrawable(0x00000000));
        lvRules.setCacheColorHint(0x00000000);
        lvRules.setPadding(dp(12), 0, dp(12), 0);
        listWrap.addView(lvRules, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvEmpty = new TextView(this);
        tvEmpty.setText("还没有规则\n点下面「图层选择」，去目标应用里点选要处理的东西");
        tvEmpty.setTextSize(13f);
        tvEmpty.setTextColor(COLOR_SUB);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setLineSpacing(dp(4), 1f);
        tvEmpty.setPadding(dp(24), dp(24), dp(24), dp(24));
        listWrap.addView(tvEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        lvRules.setEmptyView(tvEmpty);

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(6);
        root.addView(listWrap, listLp);

        // ---- 底部按钮行：图层选择 / 清空 / 返回 ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        rowLp.bottomMargin = dp(8);
        rowLp.leftMargin = dp(12);
        rowLp.rightMargin = dp(12);
        root.addView(row, rowLp);

        Button btnPick = newButton("图层选择", COLOR_ACCENT, 0xFFFFFFFF);
        Button btnClear = newButton("清空", COLOR_CARD2, COLOR_TEXT);
        Button btnBack = newButton("返回", COLOR_CARD2, COLOR_TEXT);
        addToRow(row, btnPick);
        addToRow(row, btnClear);
        addToRow(row, btnBack);

        TextView footer = new TextView(this);
        footer.setText("点击规则切换启用/停用，长按删除。真删需 LSPosed 模块模式。");
        footer.setTextSize(10f);
        footer.setTextColor(COLOR_SUB);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 适配器与事件 ----
        adapter = new RuleAdapter();
        lvRules.setAdapter(adapter);
        lvRules.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Rule r = getRule(position);
                if (r == null) {
                    return;
                }
                boolean newValue = !r.enabled;
                ConfigStore.setRuleEnabled(r.id, newValue);
                reloadRules();
                toast((newValue ? "已启用：" : "已停用：") + r.describe());
            }
        });
        lvRules.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Rule r = getRule(position);
                if (r != null) {
                    confirmDelete(r);
                }
                return true;
            }
        });
        btnBackArrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickElement();
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        setContentView(root);
    }

    /** 深色按钮：底色可指定（强调色按钮用绿底白字）。 */
    private Button newButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinimumHeight(dp(40));
        b.setBackground(buttonBackground(bgColor));
        return b;
    }

    private Drawable buttonBackground(int color) {
        int pressedColor = (color == COLOR_ACCENT) ? 0xFF66BB6A : COLOR_PRESSED;
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, rounded(pressedColor, 8));
        sl.addState(new int[]{-android.R.attr.state_enabled}, rounded(COLOR_CARD, 8));
        sl.addState(new int[]{}, rounded(color, 8));
        return sl;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void addToRow(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        row.addView(b, lp);
    }

    // ===================== 规则列表 =====================

    private void reloadRules() {
        if (pkg == null) {
            return;
        }
        rules.clear();
        rules.addAll(ConfigStore.rulesFor(pkg));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText(pkg + " · 共 " + rules.size() + " 条规则");
        }
    }

    private Rule getRule(int position) {
        if (position < 0 || position >= rules.size()) {
            return null;
        }
        return rules.get(position);
    }

    private void confirmDelete(final Rule r) {
        new AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确定删除这条规则吗？\n\n" + r.describe())
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ConfigStore.deleteRule(r.id);
                        reloadRules();
                        toast("已删除规则");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClear() {
        if (rules.isEmpty()) {
            toast("本应用还没有规则");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空本应用规则")
                .setMessage("将删除「" + label + "」的 " + rules.size() + " 条规则，是否继续？")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ConfigStore.clearRules(pkg);
                        reloadRules();
                        toast("已清空本应用规则");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 图层选择 =====================

    /** 启动目标应用并让无障碍服务进入图层选择模式（v2 语义：startPicker(pkg)）。 */
    private void pickElement() {
        BlockerService svc = BlockerService.get();
        if (svc == null) {
            toast("请先开启无障碍服务，再回来点『图层选择』");
            return;
        }
        Intent launch = null;
        try {
            launch = getPackageManager().getLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
            launch = null;
        }
        if (launch == null) {
            toast("找不到该应用的启动入口");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
        } catch (Exception e) {
            toast("无法启动目标应用");
            return;
        }
        try {
            svc.startPicker(pkg);
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? "未知错误" : t.getMessage();
            toast("进入图层选择失败：" + msg);
        }
    }

    private static String simpleCls(String cls) {
        if (cls == null) {
            return "";
        }
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }

    /** viewId 缩写：com.foo:id/btn -> id/btn；无 '/' 时原样返回。 */
    private static String shortViewId(String viewId) {
        if (viewId == null || viewId.length() == 0) {
            return null;
        }
        String s = viewId.trim();
        if (s.length() == 0) {
            return null;
        }
        int colon = s.lastIndexOf(':');
        String tail = colon >= 0 ? s.substring(colon + 1) : s;
        if (tail.length() > 0 && tail.charAt(0) == '/') {
            tail = tail.substring(1);
        }
        if (tail.length() == 0) {
            return s;
        }
        return "id/" + tail;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** 规则列表适配器：CheckBox（只显示状态）+ 描述 + 副标题（模式标签 + viewId/类名）。 */
    private class RuleAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rules.size();
        }

        @Override
        public Rule getItem(int position) {
            return getRule(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout card = new LinearLayout(AppDetailActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackground(cardBackground());

                CheckBox cb = new CheckBox(AppDetailActivity.this);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setPadding(0, 0, 0, 0);
                if (Build.VERSION.SDK_INT >= 21) {
                    cb.setButtonTintList(checkedTint());
                }
                LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cbLp.gravity = Gravity.CENTER_VERTICAL;
                card.addView(cb, cbLp);

                LinearLayout texts = new LinearLayout(AppDetailActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(6), 0, 0, 0);
                TextView title = new TextView(AppDetailActivity.this);
                title.setTextSize(15f);
                title.setTextColor(COLOR_TEXT);
                texts.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView sub = new TextView(AppDetailActivity.this);
                sub.setTextSize(11f);
                sub.setTextColor(COLOR_SUB);
                sub.setSingleLine(false);
                sub.setPadding(0, dp(3), 0, 0);
                texts.addView(sub, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                // 第三行：类名简名 · 创建时间 · 坐标 + 尺寸（v3）。
                TextView meta = new TextView(AppDetailActivity.this);
                meta.setTextSize(10f);
                meta.setTextColor(COLOR_SUB);
                meta.setSingleLine(false);
                meta.setLineSpacing(dp(1), 1f);
                meta.setPadding(0, dp(2), 0, 0);
                texts.addView(meta, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                holder = new RowHolder();
                holder.checkBox = cb;
                holder.title = title;
                holder.sub = sub;
                holder.meta = meta;
                card.setTag(holder);
                convertView = card;
            } else {
                holder = (RowHolder) convertView.getTag();
            }
            Rule r = getRule(position);
            if (r == null) {
                return convertView;
            }
            holder.checkBox.setChecked(r.enabled);
            holder.title.setText(r.describe());

            boolean gone = r.isGone();
            // 第二行：模式标签（保留 v2）+ viewId + 类名简名。
            StringBuilder sb = new StringBuilder();
            sb.append(gone ? "真删" : "遮挡");
            if (!r.enabled) {
                sb.append(" · 已停用");
            }
            String vid = shortViewId(r.viewId);
            if (vid != null && vid.length() > 0) {
                sb.append(" · ").append(vid);
            }
            String cls = simpleCls(r.cls);
            if (cls != null && cls.length() > 0) {
                sb.append(" · ").append(cls);
            }
            holder.sub.setText(sb.toString());
            holder.sub.setTextColor(gone ? COLOR_DANGER : COLOR_SUB);

            // 第三行：类名简名 · yyyy-MM-dd HH:mm · (左,上) 宽×高（行高自适应）。
            StringBuilder meta = new StringBuilder();
            if (cls != null && cls.length() > 0) {
                meta.append(cls).append(" · ");
            }
            meta.append(r.created > 0L ? TIME_FMT.format(new Date(r.created)) : "时间未知");
            meta.append(" · (").append(r.l).append(',').append(r.t).append(") ")
                    .append(Math.max(0, r.r - r.l)).append('×').append(Math.max(0, r.b - r.t));
            holder.meta.setText(meta.toString());
            return convertView;
        }
    }

    /** 规则行卡片背景（圆角 12dp）；按下时略亮。 */
    private Drawable cardBackground() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, rounded(COLOR_PRESSED, 12));
        sl.addState(new int[]{}, rounded(COLOR_CARD, 12));
        return sl;
    }

    /** CheckBox 勾选色：选中绿色，未选中灰色。 */
    private ColorStateList checkedTint() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{COLOR_ACCENT, COLOR_SUB});
    }

    /** 规则行的复用控件。 */
    private static class RowHolder {
        CheckBox checkBox;
        TextView title;
        TextView sub;
        TextView meta;
    }
}
