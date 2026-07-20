package com.quotto.fridgemanager.data.auth

import com.quotto.fridgemanager.domain.auth.AuthState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAuthCompositionTest {
    @Test
    fun `Firebase SDK初期化例外でもアプリを落とさずAIだけ無効にする`() = runTest {
        val logs = mutableListOf<String>()
        val coordinator = FirebaseAuthComposition.createSafely(logs::add) {
            error("secret SDK configuration detail")
        }

        assertFalse(coordinator.initialize())
        assertTrue((coordinator.state.value as AuthState.AiUnavailable).manualInventoryAvailable)
        assertFalse(logs.joinToString().contains("secret SDK configuration detail"))
    }
}
