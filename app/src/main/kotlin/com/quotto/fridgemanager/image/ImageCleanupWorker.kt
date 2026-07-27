package com.quotto.fridgemanager.image

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics

class ImageCleanupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : Worker(appContext, parameters) {
    override fun doWork(): Result = runCatching {
        val runs = imageTemporaryFileCleaners(
            applicationContext.cacheDir,
            ImageCleanupMonitor::report,
        ).map { it.cleanup() }
        if (runs.any { ImageCleanupWorkDecision.shouldRetry(it.report) }) Result.retry() else Result.success()
    }.getOrElse { Result.retry() }
}

internal fun imageTemporaryFileCleaners(
    cacheDirectory: java.io.File,
    monitor: (ImageCleanupReport) -> Boolean,
): List<ImageTemporaryFileCleaner> = listOf(
    ImageTemporaryFileCleaner(cacheDirectory, monitor = monitor),
    ImageTemporaryFileCleaner(
        java.io.File(cacheDirectory, CAMERA_CAPTURE_DIRECTORY),
        monitor = monitor,
    ),
)

object ImageCleanupWorkDecision {
    fun shouldRetry(report: ImageCleanupReport): Boolean =
        report.pendingCount > 0 ||
            report.persistenceFailureCount > 0 ||
            report.deadlineMarkFailureCount > 0 ||
            report.monitoringFailureCount > 0
}

class ImageCleanupCrashlyticsSink(
    private val send: (ImageCleanupReport) -> Unit,
) {
    fun report(report: ImageCleanupReport): Boolean = runCatching {
        send(report)
    }.isSuccess
}

object ImageCleanupMonitor {
    private val sink by lazy {
        ImageCleanupCrashlyticsSink { report ->
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("image_cleanup_pending_count", report.pendingCount)
                setCustomKey("image_cleanup_overdue_count", report.overdueCount)
                setCustomKey("image_cleanup_oldest_age_ms", report.oldestPendingAgeMillis)
                setCustomKey("image_cleanup_persistence_failures", report.persistenceFailureCount)
                setCustomKey("image_cleanup_deadline_mark_failures", report.deadlineMarkFailureCount)
                recordException(ImageCleanupMonitoringEvent())
            }
        }
    }

    fun report(report: ImageCleanupReport): Boolean = sink.report(report)

    private class ImageCleanupMonitoringEvent :
        IllegalStateException("Image temporary file cleanup requires attention")
}
