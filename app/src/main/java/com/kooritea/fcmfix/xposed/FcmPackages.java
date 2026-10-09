package com.kooritea.fcmfix.xposed;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.kooritea.fcmfix.util.FcmTrust;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installed apps that can receive FCM, kept in system_server for the auto-allow option. The
 * delivery hooks only read the published set; scans run on one worker thread, never inside a
 * broadcast or AMS call.
 */
final class FcmPackages {
    private static final String MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    private static final int FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    private static final ExecutorService worker =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "FCMFix-fcm-apps"));
    /** null until the first scan finishes. */
    private static volatile Set<String> packages;
    private static boolean scanQueued;

    private FcmPackages() {
    }

    static Set<String> current() {
        Set<String> snapshot = packages;
        return snapshot == null ? Collections.emptySet() : snapshot;
    }

    /** Number of known FCM apps, or -1 before the first scan. */
    static int size() {
        Set<String> snapshot = packages;
        return snapshot == null ? -1 : snapshot.size();
    }

    static synchronized void ensureScanned(Context context) {
        if (scanQueued || context == null) return;
        scanQueued = true;
        worker.execute(() -> {
            try {
                PackageManager pm = context.getPackageManager();
                Set<String> found = new HashSet<>();
                for (ResolveInfo info : pm.queryBroadcastReceivers(new Intent(FcmTrust.RECEIVE), FLAGS)) {
                    if (info.activityInfo != null) found.add(info.activityInfo.packageName);
                }
                for (ResolveInfo info : pm.queryIntentServices(new Intent(MESSAGING_EVENT), FLAGS)) {
                    if (info.serviceInfo != null) found.add(info.serviceInfo.packageName);
                }
                packages = Collections.unmodifiableSet(found);
                XposedModule.printLog("FCM apps scanned for auto-allow: " + found.size());
            } catch (Throwable e) {
                synchronized (FcmPackages.class) {
                    scanQueued = false;
                }
                XposedModule.printLog("FCM app scan failed: " + e);
            }
        });
    }

    /** Re-checks one package after it was installed, updated, changed or removed. */
    static void onPackageChanged(Context context, String packageName) {
        if (packageName == null || context == null) return;
        worker.execute(() -> {
            Set<String> snapshot = packages;
            if (snapshot == null) return;
            try {
                PackageManager pm = context.getPackageManager();
                boolean fcm = !pm.queryBroadcastReceivers(new Intent(FcmTrust.RECEIVE).setPackage(packageName), FLAGS).isEmpty()
                        || !pm.queryIntentServices(new Intent(MESSAGING_EVENT).setPackage(packageName), FLAGS).isEmpty();
                if (fcm == snapshot.contains(packageName)) return;
                Set<String> updated = new HashSet<>(snapshot);
                if (fcm) updated.add(packageName);
                else updated.remove(packageName);
                packages = Collections.unmodifiableSet(updated);
                if (fcm) XposedModule.printLog("FCM app added for auto-allow: " + packageName);
            } catch (Throwable e) {
                XposedModule.printLog("FCM app check failed: " + packageName + ": " + e);
            }
        });
    }
}
