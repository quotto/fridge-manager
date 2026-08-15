package com.quotto.fridgemanager.ui.feature.image

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidatePresenter
import com.quotto.fridgemanager.ui.theme.FridgeManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiUpdateCandidateScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `絶対値と根拠を表示し適用方法は未選択で選択後に結果を再計算する`() {
        val repository = AiUpdateUiRepository(ingredient())
        setScreen(repository)

        composeRule.onNodeWithText("現在値: 10 丁").assertIsDisplayed()
        composeRule.onNodeWithText("AI推定の絶対値です。適用方法は自動選択されません").assertIsDisplayed()
        composeRule.onNodeWithText("根拠: 画像内の個数").assertIsDisplayed()
        composeRule.onNodeWithText("AI推定絶対値").assertIsDisplayed()
        composeRule.onNodeWithText("丁").assertIsDisplayed()
        composeRule.onNodeWithText("更新結果: 未確定").assertIsDisplayed()
        composeRule.onNodeWithText("この内容で更新を確定").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("適用方法 増加").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("適用方法 減少").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("適用方法 置換").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("適用方法 増加").performClick()
        composeRule.onNodeWithText("更新結果: 12 丁").assertIsDisplayed()
        composeRule.onNodeWithText("AI推定絶対値").performTextReplacement("3.5")
        composeRule.onNodeWithText("更新結果: 13.5 丁").assertIsDisplayed()
        composeRule.onNodeWithText("この内容で更新を確定").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(0, repository.updateCount) }
    }

    @Test
    fun `範囲外では確定できず明示確定した場合だけ在庫を更新する`() {
        val repository = AiUpdateUiRepository(ingredient())
        setScreen(repository)
        composeRule.onNodeWithContentDescription("適用方法 増加").performClick()

        composeRule.onNodeWithText("AI推定絶対値").performTextReplacement("91")
        composeRule.onNodeWithText("更新後の在庫数は0以上100以下、AI推定値は小数2桁までで入力してください")
            .assertIsDisplayed()
        composeRule.onNodeWithText("この内容で更新を確定").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, repository.updateCount) }

        composeRule.onNodeWithText("AI推定絶対値").performTextReplacement("2")
        composeRule.onNodeWithText("更新結果: 12 丁").assertIsDisplayed()
        composeRule.onNodeWithText("この内容で更新を確定").performScrollTo().performClick()
        composeRule.waitUntil { repository.updateCount == 1 }
        composeRule.runOnIdle {
            assertEquals("12", repository.item.quantity.toString())
            assertEquals(1, repository.updateCount)
        }
    }

    private fun setScreen(repository: AiUpdateUiRepository) {
        composeRule.setContent {
            FridgeManagerTheme {
                AiUpdateCandidateScreen(
                    requestId = "request",
                    ingredient = repository.item,
                    candidate = AnalysisCandidate("豆腐", "2", "丁", "VISIBLE_COUNT", false),
                    presenter = AiUpdateCandidatePresenter(repository),
                    onSaved = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithText("現在値: 10 丁").fetchSemanticsNodes().isNotEmpty() }
    }
}

private class AiUpdateUiRepository(var item: StoredIngredient) : InventoryRepository {
    var updateCount = 0
    override suspend fun hasItems() = true
    override suspend fun getAll() = listOf(item)
    override suspend fun getById(id: String) = item.takeIf { it.id == id }
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(listOf(item))
    override suspend fun searchByName(normalizedQuery: String) = emptyList<StoredIngredient>()
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
    override suspend fun update(ingredient: StoredIngredient) {
        updateCount += 1
        item = ingredient
    }
}

private fun ingredient() = StoredIngredient(
    id = "id",
    name = IngredientName.from("豆腐"),
    quantity = InventoryQuantity.from("10"),
    unit = InventoryUnit.TOFU,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 1,
)
