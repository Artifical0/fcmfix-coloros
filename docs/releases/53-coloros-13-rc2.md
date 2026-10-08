# FCMFix ColorOS 53-coloros-13-rc2

测试版。包名与签名不变，versionCode 73，可直接覆盖安装，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

本版不改变拦截逻辑。包含 53-coloros-13-rc1 的全部改动。

## 新增：Hook 状态（不需要 Root）

FCMFix 界面顶部新增一行 Hook 状态，显示系统框架和电池中各项 Hook 是否生效，例如
“系统框架：32 项生效 · 电池：4 项生效”。需要 Android 14 及以上。

- 显示“未加载”：模块没有在该进程加载，通常是作用域未勾选，或改动后没有重启；
- 显示“N 项失配”：某些 Hook 与当前固件不匹配，点击可以看到具体是哪一项；
- 点击后可查看明细，并“复制”后贴到 Issue 中；明细里还会显示电池组件是否声明了 `IgnoreGmsUserSet`。

以前有几组 Hook 一个都没挂上时只记一行日志，现在统一记为失配，不会被当成生效。

## 修正

- 模块状态中的 API 版本：原来显示的是 LSPosed 框架支持的最高版本（如 102），现在显示模块实际使用的 API 101，
  框架版本不同时另行注明。

## 包含的 53-coloros-13-rc1 改动

- 移除不会在 ColorOS 上生效的 MIUI / HyperOS 适配和需要 GMS 作用域的旧重连功能；诊断日志不再逐条向 GMS 发送广播。
- 系统框架中的 ColorOS Hook 按功能拆分，Hook 逻辑本身未改动。
- “目标无响应时代发提示通知”发送前先检查通知权限；更新界面依赖库和构建工具。
- [`collect-report.sh`](https://github.com/Artifical0/fcmfix-coloros/blob/master/scripts/collect-report.sh) 新增 Doze 白名单和睡眠待机优化两栏。

## 测试重点

1. 重启后打开 FCMFix，Hook 状态中系统框架和电池都没有“未加载”或“失配”，模块状态显示 API 101；
2. 点击 Hook 状态查看明细，复制是否正常；
3. 推送收发与 53-coloros-12.1 一致。
