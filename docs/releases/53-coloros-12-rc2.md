# FCMFix ColorOS 53-coloros-12-rc2

在 53-coloros-12-rc1 基础上，修复开机解锁后 GMS 被系统禁止联网、FCM 一直未连接（`ERR_CLOSE_BY_USER_UNLOCKED`）的问题。
包名与签名不变，versionCode 69，可直接覆盖安装，安装后请重启。

## 问题

ColorOS 电池服务在开机完成（首次解锁）约 5 秒后探测 Google 连通性，探测失败就把 GMS、GSF、Play 商店设为禁止联网。
首次解锁时代理软件通常还没启动，国内网络下探测基本都会失败。此前模块只在电池组件中拦截，没有勾选电池作用域或拦截未生效时，
GMS 解锁后断开重连就会失败，FCM Diagnostics 显示未连接，`last close code` 为 `ERR_CLOSE_BY_USER_UNLOCKED`。

## 改动

- 在系统框架 `OplusNetworkManagementService.setFirewallUidRuleForNetworkType` 处丢弃针对 GMS / GSF / Play 商店 /
  ConfigUpdater 的禁网规则，不再依赖电池作用域。电池组件中的原有拦截保留。
- 包含 53-coloros-12-rc1 的深度睡眠白名单修复。

## 验证

- 单元测试通过；lintDebug 无错误；Debug 构建成功。尚未经过实测。
- 开机后日志应出现 `Oplus Google network firewall hook active`。
- 若系统尝试禁止 GMS 联网，日志会出现 `Oplus Google network reject dropped: uid=...`，FCM Diagnostics 应保持 connected。
