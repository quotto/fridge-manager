package com.quotto.fridgemanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.quotto.fridgemanager.di.AppContainer
import com.quotto.fridgemanager.ui.navigation.AppNavigation
import com.quotto.fridgemanager.ui.theme.FridgeManagerTheme
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState
import kotlinx.coroutines.launch

@Composable
fun FridgeManagerApp(container: AppContainer) {
    val presenter = container.inventoryPresenter
    var inventoryState by remember(presenter) { mutableStateOf<InventoryUiState>(InventoryUiState.Loading) }
    val scope = rememberCoroutineScope()
    val reload = fun() {
        scope.launch { inventoryState = presenter.currentState() }
    }
    LaunchedEffect(presenter) { inventoryState = presenter.currentState() }

    FridgeManagerTheme {
        AppNavigation(
            initialInventoryState = inventoryState,
            onReloadInventory = reload,
        )
    }
}
