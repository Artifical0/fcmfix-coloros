package com.kooritea.fcmfix.util;

import java.util.ArrayList;
import java.util.List;

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
    /** GMS's FCM socket as system_server saw it: FCM_* state, remote, first-seen time. */
    public static final String KEY_FCM_STATE = "check.fcmState";
    public static final String KEY_FCM_REMOTE = "check.fcmRemote";
    public static final String KEY_FCM_SINCE = "check.fcmSince";
    /** Connections replaced by a new one before the next sample; delivery was not interrupted. */
    public static final String KEY_FCM_RECONNECTS = "check.fcmReconnects";
    /** Samples that found no connection after one had been up, and the longest such gap. */
    public static final String KEY_FCM_OUTAGES = "check.fcmOutages";
    public static final String KEY_FCM_LONGEST_OUTAGE = "check.fcmLongestOutage";
    /** Wall time a connection was last seen up; 0 before the first one. */
    public static final String KEY_FCM_LAST_SEEN = "check.fcmLastSeen";
    public static final String KEY_FCM_MONITOR_START = "check.fcmMonitorStart";
    /** Timeline lines, oldest first: screen, deep Doze, network and FCM connection changes. */
    public static final String KEY_EVENTS = "check.events";
    /** Push lines, oldest first, kept apart so a busy chat cannot push the night out of KEY_EVENTS. */
    public static final String KEY_PUSH_EVENTS = "check.pushEvents";
    /** Timeline texts start with these; the app colours and filters lines by them. */
    public static final String EVENT_PUSH = "推送 → ";
    public static final String EVENT_FCM_UP = "FCM 已连接";
    public static final String EVENT_FCM_RECONNECT = "FCM 重连";
    public static final String EVENT_FCM_RESTORED = "FCM 恢复连接";
    public static final String EVENT_FCM_DOWN = "FCM 断线";
    public static final String EVENT_NETWORK = "网络";
    public static final String EVENT_SCREEN_ON = "亮屏";
    public static final String EVENT_SCREEN_OFF = "熄屏";
    public static final String EVENT_DOZE_ENTER = "进入深度 Doze";
    public static final String EVENT_DOZE_EXIT = "退出深度 Doze";

    public enum EventKind { FCM_UP, FCM_RECONNECT, FCM_DOWN, NETWORK, SCREEN, DOZE, PUSH, OTHER }

    public static final int FCM_NONE = 0;
    public static final int FCM_CONNECTING = 1;
    public static final int FCM_CONNECTED = 2;
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

    public static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 1) return "不到 1 分钟";
        if (minutes < 60) return minutes + " 分钟";
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
    }

    /**
     * since is when system_server first saw the current socket and lastSeen when it last saw
     * one up; both come from samples about a minute apart while the CPU is awake.
     */
    public static Verdict fcmConnection(int state, String remote, long since, int reconnects, int outages,
                                        long longestOutage, long lastSeen, long monitorStart, long now) {
        String history = fcmHistory(reconnects, outages, longestOutage);
        if (state == FCM_CONNECTED) {
            String age = since - monitorStart < 120_000
                    ? "FCMFix 开始监测时已连接，至今 " + duration(now - since)
                    : "已持续 " + duration(now - since);
            return new Verdict(Level.OK, "已连接 " + remote + "\n" + age + "\n" + history, null);
        }
        String down = lastSeen > 0 ? "，已断线约 " + duration(now - lastSeen) : "";
        if (state == FCM_CONNECTING) {
            return new Verdict(Level.WARN, "正在连接 " + remote + "，尚未成功" + down + "\n" + history,
                    "一直停在这里，通常是 5228 端口被封或代理不通");
        }
        if (state == FCM_NONE) {
            return new Verdict(Level.WARN, "GMS 当前没有连接 FCM 服务器（5228–5230 端口）" + down + "\n" + history,
                    "刚亮屏或刚切换网络时可能正在重连，稍后再查；一直如此通常是网络问题。少数网络下 GMS 改走 443 端口，以实际收到推送为准");
        }
        return new Verdict(Level.UNKNOWN, "无法检测", null);
    }

    static String fcmHistory(int reconnects, int outages, long longestOutage) {
        if (reconnects == 0 && outages == 0) return "开机以来没有断线或重连";
        StringBuilder text = new StringBuilder("开机以来");
        if (reconnects > 0) text.append("重连 ").append(reconnects).append(" 次");
        if (outages > 0) {
            if (reconnects > 0) text.append("，");
            text.append("断线 ").append(outages).append(" 次（最长约 ").append(duration(longestOutage)).append("）");
        }
        if (outages == 0) text.append("，每次都马上连上了新连接，不影响推送");
        return text.toString();
    }

    public static EventKind eventKind(String text) {
        if (text.startsWith(EVENT_PUSH)) return EventKind.PUSH;
        if (text.startsWith(EVENT_FCM_UP) || text.startsWith(EVENT_FCM_RESTORED)) return EventKind.FCM_UP;
        if (text.startsWith(EVENT_FCM_RECONNECT)) return EventKind.FCM_RECONNECT;
        if (text.startsWith(EVENT_FCM_DOWN)) return EventKind.FCM_DOWN;
        if (text.startsWith(EVENT_NETWORK)) return EventKind.NETWORK;
        if (text.startsWith(EVENT_SCREEN_ON) || text.startsWith(EVENT_SCREEN_OFF)) return EventKind.SCREEN;
        if (text.startsWith(EVENT_DOZE_ENTER) || text.startsWith(EVENT_DOZE_EXIT)) return EventKind.DOZE;
        return EventKind.OTHER;
    }

    /** Events with an "MM-dd HH:mm:ss " prefix, merged newest first; LogRing's "…" lines go to notes. */
    public static final class Timeline {
        public final List<String> lines = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
    }

    public static Timeline mergeEvents(List<String> events, List<String> pushes) {
        Timeline timeline = new Timeline();
        List<String> a = withoutNotes(events, "连接、网络、亮灭屏和 Doze 事件", timeline.notes);
        List<String> b = withoutNotes(pushes, "推送记录", timeline.notes);
        int i = a.size() - 1;
        int j = b.size() - 1;
        while (i >= 0 || j >= 0) {
            if (j < 0 || (i >= 0 && a.get(i).compareTo(b.get(j)) > 0)) timeline.lines.add(a.get(i--));
            else timeline.lines.add(b.get(j--));
        }
        return timeline;
    }

    private static List<String> withoutNotes(List<String> lines, String what, List<String> notes) {
        List<String> result = new ArrayList<>();
        if (lines == null) return result;
        for (String line : lines) {
            if (line.startsWith("…")) notes.add(what + "：" + line.replaceAll("[…（）]", ""));
            else result.add(line);
        }
        return result;
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
