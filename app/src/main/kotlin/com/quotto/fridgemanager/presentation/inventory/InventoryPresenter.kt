package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.InventoryRepository

sealed interface InventoryUiState {
    data object Loading : InventoryUiState
    data object Empty : InventoryUiState
    data class Error(val message: String) : InventoryUiState
    data object Content : InventoryUiState
}

class InventoryPresenter(
    private val repository: InventoryRepository,
) {
    suspend fun currentState(): InventoryUiState = runCatching {
        if (repository.hasItems()) InventoryUiState.Content else InventoryUiState.Empty
    }.getOrElse {
        InventoryUiState.Error("在庫を読み込めませんでした。再試行してください")
    }
}
