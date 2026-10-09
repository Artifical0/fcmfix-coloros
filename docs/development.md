# 开发说明

面向修改代码或发布版本的人。模块的功能与 Hook 点见[工作原理](how-it-works.md)。

## 推送信任边界

- 广播入口读取 Binder 真实 UID，核对 GMS、精确 RECEIVE action、明确且一致的目标包、
  允许列表。只在一个入口安装信任作用域，避免 AMS → BroadcastController 重复建立窗口。
- 不向 Intent extras 写入信任标记。线程内作用域保存目标和发送 UID；嵌套入口（包括
  空 Intent、不可信请求）遮蔽外层信任，正常返回、提前返回、异常均清理并恢复外层状态。
- 异步队列路径读取框架 BroadcastRecord.callingUid；服务路径按已核实的完整方法签名读取
  callingUid，并交叉核对 ServiceRecord.appInfo / 分类目标包。不以清除身份后的 Binder UID
  或字符串 `bsgcm` 单独作为凭据。未知签名不启用对应服务 Hook，并记录日志。
- Firebase 应用自拉起服务只在自己的有效窗口内放行，不能用自发 RECEIVE 建立窗口。
  ColorOS 的 `com.google.android.gms.gcm.ACTION_TASK_READY` 仅在已核验 GMS 的 `bsgcm`
  bind 分支接受，不全局认作 FCM 广播。
- 单 UID 20 秒投递窗口，单调时钟、最大到期时间语义；到期查询与续期串行化，避免删除新窗口。
  不永久放开应用，也不全局关闭 Hans。UID 解析限进程所属用户，不跨用户套用窗口。
- Ice Box 激活使用单工作线程、最多 8 个排队任务，尽力而为：不异步重放系统原方法，
  外部 SDK 挂起不阻塞原广播线程，本条消息可能丢失。
- 每组 Hook 经 `OplusHooks.runHook` 安装并登记结果，模块界面通过有序广播 `.query.status` 查询（与配置刷新相同的发送方身份校验）。
  一组 Hook 一个都没挂上时必须抛出异常，否则会被记为生效。
- Hook 适配层每次注册只执行自己的回调。回调故障记录日志并保留框架原调用/异常；清理在
  finally 中执行。
- 配置用一次 getAll 读取构造不可变快照，后台串行重载、一次发布；损坏配置保留上一份快照。
  Android 14+ 配置刷新核对发送包和 UID；旧 Android 不开放无身份校验的刷新入口，需重启。
- 通知 Hook 按已知完整签名匹配并校验实参；OTA 单点失配回退系统原行为。

## 诊断日志

- 日志只写入 logcat（标签 `fcmfix`），不再转发到 GMS：转发依赖 GMS 作用域，而模块不使用该作用域。
- 限频：完全相同的诊断文本 30 秒最多输出一次；每进程每 5 秒最多 32 条；缓存最多 128 个键；单条最多 2048 字符。
  下一条输出附带 `suppressed N diagnostic lines since last output`，这是被合并的日志行数，不是丢失的推送数。
- 初始化日志、解冻失败和找不到 Hook 点等关键错误走本地非限频路径。

## 构建与验证

本地使用 JDK 21、Android SDK 36：

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

不要删除失败的测试或关闭 lint 来规避问题，也不新增 lint baseline。

Hook 尽量挂在 Binder 入口、跨 jar 调用的服务方法或体积较大的方法上：同一 dex 内被调用的短方法（getter、私有小方法）
可能被 ART 内联，Hook 不会触发。

## 发布

- 普通 master push / PR：JUnit、lintDebug、lintRelease、Debug/Release 构建，无签名步骤。
- 版本号从 13.0 起为 `主版本.修订`，测试版加 `-rcN`，例如 `13.0-rc5`、`13.0`、`13.1`。
  此前的版本号形如 `53-coloros-12.1`，其中 53 是上游 fcmfix 的版本；旧 tag 保持不变。
- 推送 `v*` tag 触发正式发布：校验 tag 等于 `v` + `versionName`（如 `v13.0-rc5`），测试和 lint 通过后签名、校验签名、
  生成 SHA256SUMS，并以 `docs/releases/<versionName>.md` 作为 Release 说明。tag 含 `-rc` 时自动标为预发布。
- LSPosed 分发仓库 `Xposed-Modules-Repo/io.github.artifical0.fcmfix.coloros` 只发布正式版，tag 为 `versionCode-versionName`
  （如 `77-13.0`），上传同一个签名 APK；发布后会推送给所有用户。
- versionCode 只能递增。
