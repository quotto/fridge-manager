package com.quotto.fridgemanager.ui.feature.image

import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryCommit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 確定ボタンの連打が後続の永続化処理を複数回開始しないための契約。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CandidateReviewViewModelCommitTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `同じ候補を二重確定しても後続処理は一度だけ開始する`() = runBlocking {
        val presenter = CandidateReviewPresenter(NoWriteRepository())
        presenter.load(
            listOf(
                AnalysisCandidate(
                    name = "豆腐",
                    quantity = "1",
                    unit = "丁",
                    evidence = "不明",
                    requiresReview = false,
                ),
            ),
        )
        val viewModel = CandidateReviewViewModel(presenter)
        var started = 0

        viewModel.handoff { started++ }
        viewModel.handoff { started++ }

        assertEquals(1, started)
    }

    @Test
    fun `confirm連打はrepository commitを一度だけ実行し成功通知も一度だけ行う`() = runTest(dispatcher) {
        val repository = CommitRecordingRepository()
        val presenter = loadedPresenter(repository)
        val viewModel = CandidateReviewViewModel(presenter)
        var saved = 0

        viewModel.confirm { saved++ }
        viewModel.confirm { saved++ }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.commitCount)
        assertEquals(1, saved)
        assertFalse(viewModel.state.value.isCommitting)
    }

    @Test
    fun `commit失敗は入力を保持し固定文言を表示して再試行できる`() = runTest(dispatcher) {
        val repository = CommitRecordingRepository(failuresRemaining = 1)
        val presenter = loadedPresenter(repository)
        val originalName = presenter.state.items.single().name
        val viewModel = CandidateReviewViewModel(presenter)
        var saved = 0

        viewModel.confirm { saved++ }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, saved)
        assertEquals(originalName, viewModel.state.value.items.single().name)
        assertEquals("在庫へ反映できませんでした。もう一度お試しください", viewModel.state.value.commitError)
        assertTrue(viewModel.state.value.canProceed)

        viewModel.confirm { saved++ }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, repository.commitCount)
        assertEquals(1, saved)
    }
}

private suspend fun loadedPresenter(repository: InventoryRepository): CandidateReviewPresenter =
    CandidateReviewPresenter(repository).also {
        it.load(listOf(AnalysisCandidate("豆腐", "1", "丁", "不明", false)))
    }

private class NoWriteRepository : InventoryRepository {
    override suspend fun hasItems(): Boolean = false
    override suspend fun getAll(): List<StoredIngredient> = emptyList()
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(emptyList())
    override suspend fun searchByName(normalizedQuery: String): List<StoredIngredient> = emptyList()
    override suspend fun saveBatch(batch: InventoryBatch) = error("確定前に保存してはならない")
}

private class CommitRecordingRepository(
    var failuresRemaining: Int = 0,
) : InventoryRepository {
    var commitCount = 0

    override suspend fun hasItems(): Boolean = false
    override suspend fun getAll(): List<StoredIngredient> = emptyList()
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(emptyList())
    override suspend fun searchByName(normalizedQuery: String): List<StoredIngredient> = emptyList()
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
    override suspend fun commit(commit: InventoryCommit) {
        commitCount++
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IllegalStateException("sensitive internal detail")
        }
    }
}
