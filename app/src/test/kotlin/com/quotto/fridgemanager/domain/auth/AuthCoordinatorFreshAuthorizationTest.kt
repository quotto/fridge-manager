package com.quotto.fridgemanager.domain.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCoordinatorFreshAuthorizationTest {
    @Test
    fun `fresh tokenはcallback内の単一送信境界へ一度だけ渡す`() = runTest {
        val firebase = SequentialFirebaseAuth()
        val appCheck = SequentialAppCheck()
        val coordinator = AuthCoordinator(firebase, appCheck)
        var callbackCalls = 0

        val result = coordinator.withFreshAuthorization { authorization ->
            callbackCalls++
            assertEquals("id-token-1", authorization.idToken)
            assertEquals("app-check-token-1", authorization.appCheckToken)
            "sent"
        }

        assertEquals("sent", result)
        assertEquals(1, callbackCalls)
        assertEquals(1, firebase.tokenCalls)
        assertEquals(1, appCheck.tokenCalls)
    }

    @Test
    fun `送信境界の再呼出しでは前回tokenを再利用しない`() = runTest {
        val firebase = SequentialFirebaseAuth()
        val appCheck = SequentialAppCheck()
        val coordinator = AuthCoordinator(firebase, appCheck)
        val observed = mutableListOf<Pair<String, String>>()

        repeat(2) {
            coordinator.withFreshAuthorization { authorization ->
                observed += authorization.idToken to authorization.appCheckToken
            }
        }

        assertEquals(
            listOf(
                "id-token-1" to "app-check-token-1",
                "id-token-2" to "app-check-token-2",
            ),
            observed,
        )
        assertEquals(2, firebase.tokenCalls)
        assertEquals(2, appCheck.tokenCalls)
    }

    @Test
    fun `token取得のcancelを失敗状態へ変換せずcallbackも呼ばない`() = runTest {
        val firebase = SequentialFirebaseAuth(failure = CancellationException("cancelled"))
        val coordinator = AuthCoordinator(firebase, SequentialAppCheck())
        var callbackCalled = false

        var cancellationPropagated = false
        try {
            coordinator.withFreshAuthorization {
                callbackCalled = true
            }
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertFalse(callbackCalled)
        assertFalse(coordinator.state.value is AuthState.AiUnavailable)
    }

    private class SequentialFirebaseAuth(
        private val failure: Throwable? = null,
    ) : FirebaseAuthGateway {
        override suspend fun deleteCurrentAnonymousUser() = Unit
        private val user = AnonymousUser("anonymous-user")
        var tokenCalls = 0
        override fun currentAnonymousUser(): AnonymousUser = user
        override suspend fun signInAnonymously(): AnonymousUser = user
        override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean): String {
            tokenCalls++
            failure?.let { throw it }
            return "id-token-$tokenCalls"
        }
    }

    private class SequentialAppCheck : AppCheckGateway {
        var tokenCalls = 0
        override suspend fun getLimitedUseToken(): String {
            tokenCalls++
            return "app-check-token-$tokenCalls"
        }
    }
}
