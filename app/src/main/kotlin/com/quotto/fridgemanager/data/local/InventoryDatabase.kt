package com.quotto.fridgemanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [IngredientEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class InventoryDatabase : RoomDatabase() {
    abstract fun ingredientDao(): IngredientDao

    companion object {
        const val DATABASE_NAME = "inventory.db"

        @Volatile
        private var instance: InventoryDatabase? = null

        fun create(context: Context): InventoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                InventoryDatabase::class.java,
                DATABASE_NAME,
            ).build().also { instance = it }
        }
    }
}
