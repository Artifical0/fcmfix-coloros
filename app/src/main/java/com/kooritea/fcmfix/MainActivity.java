package com.kooritea.fcmfix;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.SelfCheck;
import com.kooritea.fcmfix.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private AppListAdapter appListAdapter;
    private static XposedService xposedService;
    Set<String> allowList = new HashSet<>();
    /** FCM apps unticked while auto-allow is on. */
    Set<String> excludeList = new HashSet<>();
    JSONObject config = new JSONObject();
    private volatile boolean configLoaded = false;

    static XposedService getXposedService() {
        return xposedService;
    }

    private boolean autoAllowFcm() {
        return config.optBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, false);
    }

    private SharedPreferences getRemotePreferencesOrNull() {
        if (xposedService == null) {
            return null;
        }
        try {
            return xposedService.getRemotePreferences("config");
        } catch (Throwable e) {
            Log.e("getRemotePreferences", e.toString());
            return null;
        }
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    runOnUiThread(() -> {
                        loadConfigFromRemotePreferences();
                        showStatus();
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                    runOnUiThread(MainActivity.this::showStatus);
                }
            });
        } catch (Throwable e) {
            Log.e("initXposedService", e.toString());
        }
    }

    private void ensureDefaultConfigValues() {
        try {
            if (!this.config.has("allowList")) {
                this.config.put("allowList", new JSONArray());
            }
            if (!this.config.has("disableAutoCleanNotification")) {
                this.config.put("disableAutoCleanNotification", false);
            }
            if (!this.config.has("includeIceBoxDisableApp")) {
                this.config.put("includeIceBoxDisableApp", false);
            }
            if (!this.config.has(ConfigSnapshot.AUTO_ALLOW_FCM)) {
                this.config.put(ConfigSnapshot.AUTO_ALLOW_FCM, false);
            }
        } catch (JSONException e) {
            Log.e("ensureDefaultConfig", e.toString());
        }
    }

    private void loadConfigFromRemotePreferences() {
        ensureDefaultConfigValues();
        SharedPreferences pref = getRemotePreferencesOrNull();
        if (pref == null) {
            this.configLoaded = false;
            return;
        }
        try {
            this.allowList.clear();
            this.allowList.addAll(pref.getStringSet("allowList", new HashSet<>()));
            this.config.put("allowList", new JSONArray(this.allowList));
            this.config.put("disableAutoCleanNotification", pref.getBoolean("disableAutoCleanNotification", false));
            this.config.put("includeIceBoxDisableApp", pref.getBoolean("includeIceBoxDisableApp", false));
            this.excludeList.clear();
            this.excludeList.addAll(pref.getStringSet("excludeList", new HashSet<>()));
            this.config.put(ConfigSnapshot.AUTO_ALLOW_FCM, pref.getBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, false));
            this.configLoaded = true;
            if (appListAdapter != null) {
                appListAdapter.syncAllowList();
                appListAdapter.applyFilter();
            }
            refreshAutoAllowSwitch();
            invalidateOptionsMenu();
        } catch (Throwable e) {
            this.configLoaded = false;
            Log.e("loadRemoteConfig", e.toString());
        }
    }

    /** Latest self-check answer; null until the first query returns. */
    private Bundle lastStatus;
    private boolean statusPending;
    private boolean bindTimedOut;

    /** Re-check on return: pushes may have arrived, or a setting was changed elsewhere. */
    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /**
     * Summarizes the self-check on the status card: whether LSPosed is bound, scopes, hooks,
     * config sync and the GMS checks. The same answer feeds the last-push line in the list.
     */
    private void refreshStatus() {
        if (statusPending) return;
        statusPending = true;
        CheckReport.query(this, extras -> {
            statusPending = false;
            if (isFinishing() || isDestroyed()) return;
            lastStatus = extras;
            showStatus();
            if (appListAdapter != null) {
                appListAdapter.syncLastPush();
                appListAdapter.notifyLastPushChanged();
            }
        });
    }

    private void showStatus() {
        TextView mark = findViewById(R.id.status_mark);
        TextView title = findViewById(R.id.status_title);
        TextView detail = findViewById(R.id.status_detail);
        XposedService service = xposedService;
        List<CheckReport.Row> rows = CheckReport.build(this, service,
                lastStatus == null ? new Bundle() : lastStatus, true);
        List<CheckReport.Row> problems = CheckReport.problems(rows);
        SelfCheck.Level level;
        String titleText;
        String detailText;
        if (lastStatus == null || (service == null && !bindTimedOut)) {
            level = SelfCheck.Level.UNKNOWN;
            titleText = getString(R.string.status_connecting);
            detailText = null;
        } else if (problems.isEmpty()) {
            level = SelfCheck.Level.OK;
            titleText = "一切正常";
            detailText = okSummary(service);
        } else {
            CheckReport.Row first = problems.get(0);
            boolean fail = false;
            for (CheckReport.Row row : problems) fail |= row.level == SelfCheck.Level.FAIL;
            level = fail ? SelfCheck.Level.FAIL : SelfCheck.Level.WARN;
            titleText = problems.size() == 1 ? "有 1 项需要处理" : "有 " + problems.size() + " 项需要处理";
            detailText = first.title + "：" + first.detail.split("\n")[0];
        }
        mark.setText(CheckReport.markOf(level));
        mark.setTextColor(ContextCompat.getColor(this, CheckReport.colorOf(level)));
        title.setText(titleText);
        detail.setText(detailText);
        detail.setVisibility(detailText == null ? View.GONE : View.VISIBLE);
    }

    /** e.g. "LSPosed 1.10.2 · API 101 · 系统框架 14 项 · 电池 4 项". */
    private String okSummary(XposedService service) {
        StringBuilder text = new StringBuilder();
        try {
            // getApiVersion() is the newest API the framework supports; the module itself runs
            // against the API it was built for (targetApiVersion in module.prop).
            text.append(service.getFrameworkName()).append(' ').append(service.getFrameworkVersion())
                    .append(" · API ").append(XposedInterface.LIB_API);
        } catch (Throwable e) {
            Log.w("showStatus", e.toString());
        }
        String[][] processes = {{"android", "系统框架"}, {"com.oplus.battery", "电池"}};
        for (String[] process : processes) {
            List<String> active = lastStatus.getStringArrayList(process[0] + HookStatus.ACTIVE_SUFFIX);
            if (active != null) text.append(" · ").append(process[1]).append(' ').append(active.size()).append(" 项");
        }
        return text.toString();
    }

    private void openSelfCheck() {
        startActivity(new Intent(this, SelfCheckActivity.class));
    }

    private class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public boolean isAllow = false;
        public boolean includeFcm = false;
        /** Wall time of the last FCM push system_server saw since boot, 0 if none. */
        public long lastPush = 0;

        public AppInfo(PackageInfo packageInfo) {
            this.name = packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            this.packageName = packageInfo.packageName;
            this.icon = packageInfo.applicationInfo.loadIcon(getPackageManager());
        }
    }

    private class AppListAdapter  extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        /** Every installed app; "select all" and allow-list sync always use this list. */
        private final List<AppInfo> mAllApps;
        /** The apps currently shown after search and filter. */
        private final List<AppInfo> mAppList = new ArrayList<>();
        private String mQuery = "";
        private int mFilterId = R.id.filter_all;
        class ViewHolder extends RecyclerView.ViewHolder {
            View appView;
            ImageView icon;
            TextView name;
            TextView packageName;
            TextView includeFcm;
            TextView lastPush;
            CheckBox isAllow;

            public ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                packageName = view.findViewById(R.id.packageName);
                includeFcm = view.findViewById(R.id.includeFcm);
                lastPush = view.findViewById(R.id.lastPush);
                isAllow = view.findViewById(R.id.isAllow);
            }
        }

        AppListAdapter(List<AppInfo> apps) {
            this.mAllApps = apps;
            syncAllowList();
            syncLastPush();
            // Stable sort: allowed apps first, each group keeps the FCM-then-name order.
            mAllApps.sort(Comparator.comparing((AppInfo app) -> !app.isAllow));
            this.mAppList.addAll(mAllApps);
        }

        private void syncLastPush() {
            Bundle status = lastStatus;
            for (AppInfo appInfo : mAllApps) {
                Bundle app = status == null ? null : status.getBundle(SelfCheck.APP_PREFIX + appInfo.packageName);
                appInfo.lastPush = app == null ? 0 : app.getLong(SelfCheck.APP_LAST_PUSH);
            }
        }

        private void notifyLastPushChanged() {
            notifyItemRangeChanged(0, mAppList.size());
        }

        /** Same rule as ConfigSnapshot.allows in system_server. */
        private void syncAllowList() {
            boolean auto = autoAllowFcm();
            for (AppInfo appInfo : mAllApps) {
                appInfo.isAllow = allowList.contains(appInfo.packageName)
                        || (auto && appInfo.includeFcm && !excludeList.contains(appInfo.packageName));
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        private void setFilter(String query, int filterId) {
            mQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            mFilterId = filterId;
            applyFilter();
        }

        @SuppressLint("NotifyDataSetChanged")
        private void applyFilter() {
            mAppList.clear();
            for (AppInfo appInfo : mAllApps) {
                if (mFilterId == R.id.filter_fcm && !appInfo.includeFcm) continue;
                if (mFilterId == R.id.filter_allowed && !appInfo.isAllow) continue;
                if (!mQuery.isEmpty() && !appInfo.name.toLowerCase(Locale.ROOT).contains(mQuery)
                        && !appInfo.packageName.toLowerCase(Locale.ROOT).contains(mQuery)) {
                    continue;
                }
                mAppList.add(appInfo);
            }
            notifyDataSetChanged();
            findViewById(R.id.empty_view).setVisibility(mAppList.isEmpty() ? View.VISIBLE : View.GONE);
        }


        @SuppressLint("NotifyDataSetChanged")
        @NonNull
        @Override
        public AppListAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.app_item, parent, false);
            final ViewHolder holder = new ViewHolder(view);
            holder.appView.setOnClickListener(v -> {
                if (!configLoaded) {
                    Toast.makeText(MainActivity.this,
                            "LSPosed 配置正在加载，请稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                AppInfo appInfo = mAppList.get(position);
                if (setAppAllowed(appInfo, !appInfo.isAllow)) {
                    appInfo.isAllow = !appInfo.isAllow;
                    // Re-filter so an app unchecked under "已放行" leaves that view.
                    appListAdapter.applyFilter();
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull AppListAdapter.ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            holder.icon.setImageDrawable(appInfo.icon);
            holder.name.setText(appInfo.name);
            holder.packageName.setText(appInfo.packageName);
            holder.includeFcm.setVisibility(appInfo.includeFcm ? View.VISIBLE : View.GONE);
            holder.isAllow.setChecked(appInfo.isAllow);
            holder.isAllow.setEnabled(configLoaded);
            if (appInfo.isAllow && appInfo.lastPush > 0) {
                String text = DateUtils.getRelativeTimeSpanString(appInfo.lastPush) + "收到推送";
                holder.lastPush.setText(text);
                holder.lastPush.setVisibility(View.VISIBLE);
            } else {
                holder.lastPush.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }


    /**
     * Scans every installed package for FCM receivers and loads labels and icons. This takes
     * seconds on a phone with hundreds of system packages, so it runs off the main thread.
     * Apps with FCM come first; the adapter moves allowed apps to the top once config is known.
     */
    private List<AppInfo> loadInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager packageManager = getPackageManager();
        for (PackageInfo packageInfo : packageManager.getInstalledPackages(PackageManager.GET_RECEIVERS
                | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
            AppInfo appInfo = new AppInfo(packageInfo);
            if (packageInfo.receivers != null) {
                for (ActivityInfo receiverInfo : packageInfo.receivers) {
                    if (receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver")) {
                        appInfo.includeFcm = true;
                        break;
                    }
                }
            }
            if (!appInfo.includeFcm) {
                Intent receive = new Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(packageInfo.packageName);
                Intent service = new Intent("com.google.firebase.MESSAGING_EVENT").setPackage(packageInfo.packageName);
                int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
                appInfo.includeFcm = !packageManager.queryBroadcastReceivers(receive, flags).isEmpty()
                        || !packageManager.queryIntentServices(service, flags).isEmpty();
            }
            apps.add(appInfo);
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort(Comparator.comparing((AppInfo app) -> !app.includeFcm)
                .thenComparing(app -> app.name, collator));
        return apps;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        ensureDefaultConfigValues();
        initXposedService();
        // The service is static and may already be connected after an Activity recreation.
        // Load immediately instead of waiting for a second bind callback that may never arrive.
        if (xposedService != null) {
            loadConfigFromRemotePreferences();
        } else {
            // The bind callback may take a moment; report "inactive" only if it never arrives.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                bindTimedOut = true;
                if (xposedService == null && !isDestroyed()) showStatus();
            }, 3000);
        }
        findViewById(R.id.status_card).setOnClickListener(v -> openSelfCheck());

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        new Thread(() -> {
            List<AppInfo> apps = loadInstalledApps();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                appListAdapter = new AppListAdapter(apps);
                recyclerView.setAdapter(appListAdapter);
                findViewById(R.id.progress_bar).setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                initFilterBar();
                invalidateOptionsMenu();
                if (apps.size() <= 1) {
                    new AlertDialog.Builder(this)
                            .setTitle("无法读取应用列表")
                            .setMessage("请在系统设置中允许 FCMFix 读取应用列表，然后重新打开。")
                            .setPositiveButton("确定", null)
                            .show();
                }
            });
        }, "FCMFix-apps").start();
    }

    private void refreshAutoAllowSwitch() {
        androidx.appcompat.widget.SwitchCompat toggle = findViewById(R.id.auto_allow_switch);
        if (toggle == null) return;
        // Detach the listener so restoring the saved state does not write it back.
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(autoAllowFcm());
        toggle.setEnabled(configLoaded);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            boolean previous = autoAllowFcm();
            try {
                config.put(ConfigSnapshot.AUTO_ALLOW_FCM, checked);
            } catch (JSONException e) {
                Log.e("autoAllowFcm", e.toString());
            }
            if (!updateConfig()) {
                try {
                    config.put(ConfigSnapshot.AUTO_ALLOW_FCM, previous);
                } catch (JSONException e) {
                    Log.e("autoAllowFcm", e.toString());
                }
                refreshAutoAllowSwitch();
                return;
            }
            if (appListAdapter != null) {
                appListAdapter.syncAllowList();
                appListAdapter.applyFilter();
            }
            invalidateOptionsMenu();
        });
        findViewById(R.id.auto_allow_row).setOnClickListener(v -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
    }

    private void initFilterBar() {
        EditText search = findViewById(R.id.search_input);
        RadioGroup filter = findViewById(R.id.filter_group);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (appListAdapter != null) {
                    appListAdapter.setFilter(s.toString(), filter.getCheckedRadioButtonId());
                }
            }
        });
        filter.setOnCheckedChangeListener((group, checkedId) -> {
            if (appListAdapter != null) {
                appListAdapter.setFilter(search.getText().toString(), checkedId);
            }
        });
        refreshAutoAllowSwitch();
        findViewById(R.id.filter_bar).setVisibility(View.VISIBLE);
    }

    /**
     * With auto-allow on, an FCM app is ticked by leaving the exclude list and unticked by
     * joining it; other apps use the manual allow list either way.
     */
    private boolean setAppAllowed(AppInfo appInfo, boolean allowed) {
        Set<String> previousAllow = new HashSet<>(allowList);
        Set<String> previousExclude = new HashSet<>(excludeList);
        boolean automatic = autoAllowFcm() && appInfo.includeFcm;
        if (allowed) {
            excludeList.remove(appInfo.packageName);
            if (!automatic) allowList.add(appInfo.packageName);
        } else {
            allowList.remove(appInfo.packageName);
            if (automatic) excludeList.add(appInfo.packageName);
        }
        if (updateConfig()) return true;
        allowList.clear();
        allowList.addAll(previousAllow);
        excludeList.clear();
        excludeList.addAll(previousExclude);
        return false;
    }

    private boolean updateConfig(){
        try {
            ensureDefaultConfigValues();
            if (!this.configLoaded) {
                throw new IllegalStateException("LSPosed 配置尚未加载完成，请稍后重试");
            }
            SharedPreferences pref = getRemotePreferencesOrNull();
            if (pref == null) {
                throw new IllegalStateException("XposedService 未连接，无法写入远程配置");
            }
            this.config.put("allowList", new JSONArray(this.allowList));
            boolean saved = pref.edit()
                    .putBoolean("init", true)
                    .putStringSet("allowList", new HashSet<>(this.allowList))
                    .putStringSet("excludeList", new HashSet<>(this.excludeList))
                    .putBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, autoAllowFcm())
                    .putBoolean("disableAutoCleanNotification", this.config.getBoolean("disableAutoCleanNotification"))
                    .putBoolean("includeIceBoxDisableApp", this.config.getBoolean("includeIceBoxDisableApp"))
                    // Left behind by the removed no-response notification option.
                    .remove("noResponseNotification")
                    .commit();
            if (!saved) {
                throw new IllegalStateException("配置写入失败");
            }
            Intent refresh = new Intent(getPackageName() + ".update.config");
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                sendBroadcast(refresh, null, android.app.BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true).toBundle());
            } else {
                android.widget.Toast.makeText(this, "配置已保存；Android 14 以下需重启手机生效",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            return true;
        } catch (Throwable e) {
            Log.e("updateConfig",e.toString());
            new AlertDialog.Builder(this).setTitle("更新配置文件失败").setMessage(e.getMessage()).show();
            return false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public final boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_keep_notifications).setEnabled(configLoaded)
                .setChecked(config.optBoolean("disableAutoCleanNotification", false));
        menu.findItem(R.id.action_icebox).setEnabled(configLoaded)
                .setChecked(config.optBoolean("includeIceBoxDisableApp", false));
        // Auto-allow already covers every FCM app.
        menu.findItem(R.id.action_select_all_fcm).setEnabled(configLoaded && appListAdapter != null)
                .setVisible(!autoAllowFcm());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public final boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_self_check) {
            openSelfCheck();
        } else if (id == R.id.action_keep_notifications) {
            toggleOption("disableAutoCleanNotification", !item.isChecked());
        } else if (id == R.id.action_icebox) {
            toggleOption("includeIceBoxDisableApp", !item.isChecked());
        } else if (id == R.id.action_select_all_fcm) {
            selectAllFcmApps();
        } else if (id == R.id.action_fcm_diagnostics) {
            CheckReport.openFcmDiagnostics(this);
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void toggleOption(String key, boolean enabled) {
        try {
            config.put(key, enabled);
            if (!updateConfig()) config.put(key, !enabled);
        } catch (JSONException e) {
            Log.e("toggleOption", e.toString());
        }
        invalidateOptionsMenu();
    }

    private void selectAllFcmApps() {
        if (appListAdapter == null) return;
        Set<String> previousAllowList = new HashSet<>(allowList);
        for (AppInfo appInfo : appListAdapter.mAllApps) {
            if (appInfo.includeFcm) allowList.add(appInfo.packageName);
        }
        if (updateConfig()) {
            appListAdapter.syncAllowList();
            appListAdapter.applyFilter();
        } else {
            allowList.clear();
            allowList.addAll(previousAllowList);
        }
    }
}
