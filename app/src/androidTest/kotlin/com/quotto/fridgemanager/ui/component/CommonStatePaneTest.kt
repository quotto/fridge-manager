package com.quotto.fridgemanager.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommonStatePaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 読み込み状態を支援技術へ通知する() {
        composeRule.setContent {
            MaterialTheme { LoadingPane() }
        }

        composeRule.onNodeWithContentDescription("読み込み中").assertIsDisplayed()
    }

    @Test
    fun 空状態に次の操作を表示する() {
        composeRule.setContent {
            MaterialTheme {
                EmptyPane(
                    title = "食材がありません",
                    actionLabel = "手動で登録",
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("食材がありません").assertIsDisplayed()
        composeRule.onNodeWithText("手動で登録")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun エラー状態から再試行できる() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                ErrorPane(message = "読み込めませんでした", onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithText("読み込めませんでした").assertIsDisplayed()
        composeRule.onNodeWithText("再試行")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }
}
