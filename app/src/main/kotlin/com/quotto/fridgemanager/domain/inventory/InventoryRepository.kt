package com.quotto.fridgemanager.domain.inventory

import kotlinx.coroutines.flow.Flow

/** 在庫データへの依存をUIとデータ実装から分離するドメイン境界。 */
interface InventoryRepository {
    suspend fun hasItems(): Boolean
    suspend fun getAll(): List<StoredIngredient>
    fun observeAll(): Flow<List<StoredIngredient>>
    suspend fun searchByName(normalizedQuery: String): List<StoredIngredient>
    suspend fun saveBatch(batch: InventoryBatch)
    suspend fun commit(commit: InventoryCommit) {
        if (commit.updates.isNotEmpty()) {
            throw UnsupportedOperationException("Mixed inventory commit is not supported")
        }
        saveBatch(InventoryBatch.create(commit.newItems))
    }
    suspend fun getById(id: String): StoredIngredient? = getAll().firstOrNull { it.id == id }
    suspend fun update(ingredient: StoredIngredient) {
        throw UnsupportedOperationException("Inventory update is not supported")
    }
    suspend fun delete(ingredient: StoredIngredient) {
        throw UnsupportedOperationException("Inventory deletion is not supported")
    }
}

class DuplicateStoredIngredientException(cause: Throwable) :
    IllegalStateException("A normalized ingredient name already exists", cause)

class StaleStoredIngredientException : IllegalStateException("Stored ingredient was changed")
class StoredIngredientNotFoundException : IllegalStateException("Stored ingredient was not found")
