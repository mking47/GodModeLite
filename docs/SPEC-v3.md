# 上帝模式 Lite v3 规格（悬浮球自定义 / 关闭即隐藏 / 信息增强 / 列表排序筛选 / 说明书 / 选中界面清晰度 / 内存优化）

基线：v2.0 已交付（`docs/SPEC-v2.md`）。本轮只做增量，不改双模式架构、规则模型的核心字段、备份格式。

## 1. 悬浮球自定义（dev-a11y-v3 负责绘制，dev-core-v3 负责设置 UI）
ConfigStore 新增：
```java
// 样式（FloatingBall.View 自绘）
static final int BALL_STYLE_EYE = 0;     // 眼睛（默认，v2 样式）
static final int BALL_STYLE_DOT = 1;     // 圆点
static final int BALL_STYLE_SHIELD = 2;  // 盾牌
static final int BALL_STYLE_TEXT = 3;    // 文字「隐」
static final int BALL_STYLE_SLASH = 4;   // 禁止斜杠
static int getBallStyle(); static void setBallStyle(int);
// 尺寸 dp：40/48/56；透明度 150~255
static int getBallSizeDp(); static void setBallSizeDp(int);
static int getBallAlpha(); static void setBallAlpha(int);
```
BlockerService 新增：
```java
public void applyBallSettings();   // 重新读取上面的设置并刷新悬浮球（样式/大小/透明度立即生效）
public void hideBallForAppExit();  // 仅本次运行隐藏（不写配置），用于「关闭主界面后不显示悬浮球」
```
FloatingBall：
- `public void applyStyle()`：读 ConfigStore，更新 size/alpha/样式并 `updateViewLayout`/重绘；显示中调用要立即生效。
- 自绘 5 种样式（onDraw 里 switch）：眼睛、实心圆点、盾牌轮廓、文字「隐」、斜杠禁止；颜色保持深底 #CC1E1E1E + 绿色 #4CAF50 主色（文字样式用绿字）。
- 尺寸变化时保持球心位置（越界裁剪）；双击？不需要。

## 2. 主动关闭本软件后，其他界面不显示悬浮球（dev-core-v3 + dev-a11y-v3）
- MainActivity `onResume`：若 `ConfigStore.isBallVisible()` 且服务在线 → `svc.setBallVisible(true)`（运行期恢复显示）。
- MainActivity `onDestroy`：`isFinishing()` 时 → `svc.hideBallForAppExit()`（只隐藏本次运行，不写配置；下次打开 App 会重新显示）。
- 悬浮球长按菜单增加一项：「关闭悬浮球」= `ConfigStore.setBallVisible(false)` + 隐藏（持久关闭，主界面开关同步）。
- 注意：按 Home 键（onStop，不 destroy）不隐藏；返回键/最近任务划掉（finish）才隐藏。

## 3. 操作界面信息更详细（dev-core-v3）
MainActivity 状态卡扩展为：
- `root：已授权/未授权`（原）
- `无障碍服务：已开启/未连接/未开启`（原）
- `规则：共 N 条 · 遮挡 A 条 · 真删 B 条`（需要遍历一次规则统计 mode；ConfigStore 可加 `statMask()`/`statGone()` 或一次遍历）
- `悬浮球：显示中/已隐藏 · 样式：眼睛/圆点/...`（读 ConfigStore + service）
- `LSPosed：已安装/未安装`（原有卡片合并进来也可）
应用列表行（AppListAdapter）增强：
- 左侧 40dp 应用图标（异步加载，见内存优化）、应用名 16sp、包名 11sp；
- 右侧/副标题徽章：`遮挡 N`（绿色描边小圆角标签）`真删 M`（红色描边小圆角标签），无规则不显示；正在显示的包（前台/最近操作）可显示「最近」标签（可选）。
规则行（AppDetailActivity）增强：显示 `mode` 标签（原）、viewId、类名简名、创建时间（yyyy-MM-dd HH:mm）、坐标；行高自适应。

## 4. 应用列表排序/筛选 + 已配置置顶（dev-core-v3）
ConfigStore 新增：
```java
static final int SORT_SMART = 0;   // 智能：有规则的置顶，再按名称
static final int SORT_NAME = 1;    // 名称
static final int SORT_RULES = 2;   // 规则数从多到少
static int getSortMode(); static void setSortMode(int);
static final int FILTER_ALL = 0, FILTER_WITH_RULES = 1;
static int getFilterMode(); static void setFilterMode(int);
```
MainActivity：
- 按钮行增加「排序」与「筛选」两个按钮（可复用 row3 或新增一行）：
  - 排序 → AlertDialog 单选（3 项），选中后写 ConfigStore + 重新排序；
  - 筛选 → AlertDialog 单选（全部/有规则）。
