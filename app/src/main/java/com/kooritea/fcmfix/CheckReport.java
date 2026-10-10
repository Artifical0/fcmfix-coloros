package com.kooritea.fcmfix;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.SelfCheck;
import com.kooritea.fcmfix.util.SelfCheck.Level;
import com.kooritea.fcmfix.util.SelfCheck.Verdict;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import io.github.libxposed.service.XposedService;

/**
 * Turns the self-check answer of the hooked processes into checklist rows. The main screen's
 * status card and the self-check page share it, so both count the same problems.
 */
final class CheckReport {
    private static final String[][] PROCESSES = {{"android", "系统框架"}, {"com.oplus.battery", "电池"}};

    /** The self-check page shows one page at a time; the copied text has all of them. */
    enum Page { OVERVIEW, APPS, EVENTS }

    enum Kind { SECTION, CHECK, EVENT, NOTE }

    static final class Row {
        final Kind kind;
        final Page page;
        /** INFO for sections, events and notes. */
        final Level level;
        /** Section name, check name, or an event's time of day. */
        final String title;
        final String detail;
        final String hint;
        final Runnable action;
        /** Counted by the status card as "needs attention". */
        final boolean problem;
        /** Extra lines only for the copied text. */
        String copyExtra;
        /** App rows: shown with the app's icon. */
        String packageName;
        SelfCheck.EventKind eventKind;

        Row(Kind kind, Page page, Level level, String title, String detail, String hint, Runnable action,
            boolean problem) {
            this.kind = kind;
            this.page = page;
            this.level = level;
            this.title = title;
            this.detail = detail;
            this.hint = hint;
            this.action = action;
            this.problem = problem;
        }

        boolean isSection() {
            return kind == Kind.SECTION;
        }
    }

    private final Activity activity;
    /** Status card only: skip label lookups for apps without a problem. */
    private final boolean summary;
    private final List<Row> rows = new ArrayList<>();
    private Page page = Page.OVERVIEW;

    private CheckReport(Activity activity, boolean summary) {
        this.activity = activity;
        this.summary = summary;
    }

    /**
     * Asks system_server and Battery for hook status plus the self-check data. The callback
     * gets an empty Bundle when no hooked process answers or on Android 13 and older.
     */
    static void query(Activity context, Consumer<Bundle> callback) {
        query(context, SelfCheck.EXTRA_CHECK, callback);
    }

