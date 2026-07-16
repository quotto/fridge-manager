package com.quotto.fridgemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quotto.fridgemanager.domain.inventory.DomainErrorCode
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomInventoryRepositoryTest {
    private lateinit var database: InventoryDatabase
    private lateinit var repository: RoomInventoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            InventoryDatabase::class.java,
        ).build()
        var nextId = 0
        repository = RoomInventoryRepository(database, idGenerator = { "id-${nextId++}" })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun saveBatch_savesThirtyItemsAtomicallyInNameOrder() = runBlocking {
        val batch = InventoryBatch.create(
            (1..30).map { IngredientDraft.create("食材${it.toString().padStart(2, '0')}", "1.25", "個") },
        )

        repository.saveBatch(batch)

        assertEquals(30, repository.getAll().size)
        assertEquals("食材01", repository.getAll().first().name.value)
        assertEquals("1.25", repository.getAll().first().quantity.toString())
    }

    @Test
    fun saveBatch_rollsBackEveryItemWhenNormalizedNameAlreadyExists() = runBlocking {
        repository.saveBatch(InventoryBatch.create(listOf(IngredientDraft.create("牛乳", "1", "本"))))

        assertThrows(DuplicateStoredIngredientException::class.java) {
            runBlocking {
                repository.saveBatch(
                    InventoryBatch.create(
                        listOf(
                            IngredientDraft.create("卵", "10", "個"),
                            IngredientDraft.create("  牛乳  ", "2", "本"),
                        ),
                    ),
                )
            }
        }

        assertEquals(listOf("牛乳"), repository.getAll().map { it.name.value })
    }

    @Test
    fun databaseConstraint_rejectsDuplicateNormalizedName() {
        database.ingredientDao().insertAll(
            listOf(ingredientEntity(id = "1", displayName = "牛乳", normalizedName = "牛乳")),
        )

        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            database.ingredientDao().insertAll(
                listOf(ingredientEntity(id = "2", displayName = " 牛乳 ", normalizedName = "牛乳")),
            )
        }
    }

    @Test
    fun getAll_revalidatesPersistedValuesAtDomainBoundary() {
        database.ingredientDao().insertAll(
            listOf(ingredientEntity(id = "bad", displayName = "不正", normalizedName = "不正", quantity = "101")),
        )

        val error = assertThrows(DomainValidationException::class.java) {
            runBlocking { repository.getAll() }
        }
        assertEquals(DomainErrorCode.QUANTITY_OUT_OF_RANGE, error.code)
    }

    @Test
    fun getAll_rejectsMismatchedNormalizedName() {
        database.ingredientDao().insertAll(
            listOf(
                ingredientEntity(
                    id = "bad",
                    displayName = "Ａ",
                    normalizedName = "Ａ",
                ),
            ),
        )

        assertThrows(CorruptStoredIngredientException::class.java) {
            runBlocking { repository.getAll() }
        }
    }

    @Test
    fun getAll_rejectsUpdatedTimestampBeforeCreatedTimestamp() {
        database.ingredientDao().insertAll(
            listOf(
                ingredientEntity(
                    id = "bad",
                    displayName = "牛乳",
                    normalizedName = "牛乳",
                    createdAt = 2L,
                    updatedAt = 1L,
                ),
            ),
        )

        assertThrows(CorruptStoredIngredientException::class.java) {
            runBlocking { repository.getAll() }
        }
    }

    @Test
    fun boundParameters_preserveNamesContainingSqlCharacters() = runBlocking {
        repository.saveBatch(
            InventoryBatch.create(listOf(IngredientDraft.create("ねぎ' OR 1=1 --", "1", "束"))),
        )

        assertEquals("ねぎ' OR 1=1 --", repository.getAll().single().name.value)
    }

    @Test
    fun concurrentDuplicateWrites_leaveExactlyOneValidRow() = runBlocking {
        val results = supervisorScope {
            List(2) {
                async {
                    runCatching {
                        RoomInventoryRepository(database).saveBatch(
                            InventoryBatch.create(listOf(IngredientDraft.create("豆腐", "1", "丁"))),
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is DuplicateStoredIngredientException })
        assertEquals(listOf("豆腐"), repository.getAll().map { it.name.value })
    }

    private fun ingredientEntity(
        id: String,
        displayName: String,
        normalizedName: String,
        quantity: String = "1",
        createdAt: Long = 1L,
        updatedAt: Long = 1L,
    ) = IngredientEntity(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        quantity = quantity,
        unit = "PIECE",
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
    )
}

@RunWith(AndroidJUnit4::class)
class InventoryDatabaseReopenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "inventory-reopen-${UUID.randomUUID()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun dataRemainsAfterDatabaseIsClosedAndReopened() = runBlocking {
        var database = Room.databaseBuilder(context, InventoryDatabase::class.java, databaseName).build()
        RoomInventoryRepository(database, idGenerator = { "persistent-id" }).saveBatch(
            InventoryBatch.create(listOf(IngredientDraft.create("米", "2", "kg"))),
        )
        database.close()

        database = Room.databaseBuilder(context, InventoryDatabase::class.java, databaseName).build()
        try {
            assertEquals(listOf("米"), RoomInventoryRepository(database).getAll().map { it.name.value })
        } finally {
            database.close()
        }
    }
}
