package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.IngredientUpdatePresenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class ExistingIngredientUpdateScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun 既存在庫更新から対象食材を指定して画像解析へ進める() {
        val repository = UiUpdateRepository(ingredient())
        var analysisIngredientId: String? = null
        composeRule.setContent {
            ExistingIngredientUpdateScreen(
                ingredientId = "id",
                presenter = IngredientUpdatePresenter(repository),
                onBack = {},
                onChanged = {},
                onImageAnalysis = { analysisIngredientId = it },
            )
        }

        composeRule.onNodeWithText("画像から数量を更新").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("id", analysisIngredientId) }
    }

    @Test
    fun 手動更新は置換後の在庫数を直接保存し画像用の増減UIを表示しない() {
        val repository = UiUpdateRepository(ingredient())
        var changed = 0
        composeRule.setContent {
            ExistingIngredientUpdateScreen(
                "id",
                IngredientUpdatePresenter(repository),
                {},
                { changed += 1 },
            )
        }
        composeRule.onNodeWithText("現在値: 10 丁").assertIsDisplayed()
        composeRule.onNodeWithText("増加").assertDoesNotExist()
        composeRule.onNodeWithText("減少").assertDoesNotExist()
        composeRule.onNodeWithText("数量更新を確定").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("置換後の在庫数、必須")
            .performTextReplacement("7.75")
        composeRule.onNodeWithText("編集内容を確定").performClick()
        composeRule.waitUntil { changed == 1 }
        composeRule.runOnIdle {
            assertEquals("7.75", repository.item?.quantity.toString())
        }
    }

    @Test
    fun 削除は取消不能を明示し確認後だけ実行する() {
        val repository = UiUpdateRepository(ingredient())
        composeRule.setContent {
            ExistingIngredientUpdateScreen("id", IngredientUpdatePresenter(repository), {}, {})
        }
        composeRule.onNodeWithText("この食材を削除").performClick()
        composeRule.onNodeWithText("豆腐を削除しますか？").assertIsDisplayed()
        composeRule.onNodeWithText("削除後は取り消し・復元できません。").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.onNodeWithText("現在値: 10 丁").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, repository.deleteCount) }

        composeRule.onNodeWithText("この食材を削除").performClick()
        composeRule.onNodeWithText("削除を確定").performClick()
        composeRule.runOnIdle { assertEquals(1, repository.deleteCount) }
    }
}

private class UiUpdateRepository(var item: StoredIngredient?) : InventoryRepository {
    var failReads = false
    var deleteCount = 0
    override suspend fun hasItems() = item != null
    override suspend fun getAll() = listOfNotNull(item)
    override suspend fun getById(id: String): StoredIngredient? {
        if (failReads) error("secret database detail")
        return item?.takeIf { it.id == id }
    }
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(listOfNotNull(item))
    override suspend fun searchByName(normalizedQuery: String) = emptyList<StoredIngredient>()
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
    override suspend fun update(ingredient: StoredIngredient) { item = ingredient }
    override suspend fun delete(ingredient: StoredIngredient) { deleteCount += 1; item = null }
}

private fun ingredient() = StoredIngredient(
    id = "id",
    name = IngredientName.from("豆腐"),
    quantity = InventoryQuantity.from("10"),
    unit = InventoryUnit.TOFU,
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
)
