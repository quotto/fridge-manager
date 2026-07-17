package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun 数量更新は現在値入力方法更新後値を確認し削除は取消不能を明示する() {
        val repository = UiUpdateRepository(ingredient())
        composeRule.setContent {
            ExistingIngredientUpdateScreen("id", IngredientUpdatePresenter(repository), {}, {})
        }
        composeRule.onNodeWithText("現在値: 10 丁").assertIsDisplayed()
        composeRule.onNodeWithText("減少").performClick()
        composeRule.onNodeWithText("入力値").performTextReplacement("10.01")
        composeRule.onNodeWithText("更新後の値を確認").performClick()
        composeRule.onNodeWithText("更新後の在庫数は0以上100以下で指定してください").assertIsDisplayed()
        composeRule.onNodeWithText("数量更新を確定").assertDoesNotExist()

        composeRule.onNodeWithText("入力値").performTextReplacement("2.25")
        composeRule.onNodeWithText("更新後の値を確認").performClick()
        composeRule.onNodeWithText("現在値 10、減少 2.25、更新後 7.75").assertIsDisplayed()
        composeRule.onNodeWithText("更新後の在庫数は0以上100以下で指定してください").assertDoesNotExist()

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

    @Test
    fun プレビュー時のNotFoundと読込失敗を安全な再試行メッセージへ変換する() {
        val repository = UiUpdateRepository(ingredient())
        composeRule.setContent {
            ExistingIngredientUpdateScreen("id", IngredientUpdatePresenter(repository), {}, {})
        }
        composeRule.onNodeWithText("現在値: 10 丁").assertIsDisplayed()
        repository.item = null
        composeRule.onNodeWithText("入力値").performTextReplacement("1")
        composeRule.onNodeWithText("更新後の値を確認").performClick()
        composeRule.onNodeWithText("対象の在庫が見つかりません。戻って選び直してください").assertIsDisplayed()

        repository.item = ingredient()
        repository.failReads = true
        composeRule.onNodeWithText("更新後の値を確認").performClick()
        composeRule.onNodeWithText("在庫を読み込めませんでした。入力値を保ったまま再試行できます").assertIsDisplayed()
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
