package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.quotto.fridgemanager.presentation.settings.DataDeletionState
import com.quotto.fridgemanager.presentation.settings.DataDeletionCoordinator
import com.quotto.fridgemanager.presentation.settings.DataDeletionGateway
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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

        composeRule.onNodeWithText("プライバシーとデータの取扱い").assertIsDisplayed()
        composeRule.onNodeWithText(
            "食材データは端末内だけに保存し、クラウド同期やバックアップは行いません。画像で既存在庫を更新する場合は、対象の食材名・現在数量・単位を解析目的でAWSとAmazon Bedrockへ一時送信し、永続保存しません。",
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

        composeRule.onNodeWithText("すべての利用データを削除").performScrollTo().performClick()
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

    @Test
    fun 文字サイズ2倍でもスクリーンリーダー向け削除操作を表示できる() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                SettingsContent(
                    deletionState = DataDeletionState.Failed(
                        localDataDeleted = true,
                        temporaryImagesDeleted = false,
                        anonymousUserDeleted = false,
                    ),
                    onRequestDeletion = {},
                    onConfirmDeletion = {},
                    onDismissDeletion = {},
                    onRetryDeletion = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("すべての利用データを削除、復元できません")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithContentDescription("未完了の削除だけを再試行")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithContentDescription(
            "削除状況。端末データは削除済み。一時画像と匿名ユーザーは未完了",
        ).assertExists()
    }

    @Test
    fun 削除操作の画面上の表示順は全削除から未完了再試行になる() {
        composeRule.setContent {
            SettingsContent(
                deletionState = DataDeletionState.Failed(
                    localDataDeleted = true,
                    temporaryImagesDeleted = false,
                    anonymousUserDeleted = false,
                ),
                onRequestDeletion = {},
                onConfirmDeletion = {},
                onDismissDeletion = {},
                onRetryDeletion = {},
            )
        }

        val retryNode = composeRule
            .onNodeWithContentDescription("未完了の削除だけを再試行")
            .performScrollTo()
        val deleteTop = composeRule
            .onNodeWithContentDescription("すべての利用データを削除、復元できません")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val retryTop = retryNode
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        check(deleteTop < retryTop)
    }

    @Test
    fun 全削除の部分失敗から未完了処理だけを再試行して完了する() {
        val gateway = RetryableDeletionGateway()
        composeRule.setContent {
            SettingsScreen(DataDeletionCoordinator(gateway))
        }

        composeRule.onNodeWithText("すべての利用データを削除").performScrollTo().performClick()
        composeRule.onNodeWithText("完全に削除").performClick()
        composeRule.waitUntil(3_000) {
            runCatching {
                composeRule.onNodeWithText("匿名ユーザー: 未完了").performScrollTo().assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("端末データ: 削除済み").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("一時画像: 削除済み").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("未完了の削除を再試行").performScrollTo().performClick()
        composeRule.onNodeWithText(
            "端末データとFirebase匿名ユーザーの削除を受け付けました。提供者のバックアップやセキュリティ記録は各保持期限まで残る場合があります。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            check(gateway.localCalls == 1)
            check(gateway.temporaryCalls == 1)
            check(gateway.authCalls == 2)
        }
    }
}

private class RetryableDeletionGateway : DataDeletionGateway {
    var localCalls = 0
    var temporaryCalls = 0
    var authCalls = 0

    override suspend fun deleteLocalInventory() {
        localCalls++
    }

    override suspend fun deleteTemporaryImages() {
        temporaryCalls++
    }

    override suspend fun deleteAnonymousUser() {
        authCalls++
        if (authCalls == 1) error("一時的な認証障害")
    }
}
