package com.quotto.fridgemanager.domain.auth

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCoordinatorTest {
    @Test
    fun `既存の匿名ユーザーを再利用して新規サインインしない`() = runTest {
        val existing = AnonymousUser("existing-user")
        val firebase = FakeFirebaseAuth(currentUser = existing)
        val coordinator = coordinator(firebase = firebase)

        val authorization = coordinator.prepareAiRequest()

        assertEquals(0, firebase.signInCalls)
        assertSame(existing, firebase.tokenRequestedFor)
        assertEquals("id-token", authorization?.idToken)
        assertTrue(coordinator.state.value is AuthState.Ready)
    }

    @Test
    fun `ユーザーが存在しない場合は匿名サインインする`() = runTest {
        val signedIn = AnonymousUser("new-user")
        val firebase = FakeFirebaseAuth(currentUser = null, signedInUser = signedIn)
        val coordinator = coordinator(firebase = firebase)

        coordinator.prepareAiRequest()

        assertEquals(1, firebase.signInCalls)
        assertSame(signedIn, firebase.tokenRequestedFor)
    }

    @Test
    fun `AIリクエストごとにID tokenを強制更新する`() = runTest {
        val firebase = FakeFirebaseAuth(currentUser = AnonymousUser("user"))
        val coordinator = coordinator(firebase = firebase)

        coordinator.prepareAiRequest()

        assertEquals(listOf(true), firebase.forceRefreshArguments)
    }

    @Test
    fun `limited-use App Check tokenを取得する`() = runTest {
        val appCheck = FakeAppCheck()
        val coordinator = coordinator(appCheck = appCheck)

        val authorization = coordinator.prepareAiRequest()

        assertEquals(1, appCheck.limitedUseTokenCalls)
        assertEquals("app-check-token", authorization?.appCheckToken)
    }

    @Test
    fun `ID token取得失敗時は手動機能を維持してAIだけ無効にする`() = runTest {
        val coordinator = coordinator(
            firebase = FakeFirebaseAuth(
                currentUser = AnonymousUser("user"),
                tokenFailure = IllegalStateException("private ID token failure"),
            ),
        )

        assertNull(coordinator.prepareAiRequest())
        val state = coordinator.state.value as AuthState.AiUnavailable
        assertTrue(state.manualInventoryAvailable)
    }

    @Test
    fun `App Check token取得失敗時も手動機能を維持してAIだけ無効にする`() = runTest {
        val coordinator = coordinator(
            appCheck = FakeAppCheck(failure = IllegalStateException("private app check failure")),
        )

        assertNull(coordinator.prepareAiRequest())
        val state = coordinator.state.value as AuthState.AiUnavailable
        assertTrue(state.manualInventoryAvailable)
    }

    @Test
    fun `失敗後の再試行で両tokenを再取得してAIを復旧する`() = runTest {
        val firebase = FakeFirebaseAuth(
            currentUser = AnonymousUser("user"),
            tokenFailure = IllegalStateException("temporary"),
        )
        val appCheck = FakeAppCheck()
        val coordinator = coordinator(firebase = firebase, appCheck = appCheck)
        assertNull(coordinator.prepareAiRequest())

        firebase.tokenFailure = null
        val authorization = coordinator.retry()

        assertEquals("id-token", authorization?.idToken)
        assertEquals("app-check-token", authorization?.appCheckToken)
        assertTrue(coordinator.state.value is AuthState.Ready)
        assertEquals(2, firebase.forceRefreshArguments.size)
    }

    @Test
    fun `tokenやSDK例外詳細を状態とログへ保持しない`() = runTest {
        val logs = mutableListOf<String>()
        val firebase = FakeFirebaseAuth(
            currentUser = AnonymousUser("user"),
            idToken = "secret-id-token",
        )
        val appCheck = FakeAppCheck(token = "secret-app-check-token")
        val coordinator = coordinator(firebase = firebase, appCheck = appCheck, logger = logs::add)

        val authorization = coordinator.prepareAiRequest()
        val stateAndLogs = coordinator.state.value.toString() + logs.joinToString()

        assertEquals("secret-id-token", authorization?.idToken)
        assertEquals("secret-app-check-token", authorization?.appCheckToken)
        assertFalse(stateAndLogs.contains("secret-id-token"))
        assertFalse(stateAndLogs.contains("secret-app-check-token"))
        assertFalse(stateAndLogs.contains("existing-user"))
        assertFalse(authorization.toString().contains("secret-id-token"))
        assertFalse(authorization.toString().contains("secret-app-check-token"))
    }

    @Test
    fun `呼出し元のキャンセルを認証失敗へ変換しない`() = runTest {
        val coordinator = coordinator(
            firebase = FakeFirebaseAuth(
                currentUser = AnonymousUser("user"),
                tokenFailure = CancellationException("cancelled"),
            ),
        )

        var cancelled = false
        try {
            coordinator.prepareAiRequest()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(coordinator.state.value is AuthState.AiUnavailable)
    }

    @Test
    fun `匿名ユーザー削除後は全認可経路と初期化で再作成しない`() = runTest {
        val firebase = FakeFirebaseAuth(currentUser = AnonymousUser("deleted-user"))
        val coordinator = coordinator(firebase = firebase)

        coordinator.deleteAnonymousUser()

        assertNull(coordinator.prepareAiRequest())
        assertNull(coordinator.retry())
        assertNull(coordinator.withFreshAuthorization { "must not run" })
        assertFalse(coordinator.initialize())
        assertEquals(0, firebase.signInCalls)
        assertEquals(1, firebase.deleteCalls)
        assertTrue(coordinator.state.value is AuthState.Deleted)
    }

    private fun coordinator(
        firebase: FakeFirebaseAuth = FakeFirebaseAuth(currentUser = AnonymousUser("existing-user")),
        appCheck: FakeAppCheck = FakeAppCheck(),
        logger: (String) -> Unit = {},
    ) = AuthCoordinator(firebase, appCheck, logger)

    private class FakeFirebaseAuth(
        private val currentUser: AnonymousUser?,
        private val signedInUser: AnonymousUser = AnonymousUser("signed-in-user"),
        private val idToken: String = "id-token",
        var tokenFailure: Throwable? = null,
    ) : FirebaseAuthGateway {
        var deleteCalls = 0
        override suspend fun deleteCurrentAnonymousUser() { deleteCalls++ }
        var signInCalls = 0
        var tokenRequestedFor: AnonymousUser? = null
        val forceRefreshArguments = mutableListOf<Boolean>()

        override fun currentAnonymousUser(): AnonymousUser? = currentUser

        override suspend fun signInAnonymously(): AnonymousUser {
            signInCalls++
            return signedInUser
        }

        override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean): String {
            tokenRequestedFor = user
            forceRefreshArguments += forceRefresh
            tokenFailure?.let { throw it }
            return idToken
        }
    }

    private class FakeAppCheck(
        private val token: String = "app-check-token",
        private val failure: Throwable? = null,
    ) : AppCheckGateway {
        var limitedUseTokenCalls = 0

        override suspend fun getLimitedUseToken(): String {
            limitedUseTokenCalls++
            failure?.let { throw it }
            return token
        }
    }
}
