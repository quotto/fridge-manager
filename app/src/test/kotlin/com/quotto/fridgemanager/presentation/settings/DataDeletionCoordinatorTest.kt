package com.quotto.fridgemanager.presentation.settings

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataDeletionCoordinatorTest {
    @Test
    fun `確認前は何も削除しない`() = runTest {
        val gateway = FakeDataDeletionGateway()
        val coordinator = DataDeletionCoordinator(gateway)

        coordinator.requestConfirmation()

        assertTrue(coordinator.state.value is DataDeletionState.ConfirmationRequired)
        assertEquals(0, gateway.localAttempts)
        assertEquals(0, gateway.authAttempts)
    }

    @Test
    fun `全削除成功時はRoomと一時画像と匿名ユーザーを一度ずつ削除する`() = runTest {
        val gateway = FakeDataDeletionGateway()
        val coordinator = DataDeletionCoordinator(gateway)

        coordinator.requestConfirmation()
        coordinator.confirmDeletion()

        assertTrue(coordinator.state.value is DataDeletionState.Succeeded)
        assertEquals(1, gateway.localAttempts)
        assertEquals(1, gateway.temporaryAttempts)
        assertEquals(1, gateway.authAttempts)
    }

    @Test
    fun `Firebase削除だけ失敗した場合は進捗を保持してFirebaseだけ再試行する`() = runTest {
        val gateway = FakeDataDeletionGateway(authFailures = 1)
        val coordinator = DataDeletionCoordinator(gateway)

        coordinator.requestConfirmation()
        coordinator.confirmDeletion()

        val failed = coordinator.state.value as DataDeletionState.Failed
        assertTrue(failed.localDataDeleted)
        assertTrue(failed.temporaryImagesDeleted)
        assertFalse(failed.anonymousUserDeleted)

        coordinator.retry()

        assertTrue(coordinator.state.value is DataDeletionState.Succeeded)
        assertEquals(1, gateway.localAttempts)
        assertEquals(1, gateway.temporaryAttempts)
        assertEquals(2, gateway.authAttempts)
    }

    @Test
    fun `Room削除失敗時は後続処理せず安全に再試行する`() = runTest {
        val gateway = FakeDataDeletionGateway(localFailures = 1)
        val coordinator = DataDeletionCoordinator(gateway)

        coordinator.requestConfirmation()
        coordinator.confirmDeletion()

        assertTrue(coordinator.state.value is DataDeletionState.Failed)
        assertEquals(0, gateway.temporaryAttempts)
        assertEquals(0, gateway.authAttempts)

        coordinator.retry()

        assertTrue(coordinator.state.value is DataDeletionState.Succeeded)
        assertEquals(2, gateway.localAttempts)
        assertEquals(1, gateway.temporaryAttempts)
        assertEquals(1, gateway.authAttempts)
    }

    @Test
    fun `確認の連打でも削除処理は一度だけ実行する`() = runTest {
        val gateway = FakeDataDeletionGateway()
        val coordinator = DataDeletionCoordinator(gateway)
        coordinator.requestConfirmation()

        val first = async { coordinator.confirmDeletion() }
        val second = async { coordinator.confirmDeletion() }
        first.await()
        second.await()

        assertEquals(1, gateway.localAttempts)
        assertEquals(1, gateway.temporaryAttempts)
        assertEquals(1, gateway.authAttempts)
    }
}

private class FakeDataDeletionGateway(
    private var localFailures: Int = 0,
    private var temporaryFailures: Int = 0,
    private var authFailures: Int = 0,
) : DataDeletionGateway {
    var localAttempts = 0
    var temporaryAttempts = 0
    var authAttempts = 0

    override suspend fun deleteLocalInventory() {
        localAttempts++
        if (localFailures-- > 0) error("local")
    }

    override suspend fun deleteTemporaryImages() {
        temporaryAttempts++
        if (temporaryFailures-- > 0) error("temporary")
    }

    override suspend fun deleteAnonymousUser() {
        authAttempts++
        if (authFailures-- > 0) error("auth")
    }
}
