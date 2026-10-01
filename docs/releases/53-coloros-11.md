# FCMFix ColorOS 53-coloros-11

全面覆盖 ColorOS 17 对 GMS / FCM 限制的正式版，同时继续支持 ColorOS 16。包名与签名不变，versionCode 67，
可直接覆盖安装 53-coloros-10 以及 53-coloros-11-rc1/rc2，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

## 新增处理（相比 53-coloros-10）

- **流量管理里没有 Google 入口**：ColorOS 17 隐藏了 GMS/GSF/Play 商店的联网开关，旧的“用户改过”标记会让电池组件一直跳过它们，
  旧的禁止联网状态因此保留下来。电池组件声明 `IgnoreGmsUserSet=true` 时，模块让它重新接管，再改为不限制。
- **深度睡眠断网**：“睡眠待机优化”在长时间静置时整机断网，只保留 HeyTap 推送等白名单。现在 GMS 也在白名单中，
  其闹钟也不再等待网络恢复。
- **熄屏弱信号断网**：Hans 在熄屏弱信号时对 GMS 设置的防火墙和闹钟拦截已豁免。
- **GMS 受限状态**：系统始终视 GMS 为不受限，避免 GMS/GSF 唤醒锁被丢弃、OGuard 耗电管控、前台应用唤醒 GMS 受限。
- **冷启动与启动管控**：对核验过的 FCM，放行冷启动拦截（持久限制名单、后台启动限制）、云控“恶意应用”名单（广播与服务）
  和关联启动限制。
- **FCM 后的后台作业**：投递窗口内的应用作业不再被 Hans 拦截，便于 Gmail、WorkManager 等在收到推送后同步内容。
- **界面**：显示模块激活状态、LSPosed 框架与 API 版本，提示缺少的作用域；应用列表支持搜索和“全部 / 包含 FCM / 已允许”筛选。
- **问题报告**：`scripts/collect-report.sh` 改为从 ColorOS 联网控制服务读取 Google 核心包联网策略；
  新增 FCM 断开时“网络问题还是系统限制”的判断方法。
- HyperOS `AutoStartManagerServiceStubImpl` 空指针防护。

逐项核查与依据见 [ColorOS 17 Hook 核对](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/oneplus15-coloros17-fcm-analysis.md)。
感谢 @Tlipoca1337、@zopulus、@ligensuo711 的 fork 提供的思路，采纳与未采纳的原因见同一文档的“社区 fork 对照”。

## 说明

- FCMFix 不能让 GMS 连上 FCM 服务器。国内 `mtalk.google.com` 解析常被污染，部分网络封锁 5228–5230 端口，
  需要自行解决 DNS、hosts 或代理。判断方法见[如何提交问题与日志](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/report-issue.md)。
- 微信等受系统保护的 IM 应用平时走自己的长连接，FCM 日志中没有记录属正常；强行停止后再测试是否能被 FCM 唤醒。
- ColorOS 16 `16.0.10.500` 经严格实机验证；ColorOS 17 `17.0.0.102(CN01)` 经固件静态核对，并在维护者的一加 15 上日常使用。
