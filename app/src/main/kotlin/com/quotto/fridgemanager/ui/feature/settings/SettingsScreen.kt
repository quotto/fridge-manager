package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quotto.fridgemanager.ui.component.ScreenHeader
import com.quotto.fridgemanager.presentation.settings.DataDeletionCoordinator
import com.quotto.fridgemanager.presentation.settings.DataDeletionState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.launch

internal object PrivacyLinks {
    const val policy = "https://quotto.github.io/fridge-manager/privacy-policy.html"
    const val dataDeletion = "https://quotto.github.io/fridge-manager/data-deletion.html"
}

@Composable
fun SettingsScreen(coordinator: DataDeletionCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    SettingsContent(
        deletionState = state,
        onRequestDeletion = coordinator::requestConfirmation,
        onConfirmDeletion = { scope.launch { coordinator.confirmDeletion() } },
        onDismissDeletion = coordinator::dismissConfirmation,
        onRetryDeletion = { scope.launch { coordinator.retry() } },
        onOpenPrivacyPolicy = { uriHandler.openUri(PrivacyLinks.policy) },
        onOpenDataDeletionGuide = { uriHandler.openUri(PrivacyLinks.dataDeletion) },
    )
}

@Composable
fun SettingsContent(
    deletionState: DataDeletionState,
    onRequestDeletion: () -> Unit,
    onConfirmDeletion: () -> Unit,
    onDismissDeletion: () -> Unit,
    onRetryDeletion: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenDataDeletionGuide: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "設定")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("プライバシーとデータの取扱い")
            Text("食材データは端末内だけに保存し、クラウド同期やバックアップは行いません。")
            Text("解析画像はAWSとAmazon Bedrockへ送信しますが、クラウドへ永続保存しません。端末の一時画像は遅くとも1時間以内に削除します。")
            Text("解析結果は端末へ返し、クラウド側では継続保存しません。")
            Text("Firebase匿名認証とApp Checkを不正利用防止に使用します。")
            Text("App Checkのリプレイ保護tokenはFirebaseで最大30日保持されます。")
            Text("Firebase Authenticationは認証情報とIPアドレス等を処理します。")
            Text("Firebase Crashlyticsはクラッシュ情報と端末識別子を障害対応のため処理します。")
            Text("アプリケーションログは本番環境で90日保持し、画像・在庫・トークン・匿名ユーザーIDを記録しません。")
            OutlinedButton(onClick = onOpenPrivacyPolicy, modifier = Modifier.fillMaxWidth()) {
                Text("プライバシーポリシーを開く")
            }
            OutlinedButton(onClick = onOpenDataDeletionGuide, modifier = Modifier.fillMaxWidth()) {
                Text("アプリ外の削除案内を開く")
            }
            Text("アプリのデータ消去またはアンインストール後、端末内データは復元できません。")
            Text("利用データの削除")
            Text("端末内の全食材データ、一時画像、Firebase 匿名ユーザーを削除します。")
            Button(
                onClick = onRequestDeletion,
                enabled = deletionState !is DataDeletionState.Deleting,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "すべての利用データを削除、復元できません"
                    },
            ) { Text("すべての利用データを削除") }
            when (deletionState) {
                DataDeletionState.Deleting -> Text("削除しています")
                DataDeletionState.Succeeded ->
                    Text("端末データとFirebase匿名ユーザーの削除を受け付けました。提供者のバックアップやセキュリティ記録は各保持期限まで残る場合があります。")
                is DataDeletionState.Failed -> {
                    val localStatus = if (deletionState.localDataDeleted) "削除済み" else "未完了"
                    val temporaryStatus = if (deletionState.temporaryImagesDeleted) "削除済み" else "未完了"
                    val anonymousStatus = if (deletionState.anonymousUserDeleted) "削除済み" else "未完了"
                    Column(
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription =
                                "削除状況。端末データは$localStatus。一時画像と匿名ユーザーは" +
                                if (temporaryStatus == anonymousStatus) {
                                    temporaryStatus
                                } else {
                                    "それぞれ$temporaryStatus、$anonymousStatus"
                                }
                            stateDescription = "削除処理の一部が未完了"
                            liveRegion = LiveRegionMode.Polite
                        },
                    ) {
                        Text("端末データ: $localStatus")
                        Text("一時画像: $temporaryStatus")
                        Text("匿名ユーザー: $anonymousStatus")
                    }
                    OutlinedButton(
                        onClick = onRetryDeletion,
                        modifier = Modifier.semantics {
                            contentDescription = "未完了の削除だけを再試行"
                        },
                    ) { Text("未完了の削除を再試行") }
                }
                else -> Unit
            }
        }
    }
    if (deletionState is DataDeletionState.ConfirmationRequired) {
        AlertDialog(
            onDismissRequest = onDismissDeletion,
            title = { Text("すべての利用データを削除しますか？") },
            text = { Text("削除したデータは復元できません") },
            confirmButton = { Button(onClick = onConfirmDeletion) { Text("完全に削除") } },
            dismissButton = { OutlinedButton(onClick = onDismissDeletion) { Text("キャンセル") } },
        )
    }
}
