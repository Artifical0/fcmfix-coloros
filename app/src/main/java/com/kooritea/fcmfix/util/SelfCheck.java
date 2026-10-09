package com.kooritea.fcmfix.util;

/**
 * Self-check protocol and verdicts. The module app adds EXTRA_CHECK to the status query;
 * system_server then also answers the keys below, and the app turns them into verdicts.
 */
public final class SelfCheck {
    public static final String EXTRA_CHECK = "selfCheck";

    public static final String KEY_CONFIG_LOADED = "check.configLoaded";
    public static final String KEY_MANUAL_COUNT = "check.manualCount";
    public static final String KEY_AUTO_ALLOW = "check.autoAllow";
    /** Installed FCM apps system_server knows of, or UNKNOWN when it has not scanned. */
    public static final String KEY_FCM_PACKAGES = "check.fcmPackages";
    public static final String KEY_GMS_POLICY = "check.gmsPolicy";
    public static final String KEY_GMS_BUCKET = "check.gmsBucket";
    public static final String KEY_GMS_DOZE = "check.gmsDoze";
    /** "name=value" lines of the ColorOS settings the issue guide asks about. */
    public static final String KEY_SETTINGS = "check.settings";
    /** Packages system_server lets through, each with an APP_PREFIX + package Bundle. */
    public static final String KEY_APPS = "check.apps";
    public static final String APP_PREFIX = "check.app.";

    public static final String APP_AUTO = "auto";
    public static final String APP_NOTIFY = "notify";
    public static final String APP_BLOCKED_CHANNELS = "blockedChannels";
    public static final String APP_BUCKET = "bucket";
    public static final String APP_STOPPED = "stopped";
    public static final String APP_RUNNING = "running";
    public static final String APP_LAST_PUSH = "lastPush";
    public static final String APP_PUSH_COUNT = "pushCount";

    /** Value of an int key that could not be read on this firmware. */
    public static final int UNKNOWN = -1;

    public enum Level { OK, INFO, WARN, FAIL, UNKNOWN }

    public static final class Verdict {
        public final Level level;
        public final String text;
        /** What to do about it; null when nothing is needed. */
        public final String hint;

        Verdict(Level level, String text, String hint) {
            this.level = level;
            this.text = text;
            this.hint = hint;
        }
    }

    private SelfCheck() {
    }

    /** Oplus networking_control policy: a bitmask of 1 no mobile, 2 no Wi-Fi, 4 reject all. */
    public static Verdict gmsPolicy(int policy) {
        if (policy < 0) return new Verdict(Level.UNKNOWN, "无法查询", "当前固件可能没有该服务，可导出完整报告查看");
        if (policy == 0) return new Verdict(Level.OK, "不限制", null);
        String text = (policy & 4) != 0 ? "全部禁止"
                : (policy & 3) == 3 ? "禁止 Wi‑Fi 和移动数据"
                : (policy & 2) != 0 ? "禁止 Wi‑Fi" : "禁止移动数据";
        return new Verdict(Level.FAIL, text + "（" + policy + "）",
                "确认已勾选电池作用域并重启；仍未恢复请按“提交问题”指南中“流量管理里没有 Google 入口”一节处理");
    }

    public static String bucketName(int bucket) {
        switch (bucket) {
            case 5: return "豁免";
            case 10: return "活跃";
            case 20: return "工作集";
            case 30: return "常用";
            case 40: return "极少使用";
            case 45: return "受限";
            case 50: return "从不";
            default: return String.valueOf(bucket);
        }
    }

    public static Verdict gmsBucket(int bucket) {
        if (bucket < 0) return new Verdict(Level.UNKNOWN, "无法查询", null);
        String text = bucketName(bucket) + "（" + bucket + "）";
        if (bucket <= 30) return new Verdict(Level.OK, text, null);
        return new Verdict(Level.FAIL, text, "GMS 被系统降级，后台联网和任务会被推迟。重启后再看，仍是这样请导出报告反馈");
    }

    public static Verdict gmsDoze(int state) {
        if (state < 0) return new Verdict(Level.UNKNOWN, "无法查询", null);
        if (state > 0) return new Verdict(Level.OK, "已加入", null);
        return new Verdict(Level.FAIL, "未加入", "系统框架的 Doze 白名单 Hook 可能没有生效，请导出报告反馈");
    }

    public static Verdict configSync(boolean remoteLoaded, int remoteManual, boolean remoteAuto,
                                     int localManual, boolean localAuto) {
        if (!remoteLoaded) {
            return new Verdict(Level.FAIL, "系统框架没有读到配置", "请在 FCMFix 中重新勾选一次应用，或重启手机");
        }
        if (remoteManual != localManual || remoteAuto != localAuto) {
            return new Verdict(Level.WARN, "系统框架的配置和这里不一致",
                    "Android 14 以下修改配置后需要重启；其他情况请重新打开 FCMFix 再查一次");
        }
        return new Verdict(Level.OK, "已同步", null);
    }

    public static Verdict appNotify(int notify, int blockedChannels) {
        if (notify == 0) return new Verdict(Level.FAIL, "通知已关闭", "在系统设置中打开该应用的通知");
        if (notify < 0) return new Verdict(Level.UNKNOWN, "通知状态未知", null);
        if (blockedChannels > 0) {
            return new Verdict(Level.WARN, blockedChannels + " 个通知类别已关闭",
                    "这些类别里的消息不会弹出，可在该应用的通知设置中检查");
        }
        return new Verdict(Level.OK, "通知已开启", null);
    }

    /** null when the bucket needs no comment. */
    public static Verdict appBucket(int bucket) {
        if (bucket == 45 || bucket == 50) {
            return new Verdict(Level.WARN, "待机分组：" + bucketName(bucket),
                    "系统会严格推迟该应用的后台任务，可能拉取不到消息内容。在应用的耗电设置中允许后台行为");
        }
        return null;
    }
}
