package com.kooritea.fcmfix.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class NightNetworkWhitelistTest {
    @Test public void appendsUidWithoutTouchingTheOriginal() {
        List<String> original = new ArrayList<>(List.of("1000", "10050:com.heytap.mcs"));
        assertEquals(List.of("1000", "10050:com.heytap.mcs", "10123"),
                NightNetworkWhitelist.withUid(original, 10123));
        assertEquals(2, original.size());
    }

    @Test public void listedUidIsLeftAlone() {
        assertNull(NightNetworkWhitelist.withUid(List.of("10123"), 10123));
        assertNull(NightNetworkWhitelist.withUid(List.of("10123:com.google.android.gms"), 10123));
    }

    @Test public void prefixOfAnotherUidDoesNotCount() {
        assertEquals(List.of("101234", "10123"), NightNetworkWhitelist.withUid(List.of("101234"), 10123));
    }

    @Test public void nullEntriesAreTolerated() {
        assertEquals(Arrays.asList(null, "10123"), NightNetworkWhitelist.withUid(Arrays.asList((String) null), 10123));
    }
}
