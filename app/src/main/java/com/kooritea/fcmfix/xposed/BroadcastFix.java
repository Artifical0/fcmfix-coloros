package com.kooritea.fcmfix.xposed;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.FcmTrust;
import com.kooritea.fcmfix.util.XposedUtils;

public class BroadcastFix extends XposedModule {

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookBroadcastEntryPoints();
        }catch (Throwable e) {
            printLog("hook error broadcast entry point:" + e.getMessage());
        }
        try{
            this.startHookBroadcastIntentLocked();
        }catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
        try{
            this.deoptimizeBroadcastCallers();
        }catch (Throwable e) {
            printLog("hook error broadcast deoptimize:" + e.getMessage());
        }
//        try{
//            this.startHookScheduleResultTo();
//        }catch (Throwable e) {
//            printLog("hook error com.android.server.am.BroadcastQueueModernImpl.scheduleResultTo:" + e.getMessage());
//        }
    }

    /**
     * Android 16-17 / ColorOS 16-17 validates and may clone the incoming Intent
     * before entering broadcastIntentLocked. Hook the Binder-facing entry so
     * FLAG_INCLUDE_STOPPED_PACKAGES survives that copy.
     */
    protected void startHookBroadcastEntryPoints(){
        String[] candidateClasses = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        Set<String> hookedSignatures = new HashSet<>();
        int hookCount = 0;

        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntentWithFeature".equals(method.getName())) {
                    continue;
                }
                int intentArgsIndex = findIntentParameterIndex(method);
                if (intentArgsIndex < 0) {
                    continue;
                }
                String signature = describeMethod(method);
                if (!hookedSignatures.add(signature)) {
                    continue;
                }
                try {
                    createBroadcastIntentHooker(intentArgsIndex, method, "entry");
                    hookCount++;
                } catch (Throwable e) {
                    printLog("hook broadcast entry failed: " + signature + ": " + e.getMessage());
                }
            }
            // AMS delegates to BroadcastController; do not install a second entry there.
            if (hookCount > 0) break;
        }
        printLog("broadcast entry hooks active: " + hookCount);
    }

    protected void startHookBroadcastIntentLocked(){
        String[] candidateClasses = new String[]{
                "com.android.server.am.BroadcastController",
                "com.android.server.am.ActivityManagerService"
        };
        Set<String> hookedSignatures = new HashSet<>();
        int hookCount = 0;

        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                printLog("broadcast hook class missing: " + className);
                continue;
            }

            for (Method method : clazz.getDeclaredMethods()) {
                // Android 15+ moved the body into broadcastIntentLockedTraced; on Android 17
                // (ColorOS 17) the thin broadcastIntentLocked wrapper may be inlined away.
                if (!"broadcastIntentLocked".equals(method.getName())
                        && !"broadcastIntentLockedTraced".equals(method.getName())) {
                    continue;
                }
                int intentArgsIndex = findIntentParameterIndex(method);
                if (intentArgsIndex < 0) {
                    printLog("skip broadcast candidate without Intent: " + describeMethod(method));
                    continue;
                }

                String signature = describeMethod(method);
                if (!hookedSignatures.add(signature)) {
                    continue;
                }

                try {
                    createBroadcastIntentHooker(intentArgsIndex, method, "locked");
                    hookCount++;
                } catch (Throwable e) {
                    printLog("hook broadcast candidate failed: " + signature + ": " + e.getMessage());
                }
            }
        }

        if (hookCount == 0) {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
        } else {
            printLog("broadcastIntentLocked hooks active: " + hookCount);
        }
    }

    /**
     * AOT-compiled services.jar may inline the short broadcast wrappers into their callers,
     * so hooks on those wrappers never run. Deoptimize only the framework-side broadcast
     * chain (not the Binder stub) to keep the extra interpreter cost confined to it.
     */
    protected void deoptimizeBroadcastCallers() {
        String[] classes = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        Set<String> callers = new HashSet<>(java.util.Arrays.asList(
                "broadcastIntentWithFeature",
                "broadcastIntentInPackage",
                "broadcastIntentLocked"));
        int count = 0;
        for (String className : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) continue;
            for (Method method : clazz.getDeclaredMethods()) {
                if (callers.contains(method.getName()) && XposedBridge.deoptimize(method)) count++;
            }
        }
        printLog("broadcast callers deoptimized: " + count);
    }

    private int findIntentParameterIndex(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (Intent.class.isAssignableFrom(parameterTypes[i])) {
                return i;
            }
        }
        return -1;
    }

    private String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) result.append(',');
            result.append(types[i].getSimpleName());
        }
        return result.append(')').toString();
    }

    protected void createBroadcastIntentHooker(int intentIndex, Method method, String stage) {
        printLog("hook target [" + stage + "]: " + describeMethod(method));
        final boolean entry = "entry".equals(stage);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // Mask before even resolving config/UID: a failing nested validation must
                // not expose the outer trusted scope while the original call continues.
                if (entry) param.addFinallyAction(FcmTrust.enter(null, -1));
                try {
                    Intent intent = param.args[intentIndex] instanceof Intent ? (Intent) param.args[intentIndex] : null;
                    String target = explicitTarget(intent);
                    boolean allowed = isBootComplete && targetIsAllow(target);
                    int callerUid = android.os.Binder.getCallingUid();
                    boolean trusted = entry && intent != null && allowed
                            && FcmTrust.allowsEntry(intent.getAction(), target, true, isGmsUid(callerUid));
                    if (entry) {
                        // Mask even null/invalid nested entries; the bridge restores this in finally.
                        if (trusted) param.addFinallyAction(FcmTrust.enter(target, callerUid));
                        if (!trusted && allowed && intent != null && FcmTrust.RECEIVE.equals(intent.getAction())) {
                            logOnce("FCM rejected: untrusted sender uid=" + callerUid + ", target=" + target);
                        }
                    }
                    if (!allowed || intent == null || !FcmTrust.matches(intent.getAction(), target)) return;
                    if (entry) {
                        printLog("FCM trusted sender: uid=" + callerUid + ", target=" + target, true);
                        OplusProxyFix.beginFcmDeliveryWindow(target);
                        if (getBooleanConfig("includeIceBoxDisableApp", false)
                                && !IceboxUtils.isAppEnabled(context, target)) {
                            // Bounded, best-effort activation. Never retain or replay a Binder call.
                            IceboxUtils.requestActivation(context, target);
                        }
                        OplusProxyFix.unfreeze(target);
                    }
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    // Framework AppOps arguments are deliberately left untouched.
                } catch (Throwable error) {
                    // Keep the failure mask until the original invocation has finished.
                    param.addFinallyAction(FcmTrust.enter(null, -1));
                    logOnce("FCM broadcast hook failed: " + method + ": " + error);
                }
            }
        });
    }

    protected void startHookScheduleResultTo(){
        Method method = XposedUtils.findMethod(XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl",classLoader),"scheduleResultTo",1);
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[0] == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultTo") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"intent") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode") == null){
                    return;
                }
                Intent intent = (Intent)XposedHelpers.getObjectField(methodHookParam.args[0],"intent");
                int resultCode = (int) XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode");
                String packageName = intent.getPackage();
                if(resultCode != -1 && getBooleanConfig("noResponseNotification",false) && targetIsAllow(packageName)){
                    try{
                        Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if(notifyIntent!=null){
                            notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                    context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                            createFcmfixChannel(notificationManager);
                            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("FCM Message")
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                            Bitmap icon = getAppIcon(packageName);
                            if(icon != null){
                                notification.setLargeIcon(icon);
                            }
                            notification.setContentIntent(pendingIntent).setAutoCancel(true);
                            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                printLog("No-response notification skipped: host cannot post notifications", false);
                                return;
                            }
                            notificationManager.notify((int) System.currentTimeMillis(), notification.build());
                        }else{
                            printLog("无法获取目标应用active: " + packageName,false);
                        }
                    }catch (Throwable e){
                        printLog(e.getMessage(),false);
                    }
                }
            }
        });
    }

    private static Bitmap getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable drawable = pm.getApplicationIcon(appInfo);
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            } else {
                Bitmap bitmap = Bitmap.createBitmap(
                        drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new android.graphics.Canvas(bitmap));
                return bitmap;
            }
        } catch (Throwable e) {
            return null;
        }
    }
}
