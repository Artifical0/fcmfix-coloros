package com.kooritea.fcmfix;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
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
                        updateModuleStatus();
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                    runOnUiThread(MainActivity.this::updateModuleStatus);
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

    /**
     * Show whether LSPosed has bound the module service and which recommended scopes are
     * missing. A bound service means the module is enabled; hooks still need a reboot after
     * enabling it or changing its scope.
     */
    private void updateModuleStatus() {
        TextView status = findViewById(R.id.module_status);
        if (status == null) return;
        XposedService service = xposedService;
        if (service == null) {
            status.setText(R.string.status_inactive);
            status.setTextColor(ContextCompat.getColor(this, R.color.statusWarning));
            return;
        }
        StringBuilder text = new StringBuilder("模块状态：已激活");
        try {
            // getApiVersion() is the newest API the framework supports; the module itself runs
            // against the API it was built for (targetApiVersion in module.prop).
            int frameworkApi = service.getApiVersion();
            text.append(" · ").append(service.getFrameworkName()).append(' ')
                    .append(service.getFrameworkVersion()).append(" · API ").append(XposedInterface.LIB_API);
            if (frameworkApi != XposedInterface.LIB_API) {
                text.append("（框架支持 ").append(frameworkApi).append("）");
            }
        } catch (Throwable e) {
            Log.w("updateModuleStatus", e.toString());
        }
        List<String> missing = new ArrayList<>();
        List<String> missingScopes = new ArrayList<>();
        try {
            List<String> scope = service.getScope();
            if (scope != null) {
                if (!scope.contains("system") && !scope.contains("android")) {
                    missing.add("系统框架");
                    missingScopes.add("system");
                }
                if (!scope.contains("com.oplus.battery")) {
                    missing.add("电池");
                    missingScopes.add("com.oplus.battery");
                }
            }
        } catch (Throwable e) {
            Log.w("updateModuleStatus", e.toString());
        }
        if (missing.isEmpty()) {
            status.setText(text);
            status.setTextColor(ContextCompat.getColor(this, R.color.statusOk));
            status.setOnClickListener(null);
            status.setClickable(false);
        } else {
            text.append("\n缺少作用域：").append(String.join("、", missing)).append("，点击这里申请添加");
            status.setText(text);
            status.setTextColor(ContextCompat.getColor(this, R.color.statusWarning));
            status.setOnClickListener(v -> requestScope(service, missingScopes));
        }
        queryHookStatus();
    }

    /** LSPosed asks the user to approve through a notification; hooks still need a reboot. */
    private void requestScope(XposedService service, List<String> scopes) {
        try {
            service.requestScope(scopes, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(@NonNull List<String> approved) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "作用域已添加，重启手机后生效", Toast.LENGTH_LONG).show();
                        updateModuleStatus();
                    });
                }

                @Override
                public void onScopeRequestFailed(@NonNull String message) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "申请失败：" + message + "。请在 LSPosed 中手动勾选", Toast.LENGTH_LONG).show());
                }
            });
            Toast.makeText(this, "已向 LSPosed 申请，请在弹出的通知中确认", Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Log.w("requestScope", e.toString());
            Toast.makeText(this, "申请失败，请在 LSPosed 中手动勾选作用域", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Ask the hooked processes which hook groups installed. system_server and Battery each add
     * their own extras to this ordered broadcast; a process that never loaded the module (scope
     * not ticked, or no reboot since) adds nothing.
     */
    private void queryHookStatus() {
        TextView view = findViewById(R.id.hook_status);
        if (view == null) return;
        if (Build.VERSION.SDK_INT < 34) {
            String text = "Hook 状态查询需要 Android 14 及以上";
            view.setText(text);
            view.setVisibility(View.VISIBLE);
            return;
        }
        Intent query = new Intent(getPackageName() + HookStatus.QUERY_ACTION_SUFFIX);
        Bundle options = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
        sendOrderedBroadcast(query, 0, null, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle result = getResultExtras(false);
                showHookStatus(view, result == null ? new Bundle() : result);
            }
        }, new Handler(Looper.getMainLooper()), null, null, options);
    }

    private static final String SYSTEM_PROCESS = "android";
    private static final String BATTERY_PROCESS = "com.oplus.battery";

    private void showHookStatus(TextView view, Bundle result) {
        boolean warning = false;
        StringBuilder text = new StringBuilder();
        StringBuilder details = new StringBuilder();
        String[][] processes = {{SYSTEM_PROCESS, "系统框架"}, {BATTERY_PROCESS, "电池"}};
        for (String[] process : processes) {
            List<String> active = result.getStringArrayList(process[0] + HookStatus.ACTIVE_SUFFIX);
            List<String> failed = result.getStringArrayList(process[0] + HookStatus.FAILED_SUFFIX);
            if (text.length() > 0) text.append(" · ");
            text.append(process[1]).append("：");
            details.append("【").append(process[1]).append("】\n");
            if (active == null || failed == null) {
                warning = true;
                text.append("未加载");
                details.append("未响应：模块未在该进程加载，请确认作用域已勾选并重启手机\n\n");
                continue;
            }
            text.append(active.size()).append(" 项生效");
            if (!failed.isEmpty()) {
                warning = true;
                text.append("，").append(failed.size()).append(" 项失配");
            }
            for (String name : failed) details.append("✗ ").append(name).append('\n');
            for (String name : active) details.append("✓ ").append(name).append('\n');
            details.append('\n');
        }
        if (result.containsKey(HookStatus.KEY_IGNORE_GMS_USER_SET)) {
            boolean ignores = result.getBoolean(HookStatus.KEY_IGNORE_GMS_USER_SET);
            details.append("电池组件声明 IgnoreGmsUserSet：").append(ignores ? "是" : "否")
                    .append(ignores ? "（系统框架会丢弃开机后对 Google 核心服务的禁网规则）"
                            : "（保留用户在流量管理中对 Google 应用的联网设置）");
        }
        text.append("（点击查看）");
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(this, warning ? R.color.statusWarning : R.color.statusOk));
        view.setVisibility(View.VISIBLE);
        String report = details.toString().trim();
        view.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Hook 状态")
                .setMessage(report)
                .setPositiveButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FCMFix Hook 状态", report));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("自查", (dialog, which) -> openSelfCheck())
                .setNegativeButton("关闭", null)
                .show());
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
            CheckBox isAllow;

            public ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                packageName = view.findViewById(R.id.packageName);
                includeFcm = view.findViewById(R.id.includeFcm);
                isAllow = view.findViewById(R.id.isAllow);
            }
        }

        AppListAdapter(List<AppInfo> apps) {
            this.mAllApps = apps;
            syncAllowList();
            // Stable sort: allowed apps first, each group keeps the FCM-then-name order.
            mAllApps.sort(Comparator.comparing((AppInfo app) -> !app.isAllow));
            this.mAppList.addAll(mAllApps);
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
            updateModuleStatus();
        } else {
            // The bind callback may take a moment; report "inactive" only if it never arrives.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (xposedService == null) updateModuleStatus();
            }, 3000);
        }

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
    public boolean onCreateOptionsMenu (Menu menu){
//      menu.add("隐藏启动器图标").setCheckable(true);

        menu.add("自查与导出报告");

        menu.add("阻止应用停止时自动清除通知").setCheckable(true);

        menu.add("允许唤醒被冰箱冻结的应用").setCheckable(true);

        menu.add("全选包含 FCM 的应用");

        menu.add("打开FCM Diagnostics");
        return true;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public final boolean onPrepareOptionsMenu(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!"打开FCM Diagnostics".equals(item.getTitle()) && !"自查与导出报告".equals(item.getTitle())) {
                item.setEnabled(configLoaded);
            }
            if ("全选包含 FCM 的应用".equals(item.getTitle())) {
                item.setEnabled(configLoaded && appListAdapter != null);
                // Auto-allow already covers every FCM app.
                item.setVisible(!autoAllowFcm());
            }
            if("隐藏启动器图标".equals(item.getTitle())){
                PackageManager packageManager = getPackageManager();
                item.setChecked(packageManager.getComponentEnabledSetting(new ComponentName(getPackageName(), "com.kooritea.fcmfix.Home")) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            }
            if("阻止应用停止时自动清除通知".equals(item.getTitle())){
                try {
                    item.setChecked(this.config.getBoolean("disableAutoCleanNotification"));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if("允许唤醒被冰箱冻结的应用".equals(item.getTitle())){
                try {
                    item.setChecked(this.config.getBoolean("includeIceBoxDisableApp"));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if("全选包含 FCM 的应用".equals(item.getTitle())){
                item.setOnMenuItemClickListener(menuItem -> {
                    Set<String> previousAllowList = new HashSet<>(allowList);
                    for(AppInfo appInfo : appListAdapter.mAllApps){
                        if(appInfo.includeFcm){
                            allowList.add(appInfo.packageName);
                        }
                    }
                    if (updateConfig()) {
                        appListAdapter.syncAllowList();
                        appListAdapter.applyFilter();
                    } else {
                        allowList.clear();
                        allowList.addAll(previousAllowList);
                    }
                    return false;
                });
            }
            if("打开FCM Diagnostics".equals(item.getTitle())){
                item.setOnMenuItemClickListener(menuItem -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("com.google.android.gms");
                    intent.setComponent(new ComponentName("com.google.android.gms","com.google.android.gms.gcm.GcmDiagnostics"));
                    startActivity(intent);
                    return false;
                });
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public final boolean onOptionsItemSelected(MenuItem menuItem) {
        if ("自查与导出报告".equals(menuItem.getTitle())) {
            openSelfCheck();
        }
        if(menuItem.getTitle().equals("隐藏启动器图标")){
            PackageManager packageManager = getPackageManager();
            packageManager.setComponentEnabledSetting(
                    new ComponentName(getPackageName(), "com.kooritea.fcmfix.Home"),
                    menuItem.isChecked() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        }
        if(menuItem.getTitle().equals("阻止应用停止时自动清除通知")){
            try {
                this.config.put("disableAutoCleanNotification", !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e("onOptionsItemSelected",e.toString());
            }
        }
        if(menuItem.getTitle().equals("允许唤醒被冰箱冻结的应用")){
            try {
                this.config.put("includeIceBoxDisableApp", !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e("onOptionsItemSelected",e.toString());
            }
        }
        return true;
    }
}
