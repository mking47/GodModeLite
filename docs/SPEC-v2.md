# 上帝模式 Lite v2 开发规格（黑色原生 UI + 悬浮球 + 图层选择 + 跨进程真删）

## 目标（用户本轮需求）
1. **跨进程删除 View**：真删靠「LSPosed 模块模式」——本 APK 同时是 Xposed 模块，在目标进程里 `setVisibility(GONE)`。root 免框架模式做不到真删，只能遮挡（这是 Android 进程隔离决定的，会写进说明）。
2. **悬浮图标**：屏幕上有一个可拖动悬浮球，点它进入图层选择模式，长按出菜单。
3. **图层选择**：进入选择模式后**把所有图层元素的矩形都画出来**（带颜色区分层级、小标签），点选某个元素后操作。
4. **界面优化**：原生 Material 黑色主题（Theme.Material.NoActionBar + 自定义深色 UI）。
5. v1 已有功能保留：应用列表/规则管理/备份恢复（含兼容原版 .gzip 备份）/root 一键配置。

## 视觉规范（深色）
- 背景 `#121212`，卡片 `#1E1E1E`，卡片2/按钮 `#2A2A2A`，分隔线 `#2C2C2C`
- 主文字 `#F2F2F2`，次要文字 `#9E9E9E`，强调色 `#4CAF50`，危险色 `#EF5350`，链接/信息色 `#64B5F6`
- 圆角 12dp 卡片、8dp 按钮；无 ActionBar（主题已设 NoActionBar），每个页面顶部自己画 64dp 高的标题栏（标题 20sp 粗体 + 副标题 12sp）。

## 数据模型改动（`core/Rule.java`，dev-core 负责）
新增字段：`public String mode;`，取值 `"mask"`（遮挡，默认）或 `"gone"`（真正删除）。
- `toJson` 写入 `"mode"`（默认写 "mask"）；`fromJson` 缺失时默认 "mask"。
- `describe()` 不变。
- 其余字段不变。

## ConfigStore 新增（dev-core）
```java
static boolean isBallVisible();          // 默认 true
static void setBallVisible(boolean on);
static int getBallX(); static int getBallY(); static void setBallPos(int x, int y);
```

## RulesProvider（新类 `com.dsh.godmodelite.RulesProvider`，dev-core 负责）
- manifest 已声明：`authorities="com.dsh.godmodelite.rules"`，`exported="true"`（无权限，模块进程用它同步规则）。
- `query`：
  - `content://com.dsh.godmodelite.rules/rules?pkg=<包名>` → 返回 1 行，列 `json`（该包规则 JSON 数组字符串）、`blocking`（0/1）；没有规则也返回 1 行（json="[]"）。
  - `content://com.dsh.godmodelite.rules/rules`（不带 pkg）→ 全部规则。
- `insert`：ContentValues 里 `"json"` = 单条规则 JSON（Rule.toJson().toString()）→ `ConfigStore.addRule(Rule.fromJson(...))`，返回 `content://.../rules/<id>`。
- `delete`：`selection="_id=?"` 按规则 id 删除；`selection="pkg=?"` 清空该包。
- 内部先 `ConfigStore.init(getContext())`；所有异常吞掉返回 null，不能崩。

## 无障碍服务 API（`a11y/BlockerService.java`，dev-a11y 负责）
保留：`static get()`、`startPicker(String)`、`stopPicker()`。新增：
```java
public void setBallVisible(boolean visible);   // 显示/隐藏悬浮球
public boolean isBallVisible();
```
行为：
- 服务连接后按 `ConfigStore.isBallVisible()` 自动显示悬浮球；Activity 调 `setBallVisible` 实时生效。
- `startPicker(null)` = 直接进入图层选择（用当前前台包名）。

