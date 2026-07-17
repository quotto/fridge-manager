package com.quotto.fridgemanager.ui.feature.inventory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
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
            is InventoryUiState.Content -> InventoryList(
                ingredients = state.ingredients,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InventoryList(
    ingredients: List<StoredIngredient>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items = ingredients, key = { it.id }) { ingredient ->
            val isOutOfStock = ingredient.quantity.value.signum() == 0
            val stockStatus = if (isOutOfStock) "在庫切れ" else "在庫あり"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription =
                            "${ingredient.name.value}、数量 ${ingredient.quantity} ${ingredient.unit.talkBackLabel}、$stockStatus"
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(ingredient.name.value)
                    Text("${ingredient.quantity} ${ingredient.unit.symbol}")
                }
                if (isOutOfStock) Text("在庫切れ")
            }
            HorizontalDivider()
        }
    }
}

private val InventoryUnit.talkBackLabel: String
    get() = when (this) {
        InventoryUnit.GRAM -> "グラム"
        InventoryUnit.KILOGRAM -> "キログラム"
        InventoryUnit.MILLILITER -> "ミリリットル"
        InventoryUnit.LITER -> "リットル"
        else -> symbol
    }
