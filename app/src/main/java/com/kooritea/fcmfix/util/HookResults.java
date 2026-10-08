package com.kooritea.fcmfix.util;

/** Picks the framework's own "do not restrict" return value for a hooked ColorOS method. */
public final class HookResults {
    private static final String[] NO_PROXY_NAMES = new String[]{"NOT_INCLUDE", "NOT_PROXY", "ALLOW", "PASS"};

    private HookResults() {
    }

    public static Object findEnumConstant(Class<?> type, String wantedName) {
        if (!type.isEnum()) return null;
        Object[] constants = type.getEnumConstants();
        if (constants == null) return null;
        for (Object constant : constants) {
            if (wantedName.equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    /** Boolean false, or the first known pass-through enum constant; null when unsupported. */
    public static Object getNoProxyResult(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.FALSE;
        }
        for (String name : NO_PROXY_NAMES) {
            Object constant = findEnumConstant(returnType, name);
            if (constant != null) return constant;
        }
        return null;
    }
}
