# OnePlus 15 ColorOS 17 Hook 核对

## 样本

- 全量包：`PLK110_17.0.0.102(CN01)`，`ota_version=PLK110_11.C.75_1750_202609240641`
- Android 17（SDK 37），安全补丁 2026-09-01，`post-build=OnePlus/PLK110/OP60FFL1:17/CP2A.260605.016/...`
- 来源：OPPO 官方 CDN（`allawnfs.com`）全量包。只从 payload 中提取了 `system`、`system_ext` 两个分区
- 核对文件：`services.jar`、`oplus-services.jar`、`oplus-framework.jar`、`oplus-service-jobscheduler.jar`、
  `system_ext/app/Battery/Battery.apk`（`com.oplus.battery`）

核对方式：用 dexdump 导出全部方法/字段签名逐项比对，关键方法用 jadx 反编译确认语义。

## 核对结果

| Hook 点 | ColorOS 17 | 处理 |
| --- | --- | --- |
| `ActivityManagerService/BroadcastController.broadcastIntentWithFeature` | 存在，签名同 AOSP 17 | 不变 |
| `BroadcastController.broadcastIntentLocked` → `broadcastIntentLockedTraced` | 包装层 + 实际实现，参数含 `BroadcastOptions` | 两者都 Hook，调用链 deoptimize |
| `BroadcastRecord.intent/callingUid`、`ServiceRecord.appInfo` | 存在 | 不变 |
| `OplusAppStartupManager.isAllowStartFromBindService/StartService` | 签名与 ColorOS 16 完全一致，`bsgcm` / `system[gcm]` 常量不变 | 不变 |
| `OplusStartupStrategy.isAppClassifyRestricted(String,String,String,int,int,Intent)` | 一致，所有投递路径仍调用此重载 | 不变 |
| `OplusStartupStrategy.isAppClassifyRestricted(int,String,String,Long)` | **新增**，无 Intent，按用户查询 | 静默跳过，不再打印“Unsupported”日志 |
| `OplusStartupStrategy.isGoogleRestricInfoOn(int): Boolean` | 一致 | 不变 |
| `OplusAppStartupManager.shouldPreventSendReceiver(Real)` | 一致 | 不变 |
| `OplusProxyBroadcast.shouldProxy(...)`: `RESULT{NOT_INCLUDE,NOT_PROXY,PROXY}` | 一致 | 不变 |
| `OplusProxyWakeLock.unfreezeIfNeed(int,WorkSource,String,String)`、构造器 `(Object)` | 一致，内部调用 `OplusHansManager.unFreezeForwl` | 不变 |
| `OplusHansDBConfig.isSysRestrictionCpn(...)`: `SysRestrictionResult.NOT_PROXY` | 一致 | 不变 |
| `OAppNetControlService.hansUpdateFirewallList(Pair<Integer,Boolean>,int,int)` | 一致，`second=false` 才加入防火墙 | 不变 |
| `HansSceneManager.freeze/freezeAndTransState/freezeDirectlyForSceneCombo/freezeViaSM` | 一致，`Freezing.IMPORTANT` 存在 | 不变 |
| `HansCGroup.hansFreezeLocked(OplusHansPackage,String)` | 一致，内部经 `sendHansSignal → freezeByCgroupV2` | 不变 |
| `HansCGroup.FastFreezeEnter(int)` | **已移除**，改为 `fastFreezeEnter(int uid, String pkgName)` | 新签名纳入匹配（`HansSignature`） |
| `OplusBgSceneManager.registerGmsRestrictObserver/updateGmsRestrict` | 一致 | 不变 |
| `OplusDeviceIdleHelper.getNewWhiteList(ArrayList)` / `getGoogleRestrictSwitch()` | 签名与语义一致，但类从 `oplus-services.jar` **移到** `oplus-service-jobscheduler.jar` | 该 jar 在常规 SYSTEMSERVERCLASSPATH，同一 ClassLoader，无需改动 |
| `NotificationManagerService.cancelAllNotificationsInt(IILString;LString;IIII)V` | 一致 | 不变 |
| `android.net.OplusNetworkingControlManager.setUidPolicy(int,int)` | 一致 | 不变 |
| 电池 `GoogleRestrictionController` | 仍对 gms/vending/configupdater 调用 `setUidPolicy(uid, 4)` | 不变 |

