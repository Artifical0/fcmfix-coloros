# FCMFix ColorOS

修复 **ColorOS 16 / 17（国行）** 拦截 Google FCM 推送的 LSPosed 模块，需要 **Root + LSPosed**。

GMS 已经收到推送，但 ColorOS 的后台限制把消息拦在半路：应用划掉后台或被系统停止后收不到推送，
或者要等亮屏、打开应用才收到。本模块只在消息从 GMS 投递到你选中的应用这一段解除这些限制，
并阻止 ColorOS 切断 Google 核心服务的网络和唤醒。

它不是推送服务器、代理软件或常驻保活程序，不能让不支持 FCM 的应用获得推送，也不能让 GMS
连上 FCM 服务器（国内通常还需要可用的 DNS 或网络，见[它不能解决什么](#它不能解决什么)）。

| | |
| --- | --- |
| 当前版本 | [53-coloros-12.1](https://github.com/Artifical0/fcmfix-coloros/releases/latest)（GitHub Release 与 LSPosed 模块仓库同步） |
| 支持系统 | ColorOS 16、ColorOS 17（Android 16 / 17）国行 |
| 验证机型 | 一加 15（PLK110）；其他 ColorOS 16/17 机型可能可用，但未经同等级验证 |
| 作用域 | `系统框架` + `电池`（`com.oplus.battery`），两者都要勾选 |
| 遇到问题 | [如何提交问题与日志](docs/report-issue.md) |

基于 [kooritea/fcmfix](https://github.com/kooritea/fcmfix)，针对 ColorOS 国行做了专门适配和安全加固。

## 它解决的是什么问题

正常的 FCM 推送链路如下：

```text
应用服务器 → Google FCM → Google Play 服务（GMS）→ FCM 广播 → 目标应用 → 系统通知
```

在国行 ColorOS 16 / 17 上，这条链路可能在多个位置被限制：

- ColorOS 电池组件可能把 GMS、Play 商店等 Google 核心包设置为禁止全部联网；
- GMS 发出 FCM 广播时，系统可能拒绝启动没有进程或处于 stopped 状态的应用；
- 国行版的应用分类、自启动、GCM bind、Hans 冻结和代理唤醒策略可能继续拦截；
- 即使 FCM 已经拉起目标应用，后台网络控制仍可能在数秒后重新冻结应用并关闭其
  socket，使应用不能及时拉取消息正文；
- 国行区域配置缺少国际版中的部分 GMS Doze 白名单条目；
- ColorOS 17 在 Google 连通性探测失败后，还会把 GMS 的唤醒闹钟降级、把 Google 应用放入受限待机分组，
  导致熄屏后 FCM 心跳与重连停止。

典型表现包括：

- 应用打开时能收到通知，划掉后台或被系统清理后收不到；
- 日志出现 `Failed to broadcast to stopped app`；
- 必须手动打开应用，积压的消息才一起出现；
- GMS 已连接 FCM，但消息无法拉起目标应用。
- FCM Diagnostics 显示广播成功、进程也已启动，但通知延迟到亮屏或打开应用后才出现。

本模块就是针对这些系统限制进行修复。

## 实际做了什么

### 系统框架作用域

- 核验 GMS 真实 UID、精确的 FCM action 和明确目标后，为允许列表应用补充
  `FLAG_INCLUDE_STOPPED_PACKAGES`，允许广播到达已停止应用；
- 动态适配 Android 16 / 17 的广播方法参数，避免 ColorOS 复制 Intent 后丢失放行标记，
  并对广播调用链去优化，避免 Android 17 内联导致 Hook 失效；
- 仅对模块允许列表中的目标应用绕过 ColorOS 应用分类、FCM 自启动、GCM bind 和
  service 启动限制，以及冷启动拦截（持久限制名单、后台启动限制）、云控“恶意应用”名单和关联启动限制；
- 在 FCM 到达时解除必要的 ColorOS/Hans 冻结和代理限制；
- 每次有效 FCM 到达后，仅为对应目标 UID 建立 20 秒投递窗口，暂时阻止 Hans 重新冻结
  （含 ColorOS 17 锁屏快速冻结）、Osense 场景广播代理以及 `OAppNetControlService` 关闭 socket；
  窗口结束后恢复系统原有省电策略；
- 为 GMS、GSF 和 Play 商店补充缺失的 Doze 条目，同时保留 ColorOS 原有白名单；
- 阻止 ColorOS 17 因 Google 受限状态把 GMS 唤醒闹钟降级为不唤醒（电池作用域未生效时的兜底）；
- 让系统始终认为 GMS 不受限，避免 GMS/GSF 的唤醒锁被丢弃、OGuard 对 GMS 做耗电管控、前台应用唤醒 GMS 被限制；
- 熄屏弱信号时不再对 GMS 断网或拦截其闹钟；深度睡眠期间 GMS 的闹钟不再等待网络恢复；
- FCM 投递窗口内不拦截目标应用的后台作业，便于 Gmail 等应用在收到通知后同步内容；
- 可选阻止系统在应用停止时自动删除它原有的通知。

### 电池作用域

ColorOS 的 `com.oplus.battery` 会在 Google 连通性探测失败时调用系统联网管理接口，
给 GMS、Play 商店或 ConfigUpdater 写入 `POLICY_REJECT_ALL`，即同时禁止 Wi-Fi 和
移动数据。

本模块拦截的是 `com.oplus.battery` 进程内针对指定 Google 核心 UID 的所有
`POLICY_REJECT_ALL` 写入，并改为 `POLICY_NONE`，不是仅 Hook 某一个 controller。
不直接修改“设置/流量管理”进程中的规则；但如果某个 OTA 将手动设置也转交电池进程
通过同一接口写入，该写入也可能被影响，需针对对应固件复查。

系统框架中还有一层兜底：电池组件声明 `IgnoreGmsUserSet=true`（ColorOS 17 国行，流量管理隐藏了
Google 联网开关）时，系统下发针对 Google 核心包的禁网防火墙规则会被丢弃，不依赖电池作用域。
这一层无法区分规则来自电池组件还是流量管理，因此在仍开放 Google 联网开关的系统（如 ColorOS 16）上不生效，
用户手动设置的 Wi-Fi 或移动数据权限保持不变。

同一次探测失败时，电池组件还会广播 `oplus.intent.action.google_restrict_change`。
ColorOS 17 的系统框架据此降级 GMS 闹钟并把 Google 应用放入 RARE 待机分组。本模块只把该广播中的
`restrict_enable=true` 改为 `false`，名单更新照常下发。

ColorOS 17 的“流量管理”会隐藏这几个 Google 包的联网开关（电池组件声明了 `IgnoreGmsUserSet=true`），用户无法手动修改。
此时电池组件会因旧的“用户改过”标记（`oplus_user_change_gms_network_control`）一直跳过这些包，旧的禁止状态保留下来。
本模块仅在电池组件自己声明忽略用户设置时，才在电池进程中把该标记视为未设置，让电池组件重新接管，再按上文改为不限制；
仍开放这些开关的系统（如 ColorOS 16）不受影响。详见
[流量管理里没有 Google 入口](docs/report-issue.md#流量管理里没有-google-入口)。

电池组件的“睡眠待机优化”（深度睡眠）会在长时间静置时整机断网，只保留 HeyTap 推送、VoWiFi 等白名单。
本模块在系统框架的 `OAppNetControlService.networkDisableWhiteList` 处把 GMS 加入该白名单，使 FCM 与国内推送通道
一样在深度睡眠时保持连接；网络恢复后再向 GMS 发送一次重连请求，防止它断开后不再重试。

## 它不能解决什么

- 目标应用本身不使用 FCM；
- 应用服务器没有发送消息，或账号、Token 已失效；
- Google Play 服务没有登录、被禁用或无法连接 Google 网络；
- 目标应用的系统通知权限或应用内部通知开关已关闭；
- VPN、代理、DNS 或网络环境本身无法连接 FCM。国内 `mtalk.google.com` 的解析常被污染，
  部分网络还会封锁 5228–5230 端口；FCM Diagnostics 亮屏时也一直 disconnected 时，
  需要自行解决 DNS、hosts 或代理（[判断方法](docs/report-issue.md#fcm-断开网络问题还是系统限制)）；
- OTA 更新后 ColorOS 修改了关键类名或方法，导致现有 Hook 失配。

本模块不会安装 Google 服务，不会替代 GMS，也不会绕过应用自身的通知设置。

## 适用环境

| 系统 | 验证情况 |
| --- | --- |
| ColorOS 17 `PLK110_17.0.0.102(CN01)`，Android 17 | 按全量包核对全部 Hook 点（[核对记录](docs/oneplus15-coloros17-fcm-analysis.md)），维护者日常使用正常 |
| ColorOS 16 `16.0.10.500`，Android 16 | 严格实机验证（见下文），[国行/国际版差分分析](docs/oneplus15-coloros16-fcm-analysis.md) |

需要 Modern Xposed API（libxposed API 100 及以上）的 LSPosed。其他 ColorOS 16/17 机型或后续 OTA
可能可以使用，但没有经过同等级验证；Hook 与固件不匹配时会在日志中留下 `hook error` 或 `Unsupported`，
并保持系统原行为。安装前应保留可进入安全模式或禁用 LSPosed 模块的恢复手段。

## 安装和使用

1. 确认手机已经 Root，并安装可正常工作的 LSPosed。
2. 安装本仓库发布的 APK。
3. 在 LSPosed 中启用模块。
4. 只勾选以下两个作用域（两个都要勾选）：
   - `系统框架`（`system`）；
   - `电池`（`com.oplus.battery`）。
5. 重启手机，使 system_server 和电池进程加载 Hook。
6. 打开 FCMFix，在应用列表中勾选需要由 FCM 唤醒的应用。
7. 确保目标应用本身的通知权限和通知频道已启用。

允许列表用于限制放行范围。不要无条件全选所有应用；只选择确实使用 FCM 且需要后台
推送的应用即可。Android 14 及以上通过带发送方身份的配置广播即时刷新；更早的 Android
保存配置后需重启手机。当前不承诺工作资料、应用分身或多用户推送支持。

注意：加入允许列表也意味着允许可信 FCM 唤醒被用户主动“强行停止”的应用。
系统杀进程、冻结与“强行停止”不是同一概念；当前版本没有单独的强停唤醒开关。

目标应用不需要为了本模块额外挂入系统“流量管理”白名单。模块只在一次 FCM 投递后的
短窗口内处理 ColorOS 后台断网，不会永久放开目标应用后台联网。Google 核心包另有
上文所述的电池进程策略 Hook，不能将这两项机制混为一谈。

当前版本使用 LSPosed 官方仓库可验证包名 `io.github.artifical0.fcmfix.coloros`。
包名变更后不能直接覆盖 `com.fcmfix.coloros` 或 `com.kooritea.fcmfix.op15` 旧版；
请先在 LSPosed 中停用并卸载旧版，安装新版后
重新勾选作用域、重启手机，并重新选择允许推送的应用。

新旧版可以同时安装，但 **不要在 LSPosed 中同时启用两个版本**，否则 Hook
可能重复执行。

## 是否还需要 GMS Magisk 模块

在已验证的 PLK110 `16.0.10.500` 上，不需要再启用
`coloros_gms_extreme_fix`：

- 该根模块保持禁用；
- ELSA 配置没有 bind mount；
- 仅启用本 APK 的 LSPosed 修复；
- GMS 的 5228 FCM 长连接保持正常；
- GMS、GSF、Play 商店和 ConfigUpdater 的联网策略均为 `POLICY_NONE`。

这只代表上述实测固件。后续 OTA 应重新检查 GMS 长连接、联网策略和强停推送。

## 实机验证结果

在一加 15 ColorOS 16 `16.0.10.500` 上的严格测试流程和结果如下：

1. 执行 `am force-stop tw.nekomimi.nekogram`；
2. 确认 Nekogram 为 `stopped=true` 且没有运行进程；
3. 从另一账号发送测试消息；
4. GMS 以 UID `10123` 发出 `com.google.android.c2dm.intent.RECEIVE`；
5. system_server 启动 Nekogram 的 `FirebaseInstanceIdReceiver`；
6. Nekogram 变为 `stopped=false`，并在约 0.57 秒后生成通知。

这证明当时测试的发布版本不仅能处理普通划卡或后台进程被清理，也能在本机上恢复 Android
package stopped 状态下的 FCM 投递。

## 应用内选项

- **模块状态**：标题下方显示模块是否已被 LSPosed 激活、框架版本，并提示缺少“系统框架”或“电池”作用域；
- **搜索与筛选**：按应用名或包名搜索，可筛选“全部 / 包含 FCM / 已允许”；

- **阻止应用停止时自动清除通知**：保留目标应用已有的通知；
- **允许唤醒被冰箱冻结的应用**：尝试通过 Ice Box SDK 异步激活（默认关闭）。不阻塞或
  重放系统广播；如果本次广播解析时应用仍被禁用，本条消息可能无法送达，需要后续消息
  或应用主动同步。SDK 缺失、无权限或队列满时保留系统原行为；
- **全选包含 FCM 的应用**：根据应用组件扫描结果批量加入，建议之后手动检查；
- **打开 FCM Diagnostics**：打开 GMS 自带诊断页面，检查 FCM 连接状态。

## 排查方法

提交问题前，请按 [如何提交问题与日志](docs/report-issue.md) 用脚本导出报告。

如果仍然收不到推送，依次检查：

1. LSPosed 中是否只启用了一个 FCMFix；
2. 作用域是否同时包含“系统框架”和“电池”；
3. 修改作用域或更新 APK 后是否重启过手机；
4. 目标应用是否已经加入 FCMFix 允许列表；
5. GMS 的 FCM Diagnostics 是否显示连接正常；
6. 目标应用通知权限、通知频道和应用内部通知开关是否启用；
7. LSPosed 日志中是否存在 `hook error`；
8. 当前系统版本是否已经通过 OTA 更新。

如果日志已经显示 `Successful broadcast`，应用进程也被拉起，但通知仍延迟，检查新版
日志中是否出现 `Oplus FCM delivery window`、`Oplus FCM Hans-freeze bypass` 或
`Oplus FCM socket-close bypass`。ColorOS 在广播投递后的二次冻结/断网是延迟的可能
原因之一；仅凭“广播成功、进程已启动”不能排除应用内部同步、消息优先级、网络
等其他延迟原因，需要结合时间戳和日志判断。

刚重启后 GMS 重新建立连接可能需要一点时间，测试时应先确认 FCM Diagnostics 已连接。

## 权限与隐私

- APK 自身不声明 `INTERNET` 权限，不通过开发者服务器中转任何消息；
- `QUERY_ALL_PACKAGES` 用于扫描本机哪些应用包含 FCM 接收组件，并显示允许列表；
- 配置通过 LSPosed 的远程配置接口提供给系统 Hook；
- 修复只针对允许列表中的目标应用，以及维持 FCM 所需的 Google 核心包。

## 下载与技术分析

- [下载最新版本](https://github.com/Artifical0/fcmfix-coloros/releases/latest)
- [如何提交问题与日志](docs/report-issue.md)
- [一加 15 ColorOS 16 国行/国际版差分与 Hook 分析](docs/oneplus15-coloros16-fcm-analysis.md)
- [一加 15 ColorOS 17 Hook 核对](docs/oneplus15-coloros17-fcm-analysis.md)
- [酷安、公众号与技术论坛发布素材](docs/publishing-kit.md)
- 上游项目：[kooritea/fcmfix](https://github.com/kooritea/fcmfix)

每个版本的 APK SHA-256 见对应 GitHub Release 说明。
