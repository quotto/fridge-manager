package com.quotto.fridgemanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.quotto.fridgemanager.di.AppContainer
import com.quotto.fridgemanager.ui.navigation.AppNavigation
import com.quotto.fridgemanager.ui.theme.FridgeManagerTheme

@Composable
fun FridgeManagerApp(container: AppContainer) {
    val presenter = container.inventoryPresenter
    val initialInventoryState = remember(presenter) { presenter.currentState() }

    FridgeManagerTheme {
        AppNavigation(
            initialInventoryState = initialInventoryState,
            onReloadInventory = presenter::currentState,
        )
    }
}
