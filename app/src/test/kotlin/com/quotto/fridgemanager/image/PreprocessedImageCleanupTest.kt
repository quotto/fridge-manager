package com.quotto.fridgemanager.image

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessedImageCleanupTest {
    @Test fun `closeは削除失敗を永続的な再試行対象にする`() {
        val root = createTempDirectory("processed-").toFile()
        val target = File(root, "image-upload-close.jpg").apply { writeText("bytes") }
        val cleaner = ImageTemporaryFileCleaner(root, deleteFile = { false })
        val image = PreprocessedImage(target, 640, 480, false, cleaner::deleteOrSchedule)

        image.close()

        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("image-upload-") })
        assertEquals(
            ImageDeletionResult.Deleted,
            ImageTemporaryFileCleaner(root).cleanup().results.single(),
        )
    }
}
