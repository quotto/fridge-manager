package com.quotto.fridgemanager.data.auth

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.quotto.fridgemanager.domain.auth.AnonymousUser
import com.quotto.fridgemanager.domain.auth.AppCheckGateway
import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import com.quotto.fridgemanager.domain.auth.FirebaseAuthGateway
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class FirebaseAnonymousAuthGateway(
    private val auth: FirebaseAuth,
) : FirebaseAuthGateway {
    override fun currentAnonymousUser(): AnonymousUser? = auth.currentUser
        ?.takeIf { it.isAnonymous }
        ?.let { AnonymousUser(it.uid) }

    override suspend fun signInAnonymously(): AnonymousUser {
        val user = auth.signInAnonymously().awaitResult().user
        check(user != null && user.isAnonymous) { "Anonymous Firebase user is unavailable" }
        return AnonymousUser(user.uid)
    }

    override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean): String {
        val firebaseUser = auth.currentUser
        check(firebaseUser != null && firebaseUser.isAnonymous && firebaseUser.uid == user.value) {
            "Firebase user changed while authorizing"
        }
        return firebaseUser.getIdToken(forceRefresh).awaitResult().token
            ?.takeIf(String::isNotBlank)
            ?: error("Firebase ID token is unavailable")
    }

    override suspend fun deleteCurrentAnonymousUser() {
        val user = auth.currentUser ?: return
        check(user.isAnonymous) { "Only an anonymous Firebase user can be deleted" }
        user.delete().awaitResult()
    }
}

internal class FirebaseLimitedUseAppCheckGateway(
    private val appCheck: FirebaseAppCheck,
) : AppCheckGateway {
    override suspend fun getLimitedUseToken(): String =
        appCheck.limitedUseAppCheckToken.awaitResult().token
            .takeIf(String::isNotBlank)
            ?: error("App Check token is unavailable")
}

object FirebaseAuthComposition {
    fun create(context: Context, logger: (String) -> Unit = {}): AuthCoordinator =
        createSafely(logger) { createConfigured(context, logger) }

    internal fun createSafely(
        logger: (String) -> Unit = {},
        factory: () -> AuthCoordinator,
    ): AuthCoordinator = try {
        factory()
    } catch (_: Exception) {
        unavailableCoordinator(logger)
    }

    private fun createConfigured(context: Context, logger: (String) -> Unit): AuthCoordinator {
        val app = FirebaseApp.initializeApp(context.applicationContext)
            ?: return unavailableCoordinator(logger)
        val appCheck = FirebaseAppCheck.getInstance(app).apply {
            installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
        return AuthCoordinator(
            firebaseAuth = FirebaseAnonymousAuthGateway(FirebaseAuth.getInstance(app)),
            appCheck = FirebaseLimitedUseAppCheckGateway(appCheck),
            logger = logger,
        )
    }

    fun createUnavailable(logger: (String) -> Unit = {}): AuthCoordinator =
        unavailableCoordinator(logger)

    private fun unavailableCoordinator(logger: (String) -> Unit): AuthCoordinator {
        val unavailable = object : FirebaseAuthGateway {
            override fun currentAnonymousUser(): AnonymousUser? = null
            override suspend fun signInAnonymously(): AnonymousUser = error("Firebase is not configured")
            override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean): String =
                error("Firebase is not configured")
            override suspend fun deleteCurrentAnonymousUser() = Unit
        }
        return AuthCoordinator(
            firebaseAuth = unavailable,
            appCheck = object : AppCheckGateway {
                override suspend fun getLimitedUseToken(): String = error("Firebase is not configured")
            },
            logger = logger,
        )
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        when {
            task.isSuccessful -> continuation.resume(task.result)
            else -> continuation.resumeWithException(
                task.exception ?: IllegalStateException("Firebase operation failed"),
            )
        }
    }
}
