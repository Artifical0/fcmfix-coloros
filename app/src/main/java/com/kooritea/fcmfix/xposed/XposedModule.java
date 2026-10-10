package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import com.kooritea.fcmfix.util.FcmTrust;
import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.DiagnosticLogger;
import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.OplusAttribution;
import com.kooritea.fcmfix.util.SelfCheck;
import java.lang.reflect.Method;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.Set;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    protected static final String MODULE_PACKAGE_NAME = "io.github.artifical0.fcmfix.coloros";
    protected static final String ACTION_UPDATE_CONFIG = MODULE_PACKAGE_NAME + ".update.config";
    private static final String ACTION_QUERY_STATUS = MODULE_PACKAGE_NAME + HookStatus.QUERY_ACTION_SUFFIX;
    private static String selfPackageName = "UNKNOWN";

    protected final ClassLoader classLoader;
    static final String TAG = "FcmFix";
    private static volatile ConfigSnapshot config;
    private static boolean reloadRequested;

    @SuppressLint("StaticFieldLeak")
    protected static volatile Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static volatile boolean isBootComplete = false;
    private static Thread loadConfigThread = null;
    private static final Set<String> loggedWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final DiagnosticLogger diagnosticLogger = new DiagnosticLogger(
            text -> XposedBridge.log("[fcmfix] " + text),
            error -> Log.w(TAG, "Diagnostic logging failed; further warnings suppressed", error));

    protected static void logOnce(String message) {
        // Bounded deduplication so third-party spam cannot grow system_server memory indefinitely.
        if (loggedWarnings.size() < 64 && loggedWarnings.add(message)) printLog(message);
    }

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    private static void initContext(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if (context == null) {
                    context = (Context) methodHookParam.thisObject;
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    } else {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    private static void callAllOnCanReadConfig() {
        initReceiver();
        // Upstream waited another 60 s here, but GMS reconnects and flushes the messages queued
        // during a reboot within that minute; stopped apps would miss them. Until the config
        // finishes loading, targetIsAllow() is false anyway.
        isBootComplete = true;
        for (XposedModule instance : instances) {
            try {
                instance.onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable {
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    protected static void printLog(String text, boolean isDiagnosticsLog) {
        diagnosticLogger.log("[" + getSelfPackageName() + "]" + text, isDiagnosticsLog,
                SystemClock.elapsedRealtime());
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Throwable e) {
                printLog("更新配置文件失败: " + e.getMessage());
            }
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName) {
        ConfigSnapshot snapshot = config;
        if (snapshot == null) {
            checkUserDeviceUnlockAndUpdateConfig();
            return false;
        }
        return snapshot.allows(packageName, FcmPackages.current());
    }

    protected String findAllowedPackageArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && targetIsAllow((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        ConfigSnapshot snapshot = config;
        if (snapshot == null) {
            checkUserDeviceUnlockAndUpdateConfig();
            return defaultValue;
        }
        return snapshot.options.getOrDefault(key, defaultValue);
    }

    protected static synchronized void onUpdateConfig() {
        reloadRequested = true;
        if (loadConfigThread != null) return;
        loadConfigThread = new Thread(() -> {
            while (true) {
                synchronized (XposedModule.class) {
                    if (!reloadRequested) {
                        loadConfigThread = null;
                        return;
                    }
                    reloadRequested = false;
                }
                try {
                    SharedPreferences preferences = XposedBridge.getRemotePreferences("config");
                    if (preferences == null) throw new IllegalStateException("RemotePreferences unavailable");
                    ConfigSnapshot loaded = new ConfigSnapshot(preferences.getAll());
                    config = loaded;
                    if (loaded.autoAllowFcm() && "android".equals(getSelfPackageName())) {
                        FcmPackages.ensureScanned(context);
                    }
                } catch (Throwable e) {
                    printLog("Remote config reload failed: " + e.getMessage());
                }
            }
        }, "FCMFix-config");
        loadConfigThread.start();
    }

    /** Removes the channel that older versions' no-response notification created in system_server. */
    private static void onUninstallFcmfix() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmfix");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction(ACTION_UPDATE_CONFIG);
            updateConfigIntentFilter.addAction(ACTION_QUERY_STATUS);
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (ACTION_UPDATE_CONFIG.equals(action) && isConfigSender(this)) {
                            onUpdateConfig();
                        } else if (ACTION_QUERY_STATUS.equals(action) && isOrderedBroadcast() && isConfigSender(this)) {
                            if (intent.getBooleanExtra(SelfCheck.EXTRA_CHECK, false)
                                    && "android".equals(getSelfPackageName())) {
                                answerSelfCheck(goAsync());
                            } else {
                                setResultExtras(addHookStatus(getResultExtras(true),
                                        intent.getBooleanExtra(HookStatus.EXTRA_LOGS, false)));
                            }
                        }
                    }
                }, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                logOnce("Authenticated config refresh requires Android 14+; reboot to reload on older Android.");
            }

            if ("android".equals(getSelfPackageName())) {
                FcmConnectionMonitor.start(context);
                IntentFilter packageFilter = new IntentFilter();
                packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
                packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                packageFilter.addDataScheme("package");
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getData() != null) {
                            FcmPackages.onPackageChanged(context, intent.getData().getSchemeSpecificPart());
                        }
                    }
                }, packageFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && MODULE_PACKAGE_NAME.equals(intent.getData().getSchemeSpecificPart())) {
                        Bundle extras = intent.getExtras();
                        if (extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                            return;
                        }
                        onUninstallFcmfix();
                        if ("android".equals(getSelfPackageName())) {
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    protected boolean isFCMAction(String action) {
        return com.kooritea.fcmfix.util.FcmTrust.isAction(action);
    }

    private static Bundle addHookStatus(Bundle result, boolean logs) {
        String process = getSelfPackageName();
        if (logs) result.putByteArray(process + HookStatus.LOGS_SUFFIX, HookStatus.pack(XposedBridge.recentLogs()));
        result.putStringArrayList(process + HookStatus.ACTIVE_SUFFIX, OplusHooks.STATUS.active());
        result.putStringArrayList(process + HookStatus.FAILED_SUFFIX, OplusHooks.STATUS.failed());
        if ("android".equals(process)) {
            result.putBoolean(HookStatus.KEY_IGNORE_GMS_USER_SET, OplusBatteryNetworkFix.batteryIgnoresGmsUserSet());
        }
        return result;
    }

    /** Self-check reads other services over Binder; keep it off system_server's main thread. */
    private static void answerSelfCheck(BroadcastReceiver.PendingResult pending) {
        new Thread(() -> {
            try {
                Bundle extras = addHookStatus(pending.getResultExtras(true), false);
                try {
                    SelfCheckCollector.collect(context, config, extras);
                } catch (Throwable e) {
                    printLog("Self-check failed: " + e);
                }
                pending.setResultExtras(extras);
            } finally {
                pending.finish();
            }
        }, "FCMFix-selfcheck").start();
    }

    private static boolean isConfigSender(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34 || context == null) return false;
        try {
            return MODULE_PACKAGE_NAME.equals(receiver.getSentFromPackage())
                    && receiver.getSentFromUid() == context.getPackageManager().getPackageUid(MODULE_PACKAGE_NAME, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    protected static boolean isGmsUid(int uid) {
        if (context == null || uid < 10000) return false;
        // Existing unfreeze/UID resolution uses this process's user. Do not apply its
        // window to the personal-profile copy of a work-profile broadcast target.
        if (!android.os.UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle())) return false;
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return com.kooritea.fcmfix.util.FcmTrust.isGmsSender(uid, packages);
        } catch (RuntimeException e) {
            printLog("Cannot verify GMS UID: " + e.getMessage());
        }
        return false;
    }

    protected static String explicitTarget(Intent intent) {
        if (intent == null) return null;
        return FcmTrust.target(intent.getPackage(), intent.getComponent() == null
                ? null : intent.getComponent().getPackageName());
    }

    protected static int attributionIndex(Method method) {
        String[] types = java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new);
        return OplusAttribution.callerIndex(method.getDeclaringClass().getName(),
                method.getName(), method.getReturnType().getName(), types);
    }

    protected boolean trustedDelivery(Intent intent, String target, XC_MethodHook.MethodHookParam param) {
        // Require a coherent, explicit destination. Do not select an arbitrary allowlisted string argument.
        if (!isBootComplete || intent == null || target == null || !target.equals(explicitTarget(intent))
                || !targetIsAllow(target)) return false;
        try {
            // A queued broadcast is attributed by BroadcastRecord, never by ambient Binder identity.
            for (Object arg : param.args) {
                if (arg == null || !"com.android.server.am.BroadcastRecord".equals(arg.getClass().getName())) continue;
                return XposedHelpers.getObjectField(arg, "intent") == intent
                        && FcmTrust.RECEIVE.equals(intent.getAction())
                        && isGmsUid((Integer) XposedHelpers.getObjectField(arg, "callingUid"));
            }
            if (param.method instanceof Method) {
                Method method = (Method) param.method;
                int index = attributionIndex(method);
                if (index >= 0) {
                    int callerUid = (Integer) param.args[index];
                    String methodName = method.getName();
                    // Validate the actual callee too, not only the Intent destination.
                    if ("isAppClassifyRestricted".equals(methodName)) {
                        if (!target.equals(param.args[1])) return false;
                    } else {
                        int serviceIndex = "isAllowStartFromBindService".equals(methodName) ? 3 : 4;
                        Object info = XposedHelpers.getObjectField(param.args[serviceIndex], "appInfo");
                        if (!(info instanceof android.content.pm.ApplicationInfo)
                                || !target.equals(((android.content.pm.ApplicationInfo) info).packageName)) return false;
                    }
                    boolean gms = isGmsUid(callerUid);
                    String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
                    boolean selfInWindow = packages != null && java.util.Arrays.asList(packages).contains(target)
                            && OplusProxyFix.isInFcmDeliveryWindow(callerUid);
                    boolean gcmBind = "isAllowStartFromBindService".equals(methodName)
                            && "bsgcm".equals(param.args[5]);
                    boolean trusted = FcmTrust.allowsService(intent.getAction(), gms, selfInWindow, gcmBind);
                    if (trusted && gms) OplusProxyFix.beginFcmDeliveryWindow(target);
                    if (!trusted) logOnce("FCM rejected: untrusted service sender uid=" + callerUid
                            + ", target=" + target + ", method=" + methodName);
                    return trusted;
                }
            }
            // Only the synchronous broadcast path may inherit the verified entry context.
            return FcmTrust.matches(intent.getAction(), target);
        } catch (Throwable error) {
            logOnce("Unsupported delivery attribution: " + param.method + ": " + error);
            return false;
        }
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
