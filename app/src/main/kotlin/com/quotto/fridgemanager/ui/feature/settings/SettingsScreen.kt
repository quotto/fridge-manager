package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(coordinator: DataDeletionCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SettingsContent(
        deletionState = state,
        onRequestDeletion = coordinator::requestConfirmation,
        onConfirmDeletion = { scope.launch { coordinator.confirmDeletion() } },
        onDismissDeletion = coordinator::dismissConfirmation,
        onRetryDeletion = { scope.launch { coordinator.retry() } },
    )
}

@Composable
fun SettingsContent(
    deletionState: DataDeletionState,
    onRequestDeletion: () -> Unit,
    onConfirmDeletion: () -> Unit,
    onDismissDeletion: () -> Unit,
    onRetryDeletion: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "設定")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
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
                DataDeletionState.Succeeded -> Text("すべての利用データを削除しました")
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
