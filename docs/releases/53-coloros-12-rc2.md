# FCMFix ColorOS 53-coloros-12-rc2

在 53-coloros-12-rc1 基础上，全面复核国行 ColorOS 17 的 FCM 链路，修复开机解锁后 FCM 一直未连接
（`ERR_CLOSE_BY_USER_UNLOCKED`）等问题，并把关键限制的拦截移到不受电池作用域和编译内联影响的位置。
包名与签名不变，versionCode 69，可直接覆盖安装，安装后请重启。

## 问题

- ColorOS 电池服务在开机完成（首次解锁）约 5 秒后探测 Google 连通性，探测失败就把 GMS、GSF、Play 商店设为禁止联网，
  并广播“Google 受限”，使 GMS 唤醒闹钟降级、进入 RARE 待机分组。首次解锁时代理软件通常还没启动，国内网络下探测基本都会失败。
  此前模块只在电池组件中拦截，没有勾选电池作用域或拦截未生效时，GMS 解锁后就连不上。
- 53-coloros-11 的深度睡眠 Hook 已证实因方法被内联而失效。复核发现 Doze 白名单、Google 限制状态的 Hook 也挂在可能被内联的短方法上。
- 深度睡眠“逻辑断网”结束、亮屏时，电池服务会强制停止 GMS，GMS 的闹钟全部被取消，连接中断。

## 改动

- 系统框架 `setFirewallUidRuleForNetworkType`：丢弃针对 GMS / GSF / Play 商店 / ConfigUpdater 的禁网规则。
- 系统框架广播入口：清除 `google_restrict_change` 中的 `restrict_enable=true`，三个系统接收方都不再进入受限状态。
- Doze 白名单：在 `whiteListChangedHandle` 补入 Google 包，不再只依赖可能被内联的 `getNewWhiteList`。
- 深度睡眠恢复后不再强制停止 GMS（其他原因的清理不受影响），由 rc1 的夜间白名单和 `GCM_RECONNECT` 保持连接。
- 电池组件中的原有拦截全部保留。
- 包含 53-coloros-12-rc1 的深度睡眠白名单修复。

## 验证

- 单元测试通过；lintDebug / lintRelease 无错误；Debug / Release 构建成功。尚未经过实测。
- 开机后日志应出现：
  - `Oplus Google network firewall hook active`
  - `Oplus Google restrict broadcast hook active`
  - `Oplus deep-sleep GMS force-stop hook active`
  - `Oplus Doze whitelist hook active`（两条：`getNewWhiteList` 与 `whiteListChangedHandle`）
- 触发时会看到 `Oplus Google network reject dropped`、`Oplus Google restrict broadcast cleared in system_server`、
  `Oplus deep-sleep GMS force-stop skipped`，FCM Diagnostics 应保持 connected。
- `su -c 'dumpsys deviceidle whitelist' | grep -E "gms|gsf|vending"` 应能看到 Google 包。