## 新发现

- `com.android.server.am.BroadcastProxyAction.enqueueProxyBroadcastLocked(boolean,BroadcastRecord,Object,boolean)`：
  Osense CpnProxy 在游戏、应用启动、相机等场景下暂存冷进程广播。是否代理取决于配置下发的
  隐式 action 列表（位于未提取的 my_* 分区，且可被云控更新），无法静态证明 FCM 不受影响。
  本版加入防御性 Hook：仅当 `BroadcastRecord` 归因为真实 GMS、精确 RECEIVE、明确且在允许列表内的目标时返回
  `false`（不代理），其余保持系统原行为。
- `IOplusGoogleDozeRestrict`：新接口，但 `OplusJobSchedulerServiceFactoryImpl` 未提供实现，运行时为默认空实现，
  本固件无需处理。

## Google 限制广播（53-coloros-10-rc2）

用户反馈 rc1 在 ColorOS 17 上 FCM 连不上。继续核对后确认还有一条未覆盖的路径：

- `Battery.apk` 的 `GoogleRestrictionController` 在连通性探测失败时，除了 `setUidPolicy(uid, 4)`，还会带
  `oplus.permission.OPLUS_COMPONENT_SAFE` 权限广播 `oplus.intent.action.google_restrict_change`
  （`restrict_enable`、`restrict_list`、`restrict_list_change`），并写入 `google_restric_info=1`。
- system_server 中有三个监听者：
  - `oplus-service-jobscheduler.jar` 的 `OplusGoogleRestrictionHelper` → `OplusGoogleAlarmRestrict.updateGoogleAlarmTypeAndTag`：
    把受限 Google 包的 `RTC_WAKEUP(0)` / `ELAPSED_REALTIME_WAKEUP(2)` 改为 `RTC(1)` / `ELAPSED_REALTIME(3)`；
  - `AppStandbyControllerExtImpl`：受限时把名单内的包放入 bucket 40（RARE，reason 1536）；
  - `OplusNetworkPolicyManagerServiceEx.matchGoogleRestrictRule`：受 `getGoogleRestrictSwitch()` 约束，模块已将其置为 false。
- 处理：在电池进程拦截该广播，只把 `restrict_enable=true` 改为 `false`；system_server 中 `isGoogleRestrct()`
  返回 `false` 作为兜底（全固件仅闹钟限制调用）。

## 全面限制核查（53-coloros-11）

参照旧 Magisk 模块 `coloros_gms_extreme_fix`（ELSA 补丁、Doze 白名单、`google_restric_info`、iptables Google 规则），
对 `oplus-services.jar`、`oplus-service-jobscheduler.jar`、`OplusExSystemService.apk`、`Battery.apk` 中涉及 GMS、
广播冷启动和服务启动的限制逐项核对。

### 新增处理

