package com.kooritea.fcmfix.libxposed;

import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;


import com.kooritea.fcmfix.util.LogRing;

import io.github.libxposed.api.XposedInterface;

public final class XposedBridge {

    private static XposedInterface xposedInterface;
    private static final LogRing RECENT = new LogRing(400);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");


    private XposedBridge() {
    }

    public static void init(XposedInterface xposed) {
        xposedInterface = xposed;
    }

    public static void log(String text) {
        android.util.Log.i("fcmfix", text);
        RECENT.add(LocalDateTime.now().format(TIME) + " " + text);
    }

    /** This process's recent module log lines, oldest first. */
    public static ArrayList<String> recentLogs() {
        return RECENT.snapshot();
    }

    public static SharedPreferences getRemotePreferences(String group) {
        ensureInit();
        return xposedInterface.getRemotePreferences(group);
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        ensureInit();
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }

        // One interceptor per registration. Replaying a global callback list here would
        // run callbacks N times when a method has N registrations.
        XposedInterface.HookHandle handle = xposedInterface.hook((java.lang.reflect.Executable) member)
                .intercept(chain -> {
                    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                    param.method = chain.getExecutable();
                    param.thisObject = chain.getThisObject();
                    param.args = chain.getArgs().toArray(new Object[0]);
                    return HookInvocation.invoke(param, callback, chain::proceed,
                            error -> log("Hook failed: " + member + ": " + error));
                });

        return callback.new Unhook(new HookHandleWrapper(handle));
    }

    /**
     * Force a caller back to non-inlined execution so hooks on its short callees fire.
     * Returns false instead of throwing; a failed deoptimization only loses that layer.
     */
    public static boolean deoptimize(Member member) {
        ensureInit();
        if (!(member instanceof java.lang.reflect.Executable)) return false;
        try {
            return xposedInterface.deoptimize((java.lang.reflect.Executable) member);
        } catch (Throwable e) {
            log("deoptimize failed: " + member + ": " + e);
            return false;
        }
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        ensureInit();
        if (method instanceof Method) {
            Method m = (Method) method;
            XposedInterface.Invoker<?, Method> invoker = xposedInterface.getInvoker(m);
            invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
            try {
                return invoker.invoke(thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        if (method instanceof Constructor<?>) {
            Constructor<?> c = (Constructor<?>) method;
            XposedInterface.CtorInvoker<?> invoker = xposedInterface.getInvoker(c);
            try {
                return invoker.newInstance(args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        throw new IllegalArgumentException("Unsupported member type: " + method);
    }

    static final class HookHandleWrapper {
        private final XposedInterface.HookHandle handle;

        HookHandleWrapper(XposedInterface.HookHandle handle) {
            this.handle = handle;
        }

        void unhook() {
            handle.unhook();
        }
    }

    private static void ensureInit() {
        if (xposedInterface == null) {
            throw new IllegalStateException("XposedBridge not initialized");
        }
    }
}
