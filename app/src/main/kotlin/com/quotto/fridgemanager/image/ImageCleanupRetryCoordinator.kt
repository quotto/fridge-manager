package com.quotto.fridgemanager.image

/**
 * 起動時に定期再試行を開始し、再開時にも即時再試行する。
 * Androidのスケジューラ詳細を分離して期限条件を単体テスト可能にする。
 */
class ImageCleanupRetryCoordinator(
    private val cleanup: () -> Unit,
    private val scheduleRepeating: (intervalMillis: Long, task: () -> Unit) -> Unit,
    private val execute: (task: () -> Unit) -> Unit,
) {
    fun start() {
        scheduleRepeating(RETRY_INTERVAL_MILLIS, cleanup)
    }

    fun onResume() {
        execute(cleanup)
    }

    companion object {
        const val RETRY_INTERVAL_MILLIS = 15L * 60L * 1_000L
    }
}

object ImageCleanupWorkPolicy {
    const val UNIQUE_WORK_NAME = "image-temporary-file-cleanup"
}
