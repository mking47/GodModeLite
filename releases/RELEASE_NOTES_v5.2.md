# 上帝模式 Lite v5.2

修复 LSPosed 模块「真删 `setVisibility(GONE)` 不生效」的问题。

## 修复（v5.2）

- **多策略匹配 View**：`viewId → 屏幕坐标（新增）→ 节点路径 → 文字/contentDescription`。
  - 无障碍图层选择抓的规则没有资源 id 或路径与模块 View 树下标不一致时，用规则记录的屏幕坐标 + 尺寸匹配，命中率大幅提高；
  - 一条规则命中多个 View（列表复用、重复布局）时**全部隐藏**，不再只处理第一个。
- **持续强制 GONE**：
  - 给 decorView 安装 `OnGlobalLayoutListener` + `OnScrollChangedListener`，布局变化/滚动后重新隐藏；
  - onResume 后每 900ms 兜底重压一次（最多 40 次）；
  - 应用自己把元素改回可见、列表重建后都会被再次隐藏。
- **不闪烁**：
  - 按规则 id 精确记录/还原隐藏的 View，不再每轮全部还原再隐藏；
  - 遮挡层按规则 id 原地更新位置（参数不变不触发重新布局），不再重复 add/remove。
- **模块内拾取真删绑定规则 id**，持续强制时不会被误还原。
- **诊断**：模块日志输出 `apply pkg=… rules=… gone=… mask=… unmatched=…`，在 App「LSPosed 诊断」里可直接看哪条规则没匹配上。

## 文件

| 文件 | 说明 |
|---|---|
| `GodModeLite-5.2.apk` | 安装包（v1+v2+v3 签名；内置测试 keystore，仅供测试） |
| `source-v5.2.zip` | 完整源码 + 构建脚本 + 文档 |
| `docs/使用说明-5.2.md` | 中文使用说明 |
| `docs/验收报告-5.2.md` | 构建与静态验收记录 |

SHA256：`aa7f213981ecdf5d7e070d1cdd683dca0221ad047e4e68164ae3733bb4fb246b`

## 验证情况

- 构建零错误；静态验收：177 类 / 1543 方法、Xposed API 未打包、v1+v2+v3 签名、无 INTERNET 权限。
- 自动化测试：备份格式 20 项 + 自动备份轮转 7 项全部通过。
- 未做真机 LSPosed 验证（构建环境 adb 未连设备），请按验收报告自测。

## 已知限制

- 极少数自绘界面（WebView/Flutter/Unity/游戏）没有原生 View 结构，无法真删，只能用遮挡。
- 列表 RecyclerView 复用同一 View 时，规则命中会随滚动持续重新隐藏；如果规则用「屏幕坐标」匹配，滚动后可能命中同位置的其他元素，建议优先用 viewId 稳定的元素。
- 修改模块/作用域后必须强制停止目标应用（或重启手机）才会重新加载。
