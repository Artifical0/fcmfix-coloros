package com.kooritea.fcmfix;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private CheckReport.Page page = CheckReport.Page.OVERVIEW;
    private boolean showPushes = true;
    private final Map<String, Drawable> icons = new HashMap<>();

    private final ActivityResultLauncher<String> saveReport = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), this::saveReportTo);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_self_check);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        findViewById(R.id.copy_result).setOnClickListener(v -> copyResult());
        findViewById(R.id.export_report).setOnClickListener(v -> chooseReportApp());
        if (savedInstanceState != null) {
            page = CheckReport.Page.values()[savedInstanceState.getInt("page", 0)];
            showPushes = savedInstanceState.getBoolean("showPushes", true);
        }
        findViewById(R.id.tab_overview).setOnClickListener(v -> showPage(CheckReport.Page.OVERVIEW));
        findViewById(R.id.tab_apps).setOnClickListener(v -> showPage(CheckReport.Page.APPS));
        findViewById(R.id.tab_events).setOnClickListener(v -> showPage(CheckReport.Page.EVENTS));
        updateTabs();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("page", page.ordinal());
        outState.putBoolean("showPushes", showPushes);
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
        renderSummary();
        updateTabs();
        renderPage();
        findViewById(R.id.copy_result).setEnabled(true);
    }

    private void showPage(CheckReport.Page target) {
        if (page == target) return;
        page = target;
        updateTabs();
        if (result != null) renderPage();
        findViewById(R.id.scroll).scrollTo(0, 0);
    }

    private void updateTabs() {
        findViewById(R.id.tab_overview).setSelected(page == CheckReport.Page.OVERVIEW);
        findViewById(R.id.tab_apps).setSelected(page == CheckReport.Page.APPS);
        findViewById(R.id.tab_events).setSelected(page == CheckReport.Page.EVENTS);
        ArrayList<String> apps = result == null ? null : result.getStringArrayList(SelfCheck.KEY_APPS);
        TextView appsTab = findViewById(R.id.tab_apps);
        appsTab.setText(apps == null ? getString(R.string.tab_apps_empty) : getString(R.string.tab_apps, apps.size()));
    }

    /** The verdict at the top: the first problem, or the FCM connection when all is well. */
    private void renderSummary() {
        List<CheckReport.Row> problems = CheckReport.problems(rows);
        boolean answered = result.containsKey(SelfCheck.KEY_CONFIG_LOADED);
        SelfCheck.Level level;
        String title;
        String detail = null;
        if (!problems.isEmpty()) {
            boolean fail = false;
            for (CheckReport.Row row : problems) fail |= row.level == SelfCheck.Level.FAIL;
            level = fail ? SelfCheck.Level.FAIL : SelfCheck.Level.WARN;
            title = problems.size() == 1 ? getString(R.string.check_one_problem)
                    : getString(R.string.check_problems, problems.size());
            CheckReport.Row first = problems.get(0);
            detail = first.title + "：" + first.detail.split("\n")[0];
        } else if (!answered) {
            level = SelfCheck.Level.UNKNOWN;
            title = getString(R.string.check_no_answer);
        } else {
            level = SelfCheck.Level.OK;
            title = getString(R.string.check_all_ok);
            String[] fcm = CheckReport.fcmVerdict(result).text.split("\n");
            if (fcm.length >= 3) detail = "FCM " + fcm[0] + " · " + fcm[1] + "\n" + fcm[2];
        }
        TextView mark = findViewById(R.id.summary_mark);
        styleMark(mark, level);
        mark.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.summary_title)).setText(title);
        TextView detailView = findViewById(R.id.summary_detail);
        detailView.setText(detail);
        detailView.setVisibility(detail == null ? View.GONE : View.VISIBLE);
    }

    private void styleMark(TextView mark, SelfCheck.Level level) {
        int color = ContextCompat.getColor(this, CheckReport.colorOf(level));
        mark.setText(CheckReport.markOf(level));
        mark.setTextColor(color);
        mark.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x26)));
    }

    /** Sections become cards; empty sections (e.g. after filtering pushes) are skipped. */
    private void renderPage() {
        ViewGroup list = findViewById(R.id.check_list);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        if (page == CheckReport.Page.EVENTS) {
            SwitchCompat filter = (SwitchCompat) inflater.inflate(R.layout.timeline_filter, list, false);
            filter.setChecked(showPushes);
            filter.setOnCheckedChangeListener((button, checked) -> {
                showPushes = checked;
                renderPage();
            });
            list.addView(filter);
        }
        String pendingHeader = null;
        ViewGroup card = null;
        for (CheckReport.Row row : rows) {
            if (row.page != page) continue;
            switch (row.kind) {
                case SECTION:
                    pendingHeader = row.title;
                    card = null;
                    continue;
                case NOTE:
                    card = null;
                    list.addView(note(row.detail));
                    continue;
                case EVENT:
                    if (!showPushes && row.eventKind == SelfCheck.EventKind.PUSH) continue;
                    break;
                default:
                    break;
            }
            if (card == null) {
                if (pendingHeader != null) list.addView(header(pendingHeader));
                pendingHeader = null;
                card = newCard();
                list.addView(card);
            } else {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
                params.setMarginStart(dp(row.kind == CheckReport.Kind.EVENT ? 14 : row.packageName != null ? 96 : 48));
                card.addView(divider, params);
            }
            card.addView(row.kind == CheckReport.Kind.EVENT ? eventView(inflater, card, row) : checkView(inflater, card, row));
        }
        if (page != CheckReport.Page.OVERVIEW && !result.containsKey(SelfCheck.KEY_CONFIG_LOADED)) {
            list.addView(note("系统框架没有响应，看不到这一页。请先在“概览”中处理模块问题，确认已勾选作用域并重启手机"));
        }
    }

    private ViewGroup newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClipToOutline(true);
        return card;
    }

    private TextView header(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        header.setTextSize(13);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setPadding(dp(6), dp(18), dp(6), dp(6));
        return header;
    }

    private TextView note(String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        note.setTextSize(12);
        note.setPadding(dp(6), dp(8), dp(6), dp(4));
        return note;
    }

    private View checkView(LayoutInflater inflater, ViewGroup parent, CheckReport.Row row) {
        View item = inflater.inflate(R.layout.check_item, parent, false);
        styleMark(item.findViewById(R.id.mark), row.level);
        ((TextView) item.findViewById(R.id.title)).setText(row.title);
        ((TextView) item.findViewById(R.id.detail)).setText(row.detail);
        TextView hint = item.findViewById(R.id.hint);
        if (row.hint != null) {
            hint.setText(row.hint);
            hint.setTextColor(ContextCompat.getColor(this, CheckReport.colorOf(row.level)));
            hint.setVisibility(View.VISIBLE);
        }
        if (row.packageName != null) {
            ImageView icon = item.findViewById(R.id.icon);
            icon.setImageDrawable(icon(row.packageName));
            icon.setVisibility(View.VISIBLE);
        }
        if (row.action != null) {
            item.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
            item.setBackgroundResource(selectableBackground());
            item.setOnClickListener(v -> row.action.run());
        }
        return item;
    }

    private View eventView(LayoutInflater inflater, ViewGroup parent, CheckReport.Row row) {
        View item = inflater.inflate(R.layout.event_item, parent, false);
        ((TextView) item.findViewById(R.id.time)).setText(row.title);
        TextView text = item.findViewById(R.id.text);
        text.setText(row.detail);
        int color = ContextCompat.getColor(this, eventColor(row.eventKind));
        item.findViewById(R.id.dot).setBackgroundTintList(ColorStateList.valueOf(color));
        switch (row.eventKind) {
            case FCM_UP:
            case FCM_RECONNECT:
            case FCM_DOWN:
                text.setTextColor(color);
                break;
            case PUSH:
                text.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                break;
            default:
                break;
        }
        return item;
    }

    private static int eventColor(SelfCheck.EventKind kind) {
        switch (kind) {
            case FCM_UP: return R.color.statusOk;
            case FCM_RECONNECT: return R.color.statusWarning;
            case FCM_DOWN: return R.color.statusFail;
            case NETWORK: return R.color.textAccent;
            case PUSH: return R.color.fcmBadgeText;
            default: return R.color.textSecondary;
        }
    }

    private Drawable icon(String packageName) {
        Drawable icon = icons.get(packageName);
        if (icon == null) {
            try {
                icon = getPackageManager().getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                icon = getPackageManager().getDefaultActivityIcon();
            }
            icons.put(packageName, icon);
        }
        return icon;
    }

    private int selectableBackground() {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
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
