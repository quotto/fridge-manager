package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UnitSelectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `21単位をタイル表示し選択した単位を返す`() {
        var selected: String? = null
        composeRule.setContent {
            UnitSelectionScreen(
                selectedSymbol = "個",
                onBack = {},
                onSelected = { selected = it },
            )
        }

        composeRule.onNodeWithText("単位を選択").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("単位 個").assertIsSelected()
        composeRule.onNodeWithContentDescription("単位 kg").performClick()

        composeRule.runOnIdle { assertEquals("kg", selected) }
    }

    @Test
    fun `何も選ばず戻ると選択結果を返さない`() {
        var selected: String? = null
        var backCount = 0
        composeRule.setContent {
            UnitSelectionScreen(
                selectedSymbol = "丁",
                onBack = { backCount += 1 },
                onSelected = { selected = it },
            )
        }

        composeRule.onNodeWithContentDescription("単位選択から戻る").performClick()

        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(null, selected)
        }
    }
}