## 悬浮球（dev-a11y，新类 `a11y/FloatingBall.java`）
- 48dp 圆形，半透明深色底 + 绿色圆环 + 中间白色「眼」形（自定义 View onDraw 画：圆 + 椭圆 + 瞳孔）。
- 窗口：`TYPE_ACCESSIBILITY_OVERLAY`（API<22 用 TYPE_SYSTEM_ALERT），`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`，可拖动；位置存 ConfigStore（ballX/ballY），默认在屏幕右侧 45% 高度；拖动结束存位置；限制在屏幕内。
- 单击（位移 <10px 且 <250ms）→ 进入图层选择模式（`startPicker`）。
- 长按 → 弹一个小菜单（用 PopupWindow 或直接底部卡片）：`打开上帝模式 Lite`（启动 MainActivity）、`隐藏悬浮球`、`取消`。
- 拾取/选择模式期间把球隐藏（避免挡图层），退出后恢复。
- 服务销毁时移除球。

## 图层选择（dev-a11y，替换原 PickerOverlay，新类 `a11y/LayerInspectorOverlay.java`）
- 全屏覆盖层（背景 `0x66000000` 淡黑，让用户知道在选择模式）。
- 顶部提示条：「图层选择：点选要处理的元素」+ 右侧「取消」。
- **遍历当前所有应用窗口的节点树**（`getWindows()`，跳过无障碍 overlay 窗口；复用 NodeFinder 的根节点收集与遍历），为每个 `isVisibleToUser`、bounds 有效且宽高 >=4px 的节点画一个矩形边框：
  - 边框颜色按深度循环：`#4CAF50` `#2196F3` `#FF9800` `#E91E63` `#9C27B0` `#00BCD4`；stroke 1.5dp；fill 透明（可选极淡 0x0AFFFFFF）。
  - 节点有 viewId 或 text 且宽 >= 120dp、高 >= 18dp 时，在其左上角加一个小标签（9sp 白字、背景 0xCC000000、最多 12 个字：优先 text，其次 viewId 末段）。
  - 最多画 400 个矩形/标签；超过 400 个节点的应用只画前 400 个（防卡）。
- 点击空白/某个矩形：用 `NodeFinder.findNodeAt(x,y)` 找节点 → 该节点矩形加粗（stroke 3dp + fill 0x3300FF00）→ 底部弹出动作卡片：
  - 标题：`选中：<describe()>`（副标题：`viewId / 类名`）
  - 按钮三个：**「遮挡」**（mode=mask，白色蒙版盖住）、**「真删」**（mode=gone，红色，需模块）、**「取消」**
  - 点「真删」时先提示一段说明 Toast：「需在 LSPosed 中启用本模块才会真正删除；未启用时按遮挡处理。保存后仍会添加规则。」（不用二次确认弹窗）
  - 保存：`ConfigStore.addRule(rule)`；mask 规则立即 `MaskManager.apply`，gone 规则也按遮挡显示（无障碍模式下无法真删）；关闭选择层，恢复悬浮球。
- 选择层窗口 flags 同 picker（可接收触摸），addView 失败时按 v1 的降级逻辑重试。

## MaskManager（dev-a11y）
只改一处：`apply()` 里规则过滤从 `rule.enabled` 改为 `rule.enabled && ConfigStore.isBlockingEnabled()`（已有），并**不区分 mode**（mask/gone 都按遮挡显示）。

## 主界面（dev-core，深色）
结构（纵向 LinearLayout，无 ActionBar）：
1. 自绘标题栏：左「上帝模式 Lite」20sp 粗体 + 右小字「免框架 · root 模式」。
2. 状态卡：三行（root / 无障碍 / 规则数），每行左侧 8dp 圆点（绿=正常、红=异常、灰=未知）。
3. 按钮行1：「一键配置(root)」「打开无障碍设置」。
4. 按钮行2：「图层选择」（BlockerService.get()!=null 时调 startPicker(null)，否则 Toast 提示先开无障碍）「悬浮球」开关（Switch，读写 ConfigStore + 调 service.setBallVisible）。
5. 「启用屏蔽」Switch。
6. 按钮行3：「备份规则」「恢复规则」「刷新」。
7. 搜索框（深色背景 #1E1E1E，圆角，提示「搜索应用名或包名」）。
8. 应用列表（深色行：应用名 16sp / 包名·N 条规则 12sp；行背景透明，按下高亮）。
9. 模块状态卡：「LSPosed 模块」一行：检测到 `org.lsposed.manager` 已安装则显示「已安装 LSPosed：在管理器里勾选本应用作用域即可真删 View」+ 按钮「打开 LSPosed」；否则显示「未安装 LSPosed：真删功能不可用，仅支持遮挡」。
10. 底部小字：「跨进程真删需 LSPosed 模块模式；root 仅用于自动配置。」

