# 上帝模式 Lite (GodMode Lite)

> 安卓界面元素工具：**root 免框架遮挡** + **LSPosed 模块真删** 双模式，带悬浮球、全图层选择、规则自动备份。<br>
> 不依赖 Xposed 也能用（root + 无障碍）；要让别的 App 里的元素**真正消失**，把本 APK 同时作为 LSPosed 模块启用即可。

当前版本：（[Releases](https://github.com/mking47/GodModeLite/releases)）

## 功能

- **双模式**
  - root 免框架模式：无障碍读节点树 + 悬浮窗遮挡/拦截点击，一键 root 自动配置；
  - LSPosed 模块模式：hook 目标进程 `Activity.onResume`，`setVisibility(GONE)` 真正删除 View，同时支持进程内悬浮球/图层选择。
- **悬浮球（v4：只能手动开关）**：默认关闭，只在主界面手动打开后显示；5 种自绘图标（眼睛/圆点/盾牌/文字「隐」/禁止斜杠）、3 尺寸、3 档透明度、可拖动并**记住位置**；单击进入图层选择，长按菜单；关闭 App 后从所有界面消失并保持关闭，下次需手动再开。
- **图层选择**：把目标应用所有可见控件画成彩色矩形（按层级 6 色 + 文字/ID 标签），点选后「遮挡 / 真删 / 取消」。
- **规则管理**：应用列表（图标 + 遮挡/真删徽章）、智能排序（已配置置顶）/名称/规则数、筛选、搜索。
- **自动备份（v4）**：开启「启用屏蔽」时自动备份规则到应用默认目录，只保留最近 **2 份**，新备份按时间覆盖旧的；App 当天首次启动也会补一份。
- **备份/恢复**：自带 JSON 格式，**兼容导入原版「上帝模式」导出的 .gzip 备份**；也可从自动备份一键恢复。
- **黑色原生界面**：Material 深色主题，无第三方 UI 库；内置「使用说明书」页；v4 新增深色盾牌+眼睛+禁止斜杠应用图标（自适应图标）。
- **隐私**：无 `INTERNET` 权限，不联网；规则只存在本机（跨进程同步用 exported ContentProvider，无权限，介意可自行改为 signature 权限）。

## 下载

到本仓库 **Releases** 页面下载最新 `GodModeLite-x.y.apk`（内置测试签名，可直接安装）。

## 快速使用

1. 安装 APK → 打开 →「一键配置（root）」（或手动开启无障碍服务）。
2. 主界面手动打开「悬浮球」开关 → 屏幕上出现悬浮球。
3. 单击悬浮球 → 点你要遮挡/删除的元素 → 选 **「遮挡」**（root 模式即可）或 **「真删」**（需 LSPosed）。
4. 打开「启用屏蔽」会自动备份规则；主界面右上「自动备份：N 份 ›」可查看/恢复。

### 真删（LSPosed 模块模式）

1. root 下安装 [LSPosed](https://github.com/LSPosed/LSPosed)，重启；
2. LSPosed 管理器 →「模块」→ 启用 **上帝模式 Lite**；
3. 模块 →「作用域」→ 勾选目标应用（**不要勾系统应用**）→ 保存；
4. 强制停止目标应用后重新打开；
5. App 里加规则选「真删」，元素真正消失。调试看 LSPosed 日志里的 `GodModeLite`。

## 从源码构建

依赖：`aapt2`、`javac 17`、`dx`（build-tools 29）、`zipalign`、`apksigner`、`android.jar`(API 23)。

```sh
bash toolchain/build.sh app dist/GodModeLite-4.0.apk   # Xposed API 82 仅编译期使用，不打包
bash toolchain/verify.sh dist/GodModeLite-4.0.apk      # 静态验收
bash toolchain/test/run_tests.sh                       # 备份格式 + 自动备份轮转 JVM 自测
```

> `toolchain/xposed-api/api-82.jar` 是 Xposed API（Apache-2.0），只用于编译模块代码，不会进入 APK。

## 目录

```
app/                 安卓源码（Java，无 AndroidX）
  src/com/dsh/godmodelite/         主应用（UI / 无障碍 / 数据 / 自动备份）
  src/com/dsh/godmodelite/xposed/  LSPosed 模块（hook / 真删 / 进程内 UI）
toolchain/           构建脚本、验证脚本、Xposed 编译期 API
docs/                规格、使用说明、验收报告
analysis/            原版「上帝模式」逆向分析（含 manifest 实测 dump）
releases/            发布用 APK / 源码包 / 发布说明 / SHA256
```

## 文档

- [使用说明 v4.0](docs/使用说明-4.0.md)
- [验收报告 v4.0](docs/验收报告-4.0.md)
- [原版逆向分析](analysis/原软件分析.md)

## 免责声明

- 本项目仅供**学习、研究与个人设备自定义**使用；请勿用于绕过他人应用的安全机制、广告合规或其他侵权/违法场景。
- 修改系统/使用 root 与 LSPosed 有变砖、数据丢失风险，操作前请自行备份，风险自负。
- 「上帝模式」原版版权归其作者；本项目为独立实现，仅借鉴其规则模型与备份格式（兼容导入）。

## License

代码仅供学习交流；如需要使用请注明来源。Xposed API 遵循 Apache License 2.0。
