package com.quotto.fridgemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quotto.fridgemanager.domain.inventory.DomainErrorCode
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryCommit
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException
import com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
    fun observeAll_emitsNameOrderedContentAfterSaveWithoutResubscription() = runBlocking {
        val initialEmission = CompletableDeferred<Unit>()
        val observed = async {
            repository.observeAll()
                .onEach { if (!initialEmission.isCompleted) initialEmission.complete(Unit) }
                .take(2)
                .toList()
        }
        initialEmission.await()

        repository.saveBatch(
            InventoryBatch.create(
                listOf(
                    IngredientDraft.create("りんご", "2", "個"),
                    IngredientDraft.create("豆腐", "0", "丁"),
                ),
            ),
        )

        assertEquals(emptyList<Any>(), observed.await().first())
        assertEquals(listOf("りんご", "豆腐"), observed.await().last().map { it.name.value })
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
    fun commit_savesNewItemsAndOptimisticUpdatesInOneTransaction() = runBlocking {
        repository.saveBatch(
            InventoryBatch.create(listOf(IngredientDraft.create("牛乳", "1", "本"))),
        )
        val milk = repository.getAll().single()

        repository.commit(
            InventoryCommit.create(
                newItems = listOf(IngredientDraft.create("卵", "10", "個")),
                updates = listOf(milk.withValues(name = "低脂肪乳", quantity = "2", unit = InventoryUnit.LITER)),
            ),
        )

        assertEquals(listOf("低脂肪乳", "卵"), repository.getAll().map { it.name.value })
        assertEquals("2", repository.getAll().first { it.id == milk.id }.quantity.toString())
    }

    @Test
    fun commit_rollsBackEarlierInsertWhenLaterUpdateIsStale() = runBlocking {
        repository.saveBatch(
            InventoryBatch.create(listOf(IngredientDraft.create("牛乳", "1", "本"))),
        )
        val staleSnapshot = repository.getAll().single()
        repository.update(staleSnapshot.withValues(quantity = "2"))

        assertThrows(StaleStoredIngredientException::class.java) {
            runBlocking {
                repository.commit(
                    InventoryCommit.create(
                        newItems = listOf(IngredientDraft.create("卵", "10", "個")),
                        updates = listOf(staleSnapshot.withValues(quantity = "3")),
                    ),
                )
            }
        }

        assertEquals(listOf("牛乳"), repository.getAll().map { it.name.value })
        assertEquals("2", repository.getAll().single().quantity.toString())
    }

    @Test
    fun commit_rollsBackEarlierInsertWhenLaterUpdateTargetDoesNotExist() = runBlocking {
        val missing = testStoredIngredient(id = "missing", name = "牛乳")

        assertThrows(StoredIngredientNotFoundException::class.java) {
            runBlocking {
                repository.commit(
                    InventoryCommit.create(
                        newItems = listOf(IngredientDraft.create("卵", "10", "個")),
                        updates = listOf(missing),
                    ),
                )
            }
        }

        assertEquals(emptyList<Any>(), repository.getAll())
    }

    @Test
    fun commit_rollsBackEarlierUpdateWhenLaterInsertViolatesUniqueConstraint() = runBlocking {
        repository.saveBatch(
            InventoryBatch.create(
                listOf(
                    IngredientDraft.create("牛乳", "1", "本"),
                    IngredientDraft.create("卵", "10", "個"),
                ),
            ),
        )
        val milk = repository.getAll().first { it.name.value == "牛乳" }

        assertThrows(DuplicateStoredIngredientException::class.java) {
            runBlocking {
                repository.commit(
                    InventoryCommit.create(
                        newItems = listOf(IngredientDraft.create(" 卵 ", "12", "個")),
                        updates = listOf(milk.withValues(quantity = "2")),
                    ),
                )
            }
        }

        assertEquals("1", repository.getAll().first { it.id == milk.id }.quantity.toString())
        assertEquals(2, repository.getAll().size)
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
    fun searchByName_usesLiteralBoundSubstringAndRanksExactFirst() = runBlocking {
        repository.saveBatch(
            InventoryBatch.create(
                listOf(
                    IngredientDraft.create("牛乳パン", "1", "個"),
                    IngredientDraft.create("牛乳", "2", "本"),
                    IngredientDraft.create("100%牛乳", "1", "本"),
                    IngredientDraft.create("100X牛乳", "1", "本"),
                    IngredientDraft.create("在庫_候補", "1", "個"),
                    IngredientDraft.create("在庫\\候補", "1", "個"),
                ),
            ),
        )

        assertEquals(
            listOf("牛乳", "100%牛乳", "100X牛乳", "牛乳パン"),
            repository.searchByName("牛乳").map { it.name.value },
        )
        assertEquals(listOf("100%牛乳"), repository.searchByName("100%").map { it.name.value })
        assertEquals(listOf("在庫_候補"), repository.searchByName("_").map { it.name.value })
        assertEquals(listOf("在庫\\候補"), repository.searchByName("\\").map { it.name.value })
        assertEquals(emptyList<Any>(), repository.searchByName(""))
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

    @Test
    fun update_preservesIdAndCreatedTimeAndPersistsEveryEditedField() = runBlocking {
        val timedRepository = RoomInventoryRepository(database, idGenerator = { "stable-id" }, currentTimeMillis = { 10L })
        timedRepository.saveBatch(InventoryBatch.create(listOf(IngredientDraft.create("豆腐", "1", "丁"))))
        val current = timedRepository.getAll().single()

        RoomInventoryRepository(database, currentTimeMillis = { 20L }).update(
            current.copy(
                name = com.quotto.fridgemanager.domain.inventory.IngredientName.from("木綿豆腐"),
                quantity = com.quotto.fridgemanager.domain.inventory.InventoryQuantity.from("2.25"),
                unit = com.quotto.fridgemanager.domain.inventory.InventoryUnit.PACK,
            ),
        )

        val updated = repository.getAll().single()
        assertEquals("stable-id", updated.id)
        assertEquals(10L, updated.createdAtEpochMillis)
        assertEquals(20L, updated.updatedAtEpochMillis)
        assertEquals("木綿豆腐", updated.name.value)
        assertEquals("2.25", updated.quantity.toString())
        assertEquals("パック", updated.unit.symbol)
    }

    @Test
    fun update_duplicateNormalizedNameRollsBackOriginalIngredient() = runBlocking {
        repository.saveBatch(InventoryBatch.create(listOf(
            IngredientDraft.create("豆腐", "1", "丁"),
            IngredientDraft.create("牛乳", "1", "本"),
        )))
        val tofu = repository.getAll().first { it.name.value == "豆腐" }

        assertThrows(DuplicateStoredIngredientException::class.java) {
            runBlocking {
                repository.update(tofu.copy(name = com.quotto.fridgemanager.domain.inventory.IngredientName.from(" 牛乳 ")))
            }
        }

        assertEquals(listOf("牛乳", "豆腐"), repository.getAll().map { it.name.value })
    }

    @Test
    fun delete_removesOnlyRequestedId() = runBlocking {
        repository.saveBatch(InventoryBatch.create(listOf(
            IngredientDraft.create("豆腐", "1", "丁"),
            IngredientDraft.create("牛乳", "1", "本"),
        )))
        val tofu = repository.getAll().first { it.name.value == "豆腐" }

        repository.delete(tofu)

        assertEquals(listOf("牛乳"), repository.getAll().map { it.name.value })
    }

    @Test
    fun staleUpdateAndDelete_areRejectedWithoutOverwritingTheWinningUpdate() = runBlocking {
        val first = RoomInventoryRepository(database, idGenerator = { "id" }, currentTimeMillis = { 1L })
        first.saveBatch(InventoryBatch.create(listOf(IngredientDraft.create("豆腐", "1", "丁"))))
        val staleSnapshot = first.getAll().single()
        RoomInventoryRepository(database, currentTimeMillis = { 2L }).update(
            staleSnapshot.copy(quantity = com.quotto.fridgemanager.domain.inventory.InventoryQuantity.from("2")),
        )

        assertThrows(com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException::class.java) {
            runBlocking { repository.update(staleSnapshot.copy(quantity = com.quotto.fridgemanager.domain.inventory.InventoryQuantity.from("3"))) }
        }
        assertThrows(com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException::class.java) {
            runBlocking { repository.delete(staleSnapshot) }
        }
        assertEquals("2", repository.getAll().single().quantity.toString())
    }

    @Test
    fun updateAndDelete_rejectMissingId() {
        val missing = com.quotto.fridgemanager.domain.inventory.StoredIngredient(
            id = "missing",
            name = com.quotto.fridgemanager.domain.inventory.IngredientName.from("豆腐"),
            quantity = com.quotto.fridgemanager.domain.inventory.InventoryQuantity.from("1"),
            unit = com.quotto.fridgemanager.domain.inventory.InventoryUnit.TOFU,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        assertThrows(com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException::class.java) {
            runBlocking { repository.update(missing) }
        }
        assertThrows(com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException::class.java) {
            runBlocking { repository.delete(missing) }
        }
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

private fun StoredIngredient.withValues(
    name: String = this.name.value,
    quantity: String = this.quantity.toString(),
    unit: InventoryUnit = this.unit,
) = copy(
    name = IngredientName.from(name),
    quantity = InventoryQuantity.from(quantity),
    unit = unit,
)

private fun testStoredIngredient(id: String, name: String) = StoredIngredient(
    id = id,
    name = IngredientName.from(name),
    quantity = InventoryQuantity.from("1"),
    unit = InventoryUnit.PIECE,
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
)
