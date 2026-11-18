package com.dsh.godmodelite;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.godmodelite.a11y.BlockerService;
import com.dsh.godmodelite.core.BackupManager;
import com.dsh.godmodelite.core.ConfigStore;
import com.dsh.godmodelite.core.RootHelper;
import com.dsh.godmodelite.core.Rule;

/**
 * 主界面（v3 深色）：
 * 标题栏 → 状态卡（root / 无障碍 / 规则统计 / 悬浮球 / LSPosed 五行）→
 * 按钮行（一键配置·无障碍设置·使用说明书 / 图层选择·悬浮球样式·悬浮球开关 /
 * 启用屏蔽·布局边界 / 排序·筛选·备份 / 恢复·刷新·打开 LSPosed）→ 搜索 → 应用列表 → 底部说明。
 *
 * v3 增量：状态卡信息更详细；应用列表排序（智能置顶分组 / 名称 / 规则数）、筛选、图标徽章、
 * 悬浮球样式设置；关闭主界面时仅本次隐藏悬浮球（onResume 恢复、onDestroy 且 isFinishing 隐藏）；
 * LSPosed 模块状态合并进状态卡（不再单独占一张卡片，把高度让给应用列表）。
 *
 * 全部 UI 用代码构建（无 XML，主题已是 Theme.Material.NoActionBar，自己画标题栏）；
 * 耗时操作（root、应用扫描、文件 IO）走子线程，结果回主线程。
 */
