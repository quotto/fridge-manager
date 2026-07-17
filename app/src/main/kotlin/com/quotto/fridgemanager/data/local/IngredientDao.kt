package com.quotto.fridgemanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IngredientDao {
    @Query("SELECT * FROM ingredients ORDER BY normalized_name ASC")
    suspend fun getAll(): List<IngredientEntity>

    @Query("SELECT * FROM ingredients ORDER BY normalized_name ASC")
    fun observeAll(): Flow<List<IngredientEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM ingredients LIMIT 1)")
    suspend fun hasItems(): Boolean

    @Query(
        """SELECT * FROM ingredients
           WHERE instr(normalized_name, :normalizedQuery) > 0
           ORDER BY CASE WHEN normalized_name = :normalizedQuery THEN 0 ELSE 1 END,
                    normalized_name ASC, id ASC
           LIMIT :limit""",
    )
    suspend fun searchByNormalizedName(normalizedQuery: String, limit: Int): List<IngredientEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(entities: List<IngredientEntity>)
}
