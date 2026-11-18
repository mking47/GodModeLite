package com.dsh.godmodelite;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 使用说明书（v3，SPEC-v3 §5）：深色页面 = 自绘标题栏（← 返回 + 标题/副标题）+ ScrollView 分节卡片。
 *
 * 内容 7 节：两种模式 / 安装与一键配置 / 悬浮球操作 / 图层选择与遮挡·真删 /
 * LSPosed 启用步骤 / 备份恢复 / 常见问题。
 * 纯代码构建，无 XML、无 AndroidX；以 "$ " 开头的段落按命令块（等宽字体 + 可长按复制）渲染。
 */
public class HelpActivity extends Activity {

    /** 深色主题色值（SPEC-v2/v3）。 */
    private static final int COLOR_BG = 0xFF121212;
    private static final int COLOR_CARD = 0xFF1E1E1E;
    private static final int COLOR_CARD2 = 0xFF2A2A2A;
    private static final int COLOR_TEXT = 0xFFF2F2F2;
    private static final int COLOR_SUB = 0xFF9E9E9E;
    private static final int COLOR_ACCENT = 0xFF4CAF50;

    /** 说明书内容：每节 = {标题, 段落, 段落, ...}；段落以 "$ " 开头时按命令块渲染。 */
    private static final String[][] SECTIONS = {
            {
                    "1. 两种模式",
                    "root 免框架模式：不需要装任何框架。只能「遮挡」元素——在目标元素上面盖一层白色蒙版并拦截点击。"
                            + "Android 的进程隔离决定了第三方应用无法进入别的应用进程改 View，所以 root 也不能真删。",
                    "LSPosed 模块模式：本应用同时是一个 Xposed 模块。在 LSPosed 里启用并勾选目标应用后，"
                            + "模块代码运行在目标应用进程内，可以把 View 真正 setVisibility(GONE)，也就是「真删」。",
                    "两种模式可以同时用：同一批规则，遮挡在任意模式都生效；"
                            + "真删只在模块已启用、作用域已勾选、且目标应用进程重启过之后生效。"
            },
            {
                    "2. 安装与一键配置",
                    "没有 root：打开系统「设置 → 无障碍 → 已安装的服务 / 下载的服务 → 上帝模式 Lite」，打开开关。"
                            + "Android 7.1 及以下还需要授予「显示在其他应用上层（悬浮窗）」权限。",
                    "有 root：主界面点「一键配置（root）」，会自动执行下面三条命令（也可以用 root 终端手动执行）：",
                    "$ settings put secure enabled_accessibility_services com.dsh.godmodelite/com.dsh.godmodelite.a11y.BlockerService\n"
                            + "$ settings put secure accessibility_enabled 1\n"
                            + "$ appops set com.dsh.godmodelite SYSTEM_ALERT_WINDOW allow",
                    "第一条如果系统里已经有别的无障碍服务，要保留原来的列表并用冒号拼接（如 A:B:我们的组件），"
                            + "直接覆盖会把其它服务关掉。",
                    "「系统布局边界（root）」= setprop debug.layout true：让系统给所有应用画出控件边框，"
                            + "配合图层选择可以看清每个控件的范围。它是全局属性，重启手机自动关闭，看完记得关掉。"
            },
            {
                    "3. 悬浮球操作",
                    "拖动：按住球移动，松手后记住位置（越界会裁剪回屏幕内）。",
                    "单击：进入图层选择模式（无障碍服务在线时）；长按：弹出菜单（打开上帝模式 Lite / 关闭悬浮球 等）。",
                    "v4 起悬浮球默认关闭：只有在主界面手动打开「悬浮球」开关才会显示；"
                            + "关闭后不会在任何界面出现（即使无障碍 / 悬浮窗权限都在）。"
                            + "v5 起全局只存在一个悬浮球：无障碍悬浮球存在时，LSPosed 模块不会再加第二个球。",
                    "「悬浮球样式」：5 种样式（眼睛 / 圆点 / 盾牌 / 文字「隐」/ 禁止斜杠）、"
                            + "3 档尺寸（40 / 48 / 56 dp）、3 档透明度（150 / 200 / 235）。改完立即生效，不需要重启服务。",
                    "关闭本应用（返回键 / 最近任务划掉）后悬浮球会隐藏并保持关闭，"
                            + "需要在 App 内再次手动打开开关才会出现——重新打开 App 也不会自动恢复悬浮球；"
                            + "拖动后的位置会被记住（下次手动打开仍在原位置）。"
                            + "按 Home 键只是切到后台，不会关闭悬浮球。"
            },
            {
                    "4. 图层选择与「遮挡 / 真删」",
                    "进入方式：主界面「图层选择」（服务在线时）、单击悬浮球、或规则页「图层选择」（会先启动目标应用）。",
                    "顶部提示条显示「共 N 个图层 · 当前 <包名>」；屏幕上所有可读元素都会画彩色边框，颜色按层级循环。"
                            + "点一个元素，底部出现动作卡，并显示它的 viewId / 类名 / 坐标。",
                    "「遮挡」：白色蒙版盖住元素并拦截点击，无障碍模式和模块模式都生效（mode=mask）。",
                    "「真删」：把元素 setVisibility(GONE)，只在 LSPosed 模块已启用且作用域勾选时真正生效；"
                            + "否则按遮挡处理，但规则照常保存（mode=gone）。",
                    "规则页：点一下规则行启用 / 停用，长按删除；「启用屏蔽」总开关关闭后所有规则临时失效。"
            },
            {
                    "5. LSPosed 启用步骤",
                    "1) 手机已 root（Magisk / KernelSU），按 LSPosed 官方步骤安装框架与管理器，"
                            + "重启后确认管理器能打开且显示「已激活」。",
                    "2) 打开 LSPosed 管理器 → 模块 → 勾选「上帝模式 Lite」→ 启用。",
                    "3) 在模块的作用域里勾选需要真删的目标应用（不需要勾选本应用自身）。",
                    "4) 强制停止目标应用（或在管理器里重载作用域），重新打开目标应用。之后在规则页新加 / 修改的"
                            + "「真删」规则，重新进入目标界面即生效（模块有约 2 秒内存缓存，可切出去再切回来刷新）。"
            },
            {
                    "6. 备份 / 恢复",
                    "备份规则：导出 JSON 文件，包含全部规则（含 mode）与全局屏蔽开关，保存到系统文件选择器指定的位置。",
                    "恢复规则：选 JSON 或原版「上帝模式」导出的 .gzip 备份，确认后用备份内容整体替换当前规则；"
                            + "备份里没有规则时会提示「将清空当前规则」，可以取消。",
                    "兼容原版：备份文件同时写入原版键名 package_name / res_name / view_class / depth / x / y / "
                            + "width / height，可以与原版「上帝模式」的备份互导。",
                    "v4 自动备份：每次「启用屏蔽」时自动把当前规则备份到应用默认目录 "
                            + "Android/data/com.dsh.godmodelite/files/backup/，只保留最近 2 份，新备份按时间覆盖旧备份；"
                            + "App 启动当天若还没备份过也会补一份。主界面右上「自动备份：N 份 ›」可查看、立即备份、恢复最近一份、开关自动备份。"
            },
            {
                    "7. 常见问题",
                    "Q1 Android 13 提示「受限设置」，无法开启无障碍 / 悬浮窗？\n"
                            + "A：在系统弹窗里选「允许」；被系统标记为受限应用时，到 设置 → 应用 → 上帝模式 Lite → "
                            + "右上角菜单「允许受限设置」；部分国产系统还要在「权限管理 → 特殊权限 → "
                            + "显示在其他应用上层」里手动允许。",
                    "Q2 屏幕上有两个悬浮球？\n"
                            + "A：一个是无障碍服务的球（可在这里关掉或改样式），另一个是 LSPosed 模块加在目标应用里的球。"
                            + "只用模块模式时可以关掉无障碍球；两者都开就会看到两个球。",
                    "Q3 图层选择识别不到元素？\n"
                            + "A：先开「系统布局边界」确认系统有没有给该界面画边框；WebView、游戏、Flutter 等自绘界面"
                            + "没有无障碍节点，读不到；锁屏 / 支付 / 密码等安全窗口系统不允许读取；"
                            + "同时确认状态卡里无障碍服务是「已开启」。",
                    "Q4 LSPosed 模块不生效？\n"
                            + "A：确认模块已勾选启用、作用域勾选了目标应用、改完规则后目标应用进程已重启；"
                            + "检查 LSPosed 是否被系统或安全软件杀掉；个别系统要关闭「模块隐藏」"
                            + "或把本应用加入白名单。",
                    "Q5 关掉主界面后悬浮球不见了？\n"
                            + "A：v4 起这是预期行为：悬浮球只能在 App 内手动打开；按返回键或最近任务划掉本应用后"
                            + "悬浮球会保持关闭，不会出现在任何界面，需要再次打开 App 并手动开启开关。"
            },
            {
                    "8. 布局 / 退出后台（v5）",
                    "主界面「更多…」→「布局：列表 / 瀑布流」可切换应用列表样式：列表为单列带图标徽章，"
                            + "瀑布流为双列圆角卡片（已配置的应用仍按智能排序置顶显示）。",
                    "「更多…」→「退出并关闭后台（停止服务）」：会关闭悬浮球、解绑无障碍服务并结束本应用进程；"
                            + "之后系统不会自动启动本应用，需要手动打开。Android 7 以下（API 21-23）没有 disableSelf 接口，"
                            + "会引导你先到系统设置手动关闭无障碍再退出。"
            },
            {
                    "关于",
                    "上帝模式 Lite v5 · 纯 framework、无 AndroidX、API 21~28 可用。",
                    "请只用于自己的设备：遮挡 / 删除他人设备的界面内容属于违规行为。"
            }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // ---- 自绘标题栏（64dp）：← 返回 + 标题 + 副标题 ----
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(6), 0, dp(16), 0);

