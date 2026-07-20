package com.quotto.fridgemanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quotto.fridgemanager.di.AppContainer
import com.quotto.fridgemanager.ui.navigation.AppNavigation
import com.quotto.fridgemanager.ui.theme.FridgeManagerTheme
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState

@Composable
fun FridgeManagerApp(container: AppContainer) {
    val presenter = container.inventoryPresenter
    var subscriptionKey by remember(presenter) { mutableIntStateOf(0) }
    val stateFlow = remember(presenter, subscriptionKey) { presenter.states() }
    val inventoryState by stateFlow.collectAsStateWithLifecycle(
        initialValue = InventoryUiState.Loading,
    )
    LaunchedEffect(container.authCoordinator) {
        container.authCoordinator.initialize()
    }

    FridgeManagerTheme {
        AppNavigation(
            inventoryState = inventoryState,
            registrationPresenter = container.registrationPresenter,
            ingredientUpdatePresenter = container.ingredientUpdatePresenter,
            onReloadInventory = { subscriptionKey += 1 },
        )
    }
}
