package com.quotto.fridgemanager.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCleanupMonitoringTest {
    @Test fun `Crashlytics送信成功と失敗を判定する`() {
        val report = ImageCleanupReport(2, 1, 3_600_000, 1, 1)

        assertTrue(ImageCleanupCrashlyticsSink { }.report(report))
        assertFalse(ImageCleanupCrashlyticsSink { error("offline") }.report(report))
    }

    @Test fun `cleanup残件または監視失敗時はWorkerをretryする`() {
        assertFalse(ImageCleanupWorkDecision.shouldRetry(ImageCleanupReport(0, 0, 0)))
        assertTrue(ImageCleanupWorkDecision.shouldRetry(ImageCleanupReport(1, 0, 1)))
        assertTrue(
            ImageCleanupWorkDecision.shouldRetry(
                ImageCleanupReport(0, 0, 0, monitoringFailureCount = 1),
            ),
        )
        assertTrue(
            ImageCleanupWorkDecision.shouldRetry(
                ImageCleanupReport(0, 0, 0, deadlineMarkFailureCount = 1),
            ),
        )
    }
}
