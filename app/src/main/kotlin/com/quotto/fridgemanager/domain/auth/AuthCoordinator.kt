package com.quotto.fridgemanager.domain.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Firebase SDK の型や token を UI・在庫ドメインへ漏らさない認証境界。 */
class AnonymousUser internal constructor(internal val value: String)

interface FirebaseAuthGateway {
    fun currentAnonymousUser(): AnonymousUser?
    suspend fun signInAnonymously(): AnonymousUser
    suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean): String
}

interface AppCheckGateway {
    suspend fun getLimitedUseToken(): String
}

class AiRequestAuthorization(
    internal val idToken: String,
    internal val appCheckToken: String,
) {
    override fun toString(): String = "AiRequestAuthorization(redacted)"
}

sealed interface AuthState {
    data object Initializing : AuthState
    data object Ready : AuthState
    data class AiUnavailable(
        val manualInventoryAvailable: Boolean = true,
    ) : AuthState
}

class AuthCoordinator(
    private val firebaseAuth: FirebaseAuthGateway,
    private val appCheck: AppCheckGateway,
    private val logger: (String) -> Unit = {},
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    /** 起動時に匿名ユーザーだけを確立する。token は保持しない。 */
    suspend fun initialize(): Boolean = operation.withLock {
        try {
            ensureAnonymousUser()
            mutableState.value = AuthState.Ready
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markUnavailable()
            false
        }
    }

    /** API 呼出し直前に2種類の短命tokenを取得し、呼出し側へ一度だけ引き渡す。 */
    internal suspend fun prepareAiRequest(): AiRequestAuthorization? = operation.withLock {
        try {
            val user = ensureAnonymousUser()
            val idToken = firebaseAuth.getIdToken(user, forceRefresh = true)
            val appCheckToken = appCheck.getLimitedUseToken()
            require(idToken.isNotBlank() && appCheckToken.isNotBlank())
            mutableState.value = AuthState.Ready
            AiRequestAuthorization(idToken, appCheckToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markUnavailable()
            null
        }
    }

    internal suspend fun retry(): AiRequestAuthorization? = prepareAiRequest()

    /** limited-use tokenを保持・再利用させず、取得直後の単一送信境界だけへ渡す。 */
    suspend fun <T> withFreshAuthorization(
        block: suspend (AiRequestAuthorization) -> T,
    ): T? = operation.withLock {
        val authorization = try {
            val user = ensureAnonymousUser()
            val idToken = firebaseAuth.getIdToken(user, forceRefresh = true)
            val appCheckToken = appCheck.getLimitedUseToken()
            require(idToken.isNotBlank() && appCheckToken.isNotBlank())
            mutableState.value = AuthState.Ready
            AiRequestAuthorization(idToken, appCheckToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markUnavailable()
            return@withLock null
        }
        // transport例外は認証障害へ変換せず、そのまま呼出側で分類する。
        block(authorization)
    }

    private suspend fun ensureAnonymousUser(): AnonymousUser =
        firebaseAuth.currentAnonymousUser() ?: firebaseAuth.signInAnonymously()

    private fun markUnavailable() {
        mutableState.value = AuthState.AiUnavailable()
        // SDK例外、UID、tokenは記録しない。
        logger("Firebase認証を利用できないためAI解析を一時停止しました")
    }
}
