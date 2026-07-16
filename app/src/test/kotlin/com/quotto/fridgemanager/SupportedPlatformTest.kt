package com.quotto.fridgemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPlatformTest {
    @Test
    fun `Android 11 から Android 17 までをサポートする`() {
        assertEquals(30, SupportedPlatform.minApi)
        assertEquals(37, SupportedPlatform.maxApi)
        assertEquals((30..37).toList(), SupportedPlatform.apiLevels)
    }

    @Test
    fun `サポート範囲の境界を判定できる`() {
        assertFalse(SupportedPlatform.supports(apiLevel = 29))
        assertTrue(SupportedPlatform.supports(apiLevel = 30))
        assertTrue(SupportedPlatform.supports(apiLevel = 37))
        assertFalse(SupportedPlatform.supports(apiLevel = 38))
    }
}
