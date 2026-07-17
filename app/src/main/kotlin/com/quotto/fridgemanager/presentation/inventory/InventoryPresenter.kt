package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

sealed interface InventoryUiState {
    data object Loading : InventoryUiState
    data object Empty : InventoryUiState
    data class Error(val message: String) : InventoryUiState
    data class Content(val ingredients: List<StoredIngredient>) : InventoryUiState
}

class InventoryPresenter(
    private val repository: InventoryRepository,
) {
    fun states(): Flow<InventoryUiState> = repository.observeAll()
        .map<List<StoredIngredient>, InventoryUiState> { ingredients ->
            if (ingredients.isEmpty()) InventoryUiState.Empty else InventoryUiState.Content(ingredients)
        }
        .onStart { emit(InventoryUiState.Loading) }
        .catch { emit(InventoryUiState.Error("在庫を読み込めませんでした。再試行してください")) }
}
