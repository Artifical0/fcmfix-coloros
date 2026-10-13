package com.kooritea.fcmfix.util;

import android.os.Bundle;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class GoogleNetworkCompatTest {
    static class KnownEntries {
        public int networkDisableWhiteList(List<String> list, int enable) { return 0; }
        public void requestSceneAction(Bundle bundle) {}
        public boolean requestSceneActionSync(Bundle bundle) { return true; }
    }

    static class WrongEntries {
        public boolean networkDisableWhiteList(List<String> list, int enable) { return false; }
        public int networkDisableWhiteList(ArrayList<String> list, int enable) { return 0; }
        public int networkDisableWhiteList(List<String> list, boolean enable) { return 0; }
        public int networkDisableWhiteList(int enable, List<String> list) { return 0; }
        public boolean requestSceneAction(Bundle bundle) { return false; }
        public void requestSceneActionSync(Bundle bundle) {}
        public boolean requestSceneActionSync(Object bundle) { return false; }
        public boolean requestSceneActionSync(Bundle bundle, int uid) { return false; }
        public void handleOtherAction(Bundle bundle) {}
    }

    @Test public void bothFirmwareBinderEntriesAndWhitelistBoundaryMatch() throws Exception {
        assertTrue(GoogleNetworkCompat.isNetworkWhitelist(
                KnownEntries.class.getDeclaredMethod("networkDisableWhiteList", List.class, int.class)));
        assertTrue(GoogleNetworkCompat.isSceneAction(
                KnownEntries.class.getDeclaredMethod("requestSceneAction", Bundle.class)));
        assertTrue(GoogleNetworkCompat.isSceneAction(
                KnownEntries.class.getDeclaredMethod("requestSceneActionSync", Bundle.class)));
    }

    @Test public void unknownOverloadsAndReturnTypesFailClosed() {
        for (java.lang.reflect.Method method : WrongEntries.class.getDeclaredMethods()) {
            assertFalse(method.toString(), GoogleNetworkCompat.isNetworkWhitelist(method));
            assertFalse(method.toString(), GoogleNetworkCompat.isSceneAction(method));
        }
    }

    @Test public void onlyTheGmsDeepSleepForceStopIsSkipped() {
        String pkg = "com.google.android.gms";
        String reason = "DeepSleepLogicDisNetRestore";
        String action = "CommonExternalClean";
        assertTrue(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, reason, action, 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop("com.example.app", reason, action, 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop("com.google.android.gsf", reason, action, 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, "user requested", action, 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, reason, "OtherAction", 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, reason, action, 1));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, reason, action, -1));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(null, reason, action, 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, null, action, 2));
        assertFalse(GoogleNetworkCompat.shouldSkipGmsForceStop(pkg, reason, null, 2));
    }

    static class LegacyConnectivity {
        private boolean mBtFreezeDozeRestrict;
        private final ArrayList<String> mBtConnectionControlPkgList = new ArrayList<>();
        public boolean btFreezeControlEnable() { return false; }
        public boolean isBtFreezeDozeRestrict(int uid, String pkg) { return false; }
    }

    static class ChangedWeakSignalConnectivity {
        private boolean mBtFreezeDozeRestrict;
        private final ArrayList<String> mBtConnectionControlPkgList = new ArrayList<>();
        public boolean btFreezeControlEnable() { return false; }
        public boolean isBtFreezeDozeRestrict(int uid, String pkg) { return false; }
        // Deliberately not the supported String overload: this must still report a mismatch.
        public boolean isWeakSignalNetWhiteList(int uid) { return false; }
    }

    static class ConnectivityWithFirewallField {
        private boolean mBtFreezeDozeRestrict;
        private final ArrayList<String> mBtConnectionControlPkgList = new ArrayList<>();
        private int mUidFireWallChain;
        public boolean btFreezeControlEnable() { return false; }
        public boolean isBtFreezeDozeRestrict(int uid, String pkg) { return false; }
    }

    static class InheritedConnectivity extends LegacyConnectivity {}

    @Test public void absentFeatureRequiresTheKnownAndroid16Shape() {
        assertTrue(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(LegacyConnectivity.class, 36));
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(LegacyConnectivity.class, 37));
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(LegacyConnectivity.class, 35));
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(Object.class, 36));
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(null, 36));
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(InheritedConnectivity.class, 36));
    }

    @Test public void renamedWeakSignalOrFirewallMembersCannotBeHiddenAsNotApplicable() {
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(ChangedWeakSignalConnectivity.class, 36));
        assertFalse(GoogleNetworkCompat.isLegacyConnectivityWithoutWeakSignal(ConnectivityWithFirewallField.class, 36));
    }

    static class HansWithoutWeakSignal {
        private boolean mStrictMode;
        void setFirewallRuleDeny(int uid) {}
    }

    static class HansWithWeakSignal {
        static final int STRICT_MODE_SCENE_WEAK_SIGNAL = 5;
        void setFirewallRuleDeny(int uid) {}
    }

    @Test public void weakSignalAbsentOnlyWhenHansHasNoWeakScene() {
        assertTrue(GoogleNetworkCompat.isHansWithoutWeakSignal(HansWithoutWeakSignal.class));
        assertFalse(GoogleNetworkCompat.isHansWithoutWeakSignal(HansWithWeakSignal.class));
        assertFalse(GoogleNetworkCompat.isHansWithoutWeakSignal(null));
    }

    static class ActivityOnlyStartup {
        private boolean preventByMaliciousCompStartegy(int callingUid, int userId, String pkg, String cls) { return false; }
        boolean shouldPreventStartActivity() { return false; }
        void dumpMaliciousConfig(java.io.PrintWriter pw) {}
    }

    static class StartupWithBroadcastMaliciousCheck {
        private boolean preventByMaliciousCompStartegy(int callingUid, int userId, String pkg, String cls) { return false; }
        boolean shouldPreventBroadcastByMaliciousCheck(Object record) { return false; }
    }

    static class StartupChangedSignature {
        private boolean preventByMaliciousCompStartegy(int callingUid, String pkg) { return false; }
    }

    static class StartupWithoutMaliciousCheck {
        boolean shouldPreventStartActivity() { return false; }
    }

    @Test public void maliciousCheckNotApplicableOnlyForTheActivityOnlyShape() {
        assertTrue(GoogleNetworkCompat.isActivityOnlyMaliciousCheck(ActivityOnlyStartup.class));
        assertFalse(GoogleNetworkCompat.isActivityOnlyMaliciousCheck(StartupWithBroadcastMaliciousCheck.class));
        assertFalse(GoogleNetworkCompat.isActivityOnlyMaliciousCheck(StartupChangedSignature.class));
        assertFalse(GoogleNetworkCompat.isActivityOnlyMaliciousCheck(StartupWithoutMaliciousCheck.class));
        assertFalse(GoogleNetworkCompat.isActivityOnlyMaliciousCheck(null));
    }
}
