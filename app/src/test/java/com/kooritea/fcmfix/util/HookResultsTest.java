package com.kooritea.fcmfix.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class HookResultsTest {
    private enum ProxyState { PROXY, ALLOW, NOT_PROXY }
    private enum Freeze { NORMAL, IMPORTANT }
    private enum Unknown { YES, NO }

    @Test public void booleanGatesPassWithFalse() {
        assertEquals(Boolean.FALSE, HookResults.getNoProxyResult(boolean.class));
        assertEquals(Boolean.FALSE, HookResults.getNoProxyResult(Boolean.class));
    }

    @Test public void enumGatesUseThePreferredPassThroughConstant() {
        assertSame(ProxyState.NOT_PROXY, HookResults.getNoProxyResult(ProxyState.class));
    }

    @Test public void unknownReturnTypesAreUnsupported() {
        assertNull(HookResults.getNoProxyResult(Unknown.class));
        assertNull(HookResults.getNoProxyResult(int.class));
        assertNull(HookResults.getNoProxyResult(String.class));
    }

    @Test public void findEnumConstantMatchesByNameOnly() {
        assertSame(Freeze.IMPORTANT, HookResults.findEnumConstant(Freeze.class, "IMPORTANT"));
        assertNull(HookResults.findEnumConstant(Freeze.class, "important"));
        assertNull(HookResults.findEnumConstant(String.class, "IMPORTANT"));
    }
}
