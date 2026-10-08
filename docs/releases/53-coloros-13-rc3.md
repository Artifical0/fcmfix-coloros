# FCMFix ColorOS 53-coloros-13-rc3

测试版。包名与签名不变，versionCode 74，可直接覆盖安装，安装后请重启手机。

LSPosed 作用域请**同时勾选“系统框架”和“电池（`com.oplus.battery`）”**。

## 修正：电池作用域中的 Hook 此前从未生效

电池组件的主进程是 `com.oplus.athena`，与常驻的 Athena 共用，开机时由 Athena 先启动。
以前的版本只在电池组件是进程中第一个载入的包时才挂 Hook，所以勾选电池作用域后实际什么都没有挂上。
53-coloros-13-rc2 新增的 Hook 状态因此一直显示“电池：未加载”。

此前推送正常，靠的是系统框架中的兜底：丢弃对 Google 核心包的禁网防火墙规则，
以及深度睡眠断网时把 GMS 加入白名单。本版起电池组件中的 4 项 Hook 也会生效：

- 电池组件对 GMS、Play 商店等写入“禁止全部联网”时改为不限制；
- 清除 Google 受限广播中的受限标记；
- 电池组件声明 `IgnoreGmsUserSet` 时忽略旧的“用户改过 Google 联网”标记；
- 深度睡眠断网白名单中加入 GMS。

这些 Hook 都在该共用进程中生效，因此也会作用于 Athena 在该进程内对上述 Google 包的相同调用。

## 包含的 53-coloros-13-rc1 / rc2 改动

- FCMFix 界面顶部新增 Hook 状态（Android 14 及以上），点击可查看明细并复制；模块状态显示实际使用的 API 101。
- 移除不会在 ColorOS 上生效的 MIUI / HyperOS 适配和需要 GMS 作用域的旧重连功能。
- 系统框架中的 ColorOS Hook 按功能拆分；更新界面依赖库和构建工具。
- [`collect-report.sh`](https://github.com/Artifical0/fcmfix-coloros/blob/master/scripts/collect-report.sh) 新增 Doze 白名单和睡眠待机优化两栏。

## 测试重点

1. 重启后 Hook 状态显示“电池：4 项生效”，没有“失配”；
2. 电池设置、耗电统计等界面能正常打开，没有异常耗电或重启；
3. 推送收发与 53-coloros-12.1 一致，尤其是熄屏过夜后。
