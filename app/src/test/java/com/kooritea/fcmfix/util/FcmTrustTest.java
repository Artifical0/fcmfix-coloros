package com.kooritea.fcmfix.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FcmTrustTest {
    @Test public void entryRequiresAllConditions() {
        assertTrue(FcmTrust.allowsEntry(FcmTrust.RECEIVE, "target", true, true));
        assertFalse(FcmTrust.allowsEntry(FcmTrust.RECEIVE, "target", true, false));
        assertFalse(FcmTrust.allowsEntry(FcmTrust.RECEIVE, "target", false, true));
        assertFalse(FcmTrust.allowsEntry(FcmTrust.RECEIVE, null, true, true));
        assertFalse(FcmTrust.allowsEntry(FcmTrust.RECEIVE, "", true, true));
        assertFalse(FcmTrust.allowsEntry("evil.android.c2dm.intent.RECEIVE", "target", true, true));
    }
    @Test public void destinationMustBeExplicitAndCoherent() {
        assertEquals("target", FcmTrust.target(null, "target"));
        assertEquals("target", FcmTrust.target("target", null));
        assertEquals("target", FcmTrust.target("target", "target"));
        assertNull(FcmTrust.target("target", "evil"));
        assertNull(FcmTrust.target(null, null));
    }
    @Test public void selfServiceNeedsWindowAndCannotSpoofReceiveOrTaskReady() {
        assertTrue(FcmTrust.allowsService("com.google.firebase.MESSAGING_EVENT", false, true, false));
        assertFalse(FcmTrust.allowsService("com.google.firebase.MESSAGING_EVENT", false, false, false));
        assertFalse(FcmTrust.allowsService(FcmTrust.RECEIVE, false, true, false));
        assertTrue(FcmTrust.allowsService(FcmTrust.TASK_READY, true, false, true));
        assertFalse(FcmTrust.allowsService(FcmTrust.TASK_READY, false, true, true));
        assertFalse(FcmTrust.allowsService(FcmTrust.TASK_READY, true, false, false));
        assertFalse(FcmTrust.isAction(FcmTrust.TASK_READY));
    }
    @Test public void rejectsUntrustedSender() {
        assertTrue(FcmTrust.isGmsSender(10123, new String[]{"com.google.android.gms"}));
        assertFalse(FcmTrust.isGmsSender(10124, new String[]{"evil"}));
        assertFalse(FcmTrust.isGmsSender(1000, new String[]{"com.google.android.gms"}));
        assertFalse(FcmTrust.isGmsSender(10123, null));
    }
    @Test public void actionsAreExact() {
        assertTrue(FcmTrust.isAction(FcmTrust.RECEIVE));
        assertTrue(FcmTrust.isAction("com.google.firebase.MESSAGING_EVENT"));
        assertFalse(FcmTrust.isAction("evil.android.c2dm.intent.RECEIVE"));
        assertFalse(FcmTrust.isAction(null));
        assertFalse(FcmTrust.isAction(""));
    }

    @Test public void nestedUntrustedEntryMasksAndRestoresOuterTrust() {
        Runnable outer = FcmTrust.enter("target", 10123);
        try {
            assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            Runnable inner = FcmTrust.enter(null, -1);
            try {
                assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            } finally { inner.run(); }
            assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "other"));
            assertFalse(FcmTrust.matches("com.google.firebase.MESSAGING_EVENT", "target"));
        } finally { outer.run(); }
        assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
    }

    @Test public void trustDoesNotCrossThreads() throws Exception {
        Runnable restore = FcmTrust.enter("target", 10123);
        java.util.concurrent.atomic.AtomicBoolean trusted = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            Thread thread = new Thread(() -> trusted.set(FcmTrust.matches(FcmTrust.RECEIVE, "target")));
            thread.start(); thread.join();
            assertFalse(trusted.get());
        } finally { restore.run(); }
    }

    @Test public void systemUidPackagesAreNotFcmApps() {
        assertFalse(FcmTrust.isAppUid(1000));
        assertFalse(FcmTrust.isAppUid(1001000));
        assertTrue(FcmTrust.isAppUid(10123));
        assertTrue(FcmTrust.isAppUid(1010123));
    }
}