| 限制 | 位置 | 影响 | 处理 |
| --- | --- | --- | --- |
| GMS 受限状态被直接读取 | `OplusBgSceneManager.isGmsRestricted()`：`OplusProxyWakeLock.acquireWLFilterGms`（丢弃 GMS/GSF partial wakelock）、`SysAppCtrlPolicy`（OGuard 耗电管控）、`restrictFgAssWakeupForGmsApp`（前台应用唤醒 GMS） | 受限时 GMS 无法持有唤醒锁、应用向 GMS 注册 token 受限 | getter 恒为 `false` |
| 弱信号防火墙 | `OplusHansConnectivityManager.setWeakSignalUidFireWallChain`：熄屏弱信号时对 Doze 白名单中的第三方与 GMS 名单应用设置防火墙链 9 拒绝；`OplusHansRestriction.isBlockedAlarmPolicy` 同场景拦截闹钟 | 弱信号熄屏时 GMS 断网 | `isWeakSignalNetWhiteList` 对 GMS/GSF/Play 返回 `true` |
| 冷启动拦截 | `OplusAppStartupManager.validStartProcessFromBroadcast`：持久限制名单、`restrictStartupBg` 时的限制名单且无进程 | FCM 无法拉起无进程的应用 | 按 `BroadcastRecord` 核验可信 FCM 后放行 |
| 云控恶意应用名单 | `MaliciousRestrictPolicy.shouldPreventBroadcastByMaliciousCheck` / `shouldPreventServiceByMaliciousCheck`，可按包整体限制 | FCM 广播、GMS GCM 绑定、应用绑定自身 `FirebaseMessagingService` 被拦 | 广播按 `BroadcastRecord` 核验；服务仅放行真实 `ProcessRecord` 为 GMS 或投递窗口内目标自身的调用 |
| 关联启动限制 | `OplusLinkStartManager.handleProcessBroadcastStartLocked`（默认开启，阈值 5 级，云控可调） | GMS 作为拉起方级别过高时后续冷启动被拦 | 可信 FCM 放行 |
| 深度睡眠断网 | `Battery.apk` `com.oplus.deepsleep.ControllerCenter`：白名单含 `com.heytap.mcs`、VoWiFi、P2P、IM，不含 GMS | 夜间深度睡眠时 FCM 断开 | `addPkgWhiteArray` / `addUidWhiteArray` 追加 GMS UID |
| Hans 作业限制 | `OplusHansManager.checkJobIfRestricted`：后台受限应用的作业被拦截 | FCM 通知后需要同步作业拉取内容的应用（如 Gmail）延迟 | 投递窗口内的 UID 不拦截（参考 fork @Tlipoca1337 的 Gmail 放行） |
| 深度睡眠闹钟延后 | `OplusDeepSleepHelper.filterDeepSleepAlarm` / `ruleMatchDeepSleepAlarm` | 匹配规则的闹钟等待网络恢复 | GMS/GSF 闹钟不延后 |

### 核查后无需处理

- `OplusPartialWakeLockCheck.FORCE_RELEASE_LIST` 含 GMS，但只处理熄屏后持有超过阈值（默认 300 秒）的唤醒锁，FCM 心跳与收消息的唤醒锁仅数秒。
- `OplusAppStartTracker` 启动配额：`isInvalidRecord` 仅对 Oplus 自研应用生效。
- Hans 服务代理 `isProxyService`：仅作用于处于代理状态（冻结）的 UID，投递窗口内目标不会被冻结。
- `OplusResourcePreloadManager.preloadServiceBlock`：仅针对系统预加载的进程。
- `OplusAppStartupConfig.isInSysLongDelayList`（国行加入 GMS 名单）：本固件无调用方。
- `OplusPermissionInterceptPolicy`（GMS 在跳过名单）、`OplusEapManager`（崩溃上报）、Hans GMS 定位代理：与推送无关。
- 防火墙：Oplus 按 UID 拒绝联网的来源只有联网控制服务（`networking_control`，电池作用域已处理）与弱信号防火墙（本版处理）；
  `fw_INPUT` / `fw_OUTPUT` 为 AOSP 链，`oplus_dns`、`zte_fw_gms` 链在本固件中不存在。
- 系统应用冻结判定明确排除 GMS 名单；未发现 Hans 冻结 GMS 本身的路径，但 ELSA `whitePkg` / `hansKeepAlive` 在 ColorOS 17 中的作用未逐项证明。

深度睡眠结束亮屏时，电池组件在“逻辑断网”后会主动结束 GMS 进程以促使重连，本版未改动该行为。

### 社区 fork 对照（53-coloros-11-rc2）

- @Tlipoca1337：Gmail 同步作业放行（已推广为投递窗口内作业放行）、HyperOS `AutoStartManagerServiceStubImpl` 空 component 防护（已合并）；
  配置加载前禁止修改允许列表、deoptimize（已在上游）；UnifiedPush action（与“仅信任真实 GMS 发送者”的模型冲突，未合并）。
- @ligensuo711：PHK110 的 `shouldPreventStartProcess` 放行（ColorOS 17 上该方法只拦截伪造的进程记录哨兵，放行会让伪造记录继续启动，未合并）；
  `checkReceiverIfRestricted`（ColorOS 17 上无实现，恒不拦截）；`isAllowStartService` 无条件放行（范围过宽，且只在目标冻结时调用）；
  `skipScheduleReceiver*` 整体放行（已由各道带来源核验的放行覆盖）；投递窗口 60 秒（未采纳，保留 20 秒）。
