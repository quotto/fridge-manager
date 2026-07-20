package com.quotto.fridgemanager.data.remote

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlConnectionAnalysisHttpTransportTest {
    @Test(expected = IllegalArgumentException::class)
    fun `HTTPS以外のendpointを拒否する`() {
        UrlConnectionAnalysisHttpTransport("http://example.com/v1/analysis")
    }

    @Test fun `coroutine取消時に接続を切断する`() = runBlocking {
        val connection = BlockingConnection()
        val transport = UrlConnectionAnalysisHttpTransport(
            "https://example.com/v1/analysis", Dispatchers.IO, connectionFactory = { connection },
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { transport.post(request(timeout = 5_000)) }
        assertTrue(connection.started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(connection.disconnected)
    }

    @Test fun `call全体deadlineで接続を切断してtimeout分類する`() = runBlocking {
        val connection = BlockingConnection()
        val transport = UrlConnectionAnalysisHttpTransport(
            "https://example.com/v1/analysis", Dispatchers.IO, connectionFactory = { connection },
        )
        val failure = runCatching { transport.post(request(timeout = 50)) }.exceptionOrNull()
        assertTrue(failure is AnalysisTransportTimeoutException)
        assertTrue(connection.disconnected)
    }

    private fun request(timeout: Long) = AnalysisHttpRequest(
        headers = mapOf("Content-Type" to "application/json"), body = "{}", timeoutMillis = timeout,
    )

    private class BlockingConnection : HttpURLConnection(URL("https://example.com/v1/analysis")) {
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var disconnected = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode(): Int {
            started.countDown()
            released.await()
            return 500
        }
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
