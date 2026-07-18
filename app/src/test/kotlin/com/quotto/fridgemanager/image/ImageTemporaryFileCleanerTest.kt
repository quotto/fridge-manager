package com.quotto.fridgemanager.image

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTemporaryFileCleanerTest {
    @Test fun `期限切れの画像一時ファイルだけ削除する`() {
        val root = createTempDirectory("cleaner-").toFile()
        val now = 7_200_000L
        val oldUpload = File(root, "image-upload-old.jpg").apply { writeText("x"); setLastModified(1) }
        val oldSource = File(root, "image-source-old.bin").apply { writeText("x"); setLastModified(1) }
        val recent = File(root, "image-upload-active.jpg").apply { writeText("x"); setLastModified(now - 3_599_999) }
        val unrelated = File(root, "other-private.jpg").apply { writeText("x"); setLastModified(1) }

        ImageTemporaryFileCleaner(root).cleanup(nowMillis = now)

        assertFalse(oldUpload.exists()); assertFalse(oldSource.exists())
        assertTrue(recent.exists()); assertTrue(unrelated.exists())
        root.deleteRecursively()
    }
}
