package com.quotto.fridgemanager.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DuplicateStoredIngredientException(cause: Throwable) :
    IllegalStateException("A normalized ingredient name already exists", cause)

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
