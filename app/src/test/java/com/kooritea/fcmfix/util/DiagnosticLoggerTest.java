package com.kooritea.fcmfix.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DiagnosticLoggerTest {
    @Test public void duplicateDiagnosticsAreWrittenOnce() {
        List<String> local = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, errors::add);
        logger.log("success", true, 0);
        logger.log("success", true, 1);
        assertEquals(List.of("success"), local);
        assertTrue(errors.isEmpty());
    }

    @Test public void importantLogsBypassDiagnosticLimits() {
        List<String> local = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, errors::add);
        for (int i = 0; i < 100; i++) logger.log("success " + i, true, 0);
        assertEquals(DiagnosticLogLimiter.MAX_BURST, local.size());
        for (int i = 0; i < 100; i++) logger.log("config failure", false, 0);
        assertEquals(DiagnosticLogLimiter.MAX_BURST + 100, local.size());
        assertTrue(errors.isEmpty());
    }

    @Test public void brokenLocalSinkIsReportedOnlyOnce() {
        AtomicInteger failures = new AtomicInteger();
        DiagnosticLogger logger = new DiagnosticLogger(
                ignored -> { throw new IllegalStateException("local sink"); }, e -> failures.incrementAndGet());
        logger.log("one", false, 0);
        logger.log("two", true, 1);
        assertEquals(1, failures.get());
    }

    @Test public void brokenLogSinksCannotEscapeIntoHook() {
        DiagnosticLogger logger = new DiagnosticLogger(
                ignored -> { throw new IllegalStateException("local sink"); },
                ignored -> { throw new IllegalStateException("failure sink"); });
        logger.log("important", false, 0);
        logger.log("diagnostic", true, 1);
    }
}
