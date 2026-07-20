package com.quotto.fridgemanager.presentation.image

import com.quotto.fridgemanager.image.PreprocessedImage
import java.io.File
import java.io.Closeable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult
import com.quotto.fridgemanager.domain.analysis.AnalysisFailureKind
import com.quotto.fridgemanager.domain.analysis.AnalysisRequestException

@OptIn(ExperimentalCoroutinesApi::class)
class ImageAnalysisSessionTest {
    @Test fun `変換後と同一の成果物をプレビューして送信する`() = runTest {
        val image = image(640, 480); var sent: PreprocessedImage? = null
        val session = session({ image }, { sent = it })
        session.select(asset()); advanceUntilIdle()
        assertSame(image, (session.state.value as ImageAnalysisState.Ready).image)
        session.send(); advanceUntilIdle()
        assertSame(image, sent); assertTrue(session.state.value is ImageAnalysisState.Succeeded)
        assertFalse(image.file.exists())
    }

    @Test fun `選び直しは旧成果物を削除して最新処理だけ採用する`() = runTest {
        val first = image(640, 480); val second = image(800, 600)
        val outputs = ArrayDeque(listOf(first, second)); val session = session({ outputs.removeFirst() })
        session.select(asset()); advanceUntilIdle(); session.select(asset()); advanceUntilIdle()
        assertFalse(first.file.exists()); assertSame(second, (session.state.value as ImageAnalysisState.Ready).image)
        session.close(); assertFalse(second.file.exists())
    }

    @Test fun `送信失敗でも成果物を削除する`() = runTest {
        val output = image(640, 480); val session = session({ output }, { error("network") })
        session.select(asset()); advanceUntilIdle(); session.send(); advanceUntilIdle()
        assertTrue(session.state.value is ImageAnalysisState.Failed); assertFalse(output.file.exists())
    }

    @Test fun `分類済み失敗は同じ画像を保持し明示再試行後に削除する`() = runTest {
        val output = image(640, 480); var attempts = 0
        val session = session({ output }) {
            attempts++
            if (attempts == 1) throw AnalysisRequestException(
                AnalysisApiResult.Failure(AnalysisFailureKind.QuotaExceeded, "DAILY", "2026-07-21T15:00:00Z"),
            )
        }
        session.select(asset()); advanceUntilIdle(); session.send(); advanceUntilIdle()
        val failed = session.state.value as ImageAnalysisState.Failed
        assertSame(output, failed.image); assertTrue(output.file.exists())
        assertTrue(failed.retryAt == "2026-07-21T15:00:00Z")

        session.send(); advanceUntilIdle()
        assertTrue(session.state.value is ImageAnalysisState.Succeeded)
        assertTrue(attempts == 2); assertFalse(output.file.exists())
    }

    @Test fun `処理失敗を安全なエラーにする`() = runTest {
        val session = session({ error("decode private path") })
        session.select(asset()); advanceUntilIdle()
        val state = session.state.value as ImageAnalysisState.Failed
        assertFalse(state.message.contains("private"))
    }

    @Test fun `取消と画面破棄は待機中の成果物を削除する`() = runTest {
        val ready = image(640, 480); val session = session({ ready })
        session.select(asset()); advanceUntilIdle(); session.cancel()
        assertTrue(session.state.value is ImageAnalysisState.Idle); assertFalse(ready.file.exists())
        val another = image(640, 480); val second = session({ another })
        second.select(asset()); advanceUntilIdle(); second.close(); assertFalse(another.file.exists())
    }

    @Test fun `取消と競合した旧処理は状態を上書きせず成果物を削除する`() = runTest {
        val output = image(640, 480); val gate = CompletableDeferred<Unit>()
        val session = ImageAnalysisSession<TestAsset>(
            this, StandardTestDispatcher(testScheduler),
            { withContext(NonCancellable) { gate.await() }; output }, { _, _ -> null },
        )
        session.select(asset()); runCurrent(); session.cancel(); gate.complete(Unit); advanceUntilIdle()
        assertTrue(session.state.value is ImageAnalysisState.Idle); assertFalse(output.file.exists())
    }

    @Test fun `送信中の取消はcallback終了までfileを削除しない`() = runTest {
        val output = image(640, 480); val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val session = session({ output }) {
            started.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            assertTrue(it.file.exists())
        }
        session.select(asset()); advanceUntilIdle(); session.send(); runCurrent(); started.await(); session.cancel()
        assertTrue(output.file.exists())
        var rejectedCloses = 0
        session.select(TestAsset { rejectedCloses++ })
        assertTrue(output.file.exists()); assertTrue(rejectedCloses == 1)
        gate.complete(Unit); advanceUntilIdle(); assertFalse(output.file.exists())
    }

    @Test fun `worker開始前の取消でも入力を一度だけcloseする`() = runTest {
        var closes = 0
        val input = TestAsset { closes++ }
        val session = session({ image(640, 480) })
        session.select(input); session.cancel(); advanceUntilIdle()
        assertTrue(closes == 1)
    }

    @Test fun `送信worker開始前の取消でも成果物を削除する`() = runTest {
        val output = image(640, 480); val session = session({ output })
        session.select(asset()); advanceUntilIdle()
        session.send(); session.cancel(); advanceUntilIdle()
        assertFalse(output.file.exists())
        assertTrue(session.state.value is ImageAnalysisState.Idle)
    }

    @Test fun `取消後に分類済み失敗が到着しても成果物を保持しない`() = runTest {
        val output = image(640, 480); val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val session = session({ output }) {
            started.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            throw AnalysisRequestException(AnalysisApiResult.Failure(AnalysisFailureKind.ServiceUnavailable))
        }
        session.select(asset()); advanceUntilIdle(); session.send(); runCurrent(); started.await()
        session.cancel(); assertTrue(session.state.value is ImageAnalysisState.Idle)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(output.file.exists()); assertTrue(session.state.value is ImageAnalysisState.Idle)
    }

    private fun TestScope.session(
        process: suspend (TestAsset) -> PreprocessedImage,
        send: suspend (PreprocessedImage) -> Unit = {},
    ) = ImageAnalysisSession(this, StandardTestDispatcher(testScheduler), process) { image, _ -> send(image); null }

    private fun image(width: Int, height: Int) = PreprocessedImage(
        File.createTempFile("preview-", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) },
        width, height, minOf(width, height) < 480,
    )
    private fun asset() = TestAsset()
    private class TestAsset(private val release: () -> Unit = {}) : Closeable {
        private var closed = false
        override fun close() { if (!closed) { closed = true; release() } }
    }
}
