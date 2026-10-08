package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.Pair;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.HookResults;
import com.kooritea.fcmfix.util.UnfreezeArguments;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

import static com.kooritea.fcmfix.util.HookResults.findEnumConstant;
import static com.kooritea.fcmfix.xposed.OplusHooks.*;

/**
 * Delivers a trusted FCM broadcast to the target: lifts the ColorOS broadcast/wakelock proxy,
 * unfreezes the target and keeps Hans from refreezing it or closing its sockets while the
 * push is being handled (the per-UID FCM delivery window).
 */
public class OplusProxyFix extends XposedModule {

    private static final String OPLUS_APP_NET_CONTROL_SERVICE =
            "com.android.server.nwpower.OAppNetControlService";
    private static final String OPLUS_HANS_SCENE_MANAGER =
            "com.android.server.hans.scene.HansSceneManager";
    private static final String OPLUS_HANS_CGROUP =
            "com.android.server.hans.freeze.HansCGroup";
    private static final String OPLUS_BROADCAST_PROXY_ACTION =
            "com.android.server.am.BroadcastProxyAction";
    private static final String OPLUS_HANS_MANAGER =
            "com.android.server.am.OplusHansManager";
    private static final long FCM_DELIVERY_WINDOW_MS = 20_000L;

    /**
     * ColorOS can unfreeze an FCM target, deliver the broadcast and freeze it again about
     * three seconds later. OAppNetControlService then destroys the target UID's sockets,
     * so applications such as WeChat may not finish fetching the actual message. Keep only
     * the UID involved in the current FCM delivery unfrozen and online for the same kind of
     * short execution window Android grants to high-priority push work.
     */
    private static final com.kooritea.fcmfix.util.FcmDeliveryWindow sFcmDeliveryWindows =
            new com.kooritea.fcmfix.util.FcmDeliveryWindow();
    private static final ConcurrentHashMap<Integer, String> sFcmDeliveryPackages =
            new ConcurrentHashMap<>();

    private static final String[] PROXY_BROADCAST_CLASSES = new String[]{
            "com.android.server.am.OplusProxyBroadcast",
            "com.android.server.am.OplusBroadcastProxy",
            "com.oplus.server.am.OplusProxyBroadcast"
    };

    private static final String[] PROXY_WAKELOCK_CLASSES = new String[]{
            "com.android.server.power.OplusProxyWakeLock",
            "com.android.server.power.oplus.OplusProxyWakeLock",
            "com.oplus.server.power.OplusProxyWakeLock"
    };

