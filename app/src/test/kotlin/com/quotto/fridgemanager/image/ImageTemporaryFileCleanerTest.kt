package com.quotto.fridgemanager.image

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTemporaryFileCleanerTest {
    @Test fun `削除成功は保留せず削除する`() {
        val root = root()
        val target = image(root, "image-upload-success.jpg")

        val result = ImageTemporaryFileCleaner(root).deleteOrSchedule(target, nowMillis = 1_000)

        assertEquals(ImageDeletionResult.Deleted, result)
        assertFalse(target.exists())
        assertFalse(File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).exists())
    }

    @Test fun `削除失敗を永続記録し次回起動相当のCleanerで再試行する`() {
        val root = root()
        val target = image(root, "image-upload-restart.jpg")
        val failing = ImageTemporaryFileCleaner(root, deleteFile = { false })

        assertEquals(ImageDeletionResult.Scheduled, failing.deleteOrSchedule(target, nowMillis = 1_000))
        assertEquals(1, temporaryImages(root).size)

        val restarted = ImageTemporaryFileCleaner(root)
        restarted.cleanup(nowMillis = 2_000)

        assertFalse(target.exists())
        assertFalse(File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).exists())
    }

    @Test fun `失敗と取消で残った期限切れファイルを再試行する`() {
        val root = root()
        val source = image(root, "image-source-failed.bin", lastModified = 1)
        val upload = image(root, "image-upload-cancelled.jpg", lastModified = 1)

        ImageTemporaryFileCleaner(root).cleanup(nowMillis = ONE_HOUR + 2)

        assertFalse(source.exists())
        assertFalse(upload.exists())
    }

    @Test fun `期限未満の未登録ファイルは実行中として削除しない`() {
        val root = root()
        val active = image(root, "image-upload-active.jpg", lastModified = 1_001)

        ImageTemporaryFileCleaner(root).cleanup(nowMillis = ONE_HOUR)

        assertTrue(active.exists())
    }

    @Test fun `既知prefixでもcanonical cache直下でなければ拒否する`() {
        val root = root()
        val nested = File(root, "nested").apply { mkdirs() }
        val target = image(nested, "image-upload-private.jpg")
        val outside = image(requireNotNull(root.parentFile), "image-upload-outside.jpg")
        val cleaner = ImageTemporaryFileCleaner(root)

        assertEquals(ImageDeletionResult.Rejected, cleaner.deleteOrSchedule(target))
        assertEquals(ImageDeletionResult.Rejected, cleaner.deleteOrSchedule(outside))
        assertTrue(target.exists())
        assertTrue(outside.exists())
        outside.delete()
    }

    @Test fun `未知prefixとsymlinkは削除しない`() {
        val root = root()
        val unrelated = image(root, "private-image.jpg", lastModified = 1)
        val outside = image(requireNotNull(root.parentFile), "outside-private.jpg", lastModified = 1)
        val link = File(root, "image-upload-link.jpg")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())

        var deleteCalls = 0
        ImageTemporaryFileCleaner(root, deleteFile = { deleteCalls++; it.delete() })
            .cleanup(nowMillis = ONE_HOUR + 2)

        assertTrue(unrelated.exists())
        assertTrue(outside.exists())
        assertEquals(0, deleteCalls)
        link.delete()
        outside.delete()
    }

    @Test fun `既知prefixでもdirectoryは削除しない`() {
        val root = root()
        val directory = File(root, "image-upload-directory").apply { mkdir() }

        val result = ImageTemporaryFileCleaner(root).deleteOrSchedule(directory)

        assertEquals(ImageDeletionResult.Rejected, result)
        assertTrue(directory.isDirectory)
    }

    @Test fun `検証後に通常fileへ差し替えられても別identityを削除しない`() {
        val root = root()
        val target = image(root, "image-upload-race.jpg")
        val replacement = File(requireNotNull(root.parentFile), "replacement.jpg")
            .apply { writeText("different") }
        var deleteCalls = 0
        val cleaner = ImageTemporaryFileCleaner(
            cacheDirectory = root,
            deleteFile = { deleteCalls++; it.delete() },
            beforeAtomicMove = { candidate ->
                candidate.delete()
                replacement.copyTo(candidate)
            },
        )

        val result = cleaner.deleteOrSchedule(target)

        assertEquals(ImageDeletionResult.Rejected, result)
        assertEquals(0, deleteCalls)
        assertEquals(1, temporaryImages(root).size)
        replacement.delete()
    }

    @Test fun `期限超過監視は集計値だけを通知しファイル名を含めない`() {
        val root = root()
        val secretName = "image-upload-user-secret-token.jpg"
        val target = image(root, secretName)
        val reports = mutableListOf<ImageCleanupReport>()
        val cleaner = ImageTemporaryFileCleaner(
            cacheDirectory = root,
            deleteFile = { false },
            monitor = reports::add,
        )
        cleaner.deleteOrSchedule(target, nowMillis = 1)

        // アプリ再開相当。期限超過後も失敗する。
        cleaner.cleanup(nowMillis = ONE_HOUR + 2)

        assertTrue(reports.last().overdueCount > 0)
        assertEquals(1, reports.last().pendingCount)
        assertFalse(reports.joinToString().contains(secretName))
        assertFalse(reports.joinToString().contains("secret"))
        assertFalse(reports.joinToString().contains("token"))
    }

    @Test fun `壊れた保留記録から任意パスを復元しない`() {
        val root = root()
        val outside = image(requireNotNull(root.parentFile), "image-upload-do-not-delete.jpg")
        File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).writeText(
            "../${outside.name}\t1\nunknown-prefix\t1\n",
        )

        ImageTemporaryFileCleaner(root).cleanup(nowMillis = ONE_HOUR + 2)

        assertTrue(outside.exists())
        outside.delete()
    }

    @Test fun `保留記録を書けなくても次回起動で再試行可能にする`() {
        val root = root()
        // レジストリと同名のdirectoryで永続化を意図的に失敗させる。
        File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).mkdir()
        val now = ONE_HOUR + 10_000
        val target = image(root, "image-upload-registry-failure.jpg", lastModified = now - 1_000)

        val reports = mutableListOf<ImageCleanupReport>()
        val result = ImageTemporaryFileCleaner(root, deleteFile = { false }, monitor = reports::add)
            .deleteOrSchedule(target, nowMillis = now)
        assertEquals(1, temporaryImages(root).size)
        assertEquals(ImageDeletionResult.PersistenceFailed, result)
        assertEquals(1, reports.last().persistenceFailureCount)
        ImageTemporaryFileCleaner(root).cleanup(nowMillis = now + 1)

        assertTrue(temporaryImages(root).isEmpty())
    }

    @Test fun `再試行経路の永続化失敗も成功扱いせず監視する`() {
        val root = root()
        File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).mkdir()
        val now = ONE_HOUR + 20_000
        val target = image(root, "image-source-cleanup-registry.bin", lastModified = 1)
        val reports = mutableListOf<ImageCleanupReport>()

        val run = ImageTemporaryFileCleaner(root, deleteFile = { false }, monitor = reports::add)
            .cleanup(nowMillis = now)

        assertEquals(1, temporaryImages(root).size)
        assertTrue(ImageDeletionResult.PersistenceFailed in run.results)
        assertEquals(1, run.report.persistenceFailureCount)
        assertEquals(1, reports.last().persistenceFailureCount)
    }

    @Test fun `保留0件でもregistry削除失敗を監視する`() {
        val root = root()
        File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).apply {
            mkdir()
            File(this, "undeletable").writeText("x")
        }
        val reports = mutableListOf<ImageCleanupReport>()

        val run = ImageTemporaryFileCleaner(root, monitor = reports::add).cleanup()

        assertEquals(0, run.report.pendingCount)
        assertEquals(1, run.report.persistenceFailureCount)
        assertEquals(1, reports.last().persistenceFailureCount)
    }

    @Test fun `期限切れmark失敗を検知し監視する`() {
        val root = root()
        File(root, ImageTemporaryFileCleaner.PENDING_FILE_NAME).apply {
            mkdir()
            File(this, "undeletable").writeText("x")
        }
        image(root, "image-upload-mark-failure.jpg", lastModified = 1)
        val reports = mutableListOf<ImageCleanupReport>()

        val run = ImageTemporaryFileCleaner(
            root,
            deleteFile = { false },
            monitor = reports::add,
            markLastModified = { _, _ -> false },
        ).cleanup(nowMillis = ONE_HOUR + 10)

        assertEquals(1, run.report.deadlineMarkFailureCount)
        assertEquals(1, reports.last().deadlineMarkFailureCount)
    }

    @Test fun `監視送信失敗をreportへ記録する`() {
        val root = root()
        image(root, "image-upload-monitor-failure.jpg", lastModified = 1)

        val run = ImageTemporaryFileCleaner(
            root,
            deleteFile = { false },
            monitor = { false },
        ).cleanup(nowMillis = ONE_HOUR + 10)

        assertEquals(1, run.report.monitoringFailureCount)
    }

    @Test fun `監視送信失敗は画像削除後も次Worker相当の成功まで永続再試行する`() {
        val root = root()
        image(root, "image-upload-monitor-retry.jpg", lastModified = 1)
        val first = ImageTemporaryFileCleaner(
            root,
            deleteFile = { false },
            monitor = { false },
        ).cleanup(nowMillis = ONE_HOUR + 10)
        assertEquals(1, first.report.monitoringFailureCount)
        assertTrue(File(root, ImageTemporaryFileCleaner.MONITORING_PENDING_FILE_NAME).isFile)

        val delivered = mutableListOf<ImageCleanupReport>()
        val second = ImageTemporaryFileCleaner(root, monitor = delivered::add)
            .cleanup(nowMillis = ONE_HOUR + 20)

        assertEquals(0, second.report.monitoringFailureCount)
        assertTrue(delivered.single().pendingCount > 0)
        assertFalse(File(root, ImageTemporaryFileCleaner.MONITORING_PENDING_FILE_NAME).exists())
        assertTrue(temporaryImages(root).isEmpty())
    }

    @Test fun `監視marker作成失敗時は主registryでfail closedにする`() {
        val root = root()
        File(root, ImageTemporaryFileCleaner.MONITORING_PENDING_FILE_NAME).apply {
            mkdir()
            File(this, "block-write").writeText("x")
        }
        val target = image(root, "image-upload-monitor-marker-failure.jpg")
        ImageTemporaryFileCleaner(root, deleteFile = { false }, monitor = { false })
            .deleteOrSchedule(target, nowMillis = 10_000)

        var delivered = 0
        val blocked = ImageTemporaryFileCleaner(
            root,
            deleteFile = { error("通知成功前に削除してはならない") },
            monitor = { delivered++; false },
        ).cleanup(nowMillis = 20_000)
        assertEquals(1, blocked.report.monitoringFailureCount)

        File(root, ImageTemporaryFileCleaner.MONITORING_PENDING_FILE_NAME).deleteRecursively()
        val recovered = ImageTemporaryFileCleaner(root, monitor = { delivered++; true })
            .cleanup(nowMillis = 30_000)
        assertEquals(0, recovered.report.monitoringFailureCount)
        assertTrue(temporaryImages(root).isEmpty())
        assertTrue(delivered >= 1)
    }

    @Test fun `破損監視markerは削除前にfail closedする`() {
        val root = root()
        File(root, ImageTemporaryFileCleaner.MONITORING_PENDING_FILE_NAME)
            .writeText("corrupt\tprivate-looking-value")
        image(root, "image-upload-corrupt-monitor.jpg", lastModified = 1)

        val run = ImageTemporaryFileCleaner(
            root,
            deleteFile = { error("破損marker確認前に削除してはならない") },
            monitor = { true },
        ).cleanup(nowMillis = ONE_HOUR + 10)

        assertEquals(1, run.report.monitoringFailureCount)
        assertEquals(1, temporaryImages(root).size)
    }

    @Test fun `送信成功後もmarker削除失敗ならretryし画像を削除しない`() {
        val root = root()
        image(root, "image-upload-marker-delete-failure.jpg")
        ImageTemporaryFileCleaner(root, deleteFile = { false }, monitor = { false })
            .cleanup(nowMillis = ONE_HOUR + 2_000)
        var deliveries = 0

        val blocked = ImageTemporaryFileCleaner(
            root,
            deleteFile = { error("marker削除完了前に削除してはならない") },
            monitor = { deliveries++; true },
            deleteMonitoringMarker = { false },
        ).cleanup(nowMillis = ONE_HOUR + 3_000)

        assertEquals(1, deliveries)
        assertEquals(1, blocked.report.monitoringFailureCount)
        assertTrue(File(root, ImageTemporaryFileCleaner.MONITORING_PENDING_FILE_NAME).exists())
        assertEquals(1, temporaryImages(root).size)
    }

    private fun root() = createTempDirectory("cleaner-").toFile()
    private fun temporaryImages(root: File): List<File> = root.listFiles().orEmpty().filter {
        it.name.startsWith("image-source-") || it.name.startsWith("image-upload-")
    }
    private fun image(parent: File, name: String, lastModified: Long = 1_000): File =
        File(parent, name).apply { writeText("test-bytes"); setLastModified(lastModified) }

    private companion object {
        const val ONE_HOUR = 60L * 60L * 1_000L
    }
}
