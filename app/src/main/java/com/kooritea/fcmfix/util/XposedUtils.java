package com.kooritea.fcmfix.util;

import java.lang.reflect.Method;

public class XposedUtils {

    public static Method findMethod(Class<?> clazz, String methodName, int parameterCount) {
        Method method = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterTypes().length == parameterCount) {
                method = m;
            }
        }
        return method;
    }
}
