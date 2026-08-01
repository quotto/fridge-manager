package com.quotto.fridgemanager.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.quotto.fridgemanager.presentation.inventory.InventoryUiState
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter
import com.quotto.fridgemanager.presentation.inventory.IngredientUpdatePresenter
import com.quotto.fridgemanager.ui.feature.image.ImageAnalysisScreen
import com.quotto.fridgemanager.ui.feature.inventory.InventoryScreen
import com.quotto.fridgemanager.ui.feature.registration.RegistrationScreen
import com.quotto.fridgemanager.ui.feature.registration.ExistingIngredientUpdateScreen
import com.quotto.fridgemanager.ui.feature.registration.UnitSelectionScreen
import com.quotto.fridgemanager.ui.feature.settings.SettingsScreen
import com.quotto.fridgemanager.domain.analysis.AnalysisClient
import com.quotto.fridgemanager.domain.analysis.AnalysisApiRequest
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult
import com.quotto.fridgemanager.domain.analysis.AnalysisRequestException
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidatePresenter
import com.quotto.fridgemanager.domain.analysis.AnalysisCurrentItem
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import kotlinx.coroutines.CancellationException
import com.quotto.fridgemanager.presentation.settings.DataDeletionCoordinator

@Composable
fun AppNavigation(
    inventoryState: InventoryUiState,
    registrationPresenter: RegistrationPresenter,
    ingredientUpdatePresenter: IngredientUpdatePresenter,
    candidateReviewPresenter: CandidateReviewPresenter,
    aiUpdateCandidatePresenter: AiUpdateCandidatePresenter,
    analysisApiClient: AnalysisClient?,
    dataDeletionCoordinator: DataDeletionCoordinator,
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
                    onEditIngredient = { controller.navigate(AppDestination.existingUpdateRoute(it)) },
                )
            }
            composable(AppDestination.Registration.route) { entry ->
                val selectedUnitResult by entry.savedStateHandle
                    .getStateFlow<String?>(UNIT_SELECTION_RESULT_KEY, null)
                    .collectAsStateWithLifecycle()
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
                    selectedUnitResult = selectedUnitResult,
                    onUnitResultConsumed = {
                        entry.savedStateHandle[UNIT_SELECTION_RESULT_KEY] = null
                    },
                    onUnitSelection = {
                        controller.navigate(AppDestination.unitSelectionRoute(it))
                    },
                )
            }
            composable(AppDestination.existingUpdatePattern) { entry ->
                val selectedUnitResult by entry.savedStateHandle
                    .getStateFlow<String?>(UNIT_SELECTION_RESULT_KEY, null)
                    .collectAsStateWithLifecycle()
                ExistingIngredientUpdateScreen(
                    ingredientId = entry.arguments?.getString("ingredientId").orEmpty(),
                    presenter = ingredientUpdatePresenter,
                    onBack = { controller.popBackStack() },
                    onChanged = {
                        onReloadInventory()
                        controller.popBackStack()
                    },
                    onImageAnalysis = { controller.navigate(AppDestination.updateImageRoute(it)) },
                    selectedUnitResult = selectedUnitResult,
                    onUnitResultConsumed = {
                        entry.savedStateHandle[UNIT_SELECTION_RESULT_KEY] = null
                    },
                    onUnitSelection = {
                        controller.navigate(AppDestination.unitSelectionRoute(it))
                    },
                )
            }
            composable(AppDestination.unitSelectionPattern) { entry ->
                val selectedUnit = entry.arguments?.getString("selectedUnit")
                    ?.takeIf { symbol ->
                        runCatching {
                            com.quotto.fridgemanager.domain.inventory.InventoryUnit.fromSymbol(symbol)
                        }.isSuccess
                    }
                    ?: com.quotto.fridgemanager.domain.inventory.InventoryUnit.PIECE.symbol
                UnitSelectionScreen(
                    selectedSymbol = selectedUnit,
                    onBack = { controller.popBackStack() },
                    onSelected = {
                        controller.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(UNIT_SELECTION_RESULT_KEY, it)
                        controller.popBackStack()
                    },
                )
            }
            composable(AppDestination.ImageAnalysis.route) {
                ImageAnalysisScreen(
                    candidateReviewPresenter = candidateReviewPresenter,
                    onManualFallback = {
                        controller.navigate(AppDestination.Registration.route) {
                            popUpTo(AppDestination.ImageAnalysis.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onCandidatesValidated = {
                        onReloadInventory()
                        controller.navigate(AppDestination.Inventory.route) {
                            popUpTo(AppDestination.Inventory.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onSendImage = { image, requestId, onUpload ->
                        val client = analysisApiClient ?: throw AnalysisRequestException.unavailable()
                        when (val result = client.analyze(AnalysisApiRequest(requestId, "new", image.file.readBytes()), onUpload)) {
                            is AnalysisApiResult.Success -> result
                            is AnalysisApiResult.Failure -> throw AnalysisRequestException(result)
                        }
                    },
                )
            }
            composable(AppDestination.updateImagePattern) { entry ->
                val ingredientId = entry.arguments?.getString("ingredientId").orEmpty()
                val loaded by produceState<StoredIngredient?>(initialValue = null, ingredientId) {
                    value = try {
                        ingredientUpdatePresenter.load(ingredientId)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
                loaded?.let { ingredient ->
                    ImageAnalysisScreen(
                        candidateReviewPresenter = candidateReviewPresenter,
                        onManualFallback = { controller.popBackStack() },
                        onCandidatesValidated = {},
                        updateIngredient = ingredient,
                        aiUpdateCandidatePresenter = aiUpdateCandidatePresenter,
                        onUpdateSaved = {
                            onReloadInventory()
                            controller.navigate(AppDestination.Inventory.route) {
                                popUpTo(AppDestination.Inventory.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onSendImage = { image, requestId, onUpload ->
                            val client = analysisApiClient ?: throw AnalysisRequestException.unavailable()
                            val currentItem = AnalysisCurrentItem(
                                ingredient.name.value,
                                ingredient.quantity.toString(),
                                ingredient.unit.symbol,
                            )
                            when (val result = client.analyze(
                                AnalysisApiRequest.singleItemUpdate(requestId, image.file.readBytes(), currentItem),
                                onUpload,
                            )) {
                                is AnalysisApiResult.Success -> result
                                is AnalysisApiResult.Failure -> throw AnalysisRequestException(result)
                            }
                        },
                    )
                } ?: Text("更新対象を読み込んでいます", modifier = Modifier.padding(24.dp))
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(dataDeletionCoordinator)
            }
        }
    }
}

private const val UNIT_SELECTION_RESULT_KEY = "selected-unit-result"
