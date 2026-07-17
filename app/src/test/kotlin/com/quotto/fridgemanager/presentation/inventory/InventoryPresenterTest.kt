package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class InventoryPresenterTest {
    @Test
    fun `在庫がなければ空状態を返す`() = runBlocking {
        val presenter = InventoryPresenter(repository(emptyList()))

        assertEquals(
            listOf(InventoryUiState.Loading, InventoryUiState.Empty),
            presenter.states().toList(),
        )
    }

    @Test
    fun `在庫があれば内容表示状態を返す`() = runBlocking {
        val ingredient = storedIngredient("2", "豆腐", "0", InventoryUnit.TOFU)
        val presenter = InventoryPresenter(repository(listOf(ingredient)))

        assertEquals(
            listOf(InventoryUiState.Loading, InventoryUiState.Content(listOf(ingredient))),
            presenter.states().toList(),
        )
    }

    @Test
    fun `データ取得失敗を日本語のエラー状態へ変換する`() = runBlocking {
        val presenter = InventoryPresenter(
            object : InventoryRepository {
                override suspend fun hasItems(): Boolean = error("storage unavailable")
                override suspend fun getAll() = error("storage unavailable")
                override fun observeAll(): Flow<List<StoredIngredient>> = flow {
                    error("機密なDB例外: secret-food")
                }
                override suspend fun saveBatch(batch: com.quotto.fridgemanager.domain.inventory.InventoryBatch) = Unit
            },
        )

        assertEquals(
            listOf(
                InventoryUiState.Loading,
                InventoryUiState.Error("在庫を読み込めませんでした。再試行してください"),
            ),
            presenter.states().toList(),
        )
    }

    private fun repository(items: List<StoredIngredient>) = object : InventoryRepository {
        override suspend fun hasItems(): Boolean = items.isNotEmpty()
        override suspend fun getAll() = items
        override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(items)
        override suspend fun saveBatch(batch: com.quotto.fridgemanager.domain.inventory.InventoryBatch) = Unit
    }

    private fun storedIngredient(
        id: String,
        name: String,
        quantity: String,
        unit: InventoryUnit,
    ) = StoredIngredient(
        id = id,
        name = IngredientName.from(name),
        quantity = InventoryQuantity.from(quantity),
        unit = unit,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )
}
