# 上帝模式 Lite 开发规格（v1）

## 目标
做一个「免 Xposed、自己用 root 完成配置」的安卓界面元素屏蔽工具，功能对齐原版「上帝模式」的核心体验：
1. 选中另一个应用里的界面元素（广告、按钮、横幅等）→ 保存为规则 → 以后该元素被遮挡/无法点击。
2. 规则可备份/恢复（JSON 文件）。
3. root 用于一键自动配置（自动开启无障碍服务 + 授权悬浮窗），不需要用户手动进设置页（仍保留手动入口作为兜底）。

## 工程信息
- 包名 `com.dsh.godmodelite`；应用名「上帝模式 Lite」。
- 源码根 `app/`：`app/AndroidManifest.xml`、`app/res/**`（已就绪，不要改 manifest/res，除非先和 Lead 说）、`app/src/com/dsh/godmodelite/**`。
- 构建：`bash /root/workspace/toolchain/build.sh /root/workspace/app /root/workspace/dist/GodModeLite.apk`
  （aapt2 compile → aapt2 link → javac -source 8 -target 8 -bootclasspath android.jar(API23) → dx → zip → zipalign → apksigner；输出 APK 已验证可签 v1/v2/v3。）
- 编译用 android.jar 是 **API 23**，因此只能调用 API <= 23 的框架接口（minSdk 21）。不要用 AndroidX、Kotlin、RxJava、Jetpack。
- 编译器是 javac 17 + dx（build-tools 29）。**不要用 lambda、方法引用、stream、java.nio.file、java.util.function、String.join、java.util.Base64**；用 Java 7 风格（匿名内部类、android.util.Base64）。try-with-resources 可用。
- UI 用纯代码构建（LinearLayout/TextView/Button/ListView 等），中文文案直接写在代码里；资源里已有的 string 用 `getString`。
- 线程：所有悬浮窗/无障碍操作在主线程；root 命令、备份 IO 放子线程，结果回主线程（Handler）。

## 数据模型（`core/Rule.java`）
字段（public）：
```java
String id;        // UUID
String pkg;       // 目标应用包名
String activity;  // 抓取时的窗口类名（可空，仅参考）
String viewId;    // 形如 "com.foo:id/btn"，可空
String cls;       // 控件类名，如 android.widget.ImageView
String text;      // 控件文字快照，可空
String path;      // 从根节点起的子节点下标路径，如 "0/1/3"
int l,t,r,b;      // 抓取时屏幕坐标（写备份/参考用）
boolean enabled;  // 规则是否启用
long created;     // 毫秒
```
- `JSONObject toJson()`、`static Rule fromJson(JSONObject)`（用 org.json，字段缺失要容错）。
- `String describe()`：给列表显示的一行描述（优先 text，其次 viewId 末段，其次 cls 简名）。

## 存储（`core/ConfigStore.java`，全部 static）
SharedPreferences 文件 "gm_lite"，键 `rules`（JSON 数组字符串）、`blocking`（boolean）。
API：
```java
static void init(Context ctx);
static List<Rule> allRules();
static List<Rule> rulesFor(String pkg);
static int ruleCount(String pkg);
static Set<String> packagesWithRules();
static void addRule(Rule r);
static void deleteRule(String id);
static void clearRules(String pkg);
static void setRuleEnabled(String id, boolean enabled);
static boolean isBlockingEnabled();
static void setBlockingEnabled(boolean on);
static void replaceAll(List<Rule> rules, boolean blocking);  // 恢复备份用
```
每次修改立即 persist。`allRules()` 返回拷贝，不要在外部改内部 list。

## root（`core/RootHelper.java`）
```java
static boolean isRoot();                     // su -c id 5s 超时，结果含 uid=0
static String exec(String shellCommand);     // su -c <cmd>，返回 stdout+stderr（10s 超时），失败返回错误文本
static String a11yComponent(Context);        // "com.dsh.godmodelite/com.dsh.godmodelite.a11y.BlockerService"
static boolean isA11yEnabled(Context);       // Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 包含我们的组件
static boolean autoConfigure(Context);       // root：settings put secure enabled_accessibility_services <现有串>:<我们> ；settings put secure accessibility_enabled 1；appops set <pkg> SYSTEM_ALERT_WINDOW allow
```
注意 root 命令里避免复杂引号转义；可在 shell 脚本里用 `case` 判重。

