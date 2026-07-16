package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryPresenterTest {
    @Test
    fun `在庫がなければ空状態を返す`() {
        val presenter = InventoryPresenter(repository(hasItems = false))

        assertEquals(InventoryUiState.Empty, presenter.currentState())
    }

    @Test
    fun `在庫があれば内容表示状態を返す`() {
        val presenter = InventoryPresenter(repository(hasItems = true))

        assertEquals(InventoryUiState.Content, presenter.currentState())
    }

    @Test
    fun `データ取得失敗を日本語のエラー状態へ変換する`() {
        val presenter = InventoryPresenter(
            object : InventoryRepository {
                override fun hasItems(): Boolean = error("storage unavailable")
            },
        )

        assertEquals(
            InventoryUiState.Error("在庫を読み込めませんでした。再試行してください"),
            presenter.currentState(),
        )
    }

    private fun repository(hasItems: Boolean) = object : InventoryRepository {
        override fun hasItems(): Boolean = hasItems
    }
}