- @zopulus：独立发现深度睡眠白名单（本仓库实现覆盖两种断网模式）；Ice Box 同步激活（与不阻塞广播线程的设计相反，未合并）；
  其余为包名与界面重写。

## 实测：深度睡眠白名单未生效（53-coloros-12-rc1）

53-coloros-11 在 `Battery.apk` 的 `ControllerCenter.addPkgWhiteArray` / `addUidWhiteArray` 中追加 GMS UID。
维护者设备上夜间 FCM 断开 9 小时（`last close code ERR_IO_RST_HB`），`/data/oplus/os/battery/deepsleepRcd.txt` 记录：

```text
03:16:04 set netWork: function = new, uid = [1000:31304, 10122], result = success
03:16:04 MobileData disable, reason:disable, useCustomMethod:true, useNetworkDisableWhiteList:true
03:16:04 Wifi disable, reason:disable, useCustomMethod:true, useNetworkDisableWhiteList:true
03:16:04 disableNetWork:saveDisNetType:disNetType = 4
```

下发的白名单不含 GMS（UID 10123）。两个私有方法很短，预编译的 `Battery.apk` 很可能将其内联进调用方，导致 Hook 不触发。
GMS 心跳无回应后连接被重置；8 次重连失败后不再重试，网络恢复时 Wi-Fi 未变化，因此一直未重连。
`dumpsys alarm` 中 `GCM_CONN_ALARM` 最后一次在 9h31m 前；手动发送 `GCM_RECONNECT` 后立即恢复。

处理：

- 白名单最终经反射调用 `android.nwpower.OAppNetControlManager.networkDisableWhiteList(List, int)`，到达 system_server 的
  `OAppNetControlService.networkDisableWhiteList`（`enable != 1` 开始断网，`== 1` 恢复）。改为在服务端开始断网时追加
  GMS UID（纯 UID 条目被解析为整个 UID 放行），不受电池 APK 编译方式影响；
- 恢复成功后延迟 3 秒向 GMS 发送 `com.google.android.intent.action.GCM_RECONNECT`。

同一记录中 `handleDeepSleepLimitNetWhileDozeChange` 对链 9 设置拒绝的 UID 来自配置 `LimitNetAppList`，不含 GMS。
深度睡眠另有关闭移动数据 / Wi-Fi、开启飞行模式等方式，属于整机断网，白名单不适用；恢复时网络发生变化，GMS 会自行重连。

## 解锁后 Google 禁网（53-coloros-12-rc2）

有用户反馈 FCM Diagnostics 一直未连接，`last close code` 为 `ERR_CLOSE_BY_USER_UNLOCKED`，即首次解锁前已连接，
解锁时 GMS 断开重连后再也没连上。

`Battery.apk` 的 `GoogleRestrictionController.noteBootComplete()` 在开机完成后 5 秒用 `NetworkDetector` 探测 Google，
结果为 `RESULT_FAIL`（或重查 3 次仍未通过）时调用 `K(true, …)`，对 `google_network_restriction_list` 中的包执行
`OplusNetworkingControlManager.setUidPolicy(uid, 4)`。首次解锁时代理 App 通常尚未启动，国内网络下探测基本失败。
`networking_control`（OplusExSystemService）随后调用 system_server 的
`OplusNetworkManagementService.setFirewallUidRuleForNetworkType(type, uid, 2)` 下发 netd 规则
（type 2 / 3 分别为移动数据 / Wi-Fi，rule 2 拒绝、1 放行），全仓库只有这一处调用方。

53-coloros-11 只在电池进程改写 `setUidPolicy`，依赖电池作用域且该调用未被内联；维护者家中网络探测总能成功，
这条路径未经实测。处理：在 system_server 的 `setFirewallUidRuleForNetworkType` 中丢弃针对 GMS / GSF / Play 商店 /
ConfigUpdater（按 appId 匹配，覆盖分身用户）的拒绝规则，放行规则照常执行。策略文件中的值不变，
`service call networking_control 2` 仍可能读到 4，以 FCM 连接状态和日志 `Oplus Google network reject dropped` 为准。

