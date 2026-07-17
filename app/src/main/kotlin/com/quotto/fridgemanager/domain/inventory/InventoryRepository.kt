package com.quotto.fridgemanager.domain.inventory

import kotlinx.coroutines.flow.Flow

/** 在庫データへの依存をUIとデータ実装から分離するドメイン境界。 */
interface InventoryRepository {
    suspend fun hasItems(): Boolean
    suspend fun getAll(): List<StoredIngredient>
    fun observeAll(): Flow<List<StoredIngredient>>
    suspend fun saveBatch(batch: InventoryBatch)
}
