# FCMFix ColorOS 53-coloros-11-rc1

全面核查 ColorOS 17 对 GMS / FCM 的限制后的候选版。包名与签名不变，versionCode 65，可直接覆盖安装 53-coloros-10，
安装后请重启。LSPosed 作用域请同时勾选“系统框架”和“电池（`com.oplus.battery`）”。

核查过程与结论见 [ColorOS 17 Hook 核对](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/oneplus15-coloros17-fcm-analysis.md#全面限制核查53-coloros-11)。

## 新增处理

- **流量管理里没有 Google 入口**：ColorOS 17 隐藏了 GMS/GSF/Play 商店的联网开关，旧的“用户改过”标记会让电池组件一直跳过它们，
  旧的禁止联网状态保留下来。电池组件声明 `IgnoreGmsUserSet=true` 时，模块让它重新接管，再改为不限制。
- **深度睡眠断网**：“睡眠待机优化”在长时间静置时整机断网，只保留 HeyTap 推送等白名单。现在 GMS 也在白名单中，
  深度睡眠期间 GMS 的闹钟也不再等待网络恢复。
- **熄屏弱信号断网**：Hans 在熄屏弱信号时会对 GMS 设置防火墙并拦截闹钟，现已豁免 GMS/GSF/Play 商店。
- **GMS 受限状态**：系统始终视 GMS 为不受限，避免 GMS/GSF 唤醒锁被丢弃、OGuard 耗电管控、前台应用唤醒 GMS 受限。
- **冷启动与启动管控**：对核验过的 FCM，放行冷启动拦截（持久限制名单、后台启动限制）、云控“恶意应用”名单
  （广播与服务）和关联启动限制。
- 修正问题报告脚本：Google 核心包的联网策略改为从 ColorOS 联网控制服务读取（此前 `dumpsys netpolicy` 查不到）。

## 验证情况

- 44 项单元测试通过；lintDebug / lintRelease 无错误；Debug / Release 构建成功。
- 所有新 Hook 点均已按 `PLK110_17.0.0.102(CN01)` 固件核对类名、方法名与参数。
- 尚未真机验证。升级后日志中应出现：
  - `Oplus weak-signal whitelist hook active`
  - `Oplus broadcast gate hook active`（validStartProcessFromBroadcast、恶意名单、关联启动）
  - `Oplus malicious service hook active`
  - `Oplus deep-sleep alarm hook active`
  - `Oplus Battery deep-sleep whitelist hook active`
  - `Oplus Battery GMS user-change hooks active`
- 请重点回归：夜间长时间静置后的推送、弱信号环境熄屏推送、强行停止后的推送，以及 GMS 的联网策略
  （`collect-report.sh` 中 GMS 应为 `policy=0`）。
