# FCMFix ColorOS 发布素材

本文档提供可直接复制到酷安、微信公众号和技术论坛的发布文案。发布前可按平台删减
技术细节，但不建议删除适用版本、Root 风险和 OTA 兼容性提示。

## 项目信息

- 项目名称：FCMFix ColorOS
- 安装包名：`io.github.artifical0.fcmfix.coloros`
- 适用设备：一加 15 国行版（PLK110）
- 已验证系统：ColorOS `16.0.10.500` / Android 16
- 运行条件：Root、LSPosed、已安装并能联网的 Google Play 服务
- GitHub：<https://github.com/Artifical0/fcmfix-coloros>
- 最新版本：<https://github.com/Artifical0/fcmfix-coloros/releases/latest>
- APK SHA-256：见对应 GitHub Release 说明
- 上游项目：<https://github.com/kooritea/fcmfix>

## 推荐标题

### 酷安标题

```text
[Root/LSPosed] 一加 15 国行 ColorOS 16 FCM 后台推送修复，已通过强停唤醒测试
```

### 公众号标题

```text
国行一加 15 收不到 FCM 推送？我做了一个 ColorOS 16 专用修复
```

### 技术论坛标题

```text
[源码公开][LSPosed] OnePlus 15 / ColorOS 16 国行 FCM 限制分析与应用级修复
```

## 一句话简介

```text
这是一个面向一加 15 国行 ColorOS 16 的 LSPosed 模块，用于修复 GMS 已收到 FCM 消息，
但系统不允许广播拉起后台、无进程或 stopped 应用的问题，同时阻止 ColorOS 自动禁用
Google 核心服务联网。
```

## 酷安发布稿

```text
项目：FCMFix ColorOS

一加 15 国行版的 Google Play 服务可以建立 FCM 长连接，但部分应用在划掉后台、被系统
清理，甚至进入 stopped 状态后，GMS 发出的推送广播可能被 ColorOS 拦截。常见现象是：
应用打开时推送正常，退出后收不到；再次打开应用，积压消息才一起出现。

我对一加 15 国行版和国际版 ColorOS 16 完整包进行了差分，确认它不是单一开关造成的，
而是涉及 Google 联网策略、应用分类、自启动、GCM bind、Hans 冻结、代理唤醒和 Doze
白名单等多层限制。

这个版本基于 kooritea/fcmfix 的公开源码适配，主要做了以下处理：

1. 允许真正的 FCM 广播到达允许列表中的 stopped 应用；
2. 绕过 ColorOS 国行版对 FCM 目标应用的分类、自启动、GCM bind 和 service 限制；
3. 在推送到达时解除必要的 Hans 冻结和代理限制；
4. 恢复 GMS、GSF 和 Play 商店缺失的 Doze 条目；
5. 阻止 ColorOS 电池组件自动给 Google 核心包写入“禁止全部联网”策略；
6. 不修改用户在系统流量管理中手动设置的联网权限（电池组件声明 `IgnoreGmsUserSet=true`、流量管理隐藏 Google 联网开关时，
   系统框架会丢弃针对 Google 核心包的禁网规则）。

实机验证环境：

- 一加 15 国行版 PLK110
- ColorOS 16.0.10.500 / Android 16
- Root + LSPosed
- Nekogram tw.nekomimi.nekogram

严格测试时，先通过 am force-stop 将 Nekogram 置为 stopped=true，并确认进程完全不存在。
发送消息后，GMS 成功发出 com.google.android.c2dm.intent.RECEIVE，system_server 拉起
Nekogram，应用恢复为 stopped=false，并在约 0.57 秒后生成通知。

同时验证过：coloros_gms_extreme_fix 根模块保持禁用、ELSA 没有挂载，只启用本 APK
也能完成上述推送，因此在已验证的系统版本上不需要再搭配该 Magisk 模块。

安装方法：

1. 手机需要 Root 并安装 LSPosed；
2. 安装 APK，在 LSPosed 中启用；
3. 作用域只勾选“系统框架”和“电池 com.oplus.battery”；
4. 重启手机；
5. 打开 FCMFix，只勾选确实需要 FCM 推送的应用；
6. 确认目标应用通知权限和应用内通知开关已开启。

注意：

- 这是系统级 Hook，不是普通免 Root 应用；
- 它不会安装或替代 Google Play 服务；
- 不支持 FCM 的应用不会因此获得推送；
- 可以和官方 FCMFix 同时安装，但不要在 LSPosed 中同时启用；
- 目前只对 PLK110 16.0.10.500 完成了完整实机回归，其他设备和 OTA 版本请自行测试；
- 使用前请准备好安全模式或禁用 LSPosed 模块的恢复手段。

项目源码公开，APK 本身不申请 INTERNET 权限，不经过开发者服务器中转消息。

GitHub：
https://github.com/Artifical0/fcmfix-coloros

下载：
https://github.com/Artifical0/fcmfix-coloros/releases/latest

SHA-256：见对应 GitHub Release 说明

欢迎同系统用户反馈测试结果。反馈时请提供设备型号、完整系统版本、LSPosed 版本、目标
应用包名，以及失败发生在普通划卡还是 am force-stop 场景。
```