    private static volatile Object sOplusProxyWakeLock;
    private static volatile Method sUnfreezeMethod;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        runHook("OplusProxyWakeLock", this::startHookOplusProxyWakeLock);
        runHook("OplusProxyBroadcast", this::startHookOplusProxyBroadcast);
        runHook("OAppNetControlService", this::startHookOAppNetControlService);
        runHook("HansSceneManager FCM window", this::startHookHansFcmWindow);
        runHook("HansCGroup FCM window", this::startHookHansCGroupFcmWindow);
        runHook("CpnProxy broadcast", this::startHookCpnProxyBroadcast);
        runHook("Hans job FCM window", this::startHookHansJobWindow);
    }

    /**
     * Apps such as Gmail react to an FCM tickle by scheduling a sync/WorkManager job, which Hans
     * blocks for background apps. Only the UID inside its FCM delivery window is exempted.
     * Generalized from a Gmail-only fork change by @Tlipoca1337.
     */
    private void startHookHansJobWindow() {
        Class<?> hansClass = XposedHelpers.findClassIfExists(OPLUS_HANS_MANAGER, classLoader);
        if (hansClass == null) throw new NoClassDefFoundError(OPLUS_HANS_MANAGER);

        int hooks = 0;
        for (Method method : hansClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"checkJobIfRestricted".equals(method.getName()) || !isBooleanType(method.getReturnType())
                    || types.length < 2 || types[0] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[0];
                    if (isInFcmDeliveryWindow(uid)) {
                        printLog("Oplus FCM job-restriction bypass: pkg="
                                + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus Hans job FCM-window hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("OplusHansManager#checkJobIfRestricted");
    }

    private void startHookOplusProxyBroadcast() {
        int hookCount = 0;
        for (String className : PROXY_BROADCAST_CLASSES) {
            Class<?> proxyClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (proxyClass == null) {
                continue;
            }

            for (Method method : proxyClass.getDeclaredMethods()) {
                if (!"shouldProxy".equals(method.getName())) {
                    continue;
                }
                Object noProxyResult = HookResults.getNoProxyResult(method.getReturnType());
                if (noProxyResult == null) {
                    printLog("unsupported shouldProxy candidate: " + describeMethod(method));
                    continue;
                }

                final Object finalNoProxyResult = noProxyResult;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent intent = findIntentArgument(param.args);
                        if (intent == null || !isFCMAction(intent.getAction())) {
                            return;
                        }

                        String target = getIntentTarget(intent);
                        if (target == null) {
                            target = findAllowedPackageArgument(param.args);
                        }
                        if (trustedDelivery(intent, target, param)) {
                            printLog("Oplus shouldProxy bypass: pkg=" + target
                                    + ", action=" + intent.getAction(), true);
                            param.setResult(finalNoProxyResult);
                        }
                    }
                });
                hookCount++;
                printLog("Oplus shouldProxy hook active: " + describeMethod(method));
            }
        }
        if (hookCount == 0) {
            throw new NoSuchMethodError("No compatible Oplus shouldProxy method");
        }
    }

    /**
     * ColorOS 17 Osense scene proxy (game / app start / camera) can hold broadcasts for cold
     * targets. Its action list is loaded from cloud-updatable config, so exempt only a
     * BroadcastRecord-attributed GMS RECEIVE to an allowlisted target.
     */
    private void startHookCpnProxyBroadcast() {
        Class<?> actionClass = XposedHelpers.findClassIfExists(OPLUS_BROADCAST_PROXY_ACTION, classLoader);
        if (actionClass == null) throw new NoClassDefFoundError(OPLUS_BROADCAST_PROXY_ACTION);

        int hooks = 0;
        for (Method method : actionClass.getDeclaredMethods()) {
            if (!"enqueueProxyBroadcastLocked".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !isFCMAction(intent.getAction())) return;
                    String target = getIntentTarget(intent);
                    if (trustedDelivery(intent, target, param)) {
                        printLog("Oplus CpnProxy broadcast bypass: pkg=" + target, true);
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus CpnProxy broadcast hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("BroadcastProxyAction#enqueueProxyBroadcastLocked");
    }

    private void startHookOplusProxyWakeLock() {
        Class<?> wakeLockClass = null;
        for (String className : PROXY_WAKELOCK_CLASSES) {
            wakeLockClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (wakeLockClass != null) {
                break;
            }
        }
        if (wakeLockClass == null) {
            throw new NoClassDefFoundError("OplusProxyWakeLock");
        }

        sUnfreezeMethod = findBestUnfreezeMethod(wakeLockClass);
        if (sUnfreezeMethod == null) {
            throw new NoSuchMethodError(wakeLockClass.getName() + "#unfreezeIfNeed");
        }
        sUnfreezeMethod.setAccessible(true);
        printLog("Oplus unfreeze method selected: " + describeMethod(sUnfreezeMethod));

        if (Modifier.isStatic(sUnfreezeMethod.getModifiers())) {
            return;
        }

        int constructorHooks = 0;
        for (Constructor<?> constructor : wakeLockClass.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sOplusProxyWakeLock = param.thisObject;
                    printLog("OplusProxyWakeLock instance captured");
                }
            });
            constructorHooks++;
        }
        if (constructorHooks == 0) {
            throw new NoSuchMethodError(wakeLockClass.getName() + "#<init>");
        }
    }

    private Method findBestUnfreezeMethod(Class<?> clazz) {
        Method best = null;
        int bestScore = -1;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"unfreezeIfNeed".equals(method.getName())) {
                continue;
            }
            int score = 0;
            for (Class<?> type : method.getParameterTypes()) {
                if (type == int.class || type == Integer.class) score += 4;
                if (WorkSource.class.isAssignableFrom(type)) score += 3;
                if (type == String.class) score += 1;
            }
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }

    public static void unfreeze(String target) {
        Method method = sUnfreezeMethod;
        if (method == null) {
            return;
        }
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : sOplusProxyWakeLock;
        if (!Modifier.isStatic(method.getModifiers()) && receiver == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        Object[] args = UnfreezeArguments.create(method.getParameterTypes(), uid);
        if (args == null) {
            printLog("unsupported Oplus unfreeze arguments: " + describeMethod(method));
            return;
        }

        try {
            method.invoke(receiver, args);
            printLog("unfreeze " + target + ", uid=" + uid, true);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            printLog("Oplus unfreeze invocation failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
        } catch (Throwable e) {
            printLog("Oplus unfreeze invocation failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    public static void beginFcmDeliveryWindow(String target) {
        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) return;

        sFcmDeliveryWindows.begin(uid, SystemClock.elapsedRealtime());
        sFcmDeliveryPackages.put(uid, target);
        printLog("Oplus FCM delivery window: pkg=" + target + ", uid=" + uid
                + ", duration=" + FCM_DELIVERY_WINDOW_MS + "ms", true);
    }

    static boolean isInFcmDeliveryWindow(int uid) {
        if (sFcmDeliveryWindows.contains(uid, SystemClock.elapsedRealtime())) return true;
        sFcmDeliveryPackages.remove(uid);
        return false;
    }

    static String getFcmDeliveryPackage(int uid) {
        String packageName = sFcmDeliveryPackages.get(uid);
        return packageName == null ? "uid:" + uid : packageName;
    }

    /** Prevent ColorOS background-network control from closing the target socket mid-push. */
    private void startHookOAppNetControlService() {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(
                OPLUS_APP_NET_CONTROL_SERVICE, classLoader);
        if (serviceClass == null) throw new NoClassDefFoundError(OPLUS_APP_NET_CONTROL_SERVICE);

        int hooks = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (!"hansUpdateFirewallList".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || !Pair.class.isAssignableFrom(types[0])) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Pair)) return;
                    Pair<?, ?> update = (Pair<?, ?>) param.args[0];
                    if (!(update.first instanceof Integer) || !(update.second instanceof Boolean)) {
                        return;
                    }
                    int uid = (Integer) update.first;
                    boolean networkRestore = (Boolean) update.second;
                    if (!networkRestore && isInFcmDeliveryWindow(uid)) {
                        // true is the restore/remove-from-firewall branch in ColorOS 16.
                        param.args[0] = Pair.create(uid, true);
                        printLog("Oplus FCM socket-close bypass: pkg="
                                + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                    }
                }
            });
            hooks++;
            printLog("Oplus app network-control hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("hansUpdateFirewallList");
    }

    /** Keep Hans from refreezing the just-woken target while it handles the push. */
    private void startHookHansFcmWindow() {
        Class<?> sceneClass = XposedHelpers.findClassIfExists(OPLUS_HANS_SCENE_MANAGER, classLoader);
        if (sceneClass == null) throw new NoClassDefFoundError(OPLUS_HANS_SCENE_MANAGER);

        int hooks = 0;
        for (Method method : sceneClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!"freeze".equals(name)
                    && !"freezeDirectlyForSceneCombo".equals(name)
                    && !"freezeAndTransState".equals(name)
                    && !"freezeViaSM".equals(name)) {
                continue;
            }
            if (method.getParameterTypes().length == 0) continue;
            boolean stateMachineOnly = "freezeViaSM".equals(name)
                    && method.getReturnType() == void.class;
            Object important = stateMachineOnly
                    ? null : findEnumConstant(method.getReturnType(), "IMPORTANT");
            if (!stateMachineOnly && important == null) continue;

            final Object noFreezeResult = important;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    int uid = getHansPackageUid(param.args[0]);
                    if (uid >= 0 && isInFcmDeliveryWindow(uid)) {
                        printLog("Oplus FCM Hans-freeze bypass: pkg="
                                + getFcmDeliveryPackage(uid) + ", uid=" + uid
                                + ", method=" + method.getName(), true);
                        param.setResult(noFreezeResult);
                    }
                }
            });
            hooks++;
            printLog("Oplus Hans FCM-window hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("HansSceneManager freeze methods");
    }

    /**
     * The lock-screen Fast Freezer can bypass HansSceneManager's regular freeze methods on
     * cgroup v2 devices. Intercept both the package freeze and the direct UID fast-freeze
     * entry so an in-flight push cannot be suspended through that path either.
     */
    private void startHookHansCGroupFcmWindow() {
        Class<?> cgroupClass = XposedHelpers.findClassIfExists(OPLUS_HANS_CGROUP, classLoader);
        if (cgroupClass == null) throw new NoClassDefFoundError(OPLUS_HANS_CGROUP);

        int hooks = 0;
        for (Method method : cgroupClass.getDeclaredMethods()) {
            if ("hansFreezeLocked".equals(method.getName())
                    && isBooleanType(method.getReturnType())
                    && method.getParameterTypes().length > 0
                    && !method.getParameterTypes()[0].isPrimitive()) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        int uid = getHansPackageUid(param.args[0]);
                        if (uid >= 0 && isInFcmDeliveryWindow(uid)) {
                            printLog("Oplus FCM cgroup-freeze bypass: pkg="
                                    + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                            param.setResult(false);
                        }
                    }
                });
                hooks++;
                printLog("Oplus Hans cgroup hook active: " + describeMethod(method));
            } else if (isFastFreezeEnter(method)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = (Integer) param.args[0];
                        if (isInFcmDeliveryWindow(uid)) {
                            printLog("Oplus FCM fast-freeze bypass: pkg="
                                    + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                            param.setResult(null);
                        }
                    }
                });
                hooks++;
                printLog("Oplus Hans fast-freezer hook active: " + describeMethod(method));
            }
        }
        if (hooks == 0) throw new NoSuchMethodError("HansCGroup freeze methods");
    }

    private static boolean isFastFreezeEnter(Method method) {
        String[] types = java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getName).toArray(String[]::new);
        return com.kooritea.fcmfix.util.HansSignature.isFastFreezeEnter(
                method.getName(), method.getReturnType().getName(), types);
    }

    private static int getHansPackageUid(Object hansPackage) {
        try {
            Object uid = XposedHelpers.callMethod(hansPackage, "getUid");
            return uid instanceof Integer ? (Integer) uid : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
