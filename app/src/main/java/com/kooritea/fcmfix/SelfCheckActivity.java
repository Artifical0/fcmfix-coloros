package com.kooritea.fcmfix;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.SelfCheck;
import com.kooritea.fcmfix.util.SelfCheck.Level;
import com.kooritea.fcmfix.util.SelfCheck.Verdict;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

/**
 * Checklist of the conditions FCM delivery depends on, answered by the hooked system_server,
 * plus a root export of scripts/collect-report.sh for issue reports.
 */
public class SelfCheckActivity extends AppCompatActivity {
    private static final String[][] PROCESSES = {{"android", "系统框架"}, {"com.oplus.battery", "电池"}};

    private static final class Row {
        final Level level;
        final String title;
        final String detail;
        final String hint;
        final Runnable action;

        Row(Level level, String title, String detail, String hint, Runnable action) {
            this.level = level;
            this.title = title;
            this.detail = detail;
            this.hint = hint;
            this.action = action;
        }

        boolean isSection() {
            return level == null;
        }
    }

    /** Result of the status query, null while it is pending. */
    private Bundle result;
    private final List<Row> rows = new ArrayList<>();
    private File lastReport;

    private final ActivityResultLauncher<String> saveReport = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), this::saveReportTo);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_self_check);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        findViewById(R.id.copy_result).setOnClickListener(v -> copyResult());
        findViewById(R.id.export_report).setOnClickListener(v -> chooseReportApp());
        queryStatus();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void queryStatus() {
        if (Build.VERSION.SDK_INT < 34) {
            result = new Bundle();
            render();
            return;
        }
        Intent query = new Intent(getPackageName() + HookStatus.QUERY_ACTION_SUFFIX)
                .putExtra(SelfCheck.EXTRA_CHECK, true);
        Bundle options = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
        sendOrderedBroadcast(query, 0, null, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = getResultExtras(false);
                result = extras == null ? new Bundle() : extras;
                render();
            }
        }, new Handler(Looper.getMainLooper()), null, null, options);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        buildRows();
        ViewGroup list = findViewById(R.id.check_list);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Row row : rows) {
            if (row.isSection()) {
                TextView header = new TextView(this);
                header.setText(row.title);
                header.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
                header.setTextSize(13);
                int padding = Math.round(getResources().getDisplayMetrics().density * 4);
                header.setPadding(padding, padding * 4, padding, padding);
                list.addView(header);
                continue;
            }
            View item = inflater.inflate(R.layout.check_item, list, false);
            TextView mark = item.findViewById(R.id.mark);
            mark.setText(markOf(row.level));
            mark.setTextColor(ContextCompat.getColor(this, colorOf(row.level)));
            ((TextView) item.findViewById(R.id.title)).setText(row.title);
            ((TextView) item.findViewById(R.id.detail)).setText(row.detail);
            TextView hint = item.findViewById(R.id.hint);
            if (row.hint != null) {
                hint.setText(row.hint);
                hint.setVisibility(View.VISIBLE);
            }
            if (row.action != null) item.setOnClickListener(v -> row.action.run());
            list.addView(item);
        }
        findViewById(R.id.copy_result).setEnabled(true);
    }

    private static String markOf(Level level) {
        switch (level) {
            case OK: return "✓";
            case WARN: return "!";
            case FAIL: return "✗";
            case UNKNOWN: return "?";
            default: return "•";
        }
    }

    private static int colorOf(Level level) {
        switch (level) {
            case OK: return R.color.statusOk;
            case WARN: return R.color.statusWarning;
            case FAIL: return R.color.statusFail;
            default: return R.color.textSecondary;
        }
    }

    private void section(String title) {
        rows.add(new Row(null, title, null, null, null));
    }

    private void add(Level level, String title, String detail, String hint) {
        rows.add(new Row(level, title, detail, hint, null));
    }

    private void add(String title, Verdict verdict) {
        add(verdict.level, title, verdict.text, verdict.hint);
    }

    private void buildRows() {
        rows.clear();
        Bundle status = result;
        section("模块");
        XposedService service = MainActivity.getXposedService();
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
        }
        for (String[] process : PROCESSES) addHookRow(status, process[0], process[1]);

        boolean systemAnswered = status.containsKey(SelfCheck.KEY_CONFIG_LOADED);
        if (systemAnswered) addConfigRows(service, status);

        section("Google 服务");
        if (systemAnswered) {
            add("GMS 联网策略", SelfCheck.gmsPolicy(status.getInt(SelfCheck.KEY_GMS_POLICY, SelfCheck.UNKNOWN)));
            add("GMS 待机分组", SelfCheck.gmsBucket(status.getInt(SelfCheck.KEY_GMS_BUCKET, SelfCheck.UNKNOWN)));
            add("GMS 电池优化白名单", SelfCheck.gmsDoze(status.getInt(SelfCheck.KEY_GMS_DOZE, SelfCheck.UNKNOWN)));
        }
        rows.add(new Row(Level.INFO, "FCM 连接状态", "点击打开 FCM Diagnostics，查看是否为 connected。"
                + "亮屏时也一直 disconnected 通常是网络问题（DNS 被污染或 5228 端口被封），模块无法解决",
                null, this::openFcmDiagnostics));

        if (systemAnswered) {
            addAppRows(status);
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
        }
    }

    private void addScopeRow(XposedService service) {
        try {
            List<String> scope = service.getScope();
            List<String> missing = new ArrayList<>();
            if (!scope.contains("system") && !scope.contains("android")) missing.add("系统框架");
            if (!scope.contains("com.oplus.battery")) missing.add("电池");
            if (missing.isEmpty()) add(Level.OK, "作用域", "系统框架、电池", null);
            else add(Level.FAIL, "作用域", "缺少" + String.join("、", missing),
                    "在 LSPosed 中勾选后重启手机，也可以在主界面点击模块状态申请");
        } catch (Throwable e) {
            add(Level.UNKNOWN, "作用域", "无法读取", null);
        }
    }

    private void addHookRow(Bundle status, String process, String label) {
        if (Build.VERSION.SDK_INT < 34) return;
        List<String> active = status.getStringArrayList(process + HookStatus.ACTIVE_SUFFIX);
        List<String> failed = status.getStringArrayList(process + HookStatus.FAILED_SUFFIX);
        String title = label + " Hook";
        if (active == null || failed == null) {
            add(Level.FAIL, title, "未加载", "确认已勾选" + label + "作用域，并在勾选后重启过手机");
        } else if (!failed.isEmpty()) {
            add(Level.WARN, title, active.size() + " 项生效，" + failed.size() + " 项失配：\n" + String.join("\n", failed),
                    "失配的项目与当前固件不匹配，请导出完整报告反馈");
        } else {
            add(Level.OK, title, active.size() + " 项生效", null);
        }
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
            Log.w("SelfCheck", e.toString());
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

    private void addAppRows(Bundle status) {
        ArrayList<String> apps = status.getStringArrayList(SelfCheck.KEY_APPS);
        if (apps == null) return;
        section("放行的应用（" + apps.size() + "）");
        if (apps.isEmpty()) {
            add(Level.WARN, "没有放行任何应用", "在主界面勾选应用，或开启自动放行", null);
            return;
        }
        PackageManager pm = getPackageManager();
        List<Row> appRows = new ArrayList<>();
        List<Long> lastPushes = new ArrayList<>();
        for (String name : apps) {
            Bundle app = status.getBundle(SelfCheck.APP_PREFIX + name);
            if (app == null) continue;
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                label = name;
            }
            if (app.getBoolean(SelfCheck.APP_AUTO)) label += "（自动）";

            Verdict notify = SelfCheck.appNotify(app.getInt(SelfCheck.APP_NOTIFY, SelfCheck.UNKNOWN),
                    app.getInt(SelfCheck.APP_BLOCKED_CHANNELS, SelfCheck.UNKNOWN));
            Verdict bucket = SelfCheck.appBucket(app.getInt(SelfCheck.APP_BUCKET, SelfCheck.UNKNOWN));
            Level level = notify.level;
            if (bucket != null && rank(bucket.level) < rank(level)) level = bucket.level;

            long lastPush = app.getLong(SelfCheck.APP_LAST_PUSH);
            long count = app.getLong(SelfCheck.APP_PUSH_COUNT);
            StringBuilder detail = new StringBuilder(notify.text);
            if (bucket != null) detail.append(" · ").append(bucket.text);
            detail.append('\n').append(lastPush == 0 ? "开机以来还没有收到推送"
                    : "最近一次推送：" + DateUtils.getRelativeTimeSpanString(lastPush) + "（开机以来 " + count + " 次）");
            detail.append('\n').append(app.getBoolean(SelfCheck.APP_RUNNING) ? "进程运行中"
                    : app.getBoolean(SelfCheck.APP_STOPPED) ? "已停止（被划掉或强行停止），收到推送时由模块拉起" : "未运行");
            String hint = notify.hint != null ? notify.hint : bucket != null ? bucket.hint : null;
            appRows.add(new Row(level, label, detail.toString(), hint, () -> openAppDetails(name)));
            lastPushes.add(lastPush);
        }
        // Problems first, then the most recently pushed.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < appRows.size(); i++) order.add(i);
        order.sort((a, b) -> {
            int byLevel = Integer.compare(rank(appRows.get(a).level), rank(appRows.get(b).level));
            return byLevel != 0 ? byLevel : Long.compare(lastPushes.get(b), lastPushes.get(a));
        });
        for (int index : order) rows.add(appRows.get(index));
        add(Level.INFO, "推送记录说明",
                "“最近一次推送”表示 GMS 已把消息交给该应用。收到了推送却没有弹出通知，问题通常在应用自己的通知设置或应用本身",
                null);
    }

    private void openAppDetails(String packageName) {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)));
        } catch (Throwable e) {
            Log.w("SelfCheck", e.toString());
        }
    }

    private void openFcmDiagnostics() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(
                    "com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")));
        } catch (Throwable e) {
            Toast.makeText(this, "无法打开 FCM Diagnostics", Toast.LENGTH_SHORT).show();
        }
    }

    private String resultText() {
        StringBuilder text = new StringBuilder("FCMFix 自查 ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
        for (Row row : rows) {
            if (row.isSection()) {
                text.append("\n\n【").append(row.title).append("】");
                continue;
            }
            text.append('\n').append(markOf(row.level)).append(' ').append(row.title).append("：")
                    .append(row.detail.replace("\n", "；"));
            if (row.hint != null) text.append("\n  → ").append(row.hint);
        }
        return text.toString();
    }

    private void copyResult() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("FCMFix 自查", resultText()));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    /** The report can include one app's notification state and logs; offer the allowed apps. */
    private void chooseReportApp() {
        ArrayList<String> apps = result == null ? null : result.getStringArrayList(SelfCheck.KEY_APPS);
        if (apps == null || apps.isEmpty()) {
            exportReport(null);
            return;
        }
        PackageManager pm = getPackageManager();
        String[] labels = new String[apps.size() + 1];
        labels[0] = "不指定应用";
        for (int i = 0; i < apps.size(); i++) {
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(apps.get(i), 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                label = apps.get(i);
            }
            labels[i + 1] = label;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择收不到通知的应用")
                .setItems(labels, (dialog, which) -> exportReport(which == 0 ? null : apps.get(which - 1)))
                .setNegativeButton("取消", null)
                .show();
    }

    /** Runs the bundled collect-report.sh as root; it only reads state and logs. */
    private void exportReport(String packageName) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("正在生成报告，需要授予 Root 权限…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            File report = null;
            try {
                File script = new File(getCacheDir(), "collect-report.sh");
                try (InputStream in = getAssets().open("collect-report.sh");
                     OutputStream out = new FileOutputStream(script)) {
                    copy(in, out);
                }
                File dir = new File(getCacheDir(), "reports");
                File[] old = dir.listFiles();
                if (old != null) for (File file : old) if (!file.delete()) Log.w("SelfCheck", "cannot delete " + file);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                report = new File(dir, "fcmfix-report-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt");
                String command = "sh " + script.getAbsolutePath() + " -"
                        + (packageName == null ? "" : " " + packageName);
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                process.getOutputStream().close();
                File target = report;
                Thread reader = new Thread(() -> {
                    try (InputStream in = process.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                        copy(in, out);
                    } catch (IOException e) {
                        Log.w("SelfCheck", e.toString());
                    }
                }, "FCMFix-report-reader");
                reader.start();
                if (!process.waitFor(120, TimeUnit.SECONDS)) process.destroyForcibly();
                reader.join(5000);
                String head = readHead(report);
                if (!head.startsWith("FCMFix report")) {
                    error = head.trim().isEmpty() ? "没有获得 Root 权限" : head.trim();
                }
            } catch (IOException e) {
                error = "无法执行 su：" + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "已中断";
            }
            String finalError = error;
            File finalReport = report;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                if (finalError != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("导出失败")
                            .setMessage(finalError + "\n\n导出完整报告需要 Root。没有 Root 时，可以先用“复制结果”反馈自查内容。")
                            .setPositiveButton("确定", null)
                            .show();
                    return;
                }
                lastReport = finalReport;
                showReportActions(finalReport);
            });
        }, "FCMFix-report").start();
    }

    private void showReportActions(File report) {
        String size = Math.max(1, report.length() / 1024) + " KB";
        new AlertDialog.Builder(this)
                .setTitle("报告已生成")
                .setMessage("报告大小 " + size + "。报告包含已安装应用的包名，公开发布前可以自行打码。")
                .setPositiveButton("分享", (dialog, which) -> shareReport(report))
                .setNeutralButton("保存到文件", (dialog, which) -> saveReport.launch(report.getName()))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void shareReport(File report) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".reports", report);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "分享报告"));
    }

    private void saveReportTo(Uri uri) {
        if (uri == null || lastReport == null) return;
        try (InputStream in = new FileInputStream(lastReport);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("无法写入");
            copy(in, out);
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readHead(File file) throws IOException {
        if (!file.isFile()) return "";
        byte[] buffer = new byte[512];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
    }

    /** BuildConfig is not generated for this module. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