public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;

    // ===== SPEC-v2 深色色值 =====
    private static final int COLOR_BG = 0xFF121212;
    private static final int COLOR_CARD = 0xFF1E1E1E;
    private static final int COLOR_CARD2 = 0xFF2A2A2A;
    private static final int COLOR_DIVIDER = 0xFF2C2C2C;
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_SUB = 0xFF9E9E9E;
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    private static final int COLOR_DANGER = 0xFFEF5350;
    private static final int COLOR_INFO = 0xFF64B5F6;
    /** 灰点：状态未知（如 root 检测中）。 */
    private static final int COLOR_UNKNOWN = 0xFF616161;
    private static final int COLOR_PRESSED = 0xFF3A3A3A;
    /** 弹窗里选中项的背景（深绿）。 */
    private static final int COLOR_SELECTED = 0xFF263826;

    /** LSPosed 管理器包名（检测模块框架是否安装）。 */
    private static final String PKG_LSPOSED = "org.lsposed.manager";

    /** 悬浮球尺寸/透明度选项的取值（与 ConfigStore 常量对应）。 */
    private static final int[] SIZE_VALUES = {
            ConfigStore.BALL_SIZE_SMALL, ConfigStore.BALL_SIZE_MEDIUM, ConfigStore.BALL_SIZE_LARGE};
    private static final int[] ALPHA_VALUES = {
            ConfigStore.BALL_ALPHA_LOW, ConfigStore.BALL_ALPHA_MEDIUM, ConfigStore.BALL_ALPHA_HIGH};

    private StatusRow stRoot;
    private StatusRow stA11y;
    private StatusRow stRules;
    private StatusRow stBall;
    private StatusRow stLsposed;

    private Switch swBall;
    private Switch swBlocking;
    /** 系统布局边界开关（root，全局 debug.layout） */
    private Switch swLayoutBounds;
    /** 布局边界状态检测中，避免重复起线程 */
    private boolean checkingLayoutBounds;
    private Button btnConfigure;
    private Button btnBackup;
    private Button btnSort;
    private Button btnFilter;
    private Button btnBallStyle;
    private Button btnLsposed;
    private EditText etSearch;
    private ListView lvApps;
    private TextView tvEmpty;

    private AppListAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppListAdapter.AppEntry> allApps = new ArrayList<AppListAdapter.AppEntry>();
    /** 每个包的规则统计：包名 -> [遮挡数, 真删数]（一次遍历规则后填充）。 */
    private final Map<String, int[]> ruleCounts = new HashMap<String, int[]>();

    private boolean loadingApps;
    private boolean checkingRoot;
    private boolean updatingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConfigStore.init(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshModuleCard();
        checkRootAsync();
        loadAppsAsync();
        // v3「关闭即隐藏」：回到主界面且配置为显示时，恢复本次运行的悬浮球（不写配置）。
        BlockerService svc = BlockerService.get();
        if (svc != null && ConfigStore.isBallVisible()) {
            try {
                svc.setBallVisible(true);
            } catch (Throwable ignored) {
                // 服务实现异常时忽略，不影响界面。
            }
        }
        // 从无障碍设置页返回时设置可能稍晚写入，再补一次刷新。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    refreshStatus();
                }
            }
        }, 800L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        // v3「关闭即隐藏」：返回键 / 最近任务划掉（finish）时只隐藏本次运行的悬浮球，不写配置；
        // 旋转等配置变化不触发（避免无谓隐藏，下次 onResume 也会恢复）。
        if (!isChangingConfigurations()) {
            BlockerService svc = BlockerService.get();
            if (svc != null) {
                try {
                    svc.hideBallForAppExit();
                } catch (Throwable ignored) {
                    // 服务未实现或异常时忽略（不影响退出）。
                }
            }
        }
        if (adapter != null) {
            adapter.release();
        }
        super.onDestroy();
    }

    // ===================== UI 构建 =====================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // ---- 1. 自绘标题栏（64dp）：左标题 + 右小字 ----
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(16), 0, dp(16), 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("上帝模式 Lite");
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(COLOR_TEXT);
        tvTitle.setSingleLine(true);
        titleBar.addView(tvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("免框架 · root 模式");
        tvSubtitle.setTextSize(12f);
        tvSubtitle.setTextColor(COLOR_SUB);
        tvSubtitle.setSingleLine(true);
        tvSubtitle.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(tvSubtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        // ---- 2. 状态卡：root / 无障碍 / 规则统计 / 悬浮球 / LSPosed（v3 五行）----
        LinearLayout card = newCard();
        stRoot = addStatusRow(card, "root：检测中");
        stA11y = addStatusRow(card, "无障碍服务：检测中");
        stRules = addStatusRow(card, "规则：共 0 · 遮挡 0 · 真删 0");
        stBall = addStatusRow(card, "悬浮球：检测中");
        stLsposed = addStatusRow(card, "LSPosed：检测中");
        root.addView(card, blockLp(10));

        // ---- 3. 按钮行1：一键配置 / 打开无障碍设置 / 使用说明书 ----
        LinearLayout row1 = newRow();
        btnConfigure = newButton("一键配置（root）", COLOR_CARD2, COLOR_TEXT);
        Button btnA11ySettings = newButton("打开无障碍设置", COLOR_CARD2, COLOR_TEXT);
        Button btnHelp = newButton("使用说明书", COLOR_CARD2, COLOR_TEXT);
        addToRow(row1, btnConfigure);
        addToRow(row1, btnA11ySettings);
        addToRow(row1, btnHelp);
        root.addView(row1, blockLp(8));

        // ---- 4. 按钮行2：图层选择 + 悬浮球样式 + 悬浮球开关 ----
        LinearLayout row2 = newRow();
        Button btnLayer = newButton("图层选择", COLOR_ACCENT, 0xFFFFFFFF);
        addToRow(row2, btnLayer);

        btnBallStyle = newButton("悬浮球样式", COLOR_CARD2, COLOR_TEXT);
        addToRow(row2, btnBallStyle);

        swBall = new Switch(this);
        swBall.setText("悬浮球");
        swBall.setTextSize(13f);
        swBall.setTextColor(COLOR_TEXT);
        swBall.setPadding(dp(8), 0, dp(4), 0);
        swBall.setBackground(rounded(COLOR_CARD2, 8));
        swBall.setButtonTintList(tint(COLOR_ACCENT, 0xFF616161));
        LinearLayout.LayoutParams ballLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ballLp.leftMargin = dp(6);
        ballLp.gravity = Gravity.CENTER_VERTICAL;
        row2.addView(swBall, ballLp);
        root.addView(row2, blockLp(8));

        // ---- 5. 开关行：启用屏蔽 + 系统布局边界（root，全局 debug.layout） ----
        LinearLayout switchRow = newRow();
        swBlocking = new Switch(this);
        swBlocking.setText("启用屏蔽");
        swBlocking.setTextSize(12f);
        swBlocking.setTextColor(COLOR_TEXT);
        swBlocking.setButtonTintList(tint(COLOR_ACCENT, 0xFF616161));
        switchRow.addView(swBlocking, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        swLayoutBounds = new Switch(this);
        swLayoutBounds.setText("布局边界");
        swLayoutBounds.setTextSize(12f);
        swLayoutBounds.setTextColor(COLOR_TEXT);
        swLayoutBounds.setButtonTintList(tint(COLOR_ACCENT, 0xFF616161));
        switchRow.addView(swLayoutBounds, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(switchRow, blockLp(4));

        // ---- 6. 按钮行3：排序 / 筛选 / 备份规则（v3 新增排序、筛选） ----
        LinearLayout row3 = newRow();
        btnSort = newButton("排序·智能", COLOR_CARD2, COLOR_TEXT);
        btnFilter = newButton("筛选·全部", COLOR_CARD2, COLOR_TEXT);
        btnBackup = newButton("备份规则", COLOR_CARD2, COLOR_TEXT);
        addToRow(row3, btnSort);
        addToRow(row3, btnFilter);
        addToRow(row3, btnBackup);
        root.addView(row3, blockLp(8));

        // ---- 7. 按钮行4：恢复 / 刷新 / 打开 LSPosed ----
        LinearLayout row4 = newRow();
        Button btnRestore = newButton("恢复规则", COLOR_CARD2, COLOR_TEXT);
        Button btnRefresh = newButton("刷新", COLOR_CARD2, COLOR_TEXT);
        btnLsposed = newButton("打开 LSPosed", COLOR_CARD2, COLOR_INFO);
        btnLsposed.setTextSize(12f);
        addToRow(row4, btnRestore);
        addToRow(row4, btnRefresh);
        addToRow(row4, btnLsposed);
        root.addView(row4, blockLp(8));

        // ---- 8. 搜索框（深色圆角）----
        etSearch = new EditText(this);
        etSearch.setSingleLine(true);
        etSearch.setHint("搜索应用名或包名");
        etSearch.setHintTextColor(COLOR_SUB);
        etSearch.setTextColor(COLOR_TEXT);
        etSearch.setTextSize(14f);
        etSearch.setBackground(rounded(COLOR_CARD, 12));
        etSearch.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(etSearch, blockLp(8));

        // ---- 9. 应用列表（空状态用 TextView 叠加）----
        FrameLayout listWrap = new FrameLayout(this);
        lvApps = new ListView(this);
        lvApps.setBackgroundColor(COLOR_BG);
        lvApps.setDivider(new ColorDrawable(COLOR_DIVIDER));
        lvApps.setDividerHeight(dp(1));
        lvApps.setSelector(new ColorDrawable(0x00000000));
        lvApps.setCacheColorHint(0x00000000);
        lvApps.setItemsCanFocus(false);
        listWrap.addView(lvApps, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvEmpty = new TextView(this);
        tvEmpty.setText("正在加载应用列表…");
        tvEmpty.setTextSize(13f);
        tvEmpty.setTextColor(COLOR_SUB);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(16), dp(24), dp(16), dp(24));
        listWrap.addView(tvEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        lvApps.setEmptyView(tvEmpty);

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.leftMargin = dp(12);
        listLp.rightMargin = dp(12);
        listLp.topMargin = dp(6);
        root.addView(listWrap, listLp);

        // ---- 10. 底部说明 ----
        TextView footer = new TextView(this);
        footer.setText("跨进程真删需 LSPosed 模块模式；root 仅用于自动配置。");
        footer.setTextSize(10f);
        footer.setTextColor(COLOR_SUB);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 适配器与监听 ----
        adapter = new AppListAdapter(this);
        lvApps.setAdapter(adapter);
        lvApps.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 分组标题行不可点击（adapter.isEnabled=false），这里再兜一层。
                AppListAdapter.AppEntry e = adapter.getAppEntry(position);
                if (e != null) {
                    openDetail(e);
                }
            }
        });

        btnConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doAutoConfigure();
            }
        });
        btnA11ySettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openA11ySettings();
            }
        });
        btnLayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLayerPicker();
            }
        });
        btnBallStyle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBallStyleDialog();
            }
        });
        btnSort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterDialog();
            }
        });
        btnHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHelp();
            }
        });
        btnBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doBackup();
            }
        });
        btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doRestore();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在刷新应用列表…");
                loadAppsAsync();
            }
        });
        btnLsposed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLsposed();
            }
        });
        swBall.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingUi) {
                    return;
                }
                onBallSwitchChanged(isChecked);
            }
        });
        swBlocking.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingUi) {
                    return;
                }
                ConfigStore.setBlockingEnabled(isChecked);
                toast(isChecked ? "已启用屏蔽" : "已关闭屏蔽");
            }
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });

        swLayoutBounds.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                onLayoutBoundsChanged(isChecked);
            }
        });

        setContentView(root);
        updateSortFilterButtons();
    }

    /** 深色卡片：圆角 12dp + 内边距。 */
    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD, 12));
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        return card;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** 整块内容的布局参数：左右 12dp，上边距 topDp。 */
    private LinearLayout.LayoutParams blockLp(float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /** 状态行的圆点与文字。 */
    private static class StatusRow {
        View dot;
        GradientDrawable dotBg;
        TextView text;
    }

    private StatusRow addStatusRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(COLOR_UNKNOWN);
        View dot = new View(this);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(9);
        row.addView(dot, dotLp);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13f);
        text.setTextColor(COLOR_TEXT);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StatusRow holder = new StatusRow();
        holder.dot = dot;
        holder.dotBg = dotBg;
        holder.text = text;
        return holder;
    }

    private void setStatus(StatusRow row, String text, int dotColor) {
        if (row == null) {
            return;
        }
        row.text.setText(text);
        row.dotBg.setColor(dotColor);
    }

    /** 深色按钮：圆角 8dp，按下变亮，禁用变暗。 */
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
        // 按下反馈：绿底变亮绿，深色按钮变成 #3A3A3A。
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

    /** Switch/CheckBox 的 tint：选中绿色，未选中深灰。 */
    private ColorStateList tint(int checkedColor, int normalColor) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{checkedColor, normalColor});
    }

    private void addToRow(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        row.addView(b, lp);
    }

    // ===================== 状态刷新 =====================

    private void refreshStatus() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        int total = refreshRuleStats();

        boolean a11y = RootHelper.isA11yEnabled(this);
        BlockerService svc = BlockerService.get();
        if (svc != null) {
            setStatus(stA11y, "无障碍服务：已开启", COLOR_ACCENT);
        } else if (a11y) {
            setStatus(stA11y, "无障碍服务：已开启（未连接）", COLOR_DANGER);
        } else {
            setStatus(stA11y, "无障碍服务：未开启", COLOR_DANGER);
        }

        // 悬浮球行：显示状态取 ConfigStore + 服务是否在线；样式取配置。
        boolean ballVisible = ConfigStore.isBallVisible();
        String styleName = ConfigStore.ballStyleName(ConfigStore.getBallStyle());
        if (svc == null) {
            setStatus(stBall, "悬浮球：服务未连接 · 样式：" + styleName, COLOR_SUB);
        } else if (ballVisible) {
            setStatus(stBall, "悬浮球：显示中 · 样式：" + styleName, COLOR_ACCENT);
        } else {
            setStatus(stBall, "悬浮球：已隐藏 · 样式：" + styleName, COLOR_SUB);
        }

        updatingUi = true;
        swBlocking.setChecked(ConfigStore.isBlockingEnabled());
        // 悬浮球状态以 ConfigStore 为准；服务在线时同步一次，避免服务端状态与开关不一致。
        if (svc != null) {
            try {
                svc.setBallVisible(ballVisible);
            } catch (Throwable ignored) {
                // 服务实现异常时忽略，不影响开关本身。
            }
        }
        swBall.setChecked(ballVisible);
        updatingUi = false;

        if (btnBackup != null) {
            btnBackup.setEnabled(total > 0);
        }
        applyFilter();
        refreshLayoutBoundsStatus();
        updateSortFilterButtons();
    }

    /**
     * 一次遍历全部规则：同时得到状态卡的「遮挡 / 真删」条数和每个应用的徽章统计，
     * 返回规则总数（避免状态卡再单独遍历一次）。
     */
    private int refreshRuleStats() {
        List<Rule> all = ConfigStore.allRules();
        ruleCounts.clear();
        int mask = 0;
        int gone = 0;
        for (int i = 0; i < all.size(); i++) {
            Rule r = all.get(i);
            if (r == null || r.pkg == null || r.pkg.length() == 0) {
                continue;
            }
            int[] c = ruleCounts.get(r.pkg);
            if (c == null) {
                c = new int[2];
                ruleCounts.put(r.pkg, c);
            }
            if (r.isGone()) {
                c[1]++;
                gone++;
            } else {
                c[0]++;
                mask++;
            }
        }
        int total = mask + gone;
        setStatus(stRules, "规则：共 " + total + " · 遮挡 " + mask + " · 真删 " + gone,
                total > 0 ? COLOR_ACCENT : COLOR_SUB);
        // 把统计写进应用行（getView 里不再查 ConfigStore）。
        for (int i = 0; i < allApps.size(); i++) {
            AppListAdapter.AppEntry e = allApps.get(i);
            int[] c = ruleCounts.get(e.pkg);
            e.maskCount = c == null ? 0 : c[0];
            e.goneCount = c == null ? 0 : c[1];
        }
        return total;
    }

    private void updateSortFilterButtons() {
        if (btnSort != null) {
            btnSort.setText("排序·" + ConfigStore.sortModeName(ConfigStore.getSortMode()));
        }
        if (btnFilter != null) {
            btnFilter.setText("筛选·" + ConfigStore.filterModeName(ConfigStore.getFilterMode()));
        }
    }

    private void checkRootAsync() {
        if (checkingRoot) {
            return;
        }
        checkingRoot = true;
        setStatus(stRoot, "root：检测中", COLOR_UNKNOWN);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean root = RootHelper.isRoot();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        checkingRoot = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        setStatus(stRoot, "root：" + (root ? "已授权" : "未授权"),
                                root ? COLOR_ACCENT : COLOR_DANGER);
                    }
                });
            }
        }).start();
    }

    /** 异步读取系统布局边界状态并同步到开关（root，避免卡主线程）。 */
    private void refreshLayoutBoundsStatus() {
        if (swLayoutBounds == null || checkingLayoutBounds || checkingRoot) {
            return;
        }
        checkingLayoutBounds = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean on = RootHelper.isLayoutBoundsEnabled();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        checkingLayoutBounds = false;
                        if (isFinishing() || isDestroyed() || swLayoutBounds == null) {
                            return;
                        }
                        updatingUi = true;
                        swLayoutBounds.setChecked(on);
                        updatingUi = false;
                    }
                });
            }
        }).start();
    }

    /** 系统布局边界开关：root 执行 setprop debug.layout，全局生效。 */
    private void onLayoutBoundsChanged(boolean on) {
        if (updatingUi) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootHelper.setLayoutBounds(on);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (ok) {
                            toast(on ? "已开启系统布局边界：全局显示控件边框（重启自动关闭）"
                                    : "已关闭系统布局边界");
                        } else {
                            toast("设置失败：请确认已授予 root");
                        }
                        refreshLayoutBoundsStatus();
                    }
                });
            }
        }).start();
    }

    /** 悬浮球开关：写 ConfigStore，并通知在线服务实时生效。 */
    private void onBallSwitchChanged(boolean on) {
        ConfigStore.setBallVisible(on);
        BlockerService svc = BlockerService.get();
        if (svc == null) {
            toast(on ? "已开启悬浮球：无障碍服务启动后显示" : "已隐藏悬浮球：无障碍服务启动后生效");
            refreshStatus();
            return;
        }
        try {
            svc.setBallVisible(on);
            toast(on ? "已显示悬浮球" : "已隐藏悬浮球");
        } catch (Throwable t) {
            toast("悬浮球设置失败：服务暂不可用");
        }
        refreshStatus();
    }

    // ===================== 悬浮球样式设置（v3） =====================

    private void showBallStyleDialog() {
        final int[] sel = {
                ConfigStore.getBallStyle(),
                sizeIndex(ConfigStore.getBallSizeDp()),
                alphaIndex(ConfigStore.getBallAlpha())};
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(2), dp(16), dp(2));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addOptionGroup(content, "样式", new String[]{
                "眼睛（默认）", "圆点", "盾牌", "文字「隐」", "禁止斜杠"}, sel, 0);
        addOptionGroup(content, "尺寸", new String[]{
                "小 40dp", "中 48dp（默认）", "大 56dp"}, sel, 1);
        addOptionGroup(content, "透明度", new String[]{
                "低 150", "中 200", "高 235（默认）"}, sel, 2);

        new AlertDialog.Builder(this)
                .setTitle("悬浮球样式")
                .setView(scroll)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ConfigStore.setBallStyle(sel[0]);
                        ConfigStore.setBallSizeDp(SIZE_VALUES[sel[1]]);
                        ConfigStore.setBallAlpha(ALPHA_VALUES[sel[2]]);
                        applyBallSettingsToService();
                        refreshStatus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 弹窗里的一组单选：标题 + 每行最多 3 个并排的选项按钮（选中高亮绿框），
     * 这样 5 种样式 + 3 档尺寸 + 3 档透明度在弹窗里只需要 4 行，不需要很高的滚动容器。
     */
    private void addOptionGroup(LinearLayout parent, String title, String[] labels,
                                final int[] sel, final int group) {
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(12f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(COLOR_SUB);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        parent.addView(tvTitle, titleLp);

        final TextView[] views = new TextView[labels.length];
        final int perRow = 3;
        for (int start = 0; start < labels.length; start += perRow) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            int end = Math.min(start + perRow, labels.length);
            for (int i = start; i < end; i++) {
                final int index = i;
                TextView tv = new TextView(this);
                tv.setText(labels[i]);
                tv.setTextSize(13f);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(6), dp(9), dp(6), dp(9));
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sel[group] = index;
                        refreshOptionGroup(views, sel[group]);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                line.addView(tv, lp);
                views[i] = tv;
            }
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lineLp.topMargin = dp(4);
            parent.addView(line, lineLp);
        }
        refreshOptionGroup(views, sel[group]);
    }

    /** 刷新一组选项的选中样式。 */
    private void refreshOptionGroup(TextView[] views, int selected) {
        if (views == null) {
            return;
        }
        for (int i = 0; i < views.length; i++) {
            boolean on = (i == selected);
            TextView tv = views[i];
            if (tv == null) {
                continue;
            }
            tv.setTextColor(on ? COLOR_ACCENT : COLOR_TEXT);
            tv.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? COLOR_SELECTED : COLOR_CARD2);
            bg.setCornerRadius(dp(8));
            if (on) {
                bg.setStroke(dp(1), COLOR_ACCENT);
            }
            tv.setBackground(bg);
        }
    }

    /** 写配置后立即刷新在线服务的悬浮球；服务不在线只提示已保存。 */
    private void applyBallSettingsToService() {
        BlockerService svc = BlockerService.get();
        if (svc == null) {
            toast("已保存悬浮球样式：无障碍服务未连接，开启后生效");
            return;
        }
        try {
            svc.applyBallSettings();
            toast("已应用悬浮球样式");
        } catch (Throwable t) {
            toast("样式已保存，但通知服务失败");
        }
    }

    private static int sizeIndex(int sizeDp) {
        if (sizeDp <= ConfigStore.BALL_SIZE_SMALL) {
            return 0;
        }
        if (sizeDp >= ConfigStore.BALL_SIZE_LARGE) {
            return 2;
        }
        return 1;
    }

    private static int alphaIndex(int alpha) {
        if (alpha <= ConfigStore.BALL_ALPHA_LOW) {
            return 0;
        }
        if (alpha >= ConfigStore.BALL_ALPHA_HIGH) {
            return 2;
        }
        return 1;
    }

    // ===================== 排序 / 筛选（v3） =====================

    private void showSortDialog() {
        final int current = ConfigStore.getSortMode();
        String[] items = {"智能排序（有规则的置顶 + 分组）", "按名称排序", "按规则数排序（多→少）"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ConfigStore.setSortMode(which);
                        updateSortFilterButtons();
                        applyFilter();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFilterDialog() {
        final int current = ConfigStore.getFilterMode();
        String[] items = {"全部应用", "仅显示有规则的应用"};
        new AlertDialog.Builder(this)
                .setTitle("筛选")
                .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ConfigStore.setFilterMode(which);
                        updateSortFilterButtons();
                        applyFilter();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 使用说明书（v3） =====================

    private void openHelp() {
        try {
            startActivity(new Intent(this, HelpActivity.class));
        } catch (Exception e) {
            toast("无法打开使用说明书");
        }
    }

    // ===================== LSPosed 模块状态 =====================

    private boolean isPackageInstalled(String pkgName) {
        try {
            getPackageManager().getPackageInfo(pkgName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshModuleCard() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean installed = isPackageInstalled(PKG_LSPOSED);
        if (installed) {
            // v3：模块状态合并进状态卡，不再单独占一整张卡片（省出列表高度）。
            setStatus(stLsposed, "LSPosed：已安装 · 勾选作用域可真删", COLOR_ACCENT);
            btnLsposed.setVisibility(View.VISIBLE);
        } else {
            setStatus(stLsposed, "LSPosed：未安装（仅支持遮挡）", COLOR_SUB);
            btnLsposed.setVisibility(View.GONE);
        }
    }

    private void openLsposed() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(PKG_LSPOSED);
            if (i == null) {
                toast("找不到 LSPosed 管理器");
                return;
            }
            startActivity(i);
        } catch (Exception e) {
            toast("无法打开 LSPosed 管理器");
        }
    }

    // ===================== 图层选择 =====================

    /** 进入图层选择模式（由无障碍服务绘制覆盖层）。服务未连接时必须提示，不能崩。 */
    private void startLayerPicker() {
        BlockerService svc = BlockerService.get();
        if (svc == null) {
            toast("请先开启无障碍服务，再点『图层选择』");
            return;
        }
        try {
            svc.startPicker(null);
            toast("已进入图层选择：切到目标应用后点选要处理的元素");
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? "未知错误" : t.getMessage();
            toast("进入图层选择失败：" + msg);
        }
    }

    // ===================== 应用列表 =====================

    /** 子线程：PackageManager 取有启动入口的应用，按中文名排序，过滤自己。 */
    private List<AppListAdapter.AppEntry> queryLaunchableApps() {
        List<AppListAdapter.AppEntry> result = new ArrayList<AppListAdapter.AppEntry>();
        final String self = getPackageName();
        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            if (list != null) {
                Set<String> seen = new HashSet<String>();
                for (int i = 0; i < list.size(); i++) {
                    ResolveInfo ri = list.get(i);
                    if (ri == null || ri.activityInfo == null) {
                        continue;
                    }
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.length() == 0 || pkg.equals(self) || seen.contains(pkg)) {
                        continue;
                    }
                    seen.add(pkg);
                    CharSequence label = ri.loadLabel(pm);
                    String name = label == null ? pkg : label.toString();
                    result.add(new AppListAdapter.AppEntry(pkg, name));
                }
            }
        } catch (Exception ignored) {
            // 查询失败时返回已收集到的部分，界面仍可用。
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(result, new Comparator<AppListAdapter.AppEntry>() {
            @Override
            public int compare(AppListAdapter.AppEntry a, AppListAdapter.AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return result;
    }

    private void loadAppsAsync() {
        if (loadingApps) {
            return;
        }
        loadingApps = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppListAdapter.AppEntry> loaded = queryLaunchableApps();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingApps = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        // allApps 只在数据真的变化时重建（这里是唯一重建点）。
                        allApps.clear();
                        allApps.addAll(loaded);
                        refreshRuleStats();
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    /**
     * 按搜索框 + 筛选方式过滤，按排序方式排序，SORT_SMART 时插入分组标题行。
     * 搜索、筛选、排序三者叠加生效。
     */
    private void applyFilter() {
        if (adapter == null || etSearch == null) {
            return;
        }
        String q = etSearch.getText() == null
                ? "" : etSearch.getText().toString().trim().toLowerCase(Locale.US);
        final int filterMode = ConfigStore.getFilterMode();
        final int sortMode = ConfigStore.getSortMode();

        List<AppListAdapter.AppEntry> filtered = new ArrayList<AppListAdapter.AppEntry>();
        for (int i = 0; i < allApps.size(); i++) {
            AppListAdapter.AppEntry e = allApps.get(i);
            if (filterMode == ConfigStore.FILTER_WITH_RULES && e.totalCount() == 0) {
                continue;
            }
            if (q.length() == 0
                    || e.label.toLowerCase(Locale.US).indexOf(q) >= 0
                    || e.pkg.toLowerCase(Locale.US).indexOf(q) >= 0) {
                filtered.add(e);
            }
        }

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(filtered, new Comparator<AppListAdapter.AppEntry>() {
            @Override
            public int compare(AppListAdapter.AppEntry a, AppListAdapter.AppEntry b) {
                if (sortMode == ConfigStore.SORT_RULES) {
                    int c = b.totalCount() - a.totalCount();
                    if (c != 0) {
                        return c;
                    }
                } else if (sortMode == ConfigStore.SORT_SMART) {
                    // 有规则的排前面（分组标题行再细分）。
                    boolean aHas = a.totalCount() > 0;
                    boolean bHas = b.totalCount() > 0;
                    if (aHas != bHas) {
                        return aHas ? -1 : 1;
                    }
                }
                return collator.compare(a.label, b.label);
            }
        });

        List<AppListAdapter.ListRow> rows = new ArrayList<AppListAdapter.ListRow>();
        if (sortMode == ConfigStore.SORT_SMART) {
            List<AppListAdapter.AppEntry> configured = new ArrayList<AppListAdapter.AppEntry>();
            List<AppListAdapter.AppEntry> others = new ArrayList<AppListAdapter.AppEntry>();
            for (int i = 0; i < filtered.size(); i++) {
                AppListAdapter.AppEntry e = filtered.get(i);
                if (e.totalCount() > 0) {
                    configured.add(e);
                } else {
                    others.add(e);
                }
            }
            if (!configured.isEmpty()) {
                rows.add(AppListAdapter.ListRow.header("已配置的应用（" + configured.size() + "）"));
                for (int i = 0; i < configured.size(); i++) {
                    rows.add(AppListAdapter.ListRow.app(configured.get(i)));
                }
            }
            if (!others.isEmpty()) {
                rows.add(AppListAdapter.ListRow.header("其他应用（" + others.size() + "）"));
                for (int i = 0; i < others.size(); i++) {
                    rows.add(AppListAdapter.ListRow.app(others.get(i)));
                }
            }
        } else {
            for (int i = 0; i < filtered.size(); i++) {
                rows.add(AppListAdapter.ListRow.app(filtered.get(i)));
            }
        }
        adapter.setRows(rows);

        if (allApps.isEmpty()) {
            tvEmpty.setText(loadingApps ? "正在加载应用列表…" : "没有找到可启动的应用");
        } else if (filtered.isEmpty()) {
            tvEmpty.setText(q.length() > 0 ? "没有匹配的应用" : "当前筛选下没有应用");
        }
    }

    private void openDetail(AppListAdapter.AppEntry e) {
        Intent i = new Intent(this, AppDetailActivity.class);
        i.putExtra(AppDetailActivity.EXTRA_PKG, e.pkg);
        i.putExtra(AppDetailActivity.EXTRA_LABEL, e.label);
        try {
            startActivity(i);
        } catch (Exception ex) {
            toast("无法打开规则页");
        }
    }

    // ===================== 一键配置 =====================

    private void doAutoConfigure() {
        btnConfigure.setEnabled(false);
        btnConfigure.setText("配置中…");
        toast("正在请求 root 权限…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootHelper.autoConfigure(MainActivity.this);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        btnConfigure.setEnabled(true);
                        btnConfigure.setText("一键配置（root）");
                        refreshStatus();
                        toast(ok ? "已自动配置：无障碍服务 + 悬浮窗权限"
                                : "自动配置失败：请确认已授予 root，或手动打开无障碍设置");
                        // 设置写入后系统可能稍有延迟，稍后再刷一次。
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && !isDestroyed()) {
                                    refreshStatus();
                                }
                            }
                        }, 1000L);
                    }
                });
            }
        }).start();
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("无法打开无障碍设置页");
        }
    }

    // ===================== 备份 / 恢复 =====================

    private void doBackup() {
        List<Rule> rules = ConfigStore.allRules();
        if (rules.isEmpty()) {
            toast("还没有规则可备份");
            return;
        }
        String fileName = "godmode-rules-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("无法打开文件保存界面");
        }
    }

    private void doRestore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法打开文件选择界面");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            exportTo(uri);
        } else if (requestCode == REQ_IMPORT) {
            importFrom(uri);
        }
    }

    /** 子线程写备份文件（mode 字段随 Rule.toJson 一起导出），完成后回主线程提示。 */
    private void exportTo(final Uri uri) {
        final List<Rule> snapshot = ConfigStore.allRules();
        final boolean blocking = ConfigStore.isBlockingEnabled();
        final ContentResolver cr = getContentResolver();
        final String name = uriName(uri);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    BackupManager.export(cr, uri, snapshot, blocking);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "写入文件失败" : e.getMessage();
                }
                final String fErr = err;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (fErr != null) {
                            toast("备份失败：" + fErr);
                        } else {
                            toast("已备份 " + snapshot.size() + " 条规则到 " + name);
                        }
                    }
                });
            }
        }).start();
    }

    /** 子线程读备份文件（兼容原版 ZIP），回主线程弹确认框。 */
    private void importFrom(final Uri uri) {
        final ContentResolver cr = getContentResolver();
        new Thread(new Runnable() {
            @Override
            public void run() {
                BackupManager.ImportResult result = null;
                String err = null;
                try {
                    result = BackupManager.importBackup(cr, uri);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "无法解析备份文件" : e.getMessage();
                }
                final BackupManager.ImportResult fResult = result;
                final String fErr = err;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (fResult == null) {
                            toast("恢复失败：" + fErr);
                            return;
                        }
                        showRestoreDialog(fResult);
                    }
                });
            }
        }).start();
    }

    private void showRestoreDialog(BackupManager.ImportResult result) {
        final List<Rule> rules = result.rules;
        final boolean blocking = result.hasBlocking ? result.blocking : ConfigStore.isBlockingEnabled();
        final String message;
        final String positive;
        if (rules.isEmpty()) {
            message = result.formatLabel()
                    + "\n\n备份里没有可恢复的规则（共 0 条），继续将清空当前全部规则，是否继续？";
            positive = "清空并继续";
        } else {
            message = result.formatLabel()
                    + "\n\n将用备份中的 " + rules.size() + " 条规则替换当前规则，是否继续？";
            positive = "继续";
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复规则")
                .setMessage(message)
                .setPositiveButton(positive, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ConfigStore.replaceAll(rules, blocking);
                        refreshStatus();
                        toast(rules.isEmpty() ? "已清空全部规则" : "已恢复 " + rules.size() + " 条规则");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String uriName(Uri uri) {
        String name = null;
        try {
            name = uri.getLastPathSegment();
        } catch (Exception ignored) {
            // 忽略。
        }
        if (name == null || name.length() == 0) {
            name = "备份文件";
        }
        return name;
    }

    // ===================== 小工具 =====================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
