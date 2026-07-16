package com.quotto.fridgemanager.data.local

import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.StoredIngredient

/** Preview・テストで使用する、永続化を行わない空の実装。 */
class EmptyInventoryRepository : InventoryRepository {
    override suspend fun hasItems(): Boolean = false
    override suspend fun getAll(): List<StoredIngredient> = emptyList()
    override suspend fun saveBatch(batch: InventoryBatch) = Unit
}
