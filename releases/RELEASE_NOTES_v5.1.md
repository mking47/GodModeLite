# 上帝模式 Lite v5.1

修复/优化 LSPosed 模块「勾选后重启不生效」与「本应用读取 LSPosed 状态不准确」的问题，并增加 root 读取诊断。

## 新功能 / 修复（v5.1）

- **模块生效可验证（心跳）**：模块在目标进程 `onResume/onPostResume` 向主应用写入心跳（60 秒节流）；主界面状态卡显示 `LSPosed：模块已生效 · 心跳 时间 (包名)`。没有心跳即可判断模块尚未真正注入。
- **root 读取 LSPosed 诊断**：「更多… → LSPosed 诊断（root 读取）」只读探测：
  - LSPosed 安装目录 `/data/adb/lspd`、`zygisk_lsposed/module.prop`；
  - `lspd` 进程是否存在；
  - `/data/adb/lspd/config` 里是否写入本模块、config 中出现的应用包名；
  - LSPosed 日志里本模块（`GodModeLite`）的输出；
  - 同时显示模块心跳与模块日志，便于定位「模块没启用 / 没勾作用域 / 目标应用没重启 / 框架没运行」。
- **重启不生效修复**：
  - 去掉 `isFirstApplication` 限制，界面跑在 `:ui` 等子进程的应用也能注入；
  - 增加 `Activity.onPostResume` hook（onResume 后再补一次应用），提升重启后首次打开的命中率；
  - 模块把规则缓存到目标应用私有 SharedPreferences，重启后主应用尚未被拉起（provider 不可用）时按缓存规则继续生效；
  - 心跳/日志通过导出 Provider 回传主应用，失败静默不影响目标应用。
- **读取 LSPosed 状态修复**：manifest 增加 `<queries><package org.lsposed.manager/></queries>`；管理器被隐藏时用 root 目录探测兜底。
- 其余同 v5.0：唯一悬浮球手动开关、主动退出后台、列表/瀑布流布局、自动备份保留 2 份、眼睛+红色斜杠图标、内存优化。

## 文件

| 文件 | 说明 |
|---|---|
| `GodModeLite-5.1.apk` | 安装包（v1+v2+v3 签名；内置测试 keystore，仅供测试） |
| `source-v5.1.zip` | 完整源码 + 构建脚本 + 文档 |
| `docs/使用说明-5.1.md` | 中文使用说明 |
| `docs/验收报告-5.1.md` | 构建与静态验收记录 |

SHA256：`990db4cfb47a02fa4daa003a436cc82d86adb3e619842c907853583d88874ca1`

## 验证情况

- 构建零错误；静态验收：172 类 / 1502 方法、Xposed API 未打包、v1+v2+v3 签名、无 INTERNET 权限。
- 自动化测试：备份格式 20 项 + 自动备份轮转 7 项全部通过。
- 未做真机 LSPosed 验证（构建环境 adb 未连设备），请按验收报告中的「真机重点验证」自测。

## 已知限制

- LSPosed 修改模块/作用域后必须强制停止目标应用（或重启手机）才会重新加载。
- Android 13/14 侧载 App 需先「允许受限设置」。
- 心跳/日志依赖导出 Provider；若系统限制跨应用 Provider（极少数定制 ROM），可改用 root 读取 LSPosed 日志诊断。
