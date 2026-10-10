# FCMFix ColorOS

修复 **ColorOS 16 / 17（国行）** 拦截 Google FCM 推送的 LSPosed 模块，需要 **Root + LSPosed**。

应用划掉后台或被系统停止后收不到推送、要等亮屏或打开应用才收到，多数是 ColorOS 的后台限制把消息拦在了
GMS 和应用之间。本模块解除这一段的限制，并阻止 ColorOS 切断 Google 核心服务的网络和唤醒。

| | |
| --- | --- |
| 当前版本 | [53-coloros-12.1](https://github.com/Artifical0/fcmfix-coloros/releases/latest)（GitHub Release 与 LSPosed 模块仓库同步） |
| 支持系统 | ColorOS 16、ColorOS 17（Android 16 / 17）国行 |
| 验证机型 | 一加 15（PLK110）；其他 ColorOS 16/17 机型可能可用，但未经同等级验证 |
| 作用域 | `系统框架` + `电池`（`com.oplus.battery`），两者都要勾选 |
| 遇到问题 | [如何提交问题与日志](docs/report-issue.md) |

基于上游 [kooritea/fcmfix](https://github.com/kooritea/fcmfix) 53 版，针对 ColorOS 国行做了专门适配和安全加固。
13.0 起使用独立的版本号（此前形如 `53-coloros-12.1`）。

## 安装

1. 确认手机已 Root，并安装可正常工作的 LSPosed（libxposed API 100 及以上）。
2. 安装 [Release](https://github.com/Artifical0/fcmfix-coloros/releases/latest) 中的 APK，或在 LSPosed 模块仓库中搜索 FCMFix ColorOS。
3. 在 LSPosed 中启用模块，作用域**同时勾选**“系统框架”和“电池（`com.oplus.battery`）”。
4. 重启手机。
5. 打开 FCMFix，勾选确实需要 FCM 后台推送的应用，或开启“自动放行包含 FCM 的应用”，并确认这些应用的通知权限已开启。

从旧包名 `com.fcmfix.coloros` 或 `com.kooritea.fcmfix.op15` 迁移时不能覆盖安装：先在 LSPosed 中停用并卸载旧版，
再安装新版并重新配置。**不要在 LSPosed 中同时启用两个 FCMFix。**

## 它能解决什么

- 应用划掉后台、被系统清理或处于“已停止”状态时，FCM 仍能唤醒它并显示通知；
- 推送到达后，应用不会马上被 ColorOS 再次冻结或断网，能及时拉取消息内容；
- ColorOS 不再把 GMS、Play 商店等 Google 核心服务设为禁止联网（包括开机解锁后的自动禁网）；
- 熄屏、Doze 以及“睡眠待机优化”按应用断网期间，GMS 保持联网；网络恢复后自动重连。

具体改了哪些系统行为，见[工作原理](docs/how-it-works.md)。

## 它不能解决什么

- **GMS 连不上 FCM 服务器**：国内 `mtalk.google.com` 的解析常被污染，部分网络封锁 5228–5230 端口。
  FCM Diagnostics 亮屏时也一直 disconnected，需要自行解决 DNS、hosts 或代理
  （[判断方法](docs/report-issue.md#fcm-断开网络问题还是系统限制)）；
- 应用本身不使用 FCM，或服务器没有发送消息；
- Google Play 服务未登录、被禁用；
- 应用的通知权限或应用内通知开关已关闭；
- 系统整机断网（例如深度睡眠直接关闭 Wi-Fi / 移动数据），此时所有推送通道都会断开；
- 工作资料、应用分身和多用户下的推送（未承诺支持）；
- OTA 后 ColorOS 修改了相关代码，导致 Hook 失配。

本模块不会安装或替代 GMS，不是推送服务器、代理或保活工具。

## 常见问题

**勾选应用后还是收不到？** 先按顺序检查：只启用了一个 FCMFix；两个作用域都已勾选并重启过；
FCMFix 顶部的状态卡片显示“一切正常”；
应用已放行；FCM Diagnostics（拨号 `*#*#426#*#*`）显示已连接；应用通知权限已开启。
菜单中的“自查与导出报告”会逐项检查这些条件，并显示每个放行应用最近一次收到推送的时间。
仍有问题请按[如何提交问题与日志](docs/report-issue.md)导出报告后反馈。

**放行的应用被“强行停止”后也会被唤醒吗？** 会。放行即允许可信的 FCM 唤醒它，包括被手动强行停止的情况。

**自查显示收到了推送，但没有通知？** 说明 GMS 已经把消息交给了应用，问题通常在应用这一侧：
应用或某个通知类别的通知被关闭、应用收到后需要联网拉取内容却被限制，或应用本身的逻辑。点击自查中的应用可以打开它的系统设置。

**需要把应用加入系统“流量管理”或自启动白名单吗？** 不需要。模块只在每次推送到达后的短时间内放开限制，
之后恢复系统原有的省电策略。

**报告里 GMS 的联网策略仍是 4（全部禁止）？** ColorOS 17 上，模块在系统框架中丢弃了对应的禁网规则，
记录的策略值可能不变，但实际不会断网，以 FCM Diagnostics 是否连接为准。

**微信没有走 FCM？** 微信等受系统保护的 IM 应用平时走自己的长连接，FCM 日志中没有记录属正常；
强行停止后再测试能否被 FCM 唤醒。电池设置为“优化”时可能被冻结而漏消息，建议设为“允许完全后台行为”。

## 应用内选项

- **状态卡片**：顶部汇总自查结果，显示“一切正常”或“有 N 项需要处理”及第一项问题，点击进入自查页；
  Hook 状态等系统检查需要 Android 14 及以上，不需要 Root；
- **自动放行包含 FCM 的应用**：放行所有包含 FCM 接收组件的应用，之后新安装的也会自动放行；在列表中取消勾选的应用会被排除，
  关闭开关后恢复为只放行手动勾选的应用；
- **搜索与筛选**：按应用名或包名搜索，筛选“全部 / 包含 FCM / 已放行”；已放行的应用会显示最近一次收到推送的时间；
- **全选包含 FCM 的应用**：按扫描结果批量勾选，建议之后手动检查（开启自动放行时隐藏）；
- **自查与导出报告**：分“概览 / 应用 / 时间线”三页。概览检查作用域、Hook、配置同步、FCM 连接、GMS 联网策略、待机分组、
  Doze 白名单；应用页列出每个放行应用的通知开关、待机分组和最近一次收到推送的时间，有问题的排在前面；时间线记录 FCM 重连与断线、
  网络切换、亮灭屏、深度 Doze 和收到推送，可隐藏推送只看连接。缺少作用域时点击即可向 LSPosed 申请；结果可复制。
  有 Root 时可一键导出完整报告（即 `collect-report.sh`），可选附带某个应用的日志；
- 界面跟随系统深色模式；
- **阻止应用停止时自动清除通知**：保留应用已有的通知；
- **允许唤醒被冰箱冻结的应用**：尝试通过 Ice Box SDK 激活（默认关闭），本条消息可能无法送达，需要后续消息或应用主动同步；
- **打开 FCM Diagnostics**：查看 GMS 的 FCM 连接状态。

## 权限与风险

- APK 不申请 `INTERNET` 权限，不通过开发者服务器中转任何消息；导出的报告只保存在本机，由你决定是否分享；
- `ACCESS_NETWORK_STATE`（查看网络连接，安装时自动授予）用于自查时间线记录网络切换，不能联网；
- `QUERY_ALL_PACKAGES` 仅用于扫描本机包含 FCM 接收组件的应用；
- 这是 system_server 级别的 Hook，安装前请保留进入安全模式或禁用 LSPosed 模块的恢复手段；
  Hook 与固件不匹配时会记录 `hook error` 并保持系统原行为。

## 与上游 kooritea/fcmfix 的区别

| | 上游 [kooritea/fcmfix](https://github.com/kooritea/fcmfix) | FCMFix ColorOS |
| --- | --- | --- |
| 目标系统 | Android 10–15 通用，附加 MIUI / HyperOS 适配 | ColorOS 16 / 17 国行（Android 16 / 17） |
| 作用域 | 系统框架；MIUI / HyperOS 可选“电量和性能” | 系统框架 + 电池（`com.oplus.battery`） |
| 包名 | `com.kooritea.fcmfix` | `io.github.artifical0.fcmfix.coloros`，可与上游同时安装，但不要同时启用 |
| 唤醒已停止的应用 | 支持 | 支持，并只放行来自真实 GMS 的 FCM 广播 |
| ColorOS 后台限制 | 自启动、广播代理、唤醒锁代理、GMS 受限状态 | 另外处理应用分类、bind / service、冷启动拦截、云控名单、关联启动，以及推送后的二次冻结和断网 |
| Google 核心服务联网 | 不处理 | 阻止电池组件禁网（含开机解锁后的探测）、闹钟降级和受限待机分组，补 Doze 白名单，深度睡眠时保持 GMS 联网 |
| MIUI / HyperOS 功能 | 支持 | 已移除 |
| 应用界面 | 允许列表与选项 | 另有自动放行、状态卡片、自查与导出报告、推送记录、搜索与筛选、深色模式 |

如果不是 ColorOS 国行，请使用上游版本。

## 文档

- [工作原理](docs/how-it-works.md)：ColorOS 的限制位置、模块改动、实机验证记录
- [如何提交问题与日志](docs/report-issue.md)
- [一加 15 ColorOS 17 Hook 核对](docs/oneplus15-coloros17-fcm-analysis.md)
- [一加 15 ColorOS 16 国行/国际版差分与 Hook 分析](docs/oneplus15-coloros16-fcm-analysis.md)
- [开发说明](docs/development.md)：信任边界、日志限频、构建与发布流程
- 上游项目：[kooritea/fcmfix](https://github.com/kooritea/fcmfix)

每个版本的 APK SHA-256 见对应 GitHub Release 说明。
