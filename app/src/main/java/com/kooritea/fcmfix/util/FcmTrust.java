package com.kooritea.fcmfix.util;

/** Trust is private to the synchronous framework invocation, never stored in Intent extras. */
public final class FcmTrust {
    public static final String RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    public static final String TASK_READY = "com.google.android.gms.gcm.ACTION_TASK_READY";
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private static final class Context {
        final String target;
        final int callerUid;
        Context(String target, int callerUid) {
            this.target = target;
            this.callerUid = callerUid;
        }
    }

    private FcmTrust() {}

    public static boolean isGmsSender(int uid, String[] packages) {
        if (uid < 10000 || packages == null) return false;
        for (String name : packages) if ("com.google.android.gms".equals(name)) return true;
        return false;
    }

    /**
     * Auto-allow and the "FCM" badge skip system-uid packages such as "android": they declare
     * FCM receivers but are never frozen and get no pushes of their own.
     */
    public static boolean isAppUid(int uid) {
        return uid % 100000 >= 10000;
    }

    public static boolean isAction(String action) {
        return RECEIVE.equals(action) || "com.google.firebase.MESSAGING_EVENT".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action);
    }

    public static boolean allowsEntry(String action, String target, boolean allowed, boolean gms) {
        return RECEIVE.equals(action) && target != null && !target.isEmpty() && allowed && gms;
    }

    public static boolean allowsService(String action, boolean gms, boolean selfInWindow,
                                        boolean gcmBind) {
        if (TASK_READY.equals(action)) return gcmBind && gms;
        if (!isAction(action)) return false;
        return gms || (!RECEIVE.equals(action) && selfInWindow);
    }

    public static String target(String packageName, String componentPackage) {
        if (componentPackage != null && packageName != null && !componentPackage.equals(packageName)) {
            return null;
        }
        String target = componentPackage == null ? packageName : componentPackage;
        return target == null || target.isEmpty() ? null : target;
    }

    public static Runnable enter(String target, int callerUid) {
        Context previous = CURRENT.get();
        if (target == null || callerUid < 10000) CURRENT.remove();
        else CURRENT.set(new Context(target, callerUid));
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public static boolean matches(String action, String target) {
        Context current = CURRENT.get();
        return RECEIVE.equals(action) && target != null && current != null
                && current.callerUid >= 10000 && target.equals(current.target);
    }
}
