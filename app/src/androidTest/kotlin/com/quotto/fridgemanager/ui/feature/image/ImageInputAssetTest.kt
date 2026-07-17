package com.quotto.fridgemanager.ui.feature.image

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInputAssetTest {
    @Test
    fun `前処理所有者がcloseすると一時画像を解放する`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("asset-", ".jpg", context.cacheDir)
        var releases = 0
        val asset = ImageInputAsset(Uri.EMPTY) {
            releases++
            file.delete()
        }

        // callbackから戻った後も非同期consumerが読むまで所有画像は残る。
        assertTrue(file.exists())

        asset.close()
        asset.close()

        assertEquals(1, releases)
        assertFalse(file.exists())
    }
}
