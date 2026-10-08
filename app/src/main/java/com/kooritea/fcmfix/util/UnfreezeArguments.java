package com.kooritea.fcmfix.util;

import android.os.WorkSource;

/**
 * Builds arguments for whichever OplusProxyWakeLock#unfreezeIfNeed overload the firmware has:
 * the first int is the UID, other values are neutral. Null when no UID slot or an unknown
 * primitive is found.
 */
public final class UnfreezeArguments {
    private UnfreezeArguments() {
    }

    public static Object[] create(Class<?>[] types, int uid) {
        Object[] args = new Object[types.length];
        boolean uidAssigned = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == int.class || type == Integer.class) {
                args[i] = uidAssigned ? 0 : uid;
                uidAssigned = true;
            } else if (WorkSource.class.isAssignableFrom(type)) {
                args[i] = new WorkSource();
            } else if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
                args[i] = "FCMFix";
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else if (type == float.class || type == Float.class) {
                args[i] = 0F;
            } else if (type == double.class || type == Double.class) {
                args[i] = 0D;
            } else if (!type.isPrimitive()) {
                args[i] = null;
            } else {
                return null;
            }
        }
        return uidAssigned ? args : null;
    }
}
