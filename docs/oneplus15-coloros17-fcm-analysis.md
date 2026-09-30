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
