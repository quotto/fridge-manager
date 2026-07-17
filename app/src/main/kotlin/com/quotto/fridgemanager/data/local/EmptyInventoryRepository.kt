package com.quotto.fridgemanager.data.local

import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Preview・テストで使用する、永続化を行わない空の実装。 */
class EmptyInventoryRepository : InventoryRepository {
    override suspend fun hasItems(): Boolean = false
    override suspend fun getAll(): List<StoredIngredient> = emptyList()
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(emptyList())
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
}
