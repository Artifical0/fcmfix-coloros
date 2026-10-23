# FCMFix ColorOS 53-coloros-10

支持 ColorOS 17（Android 17）的正式版，同时继续支持 ColorOS 16。包名与签名不变，versionCode 64，
可直接覆盖安装 53-coloros-8、53-coloros-9-rc1 以及 53-coloros-10-rc1/rc2，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

## ColorOS 17 适配

按一加 15 国行全量包 `PLK110_17.0.0.102(CN01)` 逐项核对了全部 Hook 点，见
[ColorOS 17 Hook 核对](https://github.com/ByteJuno/fcmfix-coloros/blob/master/docs/oneplus15-coloros17-fcm-analysis.md)。

- **广播路径**：增加 Hook `broadcastIntentLockedTraced`，并对广播调用链去优化，
  避免 Android 17 编译内联导致 Hook 不触发。
- **锁屏快速冻结**：ColorOS 17 把 `FastFreezeEnter(int)` 改为 `fastFreezeEnter(int, String)`，
  已适配，FCM 投递窗口内的应用不会在锁屏时被冻结。
- **Google 限制**：电池组件探测 Google 失败后，ColorOS 17 会降级 GMS 的唤醒闹钟，
  并把 Google 应用放入 RARE 待机分组，导致熄屏后 FCM 心跳与重连停止。本版在电池进程解除该限制，
  并在系统框架兜底恢复 GMS 闹钟。
- **Osense 场景广播代理**：对核验过来源的 FCM 广播放行，防止在游戏、应用启动、相机等场景下被延后投递。
- 其余 ColorOS 专有 Hook 的签名与 ColorOS 16 一致，无需改动。

## 其他

- 系统框架启动日志记录系统版本，便于对照固件定位问题。
- 新增[问题报告说明](https://github.com/ByteJuno/fcmfix-coloros/blob/master/docs/report-issue.md)与日志收集脚本
  `scripts/collect-report.sh`，以及 GitHub Issue 表单。
- 源码仓库更名为 [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)，旧地址会自动跳转。
- 包含 53-coloros-9-rc1 的安全加固与日志优化：核验 FCM 真实发送者，配置使用不可变快照，诊断日志限频。

## 说明

- FCMFix 不能让 GMS 连上 FCM 服务器。国内 `mtalk.google.com` 的解析常被污染，
  部分网络还会封锁 5228–5230 端口；FCM Diagnostics 亮屏时也一直 disconnected 时，需要自行解决 DNS、hosts 或代理。
- ColorOS 16 `16.0.10.500` 经过严格实机验证；ColorOS 17 在维护者的一加 15 上日常使用正常。其他机型可能可用，但未经同等级验证。
