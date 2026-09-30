package com.kooritea.fcmfix;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

import com.kooritea.fcmfix.util.IceboxUtils;

public class MainActivity extends AppCompatActivity {
    private AppListAdapter appListAdapter;
    private static XposedService xposedService;
    Set<String> allowList = new HashSet<>();
    JSONObject config = new JSONObject();
    private volatile boolean configLoaded = false;

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
            if (!this.config.has("noResponseNotification")) {
                this.config.put("noResponseNotification", false);
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
            this.config.put("noResponseNotification", pref.getBoolean("noResponseNotification", false));
            this.configLoaded = true;
            if (appListAdapter != null) {
                appListAdapter.syncAllowList();
                appListAdapter.applyFilter();
            }
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
            status.setText("模块状态：未激活，请在 LSPosed 中启用模块并重启手机");
            status.setTextColor(ContextCompat.getColor(this, R.color.statusWarning));
            return;
        }
        StringBuilder text = new StringBuilder("模块状态：已激活");
        try {
            text.append(" · ").append(service.getFrameworkName()).append(' ')
                    .append(service.getFrameworkVersion()).append(" · API ").append(service.getApiVersion());
        } catch (Throwable e) {
            Log.w("updateModuleStatus", e.toString());
        }
        List<String> missing = new ArrayList<>();
        try {
            List<String> scope = service.getScope();
            if (scope != null) {
                if (!scope.contains("system") && !scope.contains("android")) missing.add("系统框架");
                if (!scope.contains("com.oplus.battery")) missing.add("电池");
            }
        } catch (Throwable e) {
            Log.w("updateModuleStatus", e.toString());
        }
        if (missing.isEmpty()) {
            status.setText(text);
            status.setTextColor(ContextCompat.getColor(this, R.color.statusOk));
        } else {
            text.append("\n缺少作用域：").append(String.join("、", missing)).append("，勾选后重启手机");
            status.setText(text);
            status.setTextColor(ContextCompat.getColor(this, R.color.statusWarning));
        }
    }

    private class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public Boolean isAllow = false;
        public Boolean includeFcm = false;

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

        public AppListAdapter(){
            Set<String> allowListSet = new HashSet<>(allowList);
            allowListSet.containsAll(allowList);
            List<AppInfo> _allowList = new ArrayList<>();
            List<AppInfo> _notAllowList = new ArrayList<>();
            List<AppInfo> _notFoundFcm = new ArrayList<>();
            PackageManager packageManager = getPackageManager();
            for(PackageInfo packageInfo : packageManager.getInstalledPackages(PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                boolean flag = false;
                AppInfo appInfo = new AppInfo(packageInfo);
                if (packageInfo.receivers != null) {
                    for (ActivityInfo  receiverInfo : packageInfo.receivers ){
                        if(receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver")){
                            flag = true;
                            appInfo.includeFcm = true;
                            break;
                        }
                    }
                }
                if (!flag) {
                    Intent receive = new Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(packageInfo.packageName);
                    Intent service = new Intent("com.google.firebase.MESSAGING_EVENT").setPackage(packageInfo.packageName);
                    int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
                    flag = !packageManager.queryBroadcastReceivers(receive, flags).isEmpty()
                            || !packageManager.queryIntentServices(service, flags).isEmpty();
                    appInfo.includeFcm = flag;
                }
                if(allowListSet.contains(appInfo.packageName)){
                    appInfo.isAllow = true;
                    _allowList.add(appInfo);
                }else{
                    if(flag){
                        _notAllowList.add(appInfo);
                    }else{
                        _notFoundFcm.add(appInfo);
                    }
                }
            }
            class SortName implements Comparator<AppInfo> {
                final Collator localCompare = Collator.getInstance(Locale.getDefault());
                @Override
                public int compare(AppInfo a1, AppInfo a2) {
                    if(localCompare.compare(a1.name,a2.name)>0){
                        return 1;
                    }else if (localCompare.compare(a1.name, a2.name) < 0) {
                        return -1;
                    }
                    return 0;
                }
            }
            final SortName sortName = new SortName();
            _allowList.sort(sortName);
            _notAllowList.sort(sortName);
            _notFoundFcm.sort(sortName);
            _allowList.addAll(_notAllowList);
            _allowList.addAll(_notFoundFcm);
            this.mAllApps = _allowList;
            this.mAppList.addAll(_allowList);
            if(_allowList.size() == 0 || _allowList.isEmpty() ||(_allowList.size() == 1 && getPackageName().equals(_allowList.get(0).packageName))){
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("请在系统设置中授予读取应用列表权限")
                        .setMessage("或直接编辑" + getApplicationContext().getFilesDir().getAbsolutePath() + "/config.json(需重启生效)")
                        .setPositiveButton("确定", (dialog, which) -> {})
                        .show();
            }
        }

        private void syncAllowList() {
            for (AppInfo appInfo : mAllApps) {
                appInfo.isAllow = allowList.contains(appInfo.packageName);
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
                boolean updated;
                if(appInfo.isAllow){
                    updated = deleteAppInAllowList(appInfo.packageName);
                }else{
                    updated = addAppInAllowList(appInfo.packageName);
                }
                if (updated) {
                    appInfo.isAllow = !appInfo.isAllow;
                    // Re-filter so an app unchecked under "已允许" leaves that view.
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
            new Handler().postDelayed(() -> {
                if (xposedService == null) updateModuleStatus();
            }, 3000);
        }

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        new Handler().postDelayed(() -> {
            appListAdapter = new AppListAdapter();
            recyclerView.setAdapter(appListAdapter);
            findViewById(R.id.progress_bar).setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            initFilterBar();
            invalidateOptionsMenu();
        }, 1000);
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
        findViewById(R.id.filter_bar).setVisibility(View.VISIBLE);
    }

    @Nullable
    @Override
    public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
        return super.onCreateView(parent, name, context, attrs);
    }

    private boolean addAppInAllowList(String packageName){
        boolean changed = this.allowList.add(packageName);
        if (this.updateConfig()) return true;
        if (changed) this.allowList.remove(packageName);
        return false;
    }
    private boolean deleteAppInAllowList(String packageName){
        boolean changed = this.allowList.remove(packageName);
        if (this.updateConfig()) return true;
        if (changed) this.allowList.add(packageName);
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
                    .putBoolean("disableAutoCleanNotification", this.config.getBoolean("disableAutoCleanNotification"))
                    .putBoolean("includeIceBoxDisableApp", this.config.getBoolean("includeIceBoxDisableApp"))
                    .putBoolean("noResponseNotification", this.config.getBoolean("noResponseNotification"))
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

        menu.add("阻止应用停止时自动清除通知").setCheckable(true);

        menu.add("允许唤醒被冰箱冻结的应用").setCheckable(true);

//        menu.add("目标无响应时代发提示通知").setCheckable(true);

        menu.add("全选包含 FCM 的应用");

        menu.add("打开FCM Diagnostics");
        return true;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public final boolean onPrepareOptionsMenu(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!"打开FCM Diagnostics".equals(item.getTitle())) {
                item.setEnabled(configLoaded);
            }
            if ("全选包含 FCM 的应用".equals(item.getTitle())) {
                item.setEnabled(configLoaded && appListAdapter != null);
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
            if("目标无响应时代发提示通知".equals(item.getTitle())){
                try {
                    item.setChecked(this.config.getBoolean("noResponseNotification"));
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
        if(menuItem.getTitle().equals("目标无响应时代发提示通知")){
            try {
                this.config.put("noResponseNotification", !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e("onOptionsItemSelected",e.toString());
            }
        }
        return true;
    }
}