## 备份（`core/BackupManager.java`）
SAF 方式，不需要存储权限：
```java
static void export(ContentResolver cr, Uri uri, List<Rule> rules, boolean blocking) throws Exception;
// 写入 JSON: {"app":"GodModeLite","version":1,"exportedAt":毫秒,"blocking":bool,"count":n,"rules":[...]}
static List<Rule> importRules(ContentResolver cr, Uri uri) throws Exception; // 校验 version/rules，坏文件抛异常
```
MainActivity 用 `Intent.ACTION_CREATE_DOCUMENT`（type application/json，文件名 `godmode-rules-yyyyMMdd-HHmmss.json`）和 `ACTION_OPEN_DOCUMENT`，onActivityResult 里调用。

## 主界面（`MainActivity.java`，无 XML）
纵向 LinearLayout：
1. 状态卡片：三行 TextView —— `root：已授权/未授权/检测中`、`无障碍服务：已开启/未开启`、`屏蔽规则：N 条 / M 个应用`。
2. 按钮行：`一键配置（root）`（子线程调用 RootHelper.autoConfigure，完成后刷新）、`打开无障碍设置`（跳 Settings.ACTION_ACCESSIBILITY_SETTINGS）。
3. CheckBox `启用屏蔽`（ConfigStore.setBlockingEnabled；关闭后无障碍不再遮挡）。
4. 按钮行：`备份规则`、`恢复规则`、`刷新应用列表`。
5. EditText 搜索框（按应用名/包名过滤）。
6. ListView 应用列表：每行两行文字（应用名 + 「包名 · N 条规则」），点击进 `AppDetailActivity`（Intent 带 EXTRA_PKG/EXTRA_LABEL）。列表用 PackageManager 取已安装且有启动入口的应用，异步加载，过滤自己。
7. 底部小字说明：免 Xposed，root 仅用于自动配置；规则备份为 JSON。

`onResume` 刷新状态与列表。

## 规则页（`AppDetailActivity.java`）
- 顶部显示目标应用名 + 包名。
- ListView 显示该应用规则：CheckBox（启用/停用）+ 规则描述；长按行弹「删除」确认。
- 按钮行：`取元素`（启动目标应用并让 BlockService 进入拾取模式）、`清空本应用规则`、`返回`。
- 「取元素」：用 PackageManager.getLaunchIntentForPackage 启动目标应用；然后 `BlockerService b = BlockerService.get(); if (b != null) b.startPicker(pkg); else Toast("请先开启无障碍服务")`。
- 空列表提示「还没有规则，点『取元素』去目标应用里点你想遮挡的东西」。

## 无障碍服务（`a11y/BlockerService.java`）
```java
public static BlockerService get();          // 静态弱引用/单例，onServiceConnected 置位，onDestroy 清空
public void startPicker(String targetPkg);   // 展示拾取悬浮层（见下）
public void stopPicker();
```
行为：
- `onAccessibilityEvent`：
  - `TYPE_WINDOW_STATE_CHANGED`：记录当前前台包名（event.getPackageName()），若规则开启则延迟 150ms 调 `MaskManager.apply(this, pkg)`；若拾取模式的目标是本应用则更新目标窗口。
  - `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_VIEW_SCROLLED`：若当前包有规则，延迟 120ms 重刷蒙版。
- service 用 `getSystemService(WINDOW_SERVICE)` 拿 WindowManager。悬浮窗类型：`Build.VERSION.SDK_INT >= 22 ? TYPE_ACCESSIBILITY_OVERLAY : TYPE_SYSTEM_ALERT`，flag `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`（蒙版要 touchable，不加 FLAG_NOT_TOUCHABLE）。
- 蒙版（`a11y/MaskManager.java`）：
  - `apply(service, pkg)`：先 removeAll；若 ConfigStore.isBlockingEnabled() 为假直接返回；取 rulesFor(pkg) 中 enabled 的规则，在 `getRootInActiveWindow()` 和 `getWindows()` 里按 `NodeFinder` 找节点；找到就给每个节点加一个蒙版 View（背景色默认白色 `0xFFFFFFFF`，可在 MainActivity 里改？v1 先固定白色，颜色常量放 MaskManager `MASK_COLOR`），蒙版长按 → 删除该规则（Toast 提示）；最多 30 个。
  - `clear()` 移除全部蒙版。
  - 蒙版位置用 node.getBoundsInScreen(Rect)；窗口 layout x=rect.left, y=rect.top, w/h；若 w/h<=0 跳过；若蒙版覆盖屏幕 >85% 跳过。
