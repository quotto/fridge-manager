package com.quotto.fridgemanager

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.quotto.fridgemanager.image.ImageCleanupMonitor
import com.quotto.fridgemanager.image.ImageCleanupRetryCoordinator
import com.quotto.fridgemanager.image.ImageCleanupWorker
import com.quotto.fridgemanager.image.ImageCleanupWorkPolicy
import com.quotto.fridgemanager.image.ImageTemporaryFileCleaner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 起動・再開・プロセス終了後も、画像一時ファイル削除を永続的に再試行する。 */
class FridgeManagerApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val cleaner by lazy {
        ImageTemporaryFileCleaner(cacheDir, monitor = ImageCleanupMonitor::report)
    }
    private val retryCoordinator by lazy {
        ImageCleanupRetryCoordinator(
            cleanup = { cleaner.cleanup() },
            scheduleRepeating = { intervalMillis, _ ->
                val request = PeriodicWorkRequestBuilder<ImageCleanupWorker>(
                    intervalMillis,
                    TimeUnit.MILLISECONDS,
                ).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    ImageCleanupWorkPolicy.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            },
            execute = { task -> cleanupExecutor.execute(task) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        retryCoordinator.start()
    }

    override fun onActivityResumed(activity: Activity) = retryCoordinator.onResume()
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
