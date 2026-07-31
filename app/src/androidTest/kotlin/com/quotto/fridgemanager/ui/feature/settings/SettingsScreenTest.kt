package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.quotto.fridgemanager.presentation.settings.DataDeletionState
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun 収集送信保持削除の実態をアプリ内で説明する() {
        var policyOpened = 0
        var deletionGuideOpened = 0
        composeRule.setContent {
            SettingsContent(
                deletionState = DataDeletionState.Idle,
                onRequestDeletion = {},
                onConfirmDeletion = {},
                onDismissDeletion = {},
                onRetryDeletion = {},
                onOpenPrivacyPolicy = { policyOpened += 1 },
                onOpenDataDeletionGuide = { deletionGuideOpened += 1 },
            )
        }

        composeRule.onNodeWithText("プライバシーとデータの取扱い").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "食材データは端末内だけに保存し、クラウド同期やバックアップは行いません。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "解析画像はAWSとAmazon Bedrockへ送信しますが、クラウドへ永続保存しません。端末の一時画像は遅くとも1時間以内に削除します。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Firebase匿名認証とApp Checkを不正利用防止に使用します。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "アプリケーションログは本番環境で90日保持し、画像・在庫・トークン・匿名ユーザーIDを記録しません。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("プライバシーポリシーを開く")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("アプリ外の削除案内を開く")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            check(policyOpened == 1)
            check(deletionGuideOpened == 1)
        }
    }

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
                onOpenPrivacyPolicy = {},
                onOpenDataDeletionGuide = {},
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
                onOpenPrivacyPolicy = {},
                onOpenDataDeletionGuide = {},
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
                onOpenPrivacyPolicy = {},
                onOpenDataDeletionGuide = {},
            )
        }

        composeRule.onNodeWithText("端末データ: 削除済み").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("匿名ユーザー: 未完了").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("未完了の削除を再試行").performScrollTo().assertIsDisplayed()
    }
}
