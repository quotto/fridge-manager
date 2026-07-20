package com.quotto.fridgemanager.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class UrlConnectionAnalysisHttpTransport(
    endpoint: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : AnalysisHttpTransport {
    private val endpoint = URL(endpoint).also {
        require(it.protocol == "https" && it.userInfo == null && it.ref == null) { "HTTPS analysis endpoint is required" }
    }

    override suspend fun post(request: AnalysisHttpRequest): AnalysisHttpResponse = try {
        withTimeout(request.timeoutMillis) { executeCancellable(request) }
    } catch (_: TimeoutCancellationException) {
        throw AnalysisTransportTimeoutException()
    } catch (_: SocketTimeoutException) {
        throw AnalysisTransportTimeoutException()
    }

    private suspend fun executeCancellable(request: AnalysisHttpRequest): AnalysisHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val current = AtomicReference<HttpURLConnection?>()
            val job = CoroutineScope(continuation.context + dispatcher).launch {
                try {
                    val bodyBytes = request.body.toByteArray(Charsets.UTF_8)
                    val connection = connectionFactory(endpoint).also(current::set)
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = minOf(10_000L, request.timeoutMillis).toInt()
                    connection.readTimeout = request.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.setFixedLengthStreamingMode(bodyBytes.size)
                    request.headers.forEach(connection::setRequestProperty)
                    connection.outputStream.use { it.write(bodyBytes) }
                    request.onUploadComplete()
                    val status = connection.responseCode
                    if (status in 300..399) throw IOException("redirect is forbidden")
                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val body = stream?.use(::readLimited)?.toString(Charsets.UTF_8) ?: ""
                    if (continuation.isActive) continuation.resume(AnalysisHttpResponse(status, body))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    current.getAndSet(null)?.disconnect()
                }
            }
            continuation.invokeOnCancellation {
                current.getAndSet(null)?.disconnect()
                job.cancel()
            }
        }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("response is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object { const val MAX_RESPONSE_BYTES = 1_048_576 }
}
