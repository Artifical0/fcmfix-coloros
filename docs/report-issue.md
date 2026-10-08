# 如何提交问题与日志

收不到推送时，请按下面的步骤收集信息，然后[提交 Issue](https://github.com/Artifical0/fcmfix-coloros/issues/new/choose)。
FCMFix 的日志写在系统 logcat 中，标签为 `fcmfix`，不在 LSPosed 管理器的“模块日志”页面里，需要用下面的脚本导出。

## 1. 先自查

- LSPosed 中 FCMFix 同时勾选了 **系统框架** 和 **电池** 两个作用域，改动后已重启；
- 只启用了一个 FCMFix；
- 目标应用已加入 FCMFix 允许列表，应用自身的通知权限已打开；
- 打开 FCMFix →“打开 FCM Diagnostics”，查看连接状态。

**国内网络说明**：FCMFix 修复的是 GMS 收到消息之后被 ColorOS 拦截的问题，不能让 GMS 连上 FCM 服务器。
如果 FCM Diagnostics 亮屏时也一直是 disconnected，通常是网络问题：`mtalk.google.com` 的 DNS 被污染，
或者 5228–5230 端口被封。这种情况需要能返回正确结果的 DNS、hosts 或代理，模块无法解决。
判断方法见[FCM 断开：网络问题还是系统限制](#fcm-断开网络问题还是系统限制)。

## FCM 断开：网络问题还是系统限制

FCM Diagnostics 中的事件都带时间戳，判断的关键是断开发生的时机和规律。

### 偶发断开还是持续断不开

- 断开后几秒到几十秒内重新 connected：通常正常。运营商或路由器会清理长时间空闲的 TCP 连接（NAT 超时），
  GMS 会自动重连。
- 断开后长时间连不上，或反复连上又断开：继续按下文排查。
- `client entering doze` 本身是正常记录，表示手机进入 Doze 休眠。要看的是它之后连接是否保持。

### 看断开时机

| 现象 | 更可能的原因 |
| --- | --- |
| 亮屏使用中也连不上或反复断开 | 网络 |
| 只在某个网络下出问题，例如移动数据不行、某个 Wi‑Fi 正常 | 网络 |
| 断开发生在 `client entering doze` 之后，直到亮屏或退出 Doze 才恢复 | 系统限制 |
| 断开前刚发生 Wi‑Fi / 移动数据切换或网络中断 | 网络切换，属正常 |
| 开机后一直未连接，`last close code` 为 `ERR_CLOSE_BY_USER_UNLOCKED` | 解锁后被系统断网（见下） |

`ERR_CLOSE_BY_USER_UNLOCKED` 本身是正常记录：开机后、首次解锁前 GMS 已经连上，解锁时 GMS 主动断开并重连。
如果之后一直显示未连接，说明解锁后的重连失败了。ColorOS 电池服务会在开机完成（首次解锁）后约 5 秒探测 Google 连通性，
探测失败时会把 GMS、GSF、Play 商店设为禁止联网（策略 4）。此时代理软件通常还没启动，在国内网络下探测基本都会失败。
请检查模块是否勾选了“系统框架”和“电池”作用域，并用下文“排除系统限制”中的命令查看 GMS 的联网策略；
53-coloros-12 起，模块也会在系统框架中拦截这条禁网规则。

### 测试网络连通性

在 Termux 中执行（先 `pkg install curl dnsutils`）：

```sh
nslookup mtalk.google.com
curl -v --max-time 5 telnet://mtalk.google.com:5228
```

- `nslookup` 返回的不是 Google 地址（常见为 `142.250.x.x`、`74.125.x.x`、`172.217.x.x`、`108.177.x.x` 等）：DNS 被污染；
- `curl` 输出 `Connected to ...`：该端口可连通；超时或 `Connection refused`：端口被封或 IP 不对。
  解析结果可疑时，可以换一个已知正确的 IP 再测：`curl -v --max-time 5 telnet://<IP>:5228`。

请在出问题的网络下测试。

### 代理能通、直连不行

基本可以确定是网络问题。ColorOS 的限制是按 GMS 应用（UID）施加的，与流量走代理还是直连无关：
GMS 被设为禁止联网时，开代理也没有网；闹钟降级、待机分组也不会因为走代理而消失。
只有代理能通，说明系统没有拦 GMS，差别在网络路径上。

代理同时改变了 DNS（远端解析）和出口路径，可以进一步细分：

- 直连时 `nslookup` 结果不是 Google 地址：DNS 被污染。修改 hosts 或使用能返回正确结果的 DNS，
  通常直连即可恢复，不一定需要代理；
- 解析结果正确，但直连 5228 端口超时：IP 或端口被封，只能通过代理。

两个容易误判的情况：

- 手机上用 VPN 类代理时，代理应用在后台被系统杀掉或冻结，FCM 会随之断开，看起来像 FCM 问题。
  请给代理应用开启自启动并关闭电池优化；
- 亮屏时代理能通、熄屏后断开：这与走不走代理无关，按上文“看断开时机”判断是否集中在 Doze 期间。

### 排除系统限制

用下文脚本导出报告，确认：

- “Google 核心包联网策略”中 GMS 为 `policy=0`；
- GMS 待机分组不是 `40`；
- 日志中有 `Oplus Battery Google restrict broadcast hooks active` 和 `Oplus Google alarm restriction hook active`，
  没有 `hook error`。

以上正常、连通性测试不通：网络问题。连通性测试能通、断开却集中在 Doze 期间：可能存在尚未覆盖的系统限制，
请附上报告和断开时间点提交 Issue。

最直接的验证：连一个确定可用的网络，熄屏放置半小时。在该网络下不断开，而在原网络下断开，就是原网络的问题。

## 流量管理里没有 Google 入口

ColorOS 17 国行的“流量管理”会**隐藏** Google Play 服务、Google 服务框架和 Play 商店的 WLAN / 移动数据开关，
也拒绝用户修改它们。这三个包的联网由电池组件（`com.oplus.battery`）自动管理：探测 Google 失败时设为“全部禁止”，
成功时解除。这是系统设计，不是故障。

FCMFix 的电池作用域会把这次“全部禁止”改为“不限制”。电池组件每次开机都会重新设置一遍，所以勾选电池作用域并重启后，
通常会自动恢复。

这些策略保存在 ColorOS 自己的联网控制服务（`/data/oplus/common/networkingcontrolpolicy.xml`）中，
**不在** Android 的 `dumpsys netpolicy` 里。请用 [`collect-report.sh`](../scripts/collect-report.sh) 查看
“Google 核心包联网策略”一栏，或用 Root 执行：

```sh
su -c 'service call networking_control 2 i32 $(pm list packages -U com.google.android.gms | grep "^package:com.google.android.gms " | grep -o "uid:[0-9]*" | cut -d: -f2)'
```

输出 `Parcel(00000000 00000000 ...)` 表示不限制，`Parcel(00000000 00000004 ...)` 表示全部禁止。

### 勾选电池作用域并重启后仍是 4

如果系统记录了“用户手动改过”这些包（例如在旧版系统的流量管理里改过），电池组件会一直跳过它们，旧的禁止状态就会保留下来，
而 ColorOS 17 的界面又不允许修改。报告中 `oplus_user_change_gms_network_control` 不为 `0` 时就是这种情况。

FCMFix 53-coloros-11 起会自动处理：电池组件自己声明了 `IgnoreGmsUserSet=true`（ColorOS 17 即如此，此时流量管理隐藏这些开关）时，
电池进程读取该标记一律视为 `0`，电池组件每次开机重新接管这几个包，FCMFix 再把策略改为不限制。
日志中会出现 `Oplus Battery ignores stale GMS user-change flag`。电池组件没有这一声明的系统不受影响，
继续尊重用户的设置。

旧版本或自动处理未生效时，可以手动清除该标记并重启：

```sh
su -c 'settings put global oplus_user_change_gms_network_control 0'
```

重启后再次查看，GMS 应为 `policy=0`。

### 防火墙链

ColorOS 17 的按应用断网都挂在 `fw_INPUT` / `fw_OUTPUT` 两条链上：每条 REJECT/DROP 规则匹配一个 BPF 程序，
被限制的 UID 记录在对应的 BPF 表中，规则本身不出现 UID。报告的“防火墙链”一栏列出这些规则及命中次数（`pkts`），
程序名对应的功能：

| BPF 程序名包含 | 系统功能 |
| --- | --- |
| `reject_wlan_uid`、`drop_cell_uid`、`reject_qcom_uid` | 流量管理联网策略（含电池服务探测失败后的 Google 禁网） |
| `netdisable` | 深度睡眠按应用断网 |
| `hans` | 应用冻结时断网 |

FCM 连不上时，复现前后各导出一次报告，`pkts` 明显增加的规则就是拦截来源。
“ColorOS Google 拦截规则清理”一类模块会删除这两条链中的全部 REJECT/DROP 规则，使上述限制对所有应用失效；
排查时请先停用该模块并重启，否则这一栏看不到被删除的规则。

## 2. 导出报告（需要 Root）

1. 下载 [`collect-report.sh`](../scripts/collect-report.sh)（点击后选“Download raw file”），保存到手机的 `Download` 目录。
2. **重启手机**，开机后 3–5 分钟内，在 Termux 或 `adb shell` 中执行：

   ```sh
   su -c sh /sdcard/Download/collect-report.sh fcmfix-report-boot.txt
   ```

   开机时模块会打印各个 Hook 是否生效，logcat 缓冲区有限，时间长了这些行会被冲掉。
3. **复现问题后**再执行一次，例如熄屏等待 10 分钟，再发一条测试消息：

   ```sh
   su -c sh /sdcard/Download/collect-report.sh fcmfix-report-issue.txt
   ```

两份报告都保存在 `Download` 目录。脚本只读取系统状态和日志，不修改任何设置。

不方便下载脚本时，也可以直接复制 [`collect-report.sh`](../scripts/collect-report.sh) 的内容，保存为同名文件后执行。

## 3. 提交时附上

- 上面两份报告；
- FCM Diagnostics 截图；
- LSPosed 中 FCMFix 作用域截图；
- 网络环境：Wi‑Fi 或移动数据，是否使用代理；
- 测试应用，以及发送测试消息的大致时间。

报告中包含已安装应用的包名，公开发布前可以自行打码，但请保留 `fcmfix`、`GoogleController`、`OplusGoogle` 相关行。

## 报告怎么看

| 报告内容 | 含义 |
| --- | --- |
| “Google 核心包联网策略”中 GMS 为 `policy=4`（或 1、2） | GMS 被禁止全部（或部分）联网，见下文“流量管理里没有 Google 入口” |
| 待机分组为 `40`，或 `google_restric_info` 为 `1` 但日志中没有 `restrict broadcast cleared` | ColorOS 17 的 Google 限制未被解除，需要 53-coloros-10 或更新版本 |
| 日志中有 `hook error` 或 `Unsupported` | 某个 Hook 与当前固件不匹配，请在 Issue 中贴出这些行 |
| 以上均正常，但 FCM Diagnostics 一直 disconnected | 网络问题（DNS 污染或端口被封），见上文国内网络说明 |
