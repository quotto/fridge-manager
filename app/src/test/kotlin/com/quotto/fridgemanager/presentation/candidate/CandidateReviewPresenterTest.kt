package com.quotto.fridgemanager.presentation.candidate

import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #28 の候補確認に対する契約テスト。
 *
 * この画面での「確定」は検証済み候補を後続工程へ引き渡すだけであり、Room への保存は
 * Issue #31 が担う。その境界を Fake の呼び出し回数でも固定する。
 */
class CandidateReviewPresenterTest {
    @Test
    fun load_keepsUnknownFieldsEmptyAndRequiresInputBeforeHandoff() = runBlocking {
        val repository = CandidateFakeRepository()
        val presenter = CandidateReviewPresenter(repository)

        val state = presenter.load(
            candidates = listOf(candidate(name = null, quantity = null, unit = null, requiresReview = true)),
            warnings = listOf("数量を推定できませんでした"),
        )

        val item = state.items.single()
        assertEquals("", item.name)
        assertEquals("", item.quantity)
        assertEquals("", item.unit)
        assertEquals("不明", item.evidence)
        assertTrue(item.requiresReview)
        assertEquals(listOf("数量を推定できませんでした"), state.warnings)
        assertFalse(state.canProceed)
        assertTrue(state.validatedDrafts.isEmpty())
        assertTrue(presenter.handoff() is CandidateReviewResult.Invalid)
        assertEquals(0, repository.saveBatchCount)
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun candidateCanBeAddedEditedExcludedAndRestoredWithoutPersistence() = runBlocking {
        val repository = CandidateFakeRepository()
        val presenter = CandidateReviewPresenter(repository)
        presenter.load(listOf(candidate(name = "豆腐", quantity = "1", unit = "丁")))

        val added = presenter.addCandidate()
        assertEquals(2, added.items.size)
        val addedId = added.items.last().id

        val edited = presenter.updateCandidate(addedId, name = "  牛乳  ", quantity = "1.25", unit = "L")
        assertEquals("牛乳", edited.items.last().name)
        assertEquals("1.25", edited.items.last().quantity)
        assertEquals("L", edited.items.last().unit)

        assertFalse(presenter.excludeCandidate(addedId).items.last().included)
        assertTrue(presenter.restoreCandidate(addedId).items.last().included)
        assertEquals(0, repository.saveBatchCount)
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun candidateCountIsLimitedToThirtyIncludingUserAddedRows() = runBlocking {
        val presenter = CandidateReviewPresenter(CandidateFakeRepository())
        presenter.load((1..30).map { candidate(name = "食材$it", quantity = "1", unit = "個") })

        val state = presenter.addCandidate()

        assertEquals(30, state.items.size)
        assertEquals("候補は30件までです", state.batchError)
    }

    @Test
    fun handoffRejectsInvalidIncludedFieldsAndDoesNotCallRepositoryWrites() = runBlocking {
        val repository = CandidateFakeRepository()
        val presenter = CandidateReviewPresenter(repository)
        presenter.load(
            listOf(
                candidate(name = "", quantity = "1", unit = "個"),
                candidate(name = "数量不正", quantity = "1.234", unit = "個"),
                candidate(name = "範囲外", quantity = "101", unit = "個"),
                candidate(name = "単位不正", quantity = "1", unit = "piece"),
            ),
        )

        val result = presenter.handoff()

        assertTrue(result is CandidateReviewResult.Invalid)
        val state = (result as CandidateReviewResult.Invalid).state
        assertEquals("食材名を入力してください", state.items[0].nameError)
        assertNotNull(state.items[1].quantityError)
        assertNotNull(state.items[2].quantityError)
        assertEquals("一覧から単位を選択してください", state.items[3].unitError)
        assertEquals(0, repository.saveBatchCount)
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun excludedInvalidCandidateDoesNotBlockHandoff() = runBlocking {
        val repository = CandidateFakeRepository()
        val presenter = CandidateReviewPresenter(repository)
        val loaded = presenter.load(
            listOf(
                candidate(name = "豆腐", quantity = "1", unit = "丁"),
                candidate(name = null, quantity = null, unit = null),
            ),
        )
        presenter.excludeCandidate(loaded.items.last().id)

        val result = presenter.handoff()

        assertTrue(result is CandidateReviewResult.Ready)
        assertEquals(1, (result as CandidateReviewResult.Ready).candidates.size)
        assertTrue(presenter.state.canProceed)
        assertEquals(1, presenter.state.validatedDrafts.size)
        assertEquals(0, repository.saveBatchCount)
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun loadMarksExistingInventoryOnlyOnTrimmedNfkcExactMatch() = runBlocking {
        val existing = stored(id = "existing", name = "牛乳", quantity = "2", unit = InventoryUnit.LITER)
        val repository = CandidateFakeRepository(listOf(existing))
        val presenter = CandidateReviewPresenter(repository)

        val state = presenter.load(
            listOf(
                candidate(name = "  牛乳  ", quantity = "1", unit = "L"),
                candidate(name = "牛 乳", quantity = "1", unit = "L"),
                candidate(name = "牛乳パック", quantity = "1", unit = "本"),
                candidate(name = "ＮＦＫＣ", quantity = "1", unit = "個"),
            ),
        )

        assertEquals("existing", state.items[0].existingIngredient?.id)
        assertEquals("2", state.items[0].existingIngredient?.quantity.toString())
        assertNull(state.items[1].existingIngredient)
        assertNull(state.items[2].existingIngredient)
        // 編集後にも同じ完全一致規則で再照合する。
        val edited = presenter.updateCandidate(state.items[3].id, name = "NFKC", quantity = "1", unit = "個")
        assertNull(edited.items[3].existingIngredient)
        assertEquals(0, repository.saveBatchCount)
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun nfkcEquivalentCandidateMatchesExistingInventory() = runBlocking {
        val existing = stored(id = "nfkc", name = "NFKC", quantity = "3", unit = InventoryUnit.PIECE)
        val presenter = CandidateReviewPresenter(CandidateFakeRepository(listOf(existing)))

        val state = presenter.load(listOf(candidate(name = "ＮＦＫＣ", quantity = "1", unit = "個")))

        assertEquals("nfkc", state.items.single().existingIngredient?.id)
        presenter.selectUpdateMethod(state.items.single().id, com.quotto.fridgemanager.domain.inventory.UpdateMethod.REPLACE)
        val ready = presenter.handoff() as CandidateReviewResult.Ready
        assertEquals("nfkc", ready.candidates.single().existingIngredient?.id)
    }

    @Test
    fun existingCandidateRequiresMethodAndProducesFinalAbsoluteValue() = runBlocking {
        val existing = stored(id = "milk", name = "牛乳", quantity = "2", unit = InventoryUnit.LITER)
        val presenter = CandidateReviewPresenter(CandidateFakeRepository(listOf(existing)))
        val loaded = presenter.load(listOf(candidate(name = "牛乳", quantity = "1.25", unit = "L")))

        assertFalse(loaded.canProceed)
        val increased = presenter.selectUpdateMethod(
            loaded.items.single().id,
            com.quotto.fridgemanager.domain.inventory.UpdateMethod.INCREASE,
        )

        assertEquals("3.25", increased.items.single().resultQuantity)
        assertTrue(increased.canProceed)
        val ready = presenter.handoff() as CandidateReviewResult.Ready
        assertEquals("3.25", ready.candidates.single().draft.quantity.toString())
        assertEquals(1L, ready.candidates.single().existingIngredient?.updatedAtEpochMillis)
    }

    @Test
    fun duplicateCandidatesAreBlockedImmediatelyAndExplicitMergeDoesNotAddQuantities() = runBlocking {
        val presenter = CandidateReviewPresenter(CandidateFakeRepository())
        val loaded = presenter.load(
            listOf(
                candidate(name = "ＮＦＫＣ", quantity = "1", unit = "個"),
                candidate(name = "NFKC", quantity = "2", unit = "個"),
            ),
        )

        assertFalse(loaded.canProceed)
        assertNotNull(loaded.items.first().nameError)

        val merged = presenter.mergeDuplicatesInto(loaded.items.first().id)

        assertTrue(merged.canProceed)
        assertEquals("1", merged.items.first().quantity)
        assertFalse(merged.items.last().included)
    }

    @Test
    fun validHandoffReturnsAtMostThirtyDraftsButNeverPersistsThem() = runBlocking {
        val repository = CandidateFakeRepository()
        val presenter = CandidateReviewPresenter(repository)
        presenter.load((1..30).map { candidate(name = "食材$it", quantity = "1", unit = "個") })

        val result = presenter.handoff()

        assertTrue(result is CandidateReviewResult.Ready)
        assertEquals(30, (result as CandidateReviewResult.Ready).candidates.size)
        assertTrue(presenter.state.canProceed)
        assertEquals(30, presenter.state.validatedDrafts.size)
        assertEquals(0, repository.saveBatchCount)
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun nfkcEquivalentDuplicateCandidatesMustBeResolvedBeforeHandoff() = runBlocking {
        val presenter = CandidateReviewPresenter(CandidateFakeRepository())
        presenter.load(
            listOf(
                candidate(name = "ＮＦＫＣ", quantity = "1", unit = "個"),
                candidate(name = "NFKC", quantity = "2", unit = "個"),
            ),
        )

        val result = presenter.handoff()

        assertTrue(result is CandidateReviewResult.Invalid)
        val state = (result as CandidateReviewResult.Invalid).state
        assertEquals("同じ食材名の候補を統合または除外してください", state.items[0].nameError)
        assertEquals("同じ食材名の候補を統合または除外してください", state.items[1].nameError)

        presenter.updateCandidate(state.items[1].id, "別の食材", "2", "個")
        assertTrue(presenter.handoff() is CandidateReviewResult.Ready)
    }
}

private fun candidate(
    name: String?,
    quantity: String?,
    unit: String?,
    requiresReview: Boolean = false,
) = AnalysisCandidate(
    name = name,
    quantity = quantity,
    unit = unit,
    evidence = "不明",
    requiresReview = requiresReview,
)

private class CandidateFakeRepository(
    initial: List<StoredIngredient> = emptyList(),
) : InventoryRepository {
    private val items = initial.toMutableList()
    var saveBatchCount = 0
    var updateCount = 0

    override suspend fun hasItems(): Boolean = items.isNotEmpty()
    override suspend fun getAll(): List<StoredIngredient> = items.toList()
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(items.toList())
    override suspend fun searchByName(normalizedQuery: String): List<StoredIngredient> =
        items.filter { it.name.normalizedValue.contains(normalizedQuery) }

    override suspend fun saveBatch(batch: InventoryBatch) {
        saveBatchCount++
    }

    override suspend fun update(ingredient: StoredIngredient) {
        updateCount++
    }

    override suspend fun delete(ingredient: StoredIngredient) = Unit
}

private fun stored(
    id: String,
    name: String,
    quantity: String,
    unit: InventoryUnit,
) = StoredIngredient(
    id = id,
    name = IngredientName.from(name),
    quantity = InventoryQuantity.from(quantity),
    unit = unit,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 1,
)
