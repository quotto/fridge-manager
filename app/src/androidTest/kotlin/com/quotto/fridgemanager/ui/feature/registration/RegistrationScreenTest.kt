package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.registration.IngredientSuggestion
import com.quotto.fridgemanager.presentation.registration.RegistrationFormState
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.quotto.fridgemanager.ui.theme.FridgeManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegistrationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun form_exposesRequiredFieldsAndPreventsNewSaveForExactMatch() {
        val existing = storedIngredient()
        var selectedId: String? = null
        composeRule.setContent {
            FridgeManagerTheme {
                RegistrationContent(
                    state = RegistrationFormState(
                        name = "豆腐",
                        quantity = "1",
                        selectedUnitSymbol = "丁",
                        suggestions = listOf(IngredientSuggestion(existing, true)),
                    ),
                    onNameChange = {}, onQuantityChange = {}, onUnitChange = {}, onSubmit = {},
                    onSelectExisting = { selectedId = it.id },
                )
            }
        }

        composeRule.onNodeWithContentDescription("食材名、必須").assertTextContains("豆腐")
        composeRule.onNodeWithContentDescription("在庫数、必須").assertTextContains("1")
        composeRule.onNodeWithText("新規登録").assertIsNotEnabled()
        composeRule.onNodeWithText("豆腐の在庫を更新").performClick()
        assertEquals("stored", selectedId)
    }

    @Test
    fun validInput_canBeEnteredAndSubmitted() {
        var submitted = 0
        composeRule.setContent {
            FridgeManagerTheme {
                RegistrationScreen(
                    presenter = RegistrationPresenter(FakeRegistrationRepository()),
                    onBack = {},
                    onSaved = { submitted += 1 },
                    onEditIngredient = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("食材名、必須").performTextInput("米")
        composeRule.onNodeWithContentDescription("在庫数、必須").performTextInput("2")
        composeRule.onNodeWithText("新規登録").assertIsEnabled().performClick()
        composeRule.waitUntil { submitted == 1 }
    }

    @Test
    fun changingName_immediatelyClearsOldExactMatch() {
        composeRule.setContent {
            FridgeManagerTheme {
                RegistrationScreen(
                    presenter = RegistrationPresenter(FakeRegistrationRepository(listOf(storedIngredient()))),
                    onBack = {}, onSaved = {}, onEditIngredient = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("食材名、必須").performTextInput("豆腐")
        composeRule.onNodeWithContentDescription("在庫数、必須").performTextInput("1")
        composeRule.waitUntil(2_000) {
            runCatching { composeRule.onNodeWithText("新規登録").assertIsNotEnabled() }.isSuccess
        }

        composeRule.onNodeWithContentDescription("食材名、必須").performTextInput("追加")

        composeRule.onNodeWithText("新規登録").assertIsEnabled()
    }
}

private fun storedIngredient(): StoredIngredient {
    val draft = IngredientDraft.create("豆腐", "1", "丁")
    return StoredIngredient("stored", draft.name, draft.quantity, draft.unit, 1, 1)
}

private class FakeRegistrationRepository(
    private val items: List<StoredIngredient> = emptyList(),
) : InventoryRepository {
    override suspend fun hasItems() = false
    override suspend fun getAll() = items
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(items)
    override suspend fun searchByName(normalizedQuery: String) = items
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
}