53-coloros-12.1 起，这一层只在电池组件声明 `IgnoreGmsUserSet=true` 时生效。该接口收到的规则无法区分来自电池组件
还是流量管理；ColorOS 17 国行流量管理据此隐藏 Google 联网开关，用户无法手动设置，丢弃拒绝规则不会覆盖用户选择。
ColorOS 16 等仍开放开关的系统上不拦截，由电池进程内的 `setUidPolicy` 改写处理电池组件的自动禁网。

## 全链路复核（53-coloros-12-rc2）

按“GMS 存活 → GMS 联网 → 心跳闹钟 → Doze → 送达目标应用”逐段复核。53-coloros-11 的深度睡眠 Hook 因方法被内联而失效，
因此本次重点排查同类风险：同一 dex 内被调用的短方法（getter、私有小方法）上的 Hook 可能不触发，
关键限制改在 Binder 入口、跨 jar 调用的服务方法，或体积足够大、不会被内联的方法上兜底。

| 环节 | 发现 | 处理 |
| --- | --- | --- |
| 解锁后禁网 | 见上一节 | system_server `setFirewallUidRuleForNetworkType` 丢弃 Google 拒绝规则 |
| Google 限制广播 | 三个 system_server 接收方都从 `restrict_enable` 读取状态；`isGoogleRestrct()` 是单行 getter，与调用方同在 `oplus-service-jobscheduler.jar`，可能被内联 | 在 `ActivityManagerService.broadcastIntentWithFeature` 入口把 `restrict_enable=true` 改为 `false`，不依赖电池作用域 |
| `isGmsRestricted()` | 单行 getter，可能被内联 | 已有的 `updateGmsRestrict` / `registerGmsRestrictObserver` 空实现使字段保持 `false`，getter 被内联也不受影响 |
| Doze 白名单 | `getNewWhiteList` 是私有短方法，只被 `updateWhiteList` 调用，可能被内联 | 同时在 `whiteListChangedHandle(ArrayList)`（体积大，不会被内联）调用前补入 GMS / GSF / Play 商店 |
| 深度睡眠恢复后强制停止 GMS | `ControllerCenter.onScreenOn`：逻辑断网（`disNetType == 4`）恢复时经 Osense `CommonExternalClean` strategy 2 **强制停止** GMS（reason `DeepSleepLogicDisNetRestore`），GMS 的闹钟全部被取消，直到有客户端绑定才重新启动 | GMS 已在夜间联网白名单内，恢复后另发 `GCM_RECONNECT`，因此在 `OsenseResManagerService$3.requestSceneActionSync`（Binder 实现）跳过这一次强制停止；其他原因的清理不受影响 |

核查后无需处理：

- 熄屏闹钟对齐 `OplusAlarmAdjustment`：GMS 心跳 `GCM_HB_ALARM` 为非精确闹钟（实测窗口约 12 分钟），按 5 分钟或 NAT 间隔取整，
  偏移在一个间隔内，不会让心跳失效；`OplusAlarmNatDetect` 只管理配置中的首选心跳应用。
- 临时白名单 `shouldIgnoreTempWhitelistChange`：只是跳过已在白名单中的应用的重复通知，不阻止 FCM 高优先级消息的临时放行。
- Hans 冻结的广播延后（`DeferProxyPolicy`）：作用于被冻结的 UID，FCM 投递时模块已解冻目标，解冻会清除延后策略。
- `OAppNetControlService` 后台应用 / 进程断连：由 Hans 冻结触发，GMS 不会被冻结，投递窗口内的目标已放行。
- `OSysNetControlService` 增强夜间断网（`EnhancedNdAction`，云控规则）与电池深度睡眠的关网方式：整机关闭 Wi-Fi / 移动数据，
  所有推送通道一起断开，白名单不适用；恢复时网络变化，GMS 自行重连。
- `CNGmsControlService`（OplusExSystemService）：国行“Google 移动服务”开关。关闭时禁用 GMS 属用户选择；
  出境自动开启后回国（MCC 460）且状态为自动开启时会自动关闭，属于少见情况，未处理。