- 置顶：SORT_SMART 时 ListView 里先显示「已配置的应用（N）」分组标题行，再显示有规则的应用（按名称），再显示「其他应用（M）」标题行与其余应用。标题行不可点击（用 adapter 的两种 viewType 或一个不可点条目）。
- 搜索框与排序/筛选叠加生效。

## 5. 使用说明书按钮 + 独立页面（dev-core-v3）
- 新 Activity：`com.dsh.godmodelite.HelpActivity`（manifest 由 Lead 加，`android:exported="false"`，主题 AppTheme）。
- 深色页面：自绘标题栏「使用说明书」+ 返回；内容 ScrollView（纯 TextView 分节，深色卡片样式）：
  1. 两种模式（root 免框架 / LSPosed 真删）
  2. 安装与一键配置（三条 root 命令 + 系统布局边界）
  3. 悬浮球操作（拖动/单击/长按菜单）+ 样式设置
  4. 图层选择与「遮挡/真删」
  5. LSPosed 启用步骤（4 步）
  6. 备份/恢复（含原版 .gzip）
  7. 常见问题（Android13 受限设置、两个球、识别不到元素、模块不生效）
- MainActivity 加按钮「使用说明书」→ `new Intent(this, HelpActivity.class)`。

## 6. 选中界面（图层选择）清晰度优化（dev-a11y-v3 + dev-module-v3）
- 顶部提示条：字号 15sp，左侧图标位，增加副标题「共 N 个图层 · 当前 <包名>」；取消按钮做成胶囊按钮（高对比）。
- 动作卡片：卡片背景 #F22A2A2A、圆角 14dp、上边距 8dp；按钮高度 52dp、字号 16sp、粗体；「遮挡」绿色实底(白字)、「真删」红色实底(白字)、「取消」深灰底；三按钮间距 8dp。
- 选中信息：卡片内显示 2 行——第 1 行 `viewId 末段 或 文字`（16sp 白）、第 2 行 `类名 · 坐标(左,上,宽×高)`（11sp 灰）。
- 高亮：3dp 亮绿边框 + 4dp 外发光感（叠加一层 0x2200FF00 的半透明边框）；标签文字加粗。
- 模块模式（dev-module-v3）同步这些清晰度改进（进程内自绘）。

## 7. 内存占用优化（dev-core-v3 + dev-a11y-v3 + dev-module-v3）
- **NodeFinder**：遍历策略改为「用过的 getChild 结果在不再需要时 recycle」，遵循先收集后使用、避免 double recycle；`MAX_VISITED` 降到 3000；`collectVisibleNodes` 返回的 LayerNode 只保留 bounds/label/depth（**不保存 AccessibilityNodeInfo 引用**），遍历结束立即 recycle 临时节点。
- **LayerInspectorOverlay**：用**单个自绘 View（onDraw Canvas）**画所有矩形+标签（v2 若是 per-node View 则合并成一个），关闭时清空列表、把引用置空；矩形数量上限 300。
- **FloatingBall**：不持有 Bitmap（全部 onDraw 几何绘制），无静态 Context。
- **AppListAdapter / MainActivity**：应用图标用 `LruCache<String, Drawable>`（上限 48 个/约 2MB），后台线程加载，行复用；`allApps` 只在数据变化时重建；避免在 getView 里做 PackageManager 查询。
- **ModuleEngine**（dev-module-v3）：图层选择改为单个自绘 View（不再每个元素一个 View），上限 200；关闭时清空引用；`onDestroy` 释放 Handler。

## 8. 文件归属
- dev-core-v3：`MainActivity.java`、`AppDetailActivity.java`、`AppListAdapter.java`、`core/ConfigStore.java`、`HelpActivity.java`（新建）
- dev-a11y-v3：`a11y/FloatingBall.java`、`a11y/BlockerService.java`、`a11y/LayerInspectorOverlay.java`、`a11y/NodeFinder.java`、`a11y/MaskManager.java`
- dev-module-v3：`xposed/ModuleEngine.java`、`xposed/RuleClient.java`（读 provider 新的 ball_style/ball_size/ball_alpha 列）
- Lead：`AndroidManifest.xml`（HelpActivity）、构建、集成、文档、交付

## 9. Provider 新增列（dev-core-v3 改 RulesProvider；dev-module-v3 消费）
`query?pkg=` 返回行增加列：
- `ball_style`（int，默认 0）、`ball_size`（int dp，默认 48）、`ball_alpha`（int，默认 235）
- `mode` 不改：`json` 里已含每条规则 mode。
Module RuleClient 读取这三列，ModuleEngine 的悬浮球按同样 5 种样式绘制（自绘几何，不引 Bitmap）。

## 10. 验收
- 编译零错误；`toolchain/verify.sh` 通过；备份 JVM 自测通过。
- 新增类 Check：`com.dsh.godmodelite.HelpActivity`。
- 静态 grep：无 lambda/stream；a11y 与 xposed 不引用主应用 Activity（模块只能引用 JSON 字段）。
- APK 大小目标 < 120 KB；dex 方法数 < 64K。
