package com.kooritea.fcmfix.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Rate-limits diagnostic lines; important lines bypass the limiter. Never throws into a hook. */
public final class DiagnosticLogger {
    private final DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
    private final Consumer<String> localLog;
    private final Consumer<Throwable> failureLog;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    public DiagnosticLogger(Consumer<String> localLog, Consumer<Throwable> failureLog) {
        this.localLog = localLog;
        this.failureLog = failureLog;
    }

    public void log(String text, boolean diagnostic, long now) {
        String output = diagnostic ? limiter.filter(text, now) : String.valueOf(text);
        if (output == null) return;
        try {
            localLog.accept(output);
        } catch (Throwable error) {
            reportFailure(error);
        }
    }

    private void reportFailure(Throwable error) {
        if (!failureReported.compareAndSet(false, true)) return;
        try {
            failureLog.accept(error);
        } catch (Throwable ignored) {
            // Failure reporting must not escape into the intercepted system method either.
        }
    }
}
