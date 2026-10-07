# FCMFix ColorOS 53-coloros-12.1

53-coloros-12 的修正版。包名与签名不变，versionCode 71，可直接覆盖安装，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

## 修正

53-coloros-12 在系统框架中丢弃针对 GMS / GSF / Play 商店 / ConfigUpdater 的禁网防火墙规则。这一层无法区分规则来自电池组件
还是流量管理，在流量管理开放 Google 联网开关的系统上会让用户手动设置的 Wi-Fi 或移动数据禁用失效。

现在这一层只在电池组件声明 `IgnoreGmsUserSet=true` 时生效，即流量管理已隐藏 Google 联网开关、用户无法手动设置的系统
（ColorOS 17 国行 `17.0.0.102(CN01)` 已核对）。电池组件没有这一声明时恢复为 53-coloros-11 的行为：只改写电池组件自动提交的禁网策略。

## 包含的 53-coloros-12 改动

- 深度睡眠：在系统框架中把 GMS 加入夜间联网白名单，恢复联网后自动发送 `GCM_RECONNECT`，不再因“深度睡眠恢复”强制停止 GMS。
- 解锁后禁网（`ERR_CLOSE_BY_USER_UNLOCKED`）：开机解锁后电池服务探测 Google 失败时，系统框架丢弃对 Google 核心包的禁网规则
  （仅限上述系统），不依赖电池作用域。
- Google 受限广播：在系统框架广播入口清除 `restrict_enable=true`，GMS 闹钟不降级、不进入 RARE 待机分组。
- Doze 白名单：同时在 `getNewWhiteList` 和 `whiteListChangedHandle` 补入 GMS / GSF / Play 商店。

详见 [53-coloros-12 发布说明](https://github.com/Artifical0/fcmfix-coloros/releases/tag/53-coloros-12)。

## 说明

- 在 ColorOS 17 国行上，流量管理的联网策略里 Google 应用可能仍显示为禁止联网，但实际不会断网；以 FCM Diagnostics 和日志为准。
  修改版流量管理中把 Google 应用设为禁网同样不会生效。
- FCMFix 不能让 GMS 连上 FCM 服务器，DNS 污染或端口封锁需要自行解决。判断方法见
  [如何提交问题与日志](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/report-issue.md)。
