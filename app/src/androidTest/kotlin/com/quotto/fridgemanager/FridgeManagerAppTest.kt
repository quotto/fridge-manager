package com.quotto.fridgemanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.quotto.fridgemanager.data.local.EmptyInventoryRepository
import com.quotto.fridgemanager.di.DefaultAppContainer
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FridgeManagerAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun launchApp() {
        composeRule.setContent {
            FridgeManagerApp(
                container = DefaultAppContainer(EmptyInventoryRepository()),
            )
        }
    }

    @Test
    fun 空の在庫一覧から手動登録へ遷移できる() {
        launchApp()

        composeRule.onNodeWithText("在庫一覧").assertIsDisplayed()
        composeRule.onNodeWithText("食材がありません").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("食材を追加").performClick()
        composeRule.onNodeWithText("手動登録").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("在庫一覧タブ").assertIsSelected()
    }

    @Test
    fun 空の在庫一覧から画像解析へ遷移できる() {
        launchApp()

        composeRule.onNodeWithContentDescription("画像解析タブ").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("画像から食材を登録").performClick()
        composeRule.onNodeWithText("画像解析").assertIsDisplayed()
    }

    @Test
    fun 下部ナビゲーションから設定へ遷移できる() {
        launchApp()

        composeRule.onNodeWithContentDescription("設定タブ").performClick().assertIsSelected()
        composeRule.onNodeWithText("利用データの削除").assertIsDisplayed()
    }

    @Test
    fun 手動登録から在庫一覧へ戻れる() {
        launchApp()

        composeRule.onNodeWithContentDescription("食材を追加").performClick()
        composeRule.onNodeWithContentDescription("在庫一覧へ戻る").performClick()
        composeRule.onNodeWithText("食材がありません").assertIsDisplayed()
    }

    @Test
    fun 文字サイズを2倍にしても主要操作を表示できる() {
        composeRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = deviceDensity.density, fontScale = 2f),
            ) {
                FridgeManagerApp(
                    container = DefaultAppContainer(EmptyInventoryRepository()),
                )
            }
        }

        composeRule.onNodeWithContentDescription("食材を追加")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithContentDescription("単位、必須、現在値は個")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("単位を選択").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("設定タブ").assertIsDisplayed()
    }

    @Test
    fun 在庫読込エラーから再試行して空状態を表示できる() {
        var attempts = 0
        val repository = object : InventoryRepository {
            override suspend fun hasItems(): Boolean = false
            override suspend fun getAll() = emptyList<com.quotto.fridgemanager.domain.inventory.StoredIngredient>()
            override fun observeAll(): Flow<List<com.quotto.fridgemanager.domain.inventory.StoredIngredient>> = flow {
                attempts += 1
                if (attempts == 1) error("一時的な読込失敗")
                emit(emptyList())
            }
            override suspend fun searchByName(normalizedQuery: String) =
                emptyList<com.quotto.fridgemanager.domain.inventory.StoredIngredient>()
            override suspend fun saveBatch(batch: com.quotto.fridgemanager.domain.inventory.InventoryBatch) = Unit
        }
        composeRule.setContent {
            FridgeManagerApp(container = DefaultAppContainer(repository))
        }

        composeRule.onNodeWithText("エラーが発生しました").assertIsDisplayed()
        composeRule.onNodeWithText("再試行").performClick()
        composeRule.onNodeWithText("食材がありません").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(2, attempts) }
    }
}
