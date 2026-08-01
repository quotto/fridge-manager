package com.quotto.fridgemanager.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseConfigurationTest {
    private val debugConfig = File("src/debug/google-services.json").readText()
    private val releaseConfig = File("src/release/google-services.json").readText()
    private val debugProvider = File("src/debug/kotlin/com/quotto/fridgemanager/data/auth/AppCheckProviderInstaller.kt")
    private val releaseProvider = File("src/release/kotlin/com/quotto/fridgemanager/data/auth/AppCheckProviderInstaller.kt")

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

    @Test
    fun `debugだけApp Check debug providerを使う`() {
        assertTrue(debugProvider.readText().contains("DebugAppCheckProviderFactory"))
        assertFalse(releaseProvider.readText().contains("DebugAppCheckProviderFactory"))
        assertTrue(releaseProvider.readText().contains("PlayIntegrityAppCheckProviderFactory"))
    }
}
