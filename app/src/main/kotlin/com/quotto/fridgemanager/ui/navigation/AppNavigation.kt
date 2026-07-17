package com.quotto.fridgemanager.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter
import com.quotto.fridgemanager.ui.feature.image.ImageAnalysisScreen
import com.quotto.fridgemanager.ui.feature.inventory.InventoryScreen
import com.quotto.fridgemanager.ui.feature.registration.RegistrationScreen
import com.quotto.fridgemanager.ui.feature.registration.ExistingIngredientUpdateScreen
import com.quotto.fridgemanager.ui.feature.settings.SettingsScreen

@Composable
fun AppNavigation(
    inventoryState: InventoryUiState,
    registrationPresenter: RegistrationPresenter,
    onReloadInventory: () -> Unit,
) {
    val controller = rememberNavController()
    val currentRoute = controller.currentBackStackEntryAsState().value?.destination?.route
    val selectedDestination = AppDestination.selectedTopLevel(currentRoute)
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.topLevel.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedDestination == destination,
                        onClick = {
                            controller.navigate(destination.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(AppDestination.start.route) { saveState = true }
                            }
                        },
                        icon = { Text(destination.navigationLabel.take(1)) },
                        label = { Text(destination.navigationLabel) },
                        modifier = Modifier.semantics {
                            contentDescription = "${destination.title}タブ"
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = controller,
            startDestination = AppDestination.start.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.Inventory.route) {
                InventoryScreen(
                    state = inventoryState,
                    onManualRegistration = { controller.navigate(AppDestination.Registration.route) },
                    onImageAnalysis = { controller.navigate(AppDestination.ImageAnalysis.route) },
                    onRetry = onReloadInventory,
                )
            }
            composable(AppDestination.Registration.route) {
                RegistrationScreen(
                    presenter = registrationPresenter,
                    onBack = {
                        if (!controller.popBackStack()) {
                            controller.navigate(AppDestination.Inventory.route)
                        }
                    },
                    onSaved = {
                        onReloadInventory()
                        controller.popBackStack()
                    },
                    // 編集本体は後続Story。既存IDを渡す導線境界だけを用意する。
                    onEditIngredient = {
                        controller.navigate(AppDestination.existingUpdateRoute(it))
                    },
                )
            }
            composable(AppDestination.existingUpdatePattern) { entry ->
                ExistingIngredientUpdateScreen(
                    ingredientId = entry.arguments?.getString("ingredientId").orEmpty(),
                    presenter = registrationPresenter,
                    onBack = { controller.popBackStack() },
                )
            }
            composable(AppDestination.ImageAnalysis.route) {
                ImageAnalysisScreen()
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
