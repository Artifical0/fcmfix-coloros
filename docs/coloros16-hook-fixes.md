# ColorOS 16 Hook 兼容修复

2026-10-12，对照 [issue #4](https://github.com/ByteJuno/fcmfix-coloros/issues/4) 的三项失配，
使用本地一加 15 国行 `PLK110_16.0.10.500(CN01)` 全量固件（Android 16，SDK 36）核对。
反馈机型为真我 GT7 Pro `RMX5010_16.0.10.500(CN01)`；该机型的完整固件及真机尚未验证。

## 固件依据

核对 `oplus-services.jar`、`oplus-framework.jar` 和 `system_ext/app/Battery/Battery.apk`。
使用 jadx 查看调用语义，并直接读取三个文件中的 DEX 声明确认方法参数、返回类型和所属类。

| 文件 | SHA-256 |
| --- | --- |
| `oplus-services.jar` | `f53379d7ada40c52fae498964e9ec9a01111bcd7a865360156d5412a958b269e` |
| `oplus-framework.jar` | `3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78` |
| `Battery.apk` | `752597dd6a739ddd8bbfd9991a31465790e9b846004a96458223948d5ee32ebc` |

## 三项调整

### 弱信号联网白名单

该固件的 `OplusHansConnectivityManager` 仅包含蓝牙管控，没有
`isWeakSignalNetWhiteList(String)`、弱信号或防火墙成员。
`OplusHansRestriction` 的闹钟判定中也没有 ColorOS 17 的这条弱信号限制路径。

仍优先安装已有的弱信号白名单方法。不存在时，仅在 SDK 36 且类符合已核对的旧版结构时，
记录为“不适用”。识别检查包括已知蓝牙方法/字段的类型、Object 父类，以及不存在 weak/firewall 成员。
类缺失、未知结构、弱信号方法更名或重载变化、Hook 安装失败仍报告“失配”。

Hook 状态新增独立的 skipped 类别，自查及导出报告显示“不适用”数量和原因，
不计入生效数量，也不归入失败。旧版状态应答未包含 skipped 时仍可读取。

### 深睡恢复后的 GMS 强停

ColorOS 16 实际入口是 `OsenseResManagerService$3.requestSceneAction(Bundle): void`，
没有 `requestSceneActionSync`。它根据 action 分派到公共清理路径，
`CommonExternalClean` 中 `strategy=2` 对应 force-stop。

现在同时匹配：

- `requestSceneAction(Bundle): void`（本次固件已核实）；
- `requestSceneActionSync(Bundle): boolean`（保留 ColorOS 17 入口）。

Hook 仍安装在 Binder 实现的方法上。只有目标为 `com.google.android.gms`、
reason 为 `DeepSleepLogicDisNetRestore`、subAction 为 `CommonExternalClean` 且 strategy 为 2 时跳过。
void 入口返回 null，boolean 入口返回 false；其他包、其他清理原因及非 force-stop 策略保持原调用。

本次 ColorOS 16 的 Battery 没有找到发起该特定清理请求的代码，因而这条保护在该样本上通常不会触发。
“Hook 已安装”不代表存在触发行为，也不能单凭其状态认定旧版推送不通由它造成。

### 电池深睡联网白名单

ColorOS 16 没有 `ControllerCenter.addPkgWhiteArray/addUidWhiteArray`。
它通过 `determineNetWorkWhiteUidList` 等构造名单，再由
`networkDisableWhiteListInner(List,int,int,boolean)` 反射调用
`android.nwpower.OAppNetControlManager.networkDisableWhiteList(List,int): int`。

电池作用域现在 Hook 这个最终管理接口，在开始限制网络时复制名单、补入 GMS UID 后下发。
名单恢复调用（enable=1）不改写；空参数、无法初始化 Context、无法解析 UID 时保持原调用。
已含该 UID 时保持原名单。系统框架中现有的 `OAppNetControlService` 名单保护继续作为另一层检查。
该入口也覆盖此前 ColorOS 17 白名单构造器可能被内联而不触发的情况。

## 验证与装机观察

基于 master `bf7e8cd`（13.0）修改；本轮没有调整版本号。

- 原始 DEX 的四组契约检查通过：旧版联网组件、联网管理接口、Battery 名单方法、Osense Binder。
- 82 项 JUnit 测试通过，0 失败、0 错误、0 跳过。新增覆盖两代 Binder 签名、未知签名拒绝、
  GMS 清理条件、旧版功能缺失判定，以及“不适用/失败/生效”状态转换。
- `lintDebug`、`lintRelease` 均为 0 错误、8 条警告，改动文件没有 lint 问题。
- `assembleDebug`、`assembleRelease` 成功；使用 JDK 21、SDK 36，在英文路径副本构建，
  并核对源码副本与工作区的一致性。Release 构建产物未签名。

完成源码验证后，装机需要检查：

1. 更新并重启后导出自查。已识别旧版弱信号组件显示“不适用”；Osense 和电池名单显示生效。
2. 深睡开始限制网络时，实际下发名单包含 GMS UID；恢复时名单及原调用结果保持正常。
3. 检查熄屏期间 FCM 连接和真实消息通知，不把 Hook 生效或广播成功等同于通知已显示。
4. 如某机型仍失配，保留实际异常、方法签名及完整系统版本；未知变体不会被静默当作“不适用”。

本轮完成的是上述固件对照、源码修复和构建验证；真我 GT7 Pro 的装机与深睡测试结果仍待补充。

## 一加 13 `PJZ110_16.0.3.502(CN01)`

2026-10-14，对照 [issue #5](https://github.com/ByteJuno/fcmfix-coloros/issues/5) 反馈者上传的
`/system/framework/oplus-services.jar`（该机型 `system_ext` 中没有此文件）核对。13.1-rc1 上仍有 3 项失配，
都是整个类不存在：

| 文件 | SHA-256 |
| --- | --- |
| `classes.dex` | `50b0ace9b517eefdc4f525f7509bc8efd6054b16ee9765d59269b1d6b53e405b` |
| `classes2.dex` | `08791db011ddf774661816c3f871adca346e8b86ea560cb8a491b576c2f4e84c` |

### 可疑组件拦截（广播、服务两项）

没有 `MaliciousRestrictPolicy`。可疑组件名单只在
`OplusAppStartupManager.preventByMaliciousCompStartegy(int,int,String,String): boolean` 中检查，
全部两个 DEX 里唯一的调用方是 `shouldPreventStartActivity`：只拦截 Activity 启动，不拦截广播和服务，
FCM 送达不受影响。

`MaliciousRestrictPolicy` 不存在、且 `OplusAppStartupManager` 中名字含 malicious 的方法只有这一个
（`dumpMaliciousConfig` 除外）并且签名一致时，两项记为“不适用”；出现别的 malicious 方法或签名变化仍报告失配。

### 弱信号联网白名单

没有 `OplusHansConnectivityManager`，两个 DEX 中也没有任何弱信号成员（ColorOS 17 在 `OplusHansManager`
里有 `STRICT_MODE_SCENE_WEAK_SIGNAL` 等）。类不存在、且 `OplusHansManager` 中没有名字含 weak 的方法或字段时，
记为“不适用”。

### 其余

13.1-rc1 起 Osense `requestSceneAction` 和电池 `OAppNetControlManager.networkDisableWhiteList` 在该机型上已生效。
