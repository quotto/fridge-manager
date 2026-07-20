package com.quotto.fridgemanager.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseConfigurationTest {
    private val debugConfig = File("src/debug/google-services.json").readText()
    private val releaseConfig = File("src/release/google-services.json").readText()

    @Test
    fun `debugはdevstgのdebug applicationIdだけを使う`() {
        assertTrue(debugConfig.contains("\"project_id\": \"fridge-manager-devstg\""))
        assertTrue(debugConfig.contains("\"package_name\": \"com.quotto.fridgemanager.debug\""))
        assertFalse(debugConfig.contains("fridge-manager-prod"))
    }

    @Test
    fun `releaseはprodのrelease applicationIdだけを使う`() {
        assertTrue(releaseConfig.contains("\"project_id\": \"fridge-manager-prod\""))
        assertTrue(releaseConfig.contains("\"package_name\": \"com.quotto.fridgemanager\""))
        assertFalse(releaseConfig.contains("fridge-manager-devstg"))
    }
}
