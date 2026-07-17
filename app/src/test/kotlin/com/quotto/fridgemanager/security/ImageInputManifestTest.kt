package com.quotto.fridgemanager.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInputManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val paths = File("src/main/res/xml/file_paths.xml").readText()
    private val provider = Regex(
        "<provider[\\s\\S]*?android:name=\\\"\\.image\\.FridgeImageFileProvider\\\"[\\s\\S]*?</provider>",
    ).find(manifest)?.value.orEmpty()

    @Test
    fun `写真全体とカメラの実行時権限を宣言しない`() {
        assertFalse(manifest.contains("READ_MEDIA_IMAGES"))
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("android.permission.CAMERA"))
    }

    @Test
    fun `FileProviderは非公開かつ一時撮影専用パスだけを共有する`() {
        assertTrue(provider.isNotEmpty())
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertTrue(paths.contains("path=\"image-capture/\""))
        assertFalse(paths.contains("path=\".\""))
        assertFalse(paths.contains("root-path"))
    }
}
