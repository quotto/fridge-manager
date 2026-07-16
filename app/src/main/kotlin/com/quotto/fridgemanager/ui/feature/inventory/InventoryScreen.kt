package com.quotto.fridgemanager.ui.feature.inventory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState
import com.quotto.fridgemanager.ui.component.EmptyPane
import com.quotto.fridgemanager.ui.component.ErrorPane
import com.quotto.fridgemanager.ui.component.LoadingPane
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun InventoryScreen(
    state: InventoryUiState,
    onManualRegistration: () -> Unit,
    onImageAnalysis: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "在庫一覧")
        when (state) {
            InventoryUiState.Loading -> LoadingPane(modifier = Modifier.weight(1f))
            InventoryUiState.Empty -> EmptyPane(
                title = "食材がありません",
                description = "手動または画像から最初の食材を登録できます",
                actionLabel = "手動で登録",
                onAction = onManualRegistration,
                secondaryActionLabel = "画像から登録",
                onSecondaryAction = onImageAnalysis,
                modifier = Modifier.weight(1f),
            )
            is InventoryUiState.Error -> ErrorPane(
                message = state.message,
                onRetry = onRetry,
                modifier = Modifier.weight(1f),
            )
            InventoryUiState.Content -> Text("登録済みの食材を表示します")
        }
    }
}
