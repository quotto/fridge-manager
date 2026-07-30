package com.quotto.fridgemanager.ui.feature.inventory

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class InventoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 在庫一覧は品名数量単位と在庫切れを視覚とTalkBackで識別できる() {
        composeRule.setContent {
            InventoryScreen(
                state = InventoryUiState.Content(
                    listOf(
                        ingredient("1", "りんご", "2.5", InventoryUnit.PIECE),
                        ingredient("2", "豆腐", "0.00", InventoryUnit.TOFU),
                        ingredient("3", "小麦粉", "30", InventoryUnit.GRAM),
                    ),
                ),
                onManualRegistration = {},
                onImageAnalysis = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("豆腐", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("0 丁", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("在庫切れ", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("豆腐、数量 0 丁、在庫切れ、編集").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription("りんご、数量 2.5 個、在庫あり、編集").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("小麦粉、数量 30 グラム、在庫あり、編集").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("りんご、数量 2.5 個、在庫切れ").assertCountEquals(0)
    }

    @Test
    fun 空状態の2つのFABから登録方法へ直接進める() {
        var manualClicks = 0
        var imageClicks = 0
        composeRule.setContent {
            InventoryScreen(
                InventoryUiState.Empty,
                { manualClicks++ },
                { imageClicks++ },
                {},
            )
        }

        composeRule.onNodeWithText("登録方法を選択").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("食材を追加").performClick()
        composeRule.runOnIdle {
            assertEquals(1, manualClicks)
            assertEquals(0, imageClicks)
        }

        composeRule.onNodeWithContentDescription("画像から食材を登録").performClick()
        composeRule.runOnIdle {
            assertEquals(1, manualClicks)
            assertEquals(1, imageClicks)
        }
    }

    @Test
    fun 在庫がある場合も登録FABを表示する() {
        composeRule.setContent {
            InventoryScreen(
                InventoryUiState.Content(listOf(ingredient("1", "りんご", "1", InventoryUnit.PIECE))),
                {},
                {},
                {},
            )
        }

        composeRule.onNodeWithContentDescription("食材を追加")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("画像から食材を登録")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private fun ingredient(
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
}
