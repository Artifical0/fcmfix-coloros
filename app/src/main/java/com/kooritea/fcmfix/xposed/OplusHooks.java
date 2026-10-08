package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.PackageManager;

import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.HookStatus;

import java.lang.reflect.Method;

/** Reflection helpers shared by the ColorOS system_server hooks. */
final class OplusHooks {
    /** Hook groups installed in this process; reported to the module app on request. */
    static final HookStatus STATUS = new HookStatus();

    private OplusHooks() {
    }

    interface HookAction {
        void run() throws Throwable;
    }

    /**
     * Installs one hook group; a firmware mismatch is logged and leaves the system behavior.
     * The result is recorded for the in-app status, so a group must throw when nothing hooked.
     */
    static void runHook(String name, HookAction action) {
        try {
            action.run();
            STATUS.recordActive(name);
        } catch (Throwable e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            STATUS.recordFailed(name, reason);
            XposedModule.printLog("hook error " + name + ": " + reason);
        }
    }

    static int getTargetUidFromPackageName(String packageName) {
        if (packageName != null && XposedModule.context != null) {
            try {
                return XposedModule.context.getPackageManager().getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                XposedModule.printLog("error: Package not found: " + packageName);
            }
        }
        return -1;
    }

    static String getIntentTarget(Intent intent) {
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        return intent.getPackage();
    }

    static boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    static Intent findIntentArgument(Object[] args) {
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

    static String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) result.append(',');
            result.append(types[i].getSimpleName());
        }
        return result.append("): ").append(method.getReturnType().getSimpleName()).toString();
    }
}
