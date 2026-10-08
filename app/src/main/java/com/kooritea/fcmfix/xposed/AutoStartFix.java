package com.kooritea.fcmfix.xposed;

import android.content.Intent;

import java.lang.reflect.Method;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import static com.kooritea.fcmfix.xposed.OplusHooks.findIntentArgument;

public class AutoStartFix extends XposedModule {
    public AutoStartFix(ClassLoader classLoader){
        super(classLoader);
        try{
            this.startHook();
        }catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    protected void startHook(){
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
}
