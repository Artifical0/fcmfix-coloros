package com.kooritea.fcmfix.xposed;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.provider.Settings;

import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.FcmTrust;
import com.kooritea.fcmfix.util.PushRecords;
import com.kooritea.fcmfix.util.SelfCheck;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Answers the in-app self-check from system_server, which holds the permissions to read other
 * apps' notification and standby state. Every item is read-only and degrades to UNKNOWN on
 * firmware that lacks it; nothing here can affect delivery.
 */
final class SelfCheckCollector {
    private static final String GMS = "com.google.android.gms";
    private static final int MAX_APPS = 300;
    private static final PushRecords PUSHES = new PushRecords(256);

    private SelfCheckCollector() {
    }

    static void recordPush(String packageName) {
        PUSHES.record(packageName, System.currentTimeMillis());
        FcmConnectionMonitor.push(packageName);
    }

    static void collect(Context context, ConfigSnapshot config, Bundle out) {
        out.putBoolean(SelfCheck.KEY_CONFIG_LOADED, config != null);
        out.putInt(SelfCheck.KEY_FCM_PACKAGES, FcmPackages.size());
        if (config != null) {
            out.putInt(SelfCheck.KEY_MANUAL_COUNT, config.allowList.size());
            out.putBoolean(SelfCheck.KEY_AUTO_ALLOW, config.autoAllowFcm());
        }
        PackageManager pm = context.getPackageManager();

        int gmsUid = uidOf(pm, GMS);
        out.putInt(SelfCheck.KEY_GMS_POLICY, gmsUid < 0 ? SelfCheck.UNKNOWN : networkPolicy(gmsUid));
        out.putInt(SelfCheck.KEY_GMS_BUCKET, standbyBucket(context, GMS));
        out.putInt(SelfCheck.KEY_GMS_DOZE, dozeWhitelisted(context, GMS));

        FcmConnectionMonitor.sample();
        out.putInt(SelfCheck.KEY_FCM_STATE, FcmConnectionMonitor.state());
        out.putString(SelfCheck.KEY_FCM_REMOTE, FcmConnectionMonitor.remote());
        out.putLong(SelfCheck.KEY_FCM_SINCE, FcmConnectionMonitor.since());
        out.putInt(SelfCheck.KEY_FCM_RECONNECTS, FcmConnectionMonitor.reconnects());
        out.putInt(SelfCheck.KEY_FCM_OUTAGES, FcmConnectionMonitor.outages());
        out.putLong(SelfCheck.KEY_FCM_LONGEST_OUTAGE, FcmConnectionMonitor.longestOutage());
        out.putLong(SelfCheck.KEY_FCM_LAST_SEEN, FcmConnectionMonitor.lastSeen());
        out.putLong(SelfCheck.KEY_FCM_MONITOR_START, FcmConnectionMonitor.startedAt());
        out.putStringArrayList(SelfCheck.KEY_EVENTS, FcmConnectionMonitor.events());
        out.putStringArrayList(SelfCheck.KEY_PUSH_EVENTS, FcmConnectionMonitor.pushes());

        ContentResolver resolver = context.getContentResolver();
        ArrayList<String> settings = new ArrayList<>();
        settings.add("google_restric_info=" + setting(() -> Settings.Secure.getString(resolver, "google_restric_info")));
        settings.add("oplus_user_change_gms_network_control="
                + setting(() -> Settings.Global.getString(resolver, "oplus_user_change_gms_network_control")));
        settings.add("deepsleep_switch_state=" + setting(() -> Settings.Secure.getString(resolver, "deepsleep_switch_state")));
        out.putStringArrayList(SelfCheck.KEY_SETTINGS, settings);

        if (config == null) return;
        Set<String> fcm = FcmPackages.current();
        Set<String> allowed = new TreeSet<>(config.allowList);
        if (config.autoAllowFcm()) {
            for (String name : fcm) if (config.allows(name, fcm)) allowed.add(name);
        }
        Set<String> running = runningPackages(context);
        Object notifications = notificationService();
        ArrayList<String> apps = new ArrayList<>();
        for (String name : allowed) {
            if (apps.size() >= MAX_APPS) break;
            ApplicationInfo info;
            try {
                info = pm.getApplicationInfo(name, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            // A ticked system package such as "android" is harmless and gets no pushes; skip it.
            if (!FcmTrust.isAppUid(info.uid)) continue;
            Bundle app = new Bundle();
            app.putBoolean(SelfCheck.APP_AUTO, !config.allowList.contains(name));
            app.putInt(SelfCheck.APP_NOTIFY, notificationsEnabled(notifications, name, info.uid));
            app.putInt(SelfCheck.APP_BLOCKED_CHANNELS, blockedChannels(notifications, name, info.uid));
            app.putInt(SelfCheck.APP_BUCKET, standbyBucket(context, name));
            app.putBoolean(SelfCheck.APP_STOPPED, (info.flags & ApplicationInfo.FLAG_STOPPED) != 0);
            app.putBoolean(SelfCheck.APP_RUNNING, running.contains(name));
            long[] push = PUSHES.get(name);
            app.putLong(SelfCheck.APP_LAST_PUSH, push == null ? 0 : push[0]);
            app.putLong(SelfCheck.APP_PUSH_COUNT, push == null ? 0 : push[1]);
            out.putBundle(SelfCheck.APP_PREFIX + name, app);
            apps.add(name);
        }
        out.putStringArrayList(SelfCheck.KEY_APPS, apps);
    }

    private interface Reader {
        String read();
    }

    private static String setting(Reader reader) {
        try {
            return String.valueOf(reader.read());
        } catch (Throwable e) {
            return "?";
        }
    }

    private static int uidOf(PackageManager pm, String name) {
        try {
            return pm.getPackageUid(name, 0);
        } catch (Throwable e) {
            return SelfCheck.UNKNOWN;
        }
    }

    /** ColorOS networking_control (OplusExSystemService): transaction 2 is getUidPolicy(uid). */
    private static int networkPolicy(int uid) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, "networking_control");
            if (binder == null) return SelfCheck.UNKNOWN;
            data.writeInterfaceToken("android.net.IOplusNetworkingControlManager");
            data.writeInt(uid);
            if (!binder.transact(2, data, reply, 0)) return SelfCheck.UNKNOWN;
            reply.readException();
            return reply.readInt();
        } catch (Throwable e) {
            XposedModule.logOnce("Self-check network policy unavailable: " + e);
            return SelfCheck.UNKNOWN;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int standbyBucket(Context context, String name) {
        try {
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            Method method = UsageStatsManager.class.getMethod("getAppStandbyBucket", String.class);
            return (Integer) method.invoke(usage, name);
        } catch (Throwable e) {
            XposedModule.logOnce("Self-check standby bucket unavailable: " + e);
            return SelfCheck.UNKNOWN;
        }
    }

    private static int dozeWhitelisted(Context context, String name) {
        try {
            return context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(name) ? 1 : 0;
        } catch (Throwable e) {
            return SelfCheck.UNKNOWN;
        }
    }

    private static Set<String> runningPackages(Context context) {
        Set<String> result = new HashSet<>();
        try {
            List<ActivityManager.RunningAppProcessInfo> processes =
                    context.getSystemService(ActivityManager.class).getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pkgList != null) result.addAll(Arrays.asList(process.pkgList));
                }
            }
        } catch (Throwable e) {
            XposedModule.logOnce("Self-check running processes unavailable: " + e);
        }
        return result;
    }

    /** INotificationManager; its per-package queries are limited to the system. */
    private static Object notificationService() {
        try {
            return NotificationManager.class.getMethod("getService").invoke(null);
        } catch (Throwable e) {
            XposedModule.logOnce("Self-check notification service unavailable: " + e);
            return null;
        }
    }

    private static int notificationsEnabled(Object service, String name, int uid) {
        if (service == null) return SelfCheck.UNKNOWN;
        try {
            Method method = Class.forName("android.app.INotificationManager")
                    .getMethod("areNotificationsEnabledForPackage", String.class, int.class);
            return (Boolean) method.invoke(service, name, uid) ? 1 : 0;
        } catch (Throwable e) {
            XposedModule.logOnce("Self-check notification state unavailable: " + e);
            return SelfCheck.UNKNOWN;
        }
    }

    private static int blockedChannels(Object service, String name, int uid) {
        if (service == null) return SelfCheck.UNKNOWN;
        try {
            Method method = Class.forName("android.app.INotificationManager")
                    .getMethod("getNotificationChannelsForPackage", String.class, int.class, boolean.class);
            Object slice = method.invoke(service, name, uid, false);
            List<?> channels = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
            int blocked = 0;
            for (Object channel : channels) {
                if (channel instanceof NotificationChannel
                        && ((NotificationChannel) channel).getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    blocked++;
                }
            }
            return blocked;
        } catch (Throwable e) {
            XposedModule.logOnce("Self-check notification channels unavailable: " + e);
            return SelfCheck.UNKNOWN;
        }
    }
}
