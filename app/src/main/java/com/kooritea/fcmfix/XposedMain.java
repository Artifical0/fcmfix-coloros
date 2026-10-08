package com.kooritea.fcmfix;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.OplusBatteryNetworkFix;
import com.kooritea.fcmfix.xposed.OplusGoogleServiceFix;
import com.kooritea.fcmfix.xposed.OplusProxyFix;
import com.kooritea.fcmfix.xposed.OplusStartupFix;
import com.kooritea.fcmfix.xposed.OplusDeviceIdleFix;
import com.kooritea.fcmfix.xposed.XposedModule;

import io.github.libxposed.api.XposedModuleInterface;

public class XposedMain extends io.github.libxposed.api.XposedModule {

    private static boolean systemServer;
    private static boolean batteryHooked;

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        systemServer = true;
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();
        // Signature mismatches are per-OTA; record which firmware produced the hook log.
        XposedBridge.log("[fcmfix] firmware: sdk=" + android.os.Build.VERSION.SDK_INT
                + ", oplusrom=" + systemProperty("ro.build.version.oplusrom.display")
                + ", display=" + android.os.Build.DISPLAY);
        XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/com.android.server.am.BroadcastController");
        new BroadcastFix(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.am.OplusAppStartupManager");
        new AutoStartFix(classLoader);

        XposedBridge.log("[fcmfix] com.android.server.notification.NotificationManagerService");
        new KeepNotification(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.power.OplusProxyWakeLock");
        new OplusProxyFix(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.am.OplusAppStartupManager startup gates");
        new OplusStartupFix(classLoader);

        XposedBridge.log("[fcmfix] start hook Google core service restrictions");
        new OplusGoogleServiceFix(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.OplusDeviceIdleHelper");
        new OplusDeviceIdleFix(classLoader);
    }

    private static String systemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) properties.getMethod("get", String.class, String.class).invoke(null, key, "unknown");
        } catch (Throwable e) {
            return "unknown";
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        // Battery runs in process com.oplus.athena, which the persistent Athena app (same system
        // uid) starts first, so Battery is normally not that process's first package.
        if ("com.oplus.battery".equals(param.getPackageName()) && !systemServer && !batteryHooked) {
            batteryHooked = true;
            XposedModule.setSelfPackageName("com.oplus.battery");
            XposedBridge.log("[fcmfix] start hook com.oplus.battery GMS network policy");
            new OplusBatteryNetworkFix(param.getClassLoader());
        }
    }
}
