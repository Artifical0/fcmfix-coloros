# FCMFix ColorOS 工作原理

本文是 [README](../README.md) 的技术补充：ColorOS 在哪些位置限制 FCM，模块具体改了什么，以及实机验证记录。
逐个 Hook 点的固件核对见 [ColorOS 17 Hook 核对](oneplus15-coloros17-fcm-analysis.md) 与
[ColorOS 16 国行/国际版差分分析](oneplus15-coloros16-fcm-analysis.md)。

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

电池组件运行在 `com.oplus.athena` 进程中，与常驻的 Athena 共用（同为 system UID）。
本模块拦截的是该进程内针对指定 Google 核心 UID 的所有
`POLICY_REJECT_ALL` 写入，并改为 `POLICY_NONE`，不是仅 Hook 某一个 controller。
53-coloros-13-rc3 之前，模块只在电池组件是进程中第一个载入的包时才生效，而该进程由 Athena 先启动，
因此电池作用域中的 Hook 实际都没有生效，由系统框架中的兜底处理。
不直接修改“设置/流量管理”进程中的规则；但如果某个 OTA 将手动设置也转交电池进程
通过同一接口写入，该写入也可能被影响，需针对对应固件复查。

系统框架中还有一层兜底：电池组件声明 `IgnoreGmsUserSet=true` 时，系统下发针对 Google 核心包的
禁网防火墙规则会被丢弃，不依赖电池作用域。流量管理正是根据这一声明隐藏 Google 联网开关
（ColorOS 17 `17.0.0.102(CN01)` 已核对），此时用户无法手动设置。这一层无法区分规则来自电池组件还是流量管理，
因此电池组件没有这一声明时不生效，用户手动设置的 Wi-Fi 或移动数据权限保持不变。

同一次探测失败时，电池组件还会广播 `oplus.intent.action.google_restrict_change`。
ColorOS 17 的系统框架据此降级 GMS 闹钟并把 Google 应用放入 RARE 待机分组。本模块只把该广播中的
`restrict_enable=true` 改为 `false`，名单更新照常下发。

ColorOS 17 的“流量管理”会隐藏这几个 Google 包的联网开关（电池组件声明了 `IgnoreGmsUserSet=true`），用户无法手动修改。
此时电池组件会因旧的“用户改过”标记（`oplus_user_change_gms_network_control`）一直跳过这些包，旧的禁止状态保留下来。
本模块仅在电池组件自己声明忽略用户设置时，才在电池进程中把该标记视为未设置，让电池组件重新接管，再按上文改为不限制；
电池组件没有这一声明的系统不受影响。详见
[流量管理里没有 Google 入口](report-issue.md#流量管理里没有-google-入口)。

电池组件的“睡眠待机优化”（深度睡眠）会在长时间静置时整机断网，只保留 HeyTap 推送、VoWiFi 等白名单。
本模块在系统框架的 `OAppNetControlService.networkDisableWhiteList` 处把 GMS 加入该白名单，使 FCM 与国内推送通道
一样在深度睡眠时保持连接；网络恢复后再向 GMS 发送一次重连请求，防止它断开后不再重试。

## 适用环境

| 系统 | 验证情况 |
| --- | --- |
| ColorOS 17 `PLK110_17.0.0.102(CN01)`，Android 17 | 按全量包核对全部 Hook 点（[核对记录](oneplus15-coloros17-fcm-analysis.md)），维护者日常使用正常 |
| ColorOS 16 `16.0.10.500`，Android 16 | 严格实机验证（见下文“实机验证结果”），[国行/国际版差分分析](oneplus15-coloros16-fcm-analysis.md) |

需要 Modern Xposed API（libxposed API 100 及以上）的 LSPosed。其他 ColorOS 16/17 机型或后续 OTA
可能可以使用，但没有经过同等级验证；Hook 与固件不匹配时会在日志中留下 `hook error` 或 `Unsupported`，
并保持系统原行为。安装前应保留可进入安全模式或禁用 LSPosed 模块的恢复手段。

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

## 推送延迟时的日志

如果日志已经显示 `Successful broadcast`，应用进程也被拉起，但通知仍延迟，检查日志中是否出现
`Oplus FCM delivery window`、`Oplus FCM Hans-freeze bypass` 或 `Oplus FCM socket-close bypass`。
ColorOS 在广播投递后的二次冻结/断网是延迟的可能原因之一；仅凭“广播成功、进程已启动”不能排除应用内部同步、
消息优先级、网络等其他延迟原因，需要结合时间戳和日志判断。

刚重启后 GMS 重新建立连接可能需要一点时间，测试时应先确认 FCM Diagnostics 已连接。

## 配置刷新

允许列表通过 LSPosed 远程配置提供给系统 Hook。Android 14 及以上通过带发送方身份的配置广播即时刷新；
更早的 Android 保存配置后需重启手机。

开启“自动放行包含 FCM 的应用”后，系统框架会在读取配置时扫描一次声明了 FCM 接收组件
（`com.google.android.c2dm.intent.RECEIVE` 广播接收器或 `com.google.firebase.MESSAGING_EVENT` 服务）的应用，
之后在应用安装、更新、组件变化和卸载时只重新检查该应用。扫描在独立线程中进行，推送路径上只查已扫描好的集合。
放行规则为：手动勾选的应用，加上开启自动放行时扫描到的 FCM 应用中未被排除的部分；应用界面按同一规则显示勾选状态。

## 自查

自查页向系统框架发送与 Hook 状态相同的带身份查询广播，额外附带自查标记。系统框架在独立线程中只读地收集：
配置是否已读取、GMS 的 ColorOS 联网策略（`networking_control` 服务）、GMS 待机分组与 Doze 白名单，
以及每个放行应用的通知开关、被关闭的通知类别数、待机分组、是否运行或已停止、开机以来最近一次收到可信 FCM 推送的时间和次数
（只保存在系统框架内存中，最多 256 个应用，重启清空）。任何一项在当前固件上读取失败时显示为“未知”，不影响推送。

系统框架还会用 netlink `sock_diag` 每分钟查看一次 GMS 到 5228–5230 端口的 TCP 连接（系统框架不能读 `/proc/net/tcp`，
但可以用 sock_diag），记录连接建立、断开和断开次数；同时记录熄屏/亮屏、进出深度 Doze、默认网络切换和收到推送，
组成自查页的“最近事件”时间线（最多 120 条）。采样用不会唤醒设备的定时器，休眠期间不额外耗电；
休眠中发生的重连会在下次采样时以“连接已更换”的形式记录，并给出最后一次确认在线的时间。

“导出完整报告”用 Root 运行打包在 APK 中的 [`collect-report.sh`](../scripts/collect-report.sh)，输出写入 FCMFix 自己的缓存目录，
由用户选择分享或保存。报告末尾会附上自查结果，以及系统框架和电池进程中模块自己保留的最近 400 条日志
（带时间戳，只在内存中，重启清空）。有些机型的 logcat 缓冲区只能保留几分钟，开机时的 Hook 结果和夜间的断网记录早已被冲掉，
这部分日志不受影响。
