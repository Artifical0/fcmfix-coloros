package com.kooritea.fcmfix;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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

import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.SelfCheck;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Checklist of the conditions FCM delivery depends on, answered by the hooked system_server,
 * plus a root export of scripts/collect-report.sh for issue reports.
 */
public class SelfCheckActivity extends AppCompatActivity {
    /** Result of the status query, null while it is pending. */
    private Bundle result;
    private List<CheckReport.Row> rows = new ArrayList<>();
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
    }

    /** Re-check on return, e.g. after turning an app's notifications on in its settings. */
    @Override
    protected void onResume() {
        super.onResume();
        CheckReport.query(this, extras -> {
            result = extras;
            render();
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        rows = CheckReport.build(this, MainActivity.getXposedService(), result);
        ViewGroup list = findViewById(R.id.check_list);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CheckReport.Row row : rows) {
            if (row.isSection()) {
                TextView header = new TextView(this);
                header.setText(row.title);
                header.setTextColor(ContextCompat.getColor(this, R.color.textAccent));
                header.setTextSize(13);
                int padding = Math.round(getResources().getDisplayMetrics().density * 4);
                header.setPadding(padding, padding * 4, padding, padding);
                list.addView(header);
                continue;
            }
            View item = inflater.inflate(R.layout.check_item, list, false);
            TextView mark = item.findViewById(R.id.mark);
            mark.setText(CheckReport.markOf(row.level));
            mark.setTextColor(ContextCompat.getColor(this, CheckReport.colorOf(row.level)));
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

    private void copyResult() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("FCMFix 自查", CheckReport.toText(rows)));
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
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = CheckReport.label(pm, apps.get(i));
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
        // The module's own log lines outlive logcat; fetch them first, then append to the report.
        String checkText = CheckReport.toText(rows);
        CheckReport.query(this, HookStatus.EXTRA_LOGS, logs -> runReport(packageName, progress,
                "\n== 自查结果\n" + checkText + "\n" + CheckReport.logsText(logs)));
    }

    private void runReport(String packageName, AlertDialog progress, String appendix) {
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
                } else {
                    try (OutputStream out = new FileOutputStream(report, true)) {
                        out.write(appendix.getBytes(StandardCharsets.UTF_8));
                    }
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
}
