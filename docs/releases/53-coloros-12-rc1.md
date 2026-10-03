# FCMFix ColorOS 53-coloros-12-rc1

修复 53-coloros-11 中深度睡眠白名单未生效、夜间 FCM 断开后长时间不重连的问题。包名与签名不变，versionCode 68，
可直接覆盖安装，安装后请重启。

## 问题

维护者设备夜间进入“睡眠待机优化”后，系统下发的联网白名单中没有 GMS，53-coloros-11 在电池组件中追加 GMS 的 Hook 没有生效。
GMS 心跳失败（`ERR_IO_RST_HB`）、重连数次失败后不再重试，早上恢复联网后仍保持断开，直到手动重连。

## 改动

- 在系统框架的 `OAppNetControlService.networkDisableWhiteList` 处把 GMS 加入深度睡眠联网白名单，不再依赖电池组件内部方法。
- 深度睡眠恢复联网后，自动向 GMS 发送一次重连请求（与 FCM Diagnostics 中 RECONNECT 相同的 `GCM_RECONNECT`）。

## 验证

- 44 项单元测试通过；lintDebug / lintRelease 无错误；Debug / Release 构建成功。尚未经过夜间实测。
- 开机后日志应出现 `Oplus night network whitelist hook active`。
- 夜间深度睡眠后：
  - `deepsleepRcd.txt` 中 `set netWork: function = new, uid = [...]` 应包含 GMS 的 UID；
  - 日志中应有 `Oplus night network whitelist: added GMS uid=...`，早上恢复时有 `GCM_RECONNECT sent`；
  - FCM Diagnostics 应保持或迅速恢复 connected。

查看深度睡眠记录：

```sh
su -c 'grep "set netWork" /data/oplus/os/battery/deepsleepRcd.txt | tail -n 5'
```
