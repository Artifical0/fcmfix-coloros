# FCMFix ColorOS 53-coloros-11-rc2

在 53-coloros-11-rc1 基础上合并社区 fork 中适用的改动。包名与签名不变，versionCode 66，可直接覆盖安装，安装后请重启。

## 相比 rc1

- **FCM 后的后台作业**：Hans 会拦截后台受限应用的作业。收到 FCM 后需要调度同步作业拉取内容的应用（如 Gmail、使用
  WorkManager 的应用）因此延迟。现在投递窗口内的应用作业不再被拦截。参考 @Tlipoca1337 fork 中的 Gmail 放行，推广到所有允许列表应用。
- **HyperOS 空指针防护**：`AutoStartManagerServiceStubImpl` 遇到没有 component 的 Intent 时不再出错（来自 @Tlipoca1337）。

rc1 的全部内容见 [53-coloros-11-rc1](53-coloros-11-rc1.md)。各 fork 改动的评估见
[ColorOS 17 Hook 核对](https://github.com/Artifical0/fcmfix-coloros/blob/master/docs/oneplus15-coloros17-fcm-analysis.md#社区-fork-对照53-coloros-11-rc2)。

## 验证情况

- 44 项单元测试通过；lintDebug / lintRelease 无错误；Debug / Release 构建成功。尚未真机验证。
- 日志中应新增 `Oplus Hans job FCM-window hook active`；收到 FCM 后作业放行时出现 `Oplus FCM job-restriction bypass`。