### 酷安建议标签

```text
#一加15 #ColorOS16 #Root #LSPosed #FCM #谷歌服务 #源码公开 #玩机
```

## 微信公众号发布稿

### 摘要

```text
通过国行与国际版 ColorOS 16 固件差分，定位一加 15 国行版 FCM 推送被拦截的多层原因，
并制作了一个经过 stopped=true 强停场景验证、源码公开的 LSPosed 修复模块。
```

### 正文

```text
很多国行 Android 用户遇到过一种很典型的推送问题：应用打开时通知正常，一旦划掉后台
或被系统清理，消息就不再出现；等到再次打开应用，积压的消息才一起到达。

在一加 15 国行 ColorOS 16 上，这并不一定是 Google Play 服务没有连接。实际情况可能
是 GMS 已经收到了 FCM 消息，但系统拒绝将广播交给处于后台、无进程或 stopped 状态的
目标应用。

为确认限制来源，我对 PLK110 国行版与 CPH2747 国际版 ColorOS 16.0.10.500 完整包进行
了提取和差分。结果显示，国行版 FCM 限制不是一个简单开关，而是由多层策略共同组成：
Google 核心包联网策略、应用分类、自启动检查、GCM bind、Hans 冻结、代理唤醒，以及
区域 Doze 白名单差异。

基于这些结果，我在 FCMFix 公开源码的基础上制作了一加 15 / ColorOS 16 适配版。模块
通过 LSPosed 在 system_server 中修复 FCM 广播和后台启动路径，并在 ColorOS 电池进程
中拦截系统自动写入的 Google 全网禁用策略。

修复范围经过了严格限制：应用启动放行只对用户在 FCMFix 中勾选的允许列表生效；联网
策略 Hook 只处理电池进程针对 Google 核心包自动提交的 POLICY_REJECT_ALL，不会覆盖
用户在流量管理中手动设置的联网权限。

实机测试时，Nekogram 被 am force-stop 明确设置为 stopped=true，且确认没有任何运行
进程。消息到达后，GMS 发出标准 c2dm.RECEIVE，system_server 成功启动应用，约 0.57 秒
后通知生成。测试期间原有的 GMS Magisk 修复模块保持禁用，因此在已验证固件上可以仅
使用这个 LSPosed APK。

适用环境：一加 15 国行版 PLK110、ColorOS 16.0.10.500、Root、LSPosed，以及已经安装
并能够连接 FCM 的 Google Play 服务。

LSPosed 作用域只需要勾选“系统框架”和“电池 com.oplus.battery”。安装和修改作用域后
必须重启，然后在 FCMFix 应用列表中勾选需要后台推送的目标应用。

需要强调的是，这不是一个免 Root 工具，也不会安装 Google 服务或让不支持 FCM 的应用
获得推送。系统 OTA 后关键类和方法可能变化，因此每次大版本更新都应重新测试。

项目源码、APK 和完整技术分析均已公开：
https://github.com/Artifical0/fcmfix-coloros

已验证版本下载：
https://github.com/Artifical0/fcmfix-coloros/releases/latest
```

## 技术论坛发布稿

