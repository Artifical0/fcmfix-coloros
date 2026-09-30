package com.kooritea.fcmfix.xposed;

import android.content.Intent;

import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Method;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class AutoStartFix extends XposedModule {
    private final String FCM_RECEIVE = ".android.c2dm.intent.RECEIVE";

    public AutoStartFix(ClassLoader classLoader){
        super(classLoader);
        try{
            this.startHook();
            this.startHookRemovePowerPolicy();
        }catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    protected void startHook(){
        try{
            // miui12
            Class<?> BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector",classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueInjector,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[2], "intent");
                    if(isFCMIntent(intent)){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            XposedHelpers.callStaticMethod(BroadcastQueueInjector,"checkAbnormalBroadcastInQueueLocked", methodHookParam.args[1], methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target, true);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        }
        try{
            // miui13
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl",classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    if(isFCMIntent(intent)){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target, true);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart");
        }

        try{
            // hyperos
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernStubImpl",classLoader);
            printLog("[fcmfix] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkApplicationAutoStart");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if (targetIsAllow(target)) {
                        // 无日志，先放了
                        printLog("[" + intent.getAction() + "]checkApplicationAutoStart package_name: " + target, true);
                        methodHookParam.setResult(true);
//                        if(isFCMIntent(intent)){
//                            printLog("checkApplicationAutoStart package_name: " + target, true);
//                            methodHookParam.setResult(true);
//                        }else{
//                            printLog("[skip][" + intent.getAction() + "]checkApplicationAutoStart package_name: " + target, true);
//                        }

                    }
                }
            });

            printLog("[fcmfix] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkReceiverIfRestricted");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkReceiverIfRestricted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(targetIsAllow(target)){
                        if(isFCMIntent(intent)){
                            printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted package_name: " + target, true);
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl");
        }

        try {
            Class<?> AutoStartManagerServiceStubImpl = XposedHelpers.findClass("com.android.server.am.AutoStartManagerServiceStubImpl", classLoader);
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) methodHookParam.args[1];
                    if (intent == null) {
                        return;
                    }
                    // Implicit service intents have no component (fix from @Tlipoca1337's fork).
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(target != null && targetIsAllow(target)) {
                        // 拿不到action，先放了
                        printLog("[" + intent.getAction() + "]AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
                        methodHookParam.setResult(true);
//                        if(isFCMIntent(intent)){
//                            printLog("AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
//                            methodHookParam.setResult(true);
//                        }else{
//                            printLog("[skip][" + intent.getAction() + "]AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
//                        }
                    }
                }
            };

            printLog("[fcmfix] start hook com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
            XC_MethodHook.Unhook unhook1 = XposedUtils.tryFindAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 3, methodHook);
            XC_MethodHook.Unhook unhook2 = XposedUtils.tryFindAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 4, methodHook);
            if(unhook1 == null && unhook2 == null){
                throw new NoSuchMethodError();
            }
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
        }

        try {
            Class<?> SmartPowerService = XposedHelpers.findClass("com.android.server.am.SmartPowerService", classLoader);

            printLog("[fcmfix] start hook com.android.server.am.SmartPowerService.shouldInterceptBroadcast");
            XposedUtils.findAndHookMethodAnyParam(SmartPowerService, "shouldInterceptBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(targetIsAllow(target)) {
                        if(isFCMIntent(intent)){
                            printLog("SmartPowerService.shouldInterceptBroadcast package_name: " + target, true);
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.SmartPowerService");
        }

        try{
            // OOS/COS 15/16: ColorOS updates have changed the parameter count.
            // Hook every boolean overload and discover the Intent at runtime.
            Class<?> startupManager = XposedHelpers.findClass(
                    "com.android.server.am.OplusAppStartupManager", classLoader);
            int hookCount = 0;
            String[] methodNames = new String[]{
                    "shouldPreventSendReceiverReal",
                    "shouldPreventSendReceiver"
            };
            for (Method method : startupManager.getDeclaredMethods()) {
                if (!contains(methodNames, method.getName())) {
                    continue;
                }
                if (method.getReturnType() != boolean.class
                        && method.getReturnType() != Boolean.class) {
                    continue;
                }
                XposedBridge.hookMethod(method,new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                        Intent intent = findIntentArgument(methodHookParam.args);
                        if (intent == null || !isFCMIntent(intent)) {
                            return;
                        }
                        String target = intent.getComponent() == null
                                ? intent.getPackage()
                                : intent.getComponent().getPackageName();
                        if (target == null) {
                            target = findAllowedPackageArgument(methodHookParam.args);
                        }
                        if (trustedDelivery(intent, target, methodHookParam)) {
                            printLog("Oplus auto-start bypass: pkg=" + target
                                    + ", method=" + method.getName(), true);
                            methodHookParam.setResult(false);
                        }
                    }
                });
                hookCount++;
                printLog("Oplus auto-start hook active: " + method.getName()
                        + "/" + method.getParameterCount());
            }
            if (hookCount == 0) {
                throw new NoSuchMethodError();
            }
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e) {
            printLog("No compatible OplusAppStartupManager receiver restriction method");
        }
    }

    private boolean contains(String[] values, String wanted) {
        for (String value : values) {
            if (value.equals(wanted)) return true;
        }
        return false;
    }

    private Intent findIntentArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Object nestedIntent = XposedHelpers.getObjectField(arg, "intent");
                if (nestedIntent instanceof Intent) {
                    return (Intent) nestedIntent;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String findAllowedPackageArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && targetIsAllow((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    protected void startHookRemovePowerPolicy(){
        try {
            // MIUI13
            Class<?> AutoStartManagerService = XposedHelpers.findClass("com.miui.server.smartpower.SmartPowerPolicyManager",classLoader);
            XposedUtils.findAndHookMethodAnyParam(AutoStartManagerService,"shouldInterceptService",new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if("com.google.firebase.MESSAGING_EVENT".equals(intent.getAction())){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            printLog("Disable MIUI Intercept: " + target, true);
                            param.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService");
        }
    }
}
