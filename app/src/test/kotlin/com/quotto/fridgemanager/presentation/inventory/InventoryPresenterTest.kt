package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class InventoryPresenterTest {
    @Test
    fun `在庫がなければ空状態を返す`() = runBlocking {
        val presenter = InventoryPresenter(repository(hasItems = false))

        assertEquals(InventoryUiState.Empty, presenter.currentState())
    }

    @Test
    fun `在庫があれば内容表示状態を返す`() = runBlocking {
        val presenter = InventoryPresenter(repository(hasItems = true))

        assertEquals(InventoryUiState.Content, presenter.currentState())
    }

    @Test
    fun `データ取得失敗を日本語のエラー状態へ変換する`() = runBlocking {
        val presenter = InventoryPresenter(
            object : InventoryRepository {
                override suspend fun hasItems(): Boolean = error("storage unavailable")
                override suspend fun getAll() = error("storage unavailable")
                override suspend fun saveBatch(batch: com.quotto.fridgemanager.domain.inventory.InventoryBatch) = Unit
            },
        )

        assertEquals(
            InventoryUiState.Error("在庫を読み込めませんでした。再試行してください"),
            presenter.currentState(),
        )
    }

    private fun repository(hasItems: Boolean) = object : InventoryRepository {
        override suspend fun hasItems(): Boolean = hasItems
        override suspend fun getAll() = emptyList<com.quotto.fridgemanager.domain.inventory.StoredIngredient>()
        override suspend fun saveBatch(batch: com.quotto.fridgemanager.domain.inventory.InventoryBatch) = Unit
    }
}