```text
项目基于 kooritea/fcmfix，针对 OnePlus 15 国行 PLK110 ColorOS 16.0.10.500 适配。

问题并非只有 Android package stopped flag。CN/EX 固件差分确认了以下相关路径：

- CN feature：oplus.software.disable_cn_gms / hans_restriction；
- Battery GoogleRestrictionController：探测失败后 setUidPolicy(uid, 4)；
- OplusAppStartupManager：bsgcm / system[gcm] bind-service 检查；
- OplusStartupStrategy：isAppClassifyRestricted；
- OplusHansDBConfig：GMS/GSF/Play 组件限制；
- OplusDeviceIdleHelper：国行区域 GMS Doze 条目缺失；
- AMS/BroadcastController：FCM Intent 到 stopped package 的入口过滤。

本适配版增加：

1. Android 16 broadcastIntentWithFeature 动态签名匹配；
2. FCM Intent 的 FLAG_INCLUDE_STOPPED_PACKAGES；
3. 允许列表范围内的 classify/GCM bind/start-service bypass；
4. Hans GMS restriction 与代理解冻适配；
5. 原列表合并后的 GMS/GSF/Play Doze 追加；
6. com.oplus.battery 进程内对 Google UID 的 POLICY_REJECT_ALL → POLICY_NONE。

没有直接合并上游 PR #262 的清空/重建 Doze 列表方案，因为本机 mDefaultWhitelist 不含
GMS，且清空会丢失 ColorOS 已合并的本地、RUS、NFC 和用户规则。本版本只追加缺失项。

验证结果：coloros_gms_extreme_fix 禁用且无 ELSA bind mount；Nekogram 在
stopped=true、无进程状态下，由 GMS UID 10123 通过 c2dm.RECEIVE 拉起，约 0.57 秒后
发布通知。Google 核心 UID networking_control policy 均为 0。

作用域：system + com.oplus.battery。

源码与分析：
https://github.com/Artifical0/fcmfix-coloros
```

## 简短转发文案

```text
公开了一加 15 国行 ColorOS 16 的 FCM 推送修复源码：解决应用划掉后台、无进程或
stopped=true 后 GMS 无法拉起的问题，并修复 ColorOS 自动禁用 Google 核心包联网。
已在 PLK110 16.0.10.500 用 Nekogram 完成强停实测。需要 Root + LSPosed，作用域为
系统框架 + 电池。

项目与下载：https://github.com/Artifical0/fcmfix-coloros
```

## 建议配图

建议准备 4 至 6 张图，并注意遮挡账号、聊天内容、设备序列号和 IP 地址：

1. 项目封面：标题、一加 15、ColorOS 16、Root + LSPosed；
2. LSPosed 作用域截图：只显示“系统框架”和“电池”；
3. FCMFix 允许列表截图；
4. FCM Diagnostics 已连接截图；
5. 强停前后的包状态与进程对比；
6. 推送成功通知截图。

不要公开包含联系人姓名、聊天正文、设备序列号、局域网地址、Google 账号或 LSPosed
完整模块列表的原始截图。

## 用户反馈模板

```text
设备型号：
系统完整版本：
Android 版本：
Root 方案及版本：
LSPosed 版本：
FCMFix 版本：
目标应用名称和包名：
作用域是否为 system + com.oplus.battery：
FCM Diagnostics 是否已连接：
普通划卡后能否收到：
am force-stop 后能否收到：
是否安装其他 GMS/省电/冻结模块：
相关 LSPosed 日志：
```

## 发布时应保留的声明

```text
本项目为第三方源码适配，与一加、OPPO、Google、Telegram/Nekogram 及上游项目作者没有
官方隶属关系。系统级 Hook 存在兼容性风险，仅在文中列出的设备和固件上完成验证。
使用者应自行备份数据并准备恢复手段。转载或二次修改应注明上游来源和原作者，授权
范围以各代码权利人的声明为准。
```

## 许可证提示

当前仓库及所基于的上游版本没有提供 `LICENSE` 文件，因此宣传时使用“源码公开”，不要
写成 MIT、Apache、GPL 或“可自由商用/二次分发”。如果计划长期维护、接受外部贡献或
允许第三方分发 APK，应先向上游确认授权关系，再为自己有权许可的新增代码选择许可证。
