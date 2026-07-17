package com.quotto.fridgemanager.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.DuplicateStoredIngredientException
import com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException
import com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CorruptStoredIngredientException(cause: Throwable? = null) :
    IllegalStateException("Stored ingredient data is invalid", cause)

class RoomInventoryRepository(
    private val database: InventoryDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : InventoryRepository {
    private val dao get() = database.ingredientDao()

    override suspend fun hasItems(): Boolean = dao.hasItems()

    override suspend fun getAll(): List<StoredIngredient> = dao.getAll().map { it.toDomain() }

    override fun observeAll(): Flow<List<StoredIngredient>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun searchByName(normalizedQuery: String): List<StoredIngredient> {
        if (normalizedQuery.isEmpty()) return emptyList()
        return dao.searchByNormalizedName(normalizedQuery, MAX_SUGGESTIONS).map { it.toDomain() }
    }

    override suspend fun getById(id: String): StoredIngredient? = dao.getById(id)?.toDomain()

    override suspend fun update(ingredient: StoredIngredient) {
        try {
            database.withTransaction {
                val nextRevision = try {
                    Math.addExact(ingredient.updatedAtEpochMillis, 1L)
                } catch (error: ArithmeticException) {
                    throw CorruptStoredIngredientException(error)
                }
                val updatedAt = maxOf(currentTimeMillis(), nextRevision)
                val count = dao.updateIfCurrent(
                    id = ingredient.id,
                    expectedUpdatedAt = ingredient.updatedAtEpochMillis,
                    displayName = ingredient.name.value,
                    normalizedName = ingredient.name.normalizedValue,
                    quantity = ingredient.quantity.toString(),
                    unit = ingredient.unit.name,
                    updatedAt = updatedAt,
                )
                if (count == 0) {
                    if (dao.getById(ingredient.id) == null) throw StoredIngredientNotFoundException()
                    throw StaleStoredIngredientException()
                }
            }
        } catch (error: SQLiteConstraintException) {
            throw DuplicateStoredIngredientException(error)
        }
    }

    override suspend fun delete(ingredient: StoredIngredient) {
        database.withTransaction {
            val count = dao.deleteIfCurrent(ingredient.id, ingredient.updatedAtEpochMillis)
            if (count == 0) {
                if (dao.getById(ingredient.id) == null) throw StoredIngredientNotFoundException()
                throw StaleStoredIngredientException()
            }
        }
    }

    override suspend fun saveBatch(batch: InventoryBatch) {
        // InventoryBatchは生成時にも検証するが、永続化境界でも上限を防御する。
        require(batch.items.size <= InventoryBatch.MAX_ITEMS) { "Batch exceeds storage limit" }
        val now = currentTimeMillis()
        val entities = batch.items.map { draft ->
            IngredientEntity(
                id = idGenerator(),
                displayName = draft.name.value,
                normalizedName = draft.name.normalizedValue,
                quantity = draft.quantity.toString(),
                unit = draft.unit.name,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
        try {
            database.withTransaction { dao.insertAll(entities) }
        } catch (error: SQLiteConstraintException) {
            // 食材名やSQL引数を例外文へ含めない。
            throw DuplicateStoredIngredientException(error)
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 10
    }
}

private fun IngredientEntity.toDomain(): StoredIngredient {
    val name = IngredientName.from(displayName)
    if (name.normalizedValue != normalizedName) throw CorruptStoredIngredientException()
    if (createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis) {
        throw CorruptStoredIngredientException()
    }
    val inventoryUnit = try {
        InventoryUnit.valueOf(unit)
    } catch (error: IllegalArgumentException) {
        throw CorruptStoredIngredientException(error)
    }
    return StoredIngredient(
        id = id,
        name = name,
        quantity = InventoryQuantity.from(quantity),
        unit = inventoryUnit,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
