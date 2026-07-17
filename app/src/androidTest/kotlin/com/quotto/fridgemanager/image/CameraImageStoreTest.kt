package com.quotto.fridgemanager.image

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraImageStoreTest {
    @Test
    fun `撮影用URIはFileProviderで作り破棄すると一時ファイルを削除する`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val image = CameraImageStore(context).create()

        assertTrue(image.uri.toString().startsWith("content://"))
        assertTrue(image.file.exists())

        image.close()

        assertFalse(image.file.exists())
    }

    @Test
    fun `保存状態のpathとURIが同じ撮影ファイルを指す場合だけ復元する`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CameraImageStore(context)
        val first = store.create()
        val second = store.create()

        assertNotNull(store.restore(first.file.absolutePath, first.uri.toString()))
        assertNull(store.restore(first.file.absolutePath, second.uri.toString()))

        first.close()
        second.close()
    }

    @Test
    fun `孤児だけを削除し進行中ファイルは保持する`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CameraImageStore(context)
        val active = store.create()
        val orphan = store.create()
        orphan.file.setLastModified(System.currentTimeMillis() - 2L * 60L * 60L * 1_000L)

        store.cleanupOrphans(excluding = active.file, nowMillis = System.currentTimeMillis() + 1_000)

        assertTrue(active.file.exists())
        assertFalse(orphan.file.exists())
        active.close()
    }

    @Test
    fun `一時間未満の画像は画面再入場時の孤児回収でも削除しない`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CameraImageStore(context)
        val transferred = store.create()

        store.cleanupOrphans(nowMillis = System.currentTimeMillis() + 1_000)

        assertTrue(transferred.file.exists())
        transferred.close()
        assertFalse(transferred.file.exists())
    }
}
