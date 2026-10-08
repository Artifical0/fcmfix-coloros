# FCMFix ColorOS 53-coloros-13-rc1

测试版。包名与签名不变，versionCode 72，可直接覆盖安装，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

本版不改变拦截逻辑，主要是代码整理和问题报告改进。

## 改动

- 移除 MIUI / HyperOS 适配代码和需要 GMS 作用域的旧重连功能。它们在 ColorOS 上不会生效，只会在开机日志里留下
  “No Such Method”之类的无用记录。
- 诊断日志只写入 logcat，不再逐条向 GMS 发送广播（没有 GMS 作用域时这些广播无人接收），推送到达路径上少一次广播开销。
- 系统框架中的 ColorOS Hook 按功能拆分为三部分：推送送达目标应用、冷启动拦截、Google 核心服务联网。Hook 逻辑本身未改动，
  开机日志中的 `start hook` 行会与以前不同。
- “目标无响应时代发提示通知”发送前先检查通知权限。
- 更新界面依赖库和构建工具。

## 问题报告

[`collect-report.sh`](https://github.com/Artifical0/fcmfix-coloros/blob/master/scripts/collect-report.sh) 新增两栏：

- **Doze 白名单**：GMS / GSF 是否在白名单中；
- **睡眠待机优化**：深度睡眠断网开关，以及最近的深度睡眠断网 / 恢复记录，可与 FCM 断开时间对照。

解读见[如何提交问题与日志](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/report-issue.md#报告怎么看)。

## 测试重点

重启后用脚本导出报告，确认日志中各项 `hook active` 都在，且没有 `hook error`；推送收发与 53-coloros-12.1 一致。
