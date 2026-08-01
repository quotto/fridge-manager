package com.quotto.fridgemanager.ui.feature.inventory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.quotto.fridgemanager.R
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState
import com.quotto.fridgemanager.ui.component.ErrorPane
import com.quotto.fridgemanager.ui.component.LoadingPane
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun InventoryScreen(
    state: InventoryUiState,
    onManualRegistration: () -> Unit,
    onImageAnalysis: () -> Unit,
    onRetry: () -> Unit,
    onEditIngredient: (String) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = "在庫一覧")
            when (state) {
                InventoryUiState.Loading -> LoadingPane(modifier = Modifier.weight(1f))
                InventoryUiState.Empty -> Column(
                    modifier = Modifier.fillMaxSize().weight(1f).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("食材がありません")
                    Text("右下の追加またはカメラボタンから最初の食材を登録できます")
                }
                is InventoryUiState.Error -> ErrorPane(
                    message = state.message,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )
                is InventoryUiState.Content -> InventoryList(
                    ingredients = state.ingredients,
                    onEditIngredient = onEditIngredient,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(
                onClick = onImageAnalysis,
                modifier = Modifier.semantics { contentDescription = "画像から食材を登録" },
            ) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null)
            }
            FloatingActionButton(
                onClick = onManualRegistration,
                modifier = Modifier.semantics { contentDescription = "食材を追加" },
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            }
        }
    }
}

@Composable
private fun InventoryList(
    ingredients: List<StoredIngredient>,
    onEditIngredient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 152.dp),
    ) {
        items(items = ingredients, key = { it.id }) { ingredient ->
            val isOutOfStock = ingredient.quantity.value.signum() == 0
            val stockStatus = if (isOutOfStock) "在庫切れ" else "在庫あり"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onEditIngredient(ingredient.id) }
                    .clearAndSetSemantics {
                        contentDescription =
                            "${ingredient.name.value}、数量 ${ingredient.quantity} ${ingredient.unit.talkBackLabel}、$stockStatus、編集"
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
