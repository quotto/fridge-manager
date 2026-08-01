package com.quotto.fridgemanager.data.deletion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quotto.fridgemanager.data.local.IngredientDao
import com.quotto.fridgemanager.data.local.IngredientEntity
import com.quotto.fridgemanager.data.local.InventoryDatabase
import com.quotto.fridgemanager.domain.auth.AnonymousUser
import com.quotto.fridgemanager.domain.auth.AppCheckGateway
import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import com.quotto.fridgemanager.domain.auth.FirebaseAuthGateway
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidDataDeletionGatewayTest {
    private lateinit var context: Context
    private lateinit var database: InventoryDatabase
    private lateinit var dao: IngredientDao
    private lateinit var auth: DeletingAuth

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java).build()
        dao = database.ingredientDao()
        auth = DeletingAuth()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun Room全件と認識済み一時画像と匿名ユーザーを削除する() = runBlocking {
        dao.insertAll(listOf(entity()))
        val upload = File(context.cacheDir, "image-upload-deletion-test.jpg").apply { writeText("x") }
        val unrelated = File(context.cacheDir, "keep-deletion-test.txt").apply { writeText("x") }
        val captureDir = File(context.cacheDir, "image-capture").apply { mkdirs() }
        val capture = File(captureDir, "deletion-test.jpg").apply { writeText("x") }
        val gateway = AndroidDataDeletionGateway(context, database, coordinator(auth))

        gateway.deleteLocalInventory()
        gateway.deleteTemporaryImages()
        gateway.deleteAnonymousUser()

        assertTrue(dao.getAll().isEmpty())
        assertFalse(upload.exists())
        assertFalse(capture.exists())
        assertTrue(unrelated.exists())
        assertTrue(auth.deleted)
        unrelated.delete()
        Unit
    }

    @Test
    fun 削除後にデータベースを再度開いても食材は復元しない() = runBlocking {
        val name = "deletion-${UUID.randomUUID()}.db"
        val persistent = Room.databaseBuilder(context, InventoryDatabase::class.java, name).build()
        persistent.ingredientDao().insertAll(listOf(entity()))
        AndroidDataDeletionGateway(context, persistent, coordinator(auth)).deleteLocalInventory()
        persistent.close()

        val reopened = Room.databaseBuilder(context, InventoryDatabase::class.java, name).build()
        try {
            assertTrue(reopened.ingredientDao().getAll().isEmpty())
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun キャッシュルートを列挙できない場合は削除成功にしない() = runBlocking {
        val gateway = AndroidDataDeletionGateway(
            context,
            database,
            coordinator(auth),
            listChildren = { null },
        )

        try {
            gateway.deleteTemporaryImages()
            fail("列挙失敗を通知する必要がある")
        } catch (_: IllegalStateException) {
            Unit
        }
    }

    @Test
    fun 撮影ディレクトリを列挙できない場合は削除成功にしない() = runBlocking {
        val captureDir = File(context.cacheDir, "image-capture").apply { mkdirs() }
        val gateway = AndroidDataDeletionGateway(
            context,
            database,
            coordinator(auth),
            listChildren = { directory ->
                if (directory.canonicalFile == captureDir.canonicalFile) null else directory.listFiles()
            },
        )

        try {
            gateway.deleteTemporaryImages()
            fail("列挙失敗を通知する必要がある")
        } catch (_: IllegalStateException) {
            Unit
        } finally {
            captureDir.delete()
        }
    }

    private fun entity() = IngredientEntity(
        id = "id",
        displayName = "豆腐",
        normalizedName = "豆腐",
        quantity = "1",
        unit = "PIECE",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun coordinator(auth: FirebaseAuthGateway) = AuthCoordinator(
        auth,
        object : AppCheckGateway {
            override suspend fun getLimitedUseToken() = error("unused")
        },
    )
}

private class DeletingAuth : FirebaseAuthGateway {
    var deleted = false
    override fun currentAnonymousUser() = AnonymousUser("test")
    override suspend fun signInAnonymously() = error("must not sign in")
    override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean) = error("unused")
    override suspend fun deleteCurrentAnonymousUser() { deleted = true }
}