    /** extra selects what the hooked processes add: SelfCheck.EXTRA_CHECK or HookStatus.EXTRA_LOGS. */
    static void query(Activity context, String extra, Consumer<Bundle> callback) {
        if (Build.VERSION.SDK_INT < 34) {
            callback.accept(new Bundle());
            return;
        }
        Intent query = new Intent(context.getPackageName() + HookStatus.QUERY_ACTION_SUFFIX)
                .putExtra(extra, true);
        Bundle options = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
        context.sendOrderedBroadcast(query, 0, null, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = getResultExtras(false);
                callback.accept(extras == null ? new Bundle() : extras);
            }
        }, new Handler(Looper.getMainLooper()), null, null, options);
    }

    static List<Row> build(Activity activity, XposedService service, Bundle status) {
        return build(activity, service, status, false);
    }

    static List<Row> build(Activity activity, XposedService service, Bundle status, boolean summary) {
        CheckReport report = new CheckReport(activity, summary);
        report.buildRows(service, status);
        return report.rows;
    }

    static List<Row> problems(List<Row> rows) {
        List<Row> result = new ArrayList<>();
        for (Row row : rows) if (row.problem) result.add(row);
        return result;
    }

    /** The scopes FCMFix needs that are not ticked, or null when the scope cannot be read. */
    static List<String> missingScopes(XposedService service) {
        try {
            List<String> scope = service.getScope();
            List<String> missing = new ArrayList<>();
            if (!scope.contains("system") && !scope.contains("android")) missing.add("system");
            if (!scope.contains("com.oplus.battery")) missing.add("com.oplus.battery");
            return missing;
        } catch (Throwable e) {
            Log.w("CheckReport", e.toString());
            return null;
        }
    }

    /** LSPosed asks the user to approve through a notification; hooks still need a reboot. */
    static void requestScope(Activity activity, XposedService service, List<String> scopes, Runnable onApproved) {
        try {
            service.requestScope(scopes, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(@NonNull List<String> approved) {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, "作用域已添加，重启手机后生效", Toast.LENGTH_LONG).show();
                        onApproved.run();
                    });
                }

                @Override
                public void onScopeRequestFailed(@NonNull String message) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "申请失败：" + message + "。请在 LSPosed 中手动勾选", Toast.LENGTH_LONG).show());
                }
            });
            Toast.makeText(activity, "已向 LSPosed 申请，请在弹出的通知中确认", Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Log.w("requestScope", e.toString());
            Toast.makeText(activity, "申请失败，请在 LSPosed 中手动勾选作用域", Toast.LENGTH_LONG).show();
        }
    }

    static String markOf(Level level) {
        switch (level) {
            case OK: return "✓";
            case WARN: return "!";
            case FAIL: return "✗";
            case UNKNOWN: return "?";
            default: return "•";
        }
    }

    static int colorOf(Level level) {
        switch (level) {
            case OK: return R.color.statusOk;
            case WARN: return R.color.statusWarning;
            case FAIL: return R.color.statusFail;
            default: return R.color.textSecondary;
        }
    }

    /** Report sections for the module log lines each hooked process kept since boot. */
    static String logsText(Bundle logs) {
        StringBuilder text = new StringBuilder();
        for (String[] process : PROCESSES) {
            text.append("\n== FCMFix 模块日志（").append(process[1]).append("，由模块自身保留，不受 logcat 缓冲区限制）\n");
            List<String> lines = logLines(logs, process[0] + HookStatus.LOGS_SUFFIX);
            if (lines == null) text.append("未响应：模块未在该进程加载\n");
            else for (String line : lines) text.append(line).append('\n');
        }
        return text.toString();
    }

    /** Packed since 13.0-rc7; a process still running the previous version sends the plain list. */
    private static List<String> logLines(Bundle logs, String key) {
        byte[] packed = logs.getByteArray(key);
        if (packed != null) {
            try {
                return HookStatus.unpack(packed);
            } catch (IOException e) {
                return Collections.singletonList("无法解压日志：" + e);
            }
        }
        return logs.getStringArrayList(key);
    }

    static String toText(List<Row> rows) {
        StringBuilder text = new StringBuilder("FCMFix 自查 ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
        for (Row row : rows) {
            switch (row.kind) {
                case SECTION:
                    text.append("\n\n【").append(row.title).append("】");
                    break;
                case EVENT:
                    text.append('\n').append(row.title).append(' ').append(row.detail);
                    break;
                case NOTE:
                    text.append("\n• ").append(row.detail);
                    break;
                default:
                    text.append('\n').append(markOf(row.level)).append(' ').append(row.title).append("：")
                            .append(row.detail.replace("\n", "；"));
                    if (row.hint != null) text.append("\n  → ").append(row.hint);
                    if (row.copyExtra != null) text.append("\n  ").append(row.copyExtra);
            }
        }
        return text.toString();
    }

    private void section(String title) {
        rows.add(new Row(Kind.SECTION, page, Level.INFO, title, null, null, null, false));
    }

    private void note(String text) {
        rows.add(new Row(Kind.NOTE, page, Level.INFO, null, text, null, null, false));
    }

    private Row add(Level level, String title, String detail, String hint) {
        return add(level, title, detail, hint, null, level == Level.FAIL || level == Level.WARN);
    }

    private Row add(Level level, String title, String detail, String hint, Runnable action, boolean problem) {
        Row row = new Row(Kind.CHECK, page, level, title, detail, hint, action, problem);
        rows.add(row);
        return row;
    }

    private void add(String title, Verdict verdict) {
        add(verdict.level, title, verdict.text, verdict.hint);
    }

    /** The FCM connection verdict, also shown at the top of the self-check page. */
    static Verdict fcmVerdict(Bundle status) {
        return SelfCheck.fcmConnection(
                status.getInt(SelfCheck.KEY_FCM_STATE, SelfCheck.UNKNOWN),
                status.getString(SelfCheck.KEY_FCM_REMOTE),
                status.getLong(SelfCheck.KEY_FCM_SINCE),
                status.getInt(SelfCheck.KEY_FCM_RECONNECTS),
                status.getInt(SelfCheck.KEY_FCM_OUTAGES),
                status.getLong(SelfCheck.KEY_FCM_LONGEST_OUTAGE),
                status.getLong(SelfCheck.KEY_FCM_LAST_SEEN),
                status.getLong(SelfCheck.KEY_FCM_MONITOR_START),
                System.currentTimeMillis());
    }

    private void buildRows(XposedService service, Bundle status) {
        section("模块");
        if (service == null) {
            add(Level.FAIL, "LSPosed", "模块未激活", "在 LSPosed 中启用 FCMFix，勾选系统框架和电池后重启手机");
        } else {
            String framework;
            try {
                framework = service.getFrameworkName() + " " + service.getFrameworkVersion();
            } catch (Throwable e) {
                framework = "LSPosed";
            }
            add(Level.OK, "LSPosed", "已激活 · " + framework, null);
            addScopeRow(service);
        }
        if (Build.VERSION.SDK_INT < 34) {
            add(Level.UNKNOWN, "系统检查", "需要 Android 14 及以上", null);
        } else {
            for (String[] process : PROCESSES) addHookRow(status, process[0], process[1]);
        }

        boolean systemAnswered = status.containsKey(SelfCheck.KEY_CONFIG_LOADED);
        if (systemAnswered) addConfigRows(service, status);

        section("Google 服务");
        if (systemAnswered) {
            add("FCM 连接（系统框架检测）", fcmVerdict(status));
            add("GMS 联网策略", SelfCheck.gmsPolicy(status.getInt(SelfCheck.KEY_GMS_POLICY, SelfCheck.UNKNOWN)));
            add("GMS 待机分组", SelfCheck.gmsBucket(status.getInt(SelfCheck.KEY_GMS_BUCKET, SelfCheck.UNKNOWN)));
            add("GMS 电池优化白名单", SelfCheck.gmsDoze(status.getInt(SelfCheck.KEY_GMS_DOZE, SelfCheck.UNKNOWN)));
        }
        add(Level.INFO, "FCM Diagnostics", "点击打开 GMS 自带的诊断页，看是否为 connected。"
                + "亮屏时也一直 disconnected 通常是网络问题（DNS 被污染或 5228 端口被封），模块无法解决",
                null, this::openFcmDiagnostics, false);

        if (!systemAnswered) return;
        section("系统信息（反馈问题时附上）");
        StringBuilder info = new StringBuilder()
                .append("FCMFix ").append(versionName())
                .append("\nsdk=").append(Build.VERSION.SDK_INT)
                .append("\ndisplay=").append(Build.DISPLAY);
        ArrayList<String> settings = status.getStringArrayList(SelfCheck.KEY_SETTINGS);
        if (settings != null) for (String line : settings) info.append('\n').append(line);
        if (status.containsKey(HookStatus.KEY_IGNORE_GMS_USER_SET)) {
            info.append("\nIgnoreGmsUserSet=").append(status.getBoolean(HookStatus.KEY_IGNORE_GMS_USER_SET));
        }
        add(Level.INFO, "版本与系统设置", info.toString(), null);

        page = Page.APPS;
        addAppRows(status);
        page = Page.EVENTS;
        addEventRows(status);
    }

    private void addScopeRow(XposedService service) {
        List<String> missing = missingScopes(service);
        if (missing == null) {
            add(Level.UNKNOWN, "作用域", "无法读取", null);
        } else if (missing.isEmpty()) {
            add(Level.OK, "作用域", "系统框架、电池", null);
        } else {
            List<String> labels = new ArrayList<>();
            for (String scope : missing) labels.add("system".equals(scope) ? "系统框架" : "电池");
            add(Level.FAIL, "作用域", "缺少" + String.join("、", labels), "点击向 LSPosed 申请添加，确认后重启手机",
                    () -> requestScope(activity, service, missing, activity::recreate), true);
        }
    }

    private void addHookRow(Bundle status, String process, String label) {
        List<String> active = status.getStringArrayList(process + HookStatus.ACTIVE_SUFFIX);
        List<String> failed = status.getStringArrayList(process + HookStatus.FAILED_SUFFIX);
        String title = label + " Hook";
        Row row;
        if (active == null || failed == null) {
            row = add(Level.FAIL, title, "未加载", "确认已勾选" + label + "作用域，并在勾选后重启过手机");
        } else if (!failed.isEmpty()) {
            row = add(Level.WARN, title, active.size() + " 项生效，" + failed.size() + " 项失配：\n" + String.join("\n", failed),
                    "失配的项目与当前固件不匹配，请导出完整报告反馈");
        } else {
            row = add(Level.OK, title, active.size() + " 项生效", null);
        }
        if (active != null && !active.isEmpty()) row.copyExtra = "生效：" + String.join("、", active);
    }

    private void addConfigRows(XposedService service, Bundle status) {
        int localManual = -1;
        boolean localAuto = false;
        try {
            SharedPreferences pref = service == null ? null : service.getRemotePreferences("config");
            if (pref != null) {
                localManual = pref.getStringSet("allowList", Collections.emptySet()).size();
                localAuto = pref.getBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, false);
            }
        } catch (Throwable e) {
            Log.w("CheckReport", e.toString());
        }
        boolean remoteLoaded = status.getBoolean(SelfCheck.KEY_CONFIG_LOADED);
        boolean remoteAuto = status.getBoolean(SelfCheck.KEY_AUTO_ALLOW);
        if (localManual >= 0) {
            add("配置同步", SelfCheck.configSync(remoteLoaded, status.getInt(SelfCheck.KEY_MANUAL_COUNT),
                    remoteAuto, localManual, localAuto));
        }
        if (remoteAuto) {
            int fcm = status.getInt(SelfCheck.KEY_FCM_PACKAGES, SelfCheck.UNKNOWN);
            if (fcm < 0) {
                add(Level.WARN, "自动放行", "已开启，系统框架还没有完成应用扫描", "稍后重新打开自查；一直如此请重启手机");
            } else {
                add(Level.INFO, "自动放行", "已开启，系统框架识别到 " + fcm + " 个包含 FCM 的应用", null);
            }
        }
    }

    private static int rank(Level level) {
        switch (level) {
            case FAIL: return 0;
            case WARN: return 1;
            case UNKNOWN: return 2;
            default: return 3;
        }
    }

    /** Problems first, each group by the most recent push. */
    private void addAppRows(Bundle status) {
        ArrayList<String> apps = status.getStringArrayList(SelfCheck.KEY_APPS);
        if (apps == null) return;
        section("放行的应用（" + apps.size() + "）");
        if (apps.isEmpty()) {
            add(Level.WARN, "还没有放行任何应用", "在主界面勾选需要推送的应用，或开启“自动放行包含 FCM 的应用”", null);
            return;
        }
        PackageManager pm = activity.getPackageManager();
        List<Row> attention = new ArrayList<>();
        List<Row> normal = new ArrayList<>();
        Map<Row, Long> lastPushes = new HashMap<>();
        for (String name : apps) {
            Bundle app = status.getBundle(SelfCheck.APP_PREFIX + name);
            if (app == null) continue;
            Verdict notify = SelfCheck.appNotify(app.getInt(SelfCheck.APP_NOTIFY, SelfCheck.UNKNOWN),
                    app.getInt(SelfCheck.APP_BLOCKED_CHANNELS, SelfCheck.UNKNOWN));
            Verdict bucket = SelfCheck.appBucket(app.getInt(SelfCheck.APP_BUCKET, SelfCheck.UNKNOWN));
            Level level = notify.level;
            if (bucket != null && rank(bucket.level) < rank(level)) level = bucket.level;
            String label = summary && level != Level.FAIL ? name : label(pm, name);
            if (app.getBoolean(SelfCheck.APP_AUTO)) label += "（自动）";

            long lastPush = app.getLong(SelfCheck.APP_LAST_PUSH);
            long count = app.getLong(SelfCheck.APP_PUSH_COUNT);
            String pushes = lastPush == 0 ? "开机以来还没有收到推送"
                    : DateUtils.getRelativeTimeSpanString(lastPush) + "收到推送 · 开机以来 " + count + " 次";
            String process = app.getBoolean(SelfCheck.APP_RUNNING) ? "运行中"
                    : app.getBoolean(SelfCheck.APP_STOPPED) ? "已停止，推送时由模块拉起" : "未运行";
            StringBuilder detail = new StringBuilder();
            if (level != Level.OK) {
                detail.append(notify.text);
                if (bucket != null) detail.append(" · ").append(bucket.text);
                detail.append('\n');
            }
            detail.append(pushes).append(" · ").append(process);
            String hint = notify.hint != null ? notify.hint : bucket != null ? bucket.hint : null;
            // Only a fully disabled app counts on the status card; a muted channel is often deliberate.
            Row row = new Row(Kind.CHECK, page, level, label, detail.toString(), hint, () -> openAppDetails(name),
                    level == Level.FAIL);
            row.packageName = name;
            lastPushes.put(row, lastPush);
            (level == Level.FAIL || level == Level.WARN ? attention : normal).add(row);
        }
        Comparator<Row> order = (a, b) -> {
            int byLevel = Integer.compare(rank(a.level), rank(b.level));
            return byLevel != 0 ? byLevel : Long.compare(lastPushes.get(b), lastPushes.get(a));
        };
        attention.sort(order);
        normal.sort(order);
        if (!attention.isEmpty()) {
            section("需要注意（" + attention.size() + "）");
            rows.addAll(attention);
        }
        if (!normal.isEmpty()) {
            section("正常（" + normal.size() + "）");
            rows.addAll(normal);
        }
        note("“收到推送”表示 GMS 已把消息交给该应用。收到了推送却没有弹出通知，问题通常在应用自己的通知设置或应用本身。点击应用可打开它的系统设置");
    }

    /** One row per event, newest first, under a header for each day. */
    private void addEventRows(Bundle status) {
        ArrayList<String> events = status.getStringArrayList(SelfCheck.KEY_EVENTS);
        if (events == null) return;
        section("最近事件（开机以来，最新在上）");
        SelfCheck.Timeline timeline = SelfCheck.mergeEvents(events,
                status.getStringArrayList(SelfCheck.KEY_PUSH_EVENTS));
        if (timeline.lines.isEmpty()) {
            note("熄屏、深度 Doze、网络切换、FCM 连接变化和收到推送会记录在这里");
            return;
        }
        note("FCM 断线前后是否刚熄屏、进入深度 Doze 或切换网络，通常能说明原因。“重连”是旧连接换成了新连接，中间不影响推送");
        String day = null;
        for (String line : timeline.lines) {
            // "MM-dd HH:mm:ss text"
            boolean stamped = line.length() > 15 && line.charAt(5) == ' ' && line.charAt(14) == ' ';
            String lineDay = stamped ? line.substring(0, 5) : "";
            if (!lineDay.equals(day)) {
                day = lineDay;
                section(stamped ? dayLabel(lineDay) : "其他");
            }
            String text = stamped ? line.substring(15) : line;
            Row row = new Row(Kind.EVENT, page, Level.INFO, stamped ? line.substring(6, 14) : "", text,
                    null, null, false);
            row.eventKind = SelfCheck.eventKind(text);
            rows.add(row);
        }
        for (String text : timeline.notes) note(text);
    }

    /** "10-09" → "10 月 9 日". */
    private static String dayLabel(String monthDay) {
        try {
            return Integer.parseInt(monthDay.substring(0, 2)) + " 月 " + Integer.parseInt(monthDay.substring(3, 5)) + " 日";
        } catch (NumberFormatException e) {
            return monthDay;
        }
    }

    static String label(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private void openAppDetails(String packageName) {
        try {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)));
        } catch (Throwable e) {
            Log.w("CheckReport", e.toString());
        }
    }

    private void openFcmDiagnostics() {
        openFcmDiagnostics(activity);
    }

    static void openFcmDiagnostics(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(
                    "com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")));
        } catch (Throwable e) {
            Toast.makeText(activity, "无法打开 FCM Diagnostics", Toast.LENGTH_SHORT).show();
        }
    }

    /** BuildConfig is not generated for this module. */
    private String versionName() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