- 节点查找（`a11y/NodeFinder.java`，静态工具）：
  - `AccessibilityNodeInfo findByRule(AccessibilityService svc, Rule rule)`：优先级 1) viewId 匹配（getViewIdResourceName 相等且节点可见）；2) path 路径逐级 getChild 且类名匹配；3) cls+text 匹配（text 相等且非空）。遍历时用 `flagIncludeNotImportantViews` 已开。
  - `AccessibilityNodeInfo findNodeAt(AccessibilityService svc, int x, int y)`：遍历 interactive windows 的根节点，深度优先，收集 bounds 包含 (x,y) 的节点，选「最深 + 有 viewId/text/clickable」者；返回最合适节点。
  - `String pathOf(AccessibilityNodeInfo root, AccessibilityNodeInfo target)`：BFS/DFS 找父链，生成 "0/1/3" 形式。
  - 所有方法要防 `AccessibilityNodeInfo` 空指针与 recycle（API 23 上 recycle 可调用；保证不 double-recycle）。
- 拾取悬浮层（`a11y/PickerOverlay.java`）：
  - 全屏透明 FrameLayout（背景 `0x11000000` 便于看出在拾取模式）；顶部提示条「点击要遮挡的元素（可点取消退出）」；底部「取消」按钮。
  - 点击屏幕：取 ACTION_UP 坐标 → `NodeFinder.findNodeAt`；若没找到 Toast「没识别到元素」；找到后在同一 window 里显示半透明绿色高亮框（bounds 位置）+ 底部确认卡片（显示 rule.describe()，按钮「保存」「重选」「取消」）。
  - 「保存」→ 用 ConfigStore.addRule(new Rule(当前包, activity 可空, viewId, cls, text, path, bounds)) → 停止拾取、立即 apply 蒙版、Toast「已保存规则」。回调 MainActivity/AppDetail 刷新由 onResume 处理。
  - 拾取期间新窗口事件（目标应用切换 activity）不退出，只更新 targetPkg 匹配；若用户回到桌面/别的应用，保持拾取但记录当前包即可。
- 权限/异常：所有 WindowManager.addView 包 try/catch（BadTokenException 等），失败 Toast 提示。

## 备份/恢复 UI 流程
- 备份：无规则时禁用；点击 → ACTION_CREATE_DOCUMENT → 写文件 → Toast「已备份 N 条规则到 文件名」。
- 恢复：ACTION_OPEN_DOCUMENT → 解析 → AlertDialog「将用备份中的 N 条规则替换当前规则，是否继续？」→ replaceAll → Toast → 刷新。

## 测试与验收
- `bash toolchain/build.sh app dist/GodModeLite.apk` 必须成功。
- `aapt2 dump badging dist/GodModeLite.apk` 能看到包名/应用名/无障碍服务。
- `apksigner verify` 通过。
- 代码里不能有 lambda/stream/AndroidX；`grep -R "->" app/src` 不应出现 lambda（注释除外）。
- 每个类都要有中文注释说明用途，代码整洁可读。

## 文件归属（避免并行冲突）
- dev-core 只写：`app/src/com/dsh/godmodelite/MainActivity.java`、`AppDetailActivity.java`、`AppListAdapter.java`、`core/RootHelper.java`、`core/BackupManager.java`、`core/ConfigStore.java`、`core/Rule.java`
- dev-a11y 只写：`app/src/com/dsh/godmodelite/a11y/BlockerService.java`、`PickerOverlay.java`、`MaskManager.java`、`NodeFinder.java`
- Lead 负责 `app/AndroidManifest.xml`、`app/res/**`、构建、集成、验收。
