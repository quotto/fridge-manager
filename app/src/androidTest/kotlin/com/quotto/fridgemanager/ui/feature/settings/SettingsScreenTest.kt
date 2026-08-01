package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.quotto.fridgemanager.presentation.settings.DataDeletionState
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun 削除前に復元不能の確認を表示する() {
        var requested = false
        composeRule.setContent {
            SettingsContent(
                deletionState = DataDeletionState.Idle,
                onRequestDeletion = { requested = true },
                onConfirmDeletion = {},
                onDismissDeletion = {},
                onRetryDeletion = {},
            )
        }

        composeRule.onNodeWithText("すべての利用データを削除").performClick()
        composeRule.runOnIdle { check(requested) }
    }

    @Test
    fun 確認画面に復元不能とキャンセルを表示する() {
        composeRule.setContent {
            SettingsContent(
                deletionState = DataDeletionState.ConfirmationRequired,
                onRequestDeletion = {},
                onConfirmDeletion = {},
                onDismissDeletion = {},
                onRetryDeletion = {},
            )
        }

        composeRule.onNodeWithText("削除したデータは復元できません").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").assertIsDisplayed()
        composeRule.onNodeWithText("完全に削除").assertIsDisplayed()
    }

    @Test
    fun 部分失敗時に削除済み状態と再試行を表示する() {
        composeRule.setContent {
            SettingsContent(
                deletionState = DataDeletionState.Failed(
                    localDataDeleted = true,
                    temporaryImagesDeleted = true,
                    anonymousUserDeleted = false,
                ),
                onRequestDeletion = {},
                onConfirmDeletion = {},
                onDismissDeletion = {},
                onRetryDeletion = {},
            )
        }

        composeRule.onNodeWithText("端末データ: 削除済み").assertIsDisplayed()
        composeRule.onNodeWithText("匿名ユーザー: 未完了").assertIsDisplayed()
        composeRule.onNodeWithText("未完了の削除を再試行").assertIsDisplayed()
    }
}
