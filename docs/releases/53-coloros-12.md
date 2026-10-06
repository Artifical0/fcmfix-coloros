# FCMFix ColorOS 53-coloros-12

修复 ColorOS 17 国行版夜间深度睡眠后、开机解锁后 FCM 断开不恢复的问题，并把关键拦截移到不受电池作用域和编译内联影响的位置。
包名与签名不变，versionCode 70，可直接覆盖安装 53-coloros-11 以及 53-coloros-12-rc1/rc2，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

## 修复的问题

- **夜间断开不恢复**：“睡眠待机优化”整机断网时，53-coloros-11 在电池组件中把 GMS 加入白名单的 Hook 因方法被内联而没有生效，
  GMS 心跳失败（`ERR_IO_RST_HB`）后长时间不重连。
- **解锁后一直未连接（`ERR_CLOSE_BY_USER_UNLOCKED`）**：开机首次解锁约 5 秒后，电池服务探测 Google 连通性，
  失败就把 GMS、GSF、Play 商店设为禁止联网，并广播“Google 受限”，使 GMS 唤醒闹钟降级、进入 RARE 待机分组。
  解锁时代理软件通常还没启动，国内网络下探测基本都会失败。
- **深度睡眠结束后 GMS 被强制停止**：亮屏恢复联网时，电池服务会强制停止 GMS，闹钟全部被取消，连接中断。

## 改动

- 深度睡眠：在系统框架 `OAppNetControlService.networkDisableWhiteList` 处把 GMS 加入联网白名单；恢复联网后自动发送一次
  `GCM_RECONNECT`；不再因“深度睡眠恢复”强制停止 GMS（其他原因的清理不受影响）。
- Google 禁网：系统框架 `setFirewallUidRuleForNetworkType` 丢弃针对 GMS / GSF / Play 商店 / ConfigUpdater 的禁网规则，
  “允许”规则照常执行。没有勾选电池作用域、或电池组件中的拦截未生效时，GMS 也不会被断网。
- Google 受限广播：在系统框架广播入口清除 `google_restrict_change` 中的 `restrict_enable=true`，闹钟、待机分组、
  网络策略三处接收方都不再进入受限状态。
- Doze 白名单：同时在 `getNewWhiteList` 和 `whiteListChangedHandle` 补入 GMS / GSF / Play 商店。
- 电池组件中的原有拦截全部保留，作为第一层处理。
- 问题报告说明新增 `ERR_CLOSE_BY_USER_UNLOCKED` 的含义与排查方法。

逐项核查与依据见 [ColorOS 17 Hook 核对](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/oneplus15-coloros17-fcm-analysis.md)
中的“解锁后 Google 禁网”和“全链路复核”两节。

## 说明

- 由于禁网规则在系统框架层被丢弃，流量管理的联网策略里 Google 应用可能仍显示为禁止联网（`service call networking_control 2 i32 <uid>`
  读到 4），但实际不会断网；以 FCM Diagnostics 是否 connected 和日志为准。修改版流量管理中把 Google 应用设为禁网同样不会生效。
- 整机夜间断网（云控的夜间网络关闭）、“GMS 开关”关闭以及境外自动开关 GMS 属于用户或系统设置，模块不干预。
- FCMFix 不能让 GMS 连上 FCM 服务器。国内 `mtalk.google.com` 解析常被污染，部分网络封锁 5228–5230 端口，
  需要自行解决 DNS、hosts 或代理。判断方法见[如何提交问题与日志](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/report-issue.md)。
- 开机后日志应出现 `Oplus night network whitelist hook active`、`Oplus Google network firewall hook active`、
  `Oplus Google restrict broadcast hook active`、`Oplus deep-sleep GMS force-stop hook active` 和两条 `Oplus Doze whitelist hook active`。
- ColorOS 17 `17.0.0.102(CN01)` 经固件静态核对，并在维护者的一加 15 上日常使用；本版新增的 Hook 未在 ColorOS 16 上重新实机验证，找不到对应方法时会自动跳过。
