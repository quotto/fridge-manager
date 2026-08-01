package com.quotto.fridgemanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.quotto.fridgemanager.image.ImageCleanupWorkPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgeManagerApplicationTest {
    @Test fun `manifestは起動時削除再試行を持つApplicationを使用する`() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext

        assertTrue(application is FridgeManagerApplication)
    }

    @Test fun `起動時に永続的なperiodic cleanupを一意登録する`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ImageCleanupWorkPolicy.UNIQUE_WORK_NAME)
            .get(10, TimeUnit.SECONDS)

        assertTrue(work.isNotEmpty())
    }
}