        TextView btnBack = new TextView(this);
        btnBack.setText("←");
        btnBack.setTextSize(22f);
        btnBack.setTextColor(COLOR_TEXT);
        btnBack.setGravity(Gravity.CENTER);
        btnBack.setPadding(dp(10), dp(8), dp(10), dp(8));
        titleBar.addView(btnBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(4), 0, 0, 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("使用说明书");
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(COLOR_TEXT);
        tvTitle.setSingleLine(true);
        titleCol.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvSub = new TextView(this);
        tvSub.setText("上帝模式 Lite v3");
        tvSub.setTextSize(12f);
        tvSub.setTextColor(COLOR_SUB);
        tvSub.setSingleLine(true);
        tvSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleBar.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // ---- 内容区：ScrollView + 分节卡片 ----
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(0), dp(12), dp(24));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < SECTIONS.length; i++) {
            String[] section = SECTIONS[i];
            if (section == null || section.length == 0) {
                continue;
            }
            LinearLayout card = newCard();
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = dp(10);
            content.addView(card, cardLp);

            TextView tvSectionTitle = new TextView(this);
            tvSectionTitle.setText(section[0]);
            tvSectionTitle.setTextSize(15f);
            tvSectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
            tvSectionTitle.setTextColor(COLOR_ACCENT);
            card.addView(tvSectionTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int j = 1; j < section.length; j++) {
                String text = section[j];
                if (text == null || text.length() == 0) {
                    continue;
                }
                boolean command = text.startsWith("$ ");
                TextView body;
                if (command) {
                    body = newCommandBlock(text);
                } else {
                    body = new TextView(this);
                    body.setText(text);
                    body.setTextSize(13f);
                    body.setTextColor(COLOR_TEXT);
                    body.setLineSpacing(dp(4), 1f);
                    body.setPadding(0, dp(6), 0, 0);
                }
                LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (command) {
                    bodyLp.topMargin = dp(8);
                }
                card.addView(body, bodyLp);
            }
        }

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    /** 深色卡片：圆角 12dp + 内边距。 */
    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        return card;
    }

    /** 命令块：等宽字体 + 深色底，可长按复制（每行以 "$ " 开头）。 */
    private TextView newCommandBlock(String text) {
        TextView tv = new TextView(this);
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        tv.setText(sb.toString());
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFFB0BEC5);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD2);
        bg.setCornerRadius(dp(8));
        tv.setBackground(bg);
        return tv;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
