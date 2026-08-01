package com.quotto.fridgemanager.presentation.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DataDeletionGateway {
    suspend fun deleteLocalInventory()
    suspend fun deleteTemporaryImages()
    suspend fun deleteAnonymousUser()
}

sealed interface DataDeletionState {
    data object Idle : DataDeletionState
    data object ConfirmationRequired : DataDeletionState
    data object Deleting : DataDeletionState
    data class Failed(
        val localDataDeleted: Boolean,
        val temporaryImagesDeleted: Boolean,
        val anonymousUserDeleted: Boolean,
    ) : DataDeletionState
    data object Succeeded : DataDeletionState
}

class DataDeletionCoordinator(private val gateway: DataDeletionGateway) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<DataDeletionState>(DataDeletionState.Idle)
    val state: StateFlow<DataDeletionState> = mutableState.asStateFlow()

    fun requestConfirmation() {
        if (mutableState.value !is DataDeletionState.Deleting) {
            mutableState.value = DataDeletionState.ConfirmationRequired
        }
    }

    fun dismissConfirmation() {
        if (mutableState.value is DataDeletionState.ConfirmationRequired) {
            mutableState.value = DataDeletionState.Idle
        }
    }

    suspend fun confirmDeletion() {
        deleteRemaining(Progress(), ExpectedState.Confirmation)
    }

    suspend fun retry() {
        val failed = mutableState.value as? DataDeletionState.Failed ?: return
        deleteRemaining(
            Progress(
                local = failed.localDataDeleted,
                temporary = failed.temporaryImagesDeleted,
                auth = failed.anonymousUserDeleted,
            ),
            ExpectedState.Failure,
        )
    }

    private suspend fun deleteRemaining(initial: Progress, expected: ExpectedState) = mutex.withLock {
        val current = mutableState.value
        if (expected == ExpectedState.Confirmation && current !is DataDeletionState.ConfirmationRequired) {
            return@withLock
        }
        if (expected == ExpectedState.Failure && current !is DataDeletionState.Failed) {
            return@withLock
        }
        var progress = initial
        mutableState.value = DataDeletionState.Deleting
        try {
            if (!progress.local) {
                gateway.deleteLocalInventory()
                progress = progress.copy(local = true)
            }
            if (!progress.temporary) {
                gateway.deleteTemporaryImages()
                progress = progress.copy(temporary = true)
            }
            if (!progress.auth) {
                gateway.deleteAnonymousUser()
                progress = progress.copy(auth = true)
            }
            mutableState.value = DataDeletionState.Succeeded
        } catch (cancelled: CancellationException) {
            mutableState.value = progress.toFailed()
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = progress.toFailed()
        }
    }

    private data class Progress(
        val local: Boolean = false,
        val temporary: Boolean = false,
        val auth: Boolean = false,
    ) {
        fun toFailed() = DataDeletionState.Failed(local, temporary, auth)
    }

    private enum class ExpectedState { Confirmation, Failure }
}
