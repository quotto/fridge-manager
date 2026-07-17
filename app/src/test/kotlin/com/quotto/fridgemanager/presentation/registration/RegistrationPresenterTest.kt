package com.quotto.fridgemanager.presentation.registration

import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.quotto.fridgemanager.domain.inventory.DuplicateStoredIngredientException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationPresenterTest {
    @Test
    fun suggestions_placesNormalizedExactMatchBeforePartialMatches() = runBlocking {
        val repository = FakeRepository(
            suggestions = listOf(
                ingredient("1", "牛乳パン", "1", "個"),
                ingredient("2", "牛乳", "2", "本"),
            ),
        )

        val result = (RegistrationPresenter(repository).suggestions("  牛乳  ") as SuggestionResult.Success).suggestions

        assertEquals(listOf("牛乳", "牛乳パン"), result.map { it.ingredient.name.value })
        assertTrue(result.first().isExactMatch)
        assertFalse(result.last().isExactMatch)
    }

    @Test
    fun submit_returnsExistingIngredientAndNeverSavesNormalizedExactMatch() = runBlocking {
        val existing = ingredient("existing", "ＡＢＣ", "2", "袋")
        val repository = FakeRepository(suggestions = listOf(existing))

        val result = RegistrationPresenter(repository).submit(" ABC ", "3", "袋")

        assertEquals(RegistrationResult.ExistingIngredient(existing), result)
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun submit_savesValidatedIngredientOffline() = runBlocking {
        val repository = FakeRepository()

        val result = RegistrationPresenter(repository).submit("  豆腐  ", "1.25", "丁")

        assertEquals(RegistrationResult.Saved, result)
        assertEquals("豆腐", repository.saved.single().name.value)
        assertEquals("1.25", repository.saved.single().quantity.toString())
    }

    @Test
    fun submit_mapsEveryBusinessValidationErrorToItsField() = runBlocking {
        val presenter = RegistrationPresenter(FakeRepository())

        assertEquals(RegistrationField.NAME, (presenter.submit(" ", "1", "個") as RegistrationResult.Invalid).field)
        assertEquals(RegistrationField.NAME, (presenter.submit("あ".repeat(31), "1", "個") as RegistrationResult.Invalid).field)
        assertEquals(RegistrationField.QUANTITY, (presenter.submit("米", "1.234", "kg") as RegistrationResult.Invalid).field)
        assertEquals(RegistrationField.QUANTITY, (presenter.submit("米", "101", "kg") as RegistrationResult.Invalid).field)
        assertEquals(RegistrationField.UNIT, (presenter.submit("米", "1", "俵") as RegistrationResult.Invalid).field)
    }

    @Test
    fun submit_convertsUniqueRaceToExistingIngredient() = runBlocking {
        val existing = ingredient("race", "豆腐", "1", "丁")
        val repository = FakeRepository(
            searchSequence = ArrayDeque(listOf(emptyList(), listOf(existing))),
            saveError = DuplicateStoredIngredientException(IllegalStateException()),
        )

        assertEquals(
            RegistrationResult.ExistingIngredient(existing),
            RegistrationPresenter(repository).submit("豆腐", "2", "丁"),
        )
    }

    @Test
    fun submit_keepsUnexpectedStorageDetailsOutOfTheResult() = runBlocking {
        val repository = FakeRepository(saveError = IllegalStateException("secret-food"))

        assertEquals(RegistrationResult.Failed, RegistrationPresenter(repository).submit("米", "1", "kg"))
    }

    @Test
    fun existingIngredient_returnsOnlyTheRequestedIdentifier() = runBlocking {
        val expected = ingredient("target", "米", "1", "kg")
        val presenter = RegistrationPresenter(
            FakeRepository(allItems = listOf(ingredient("other", "豆腐", "1", "丁"), expected)),
        )

        assertEquals(ExistingIngredientResult.Found(expected), presenter.existingIngredient("target"))
        assertEquals(ExistingIngredientResult.NotFound, presenter.existingIngredient("missing"))
        assertEquals(SuggestionResult.Success(emptyList()), presenter.suggestions(" "))
    }

    @Test
    fun readFailures_areConvertedToSafeResults() = runBlocking {
        val presenter = RegistrationPresenter(FakeRepository(readError = IllegalStateException("secret-food")))

        assertEquals(SuggestionResult.Failed, presenter.suggestions("米"))
        assertEquals(ExistingIngredientResult.Failed, presenter.existingIngredient("id"))
        assertEquals(RegistrationResult.Failed, presenter.submit("米", "1", "kg"))
    }

    @Test
    fun cancellation_isNeverConvertedToUiFailure() {
        val presenter = RegistrationPresenter(FakeRepository(readError = CancellationException("cancel")))

        assertThrows(CancellationException::class.java) { runBlocking { presenter.suggestions("米") } }
        assertThrows(CancellationException::class.java) { runBlocking { presenter.existingIngredient("id") } }
        assertThrows(CancellationException::class.java) { runBlocking { presenter.submit("米", "1", "kg") } }
    }
}

private class FakeRepository(
    private val suggestions: List<StoredIngredient> = emptyList(),
    private val allItems: List<StoredIngredient> = suggestions,
    private val searchSequence: ArrayDeque<List<StoredIngredient>>? = null,
    private val saveError: Exception? = null,
    private val readError: Exception? = null,
) : InventoryRepository {
    var saveCount = 0
    var saved: List<IngredientDraft> = emptyList()
    override suspend fun hasItems() = false
    override suspend fun getAll(): List<StoredIngredient> {
        readError?.let { throw it }
        return allItems
    }
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(suggestions)
    override suspend fun searchByName(normalizedQuery: String): List<StoredIngredient> {
        readError?.let { throw it }
        return searchSequence?.removeFirstOrNull() ?: suggestions
    }
    override suspend fun saveBatch(batch: InventoryBatch) {
        saveError?.let { throw it }
        saveCount += 1
        saved = batch.items
    }
}

private fun ingredient(id: String, name: String, quantity: String, unit: String): StoredIngredient {
    val draft = IngredientDraft.create(name, quantity, unit)
    return StoredIngredient(id, draft.name, draft.quantity, draft.unit, 1, 1)
}
