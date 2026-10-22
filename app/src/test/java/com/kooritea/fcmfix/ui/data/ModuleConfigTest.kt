package com.kooritea.fcmfix.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigTest {
    @Test
    fun manualListWithoutAutoAllow() {
        val config = ModuleConfig()
        val allowed = config.withAllowed("a", includeFcm = true, allowed = true)
        assertEquals(setOf("a"), allowed.allowList)
        assertTrue(allowed.allows("a", true))
        assertFalse(allowed.allowsAutomatically("a", true))
        assertEquals(ModuleConfig(), allowed.withAllowed("a", includeFcm = true, allowed = false))
    }

    @Test
    fun autoAllowedFcmAppIsUntickedThroughTheExcludeList() {
        val config = ModuleConfig(autoAllowFcm = true)
        assertTrue(config.allowsAutomatically("a", true))
        val excluded = config.withAllowed("a", includeFcm = true, allowed = false)
        assertEquals(setOf("a"), excluded.excludeList)
        assertTrue(excluded.allowList.isEmpty())
        assertFalse(excluded.allows("a", true))
        val back = excluded.withAllowed("a", includeFcm = true, allowed = true)
        assertTrue(back.excludeList.isEmpty())
        assertTrue("Ticking again goes back to automatic", back.allowList.isEmpty())
        assertTrue(back.allows("a", true))
    }

    @Test
    fun appsWithoutFcmUseTheManualListEvenWithAutoAllow() {
        val config = ModuleConfig(autoAllowFcm = true)
        assertFalse(config.allows("b", false))
        val allowed = config.withAllowed("b", includeFcm = false, allowed = true)
        assertEquals(setOf("b"), allowed.allowList)
        val removed = allowed.withAllowed("b", includeFcm = false, allowed = false)
        assertTrue(removed.allowList.isEmpty())
        assertTrue(removed.excludeList.isEmpty())
    }

    @Test
    fun manuallyTickedFcmAppStaysAfterAutoAllowIsTurnedOn() {
        val config = ModuleConfig(allowList = setOf("a"), autoAllowFcm = true)
        assertTrue(config.allows("a", true))
        assertFalse(config.allowsAutomatically("a", true))
        val unticked = config.withAllowed("a", includeFcm = true, allowed = false)
        assertTrue(unticked.allowList.isEmpty())
        assertEquals(setOf("a"), unticked.excludeList)
    }
}
