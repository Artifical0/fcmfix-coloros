# The hook side runs inside system_server and Battery (com.oplus.athena). Keep it exactly as
# written: no renaming, no removal, so hook behaviour and stack traces in the module log stay
# the same as in a debug build. R8 only shrinks the app UI and the libraries.
-keep class com.kooritea.fcmfix.XposedMain { *; }
-keep class com.kooritea.fcmfix.xposed.** { *; }
-keep class com.kooritea.fcmfix.libxposed.** { *; }
-keep class com.kooritea.fcmfix.util.** { *; }

# libxposed: the API is provided by LSPosed at runtime; the service binds through its provider.
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.api.**

# Readable line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
