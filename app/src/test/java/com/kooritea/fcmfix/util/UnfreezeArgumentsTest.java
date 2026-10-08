package com.kooritea.fcmfix.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnfreezeArgumentsTest {
    @Test public void firstIntIsTheUidAndOthersAreNeutral() {
        Object[] args = UnfreezeArguments.create(new Class<?>[]{
                String.class, int.class, Integer.class, boolean.class, long.class, Object.class}, 10123);
        assertArrayEquals(new Object[]{"FCMFix", 10123, 0, false, 0L, null}, args);
    }

    @Test public void overloadWithoutUidSlotIsRejected() {
        assertNull(UnfreezeArguments.create(new Class<?>[]{String.class, boolean.class}, 10123));
        assertNull(UnfreezeArguments.create(new Class<?>[0], 10123));
    }

    @Test public void unknownPrimitiveIsRejected() {
        assertNull(UnfreezeArguments.create(new Class<?>[]{int.class, char.class}, 10123));
    }
}