## 规则页（dev-core，深色）
- 标题栏「规则 · <应用名>」+ 返回按钮（左边距）。
- 规则行：左侧 CheckBox（启用）+ 标题 describe() + 副标题（`遮挡`/`真删` 标签 + viewId/cls），行背景 #1E1E1E 圆角，间距 6dp。
- 底部按钮行：「图层选择」（启动目标应用 + startPicker(pkg)）、「清空」、「返回」。
- 空状态：深色提示文字。

## 模块模式（dev-module 负责，`app/src/com/dsh/godmodelite/xposed/` 下）
入口 `GodModeModule implements IXposedHookLoadPackage`（assets/xposed_init 已指向它）。
- `handleLoadPackage`：跳过 `com.dsh.godmodelite` 自身与 `android` 系统包；只处理 `lpparam.isFirstApplication` 的应用进程。
- hook `android.app.Activity.onResume`（after）：调用 `ModuleEngine.onResume(activity)`。
- `ModuleEngine`：
  1. 通过 `RuleClient` 从 `content://com.dsh.godmodelite.rules/rules?pkg=<包>` 拉规则（带 2 秒内存缓存）；
  2. 遍历 `activity.getWindow().getDecorView()`，按规则（viewId → path → cls+text）匹配 View：
     - mode=gone → `view.setVisibility(View.GONE)`（真删）；mode=mask → 在 decorView 上覆盖一个同尺寸 View（半透明白 `0xE6FFFFFF`）并拦截点击；
     - 已处理过的 view 记录 tag 防止重复 add。
  3. 安装悬浮球（若该 Activity 还没有）：一个 48dp 圆形 TextView/自定义 View 加到 decorView（`FrameLayout` 是 decorView 根，`addView` 后 bringToFront），可拖动、单击进入图层选择、长按菜单（打开主应用/刷新规则）；
  4. 图层选择：在 decorView 上加全屏 FrameLayout，递归遍历 decorView 所有子 View（排除我们加的球/覆盖层），给每个 `getVisibility()==VISIBLE` 且宽高>=4px 的 View 画边框（颜色按深度），点击时 `findViewAt` 命中最深 View → 底部动作卡「真删 / 遮挡 / 取消」；真删 = `setVisibility(GONE)` + `RuleClient.insertRule(...)`；遮挡 = 覆盖 View + insertRule(mode=mask)。
- `RuleClient`：`ContentResolver.query/insert`，Uri 见上；解析/生成 JSON 用 org.json；异常静默（provider 不存在说明主应用被卸载）。
- 注意：模块代码运行在目标进程，**不要引用主应用的 Activity/Service/SharedPreferences/ConfigStore 类**（只允许 Rule 的 JSON 字段字面量）；只用 android + org.json + Xposed API。
- 所有 hook 方法与 View 操作都包 try/catch，单条规则出错不能影响目标应用。

## 文件归属
- dev-core：`MainActivity.java`、`AppDetailActivity.java`、`AppListAdapter.java`、`core/Rule.java`、`core/ConfigStore.java`、`core/RulesProvider.java`（新建）、`core/BackupManager.java`（mode 字段兼容即可，无需大改）
- dev-a11y：`a11y/BlockerService.java`、`a11y/FloatingBall.java`（新建）、`a11y/LayerInspectorOverlay.java`（新建）、`a11y/MaskManager.java`、`a11y/NodeFinder.java`；删除 `a11y/PickerOverlay.java`（不再使用）
- dev-module：`xposed/GodModeModule.java`、`xposed/ModuleEngine.java`、`xposed/RuleClient.java`（新建目录）
- Lead：`AndroidManifest.xml`、`app/res/**`、`app/assets/xposed_init`、`toolchain/**`（构建脚本 + Xposed API stub）、集成编译、文档、交付

## 构建（Lead 已扩展）
```sh
bash toolchain/build.sh app dist/GodModeLite-2.0.apk
```
脚本会先编译 `toolchain/xposed-stubs/` 为 stub jar（仅编译期用，**不进 APK**），再用它编译 app 源码；assets 会被 aapt2 link 打进 APK。
