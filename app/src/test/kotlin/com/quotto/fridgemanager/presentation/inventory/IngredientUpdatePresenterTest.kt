package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.IngredientDraft
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
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientUpdatePresenterTest {
    @Test
    fun preview_showsCurrentInputAndResultForEveryUpdateModeWithoutSaving() = runBlocking {
        val repository = UpdateFakeRepository(ingredient(quantity = "10"))
        val presenter = IngredientUpdatePresenter(repository)

        assertEquals("12.25", success(presenter.preview("id", "増加", "2.25")).updatedQuantity.toString())
        assertEquals("7.75", success(presenter.preview("id", "減少", "2.25")).updatedQuantity.toString())
        val replaced = success(presenter.preview("id", "置換", "2.25"))
        assertEquals("10", replaced.currentQuantity.toString())
        assertEquals("2.25", replaced.inputQuantity.toString())
        assertEquals("2.25", replaced.updatedQuantity.toString())
        assertEquals(0, repository.updateCount)
    }

    @Test
    fun preview_rejectsOutOfRangeAndMoreThanTwoDecimalPlaces() = runBlocking {
        val presenter = IngredientUpdatePresenter(UpdateFakeRepository(ingredient(quantity = "99")))

        assertTrue(presenter.preview("id", "増加", "1.01") is UpdatePreviewResult.Invalid)
        assertTrue(presenter.preview("id", "減少", "99.01") is UpdatePreviewResult.Invalid)
        assertTrue(presenter.preview("id", "置換", "1.234") is UpdatePreviewResult.Invalid)
    }

    @Test
    fun preview_acceptsExactZeroAndHundredBoundaries() = runBlocking {
        val presenter = IngredientUpdatePresenter(UpdateFakeRepository(ingredient(quantity = "99.99")))

        assertEquals("100", success(presenter.preview("id", "増加", "0.01")).updatedQuantity.toString())
        val decreasePresenter = IngredientUpdatePresenter(UpdateFakeRepository(ingredient(quantity = "0.01")))
        assertEquals("0", success(decreasePresenter.preview("id", "減少", "0.01")).updatedQuantity.toString())
    }

    @Test
    fun confirmEdit_validatesAllFieldsAndPersistsExistingIdOnlyAfterConfirmation() = runBlocking {
        val repository = UpdateFakeRepository(ingredient(quantity = "10"))
        val presenter = IngredientUpdatePresenter(repository)

        assertTrue(presenter.confirmEdit(repository.current(), " ", "1", "個") is IngredientMutationResult.Invalid)
        assertEquals(0, repository.updateCount)
        assertEquals(IngredientMutationResult.Saved, presenter.confirmEdit(repository.current(), "  豆腐  ", "1.25", "丁"))
        assertEquals("id", repository.updated!!.id)
        assertEquals("豆腐", repository.updated!!.name.value)
        assertEquals("1.25", repository.updated!!.quantity.toString())
    }

    @Test
    fun delete_requiresExplicitIrreversibleConfirmation() = runBlocking {
        val repository = UpdateFakeRepository(ingredient())
        val presenter = IngredientUpdatePresenter(repository)

        assertEquals(IngredientMutationResult.ConfirmationRequired, presenter.delete(repository.current(), confirmed = false))
        assertEquals(0, repository.deleteCount)
        assertEquals(IngredientMutationResult.Deleted, presenter.delete(repository.current(), confirmed = true))
        assertEquals(1, repository.deleteCount)
    }

    private fun success(result: UpdatePreviewResult) = result as UpdatePreviewResult.Success
}

private class UpdateFakeRepository(initial: StoredIngredient) : InventoryRepository {
    private var items = listOf(initial)
    var updateCount = 0
    var deleteCount = 0
    var updated: StoredIngredient? = null
    fun current() = items.single()
    override suspend fun hasItems() = items.isNotEmpty()
    override suspend fun getAll() = items
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(items)
    override suspend fun searchByName(normalizedQuery: String) = items.filter { it.name.normalizedValue.contains(normalizedQuery) }
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
    override suspend fun update(ingredient: StoredIngredient) {
        updateCount++
        updated = ingredient
        items = items.map { if (it.id == ingredient.id) ingredient else it }
    }
    override suspend fun delete(ingredient: StoredIngredient) {
        deleteCount++
        items = items.filterNot { it.id == ingredient.id }
    }
}

private fun ingredient(quantity: String = "1") = StoredIngredient(
    id = "id",
    name = IngredientName.from("豆腐"),
    quantity = InventoryQuantity.from(quantity),
    unit = InventoryUnit.TOFU,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 1,
)
