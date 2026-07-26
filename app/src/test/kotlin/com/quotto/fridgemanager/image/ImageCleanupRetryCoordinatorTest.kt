package com.quotto.fridgemanager.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCleanupRetryCoordinatorTest {
    @Test fun `起動後は期限より短い間隔で再試行し再開時にも即時実行する`() {
        var cleanups = 0
        var interval = Long.MAX_VALUE
        var scheduled: (() -> Unit)? = null
        val coordinator = ImageCleanupRetryCoordinator(
            cleanup = { cleanups++ },
            scheduleRepeating = { value, task -> interval = value; scheduled = task },
            execute = { it() },
        )

        coordinator.start()
        assertTrue(interval < ImageTemporaryFileCleaner.DELETION_DEADLINE_MILLIS)
        requireNotNull(scheduled).invoke()
        coordinator.onResume()

        assertEquals(2, cleanups)
    }
}
