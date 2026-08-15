package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.UpdateMethod
import com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiUpdateCandidatePresenterTest {
    @Test
    fun `初期状態は現在値とAI絶対値と単位を示すが適用方法を自動選択しない`() = runTest {
        val presenter = AiUpdateCandidatePresenter(RecordingRepository(ingredient("10")))

        val state = presenter.prepare(ingredient("10"), candidate(quantity = "2.5", unit = "本"))

        assertEquals("10", state.currentQuantity.toString())
        assertEquals("2.5", state.estimatedAbsoluteQuantity)
        assertEquals("本", state.unit.symbol)
        assertNull(state.method)
        assertNull(state.resultQuantity)
        assertFalse(state.canConfirm)
    }

    @Test
    fun `増加減少置換の選択と推定値編集で更新後数量を即時再計算する`() = runTest {
        val presenter = AiUpdateCandidatePresenter(RecordingRepository(ingredient("10")))
        val initial = presenter.prepare(ingredient("10"), candidate(quantity = "2.5", unit = "本"))

        assertEquals("12.5", presenter.selectMethod(initial, UpdateMethod.INCREASE).resultQuantity.toString())
        assertEquals("7.5", presenter.selectMethod(initial, UpdateMethod.DECREASE).resultQuantity.toString())
        val replaced = presenter.selectMethod(initial, UpdateMethod.REPLACE)
        assertEquals("2.5", replaced.resultQuantity.toString())

        val edited = presenter.editEstimatedAbsoluteQuantity(replaced, "4.25")
        assertEquals("4.25", edited.estimatedAbsoluteQuantity)
        assertEquals("4.25", edited.resultQuantity.toString())
        assertTrue(edited.canConfirm)
    }

    @Test
    fun `更新結果0と100は確定可能で範囲外は確定不可`() = runTest {
        val presenter = AiUpdateCandidatePresenter(RecordingRepository(ingredient("50")))
        val initial = presenter.prepare(ingredient("50"), candidate(quantity = "50", unit = "本"))

        assertTrue(presenter.selectMethod(initial, UpdateMethod.DECREASE).canConfirm)
        assertEquals("0", presenter.selectMethod(initial, UpdateMethod.DECREASE).resultQuantity.toString())
        assertTrue(presenter.selectMethod(initial, UpdateMethod.INCREASE).canConfirm)
        assertEquals("100", presenter.selectMethod(initial, UpdateMethod.INCREASE).resultQuantity.toString())

        assertFalse(
            presenter.editEstimatedAbsoluteQuantity(
                presenter.selectMethod(initial, UpdateMethod.INCREASE),
                "50.01",
            ).canConfirm,
        )
        assertFalse(
            presenter.editEstimatedAbsoluteQuantity(
                presenter.selectMethod(initial, UpdateMethod.DECREASE),
                "50.01",
            ).canConfirm,
        )
    }

    @Test
    fun `適用方法未選択では確定できずRoom相当Repositoryは明示確定後だけ更新する`() = runTest {
        val repository = RecordingRepository(ingredient("10"))
        val presenter = AiUpdateCandidatePresenter(repository)
        val initial = presenter.prepare(ingredient("10"), candidate(quantity = "2", unit = "本"))

        assertEquals(AiUpdateConfirmationResult.Invalid, presenter.confirm(initial))
        assertEquals(0, repository.updateCount)

        val selected = presenter.selectMethod(initial, UpdateMethod.INCREASE)
        assertEquals(AiUpdateConfirmationResult.Saved, presenter.confirm(selected))
        assertEquals(1, repository.updateCount)
        assertEquals("12", repository.updated?.quantity.toString())
    }

    @Test
    fun `現在在庫と単位が異なるAI候補は方法を選んでも確定できない`() = runTest {
        val repository = RecordingRepository(ingredient("10"))
        val presenter = AiUpdateCandidatePresenter(repository)

        val state = presenter.prepare(ingredient("10"), candidate(quantity = "2", unit = "個"))
        val selected = presenter.selectMethod(state, UpdateMethod.REPLACE)

        assertFalse(selected.canConfirm)
        assertNull(selected.resultQuantity)
        assertEquals(AiUpdateConfirmationResult.Invalid, presenter.confirm(selected))
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun `解析開始後に在庫revisionが変わった場合は競合として拒否する`() = runTest {
        val snapshot = ingredient("10")
        val repository = RecordingRepository(snapshot)
        val presenter = AiUpdateCandidatePresenter(repository)
        repository.externalUpdate(ingredient("20").copy(updatedAtEpochMillis = 2))

        val prepared = presenter.prepare(snapshot, candidate(quantity = "2", unit = "本"))
        val selected = presenter.selectMethod(prepared, UpdateMethod.INCREASE)

        assertEquals(AiUpdateConfirmationResult.Conflict, presenter.confirm(selected))
        assertEquals(0, repository.updateCount)
    }
}

private fun candidate(quantity: String, unit: String) = AnalysisCandidate(
    name = "牛乳",
    quantity = quantity,
    unit = unit,
    evidence = "VISUAL_ESTIMATE",
    requiresReview = true,
)

private fun ingredient(quantity: String) = StoredIngredient(
    id = "ingredient-id",
    name = IngredientName.from("牛乳"),
    quantity = InventoryQuantity.from(quantity),
    unit = InventoryUnit.BOTTLE,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 1,
)

private class RecordingRepository(initial: StoredIngredient) : InventoryRepository {
    private var item = initial
    var updateCount = 0
    var updated: StoredIngredient? = null

    override suspend fun hasItems() = true
    override suspend fun getAll() = listOf(item)
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(listOf(item))
    override suspend fun searchByName(normalizedQuery: String) = listOf(item)
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
    override suspend fun update(ingredient: StoredIngredient) {
        if (ingredient.updatedAtEpochMillis != item.updatedAtEpochMillis) throw StaleStoredIngredientException()
        updateCount++
        updated = ingredient
        item = ingredient
    }

    fun externalUpdate(ingredient: StoredIngredient) { item = ingredient }
}
