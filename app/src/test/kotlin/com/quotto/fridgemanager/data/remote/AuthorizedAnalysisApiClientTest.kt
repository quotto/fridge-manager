package com.quotto.fridgemanager.data.remote

import com.quotto.fridgemanager.domain.auth.AnonymousUser
import com.quotto.fridgemanager.domain.auth.AppCheckGateway
import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import com.quotto.fridgemanager.domain.auth.FirebaseAuthGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult

class AuthorizedAnalysisApiClientTest {
    @Test
    fun `send直前に認証を一度だけ取得して両tokenを単一HTTP requestへ付ける`() = runTest {
        val auth = CountingFirebaseAuth()
        val appCheck = CountingAppCheck()
        val transport = RecordingTransport(successResponse())
        val client = client(auth, appCheck, transport)

        client.analyze(request())

        assertEquals(1, auth.idTokenCalls)
        assertEquals(1, appCheck.tokenCalls)
        assertEquals(1, transport.requests.size)
        assertEquals("Bearer id-token-1", transport.requests.single().headers["Authorization"])
        assertEquals("app-check-token-1", transport.requests.single().headers["X-Firebase-AppCheck"])
        assertFalse(transport.requests.single().body.contains("id-token-1"))
        assertFalse(transport.requests.single().body.contains("app-check-token-1"))
    }

    @Test
    fun `requestIdとJPEG base64を契約JSONにして60秒timeoutで一度だけ送る`() = runTest {
        val transport = RecordingTransport(successResponse())
        val client = client(transport = transport)

        client.analyze(request())

        val sent = transport.requests.single()
        assertEquals(60_000L, sent.timeoutMillis)
        assertEquals("application/json", sent.headers["Content-Type"])
        assertTrue(sent.body.contains("\"requestId\":\"018f47a0-90c0-7d54-b92d-4285f7fb3312\""))
        assertTrue(sent.body.contains("\"mode\":\"new\""))
        assertTrue(sent.body.contains("\"mediaType\":\"image/jpeg\""))
        assertTrue(sent.body.contains("\"base64\":\"/9j/2Q==\""))
    }

    @Test
    fun `HTTPエラーを画面表示可能な分類へ変換する`() = runTest {
        val cases = listOf(
            Triple(401, errorResponse("UNAUTHORIZED"), AnalysisFailureKind.Unauthorized),
            Triple(422, errorResponse("UNANALYZABLE_IMAGE"), AnalysisFailureKind.UnanalyzableImage),
            Triple(503, errorResponse("PROVIDER_UNAVAILABLE"), AnalysisFailureKind.ServiceUnavailable),
        )

        cases.forEach { (status, body, expected) ->
            val result = client(transport = RecordingTransport(AnalysisHttpResponse(status, body)))
                .analyze(request()) as AnalysisApiResult.Failure
            assertEquals(expected, result.kind)
        }
    }

    @Test
    fun `429は上限種別と再利用日時を失わず分類する`() = runTest {
        val body = errorResponse(
            code = "QUOTA_EXCEEDED",
            extra = ",\"quotaType\":\"DAILY\",\"retryAt\":\"2026-07-21T15:00:00.000Z\"",
        )
        val result = client(transport = RecordingTransport(AnalysisHttpResponse(429, body)))
            .analyze(request()) as AnalysisApiResult.Failure

        assertEquals(AnalysisFailureKind.QuotaExceeded, result.kind)
        assertEquals("DAILY", result.quotaType)
        assertEquals("2026-07-21T15:00:00.000Z", result.retryAt)
    }

    @Test
    fun `transport timeoutを分類しclient内部では再送しない`() = runTest {
        val transport = RecordingTransport(failure = AnalysisTransportTimeoutException())
        val result = client(transport = transport).analyze(request()) as AnalysisApiResult.Failure

        assertEquals(AnalysisFailureKind.Timeout, result.kind)
        assertEquals(1, transport.attempts)
    }

    @Test
    fun `明示的な再試行は同じrequestIdでも新しいlimited-use tokenを取得する`() = runTest {
        val auth = CountingFirebaseAuth()
        val appCheck = CountingAppCheck()
        val transport = RecordingTransport(successResponse())
        val client = client(auth, appCheck, transport)

        client.analyze(request())
        client.analyze(request())

        assertEquals(2, transport.requests.size)
        assertEquals("app-check-token-1", transport.requests[0].headers["X-Firebase-AppCheck"])
        assertEquals("app-check-token-2", transport.requests[1].headers["X-Firebase-AppCheck"])
        assertEquals("Bearer id-token-1", transport.requests[0].headers["Authorization"])
        assertEquals("Bearer id-token-2", transport.requests[1].headers["Authorization"])
        assertEquals(2, appCheck.tokenCalls)
        assertEquals(2, auth.idTokenCalls)
    }

    @Test
    fun `成功JSONはrequestIdとschemaを厳格検証する`() = runTest {
        val wrongId = successResponse().copy(body = successResponse().body.replace(request().requestId, "550e8400-e29b-41d4-a716-446655440000"))
        val extra = successResponse().copy(body = successResponse().body.dropLast(1) + ",\"operation\":\"increase\"}")
        for (response in listOf(wrongId, extra)) {
            val result = client(transport = RecordingTransport(response)).analyze(request()) as AnalysisApiResult.Failure
            assertEquals(AnalysisFailureKind.InvalidResponse, result.kind)
        }
    }

    @Test
    fun `候補名はUnicodeコードポイント30文字以内かつ制御文字なしを要求する`() = runTest {
        val tooLong = successResponseWithName("🍎".repeat(31))
        val controlCharacter = successResponseWithName("りんご\\nジュース")

        for (response in listOf(tooLong, controlCharacter)) {
            val result = client(transport = RecordingTransport(response)).analyze(request()) as AnalysisApiResult.Failure
            assertEquals(AnalysisFailureKind.InvalidResponse, result.kind)
        }
    }

    @Test
    fun `timeoutと不正画像のAPI error codeを区別する`() = runTest {
        val timeout = client(transport = RecordingTransport(AnalysisHttpResponse(504, errorResponse("TIMEOUT"))))
            .analyze(request()) as AnalysisApiResult.Failure
        val invalid = client(transport = RecordingTransport(AnalysisHttpResponse(400, errorResponse("INVALID_IMAGE"))))
            .analyze(request()) as AnalysisApiResult.Failure
        assertEquals(AnalysisFailureKind.Timeout, timeout.kind)
        assertEquals(AnalysisFailureKind.InvalidImage, invalid.kind)
    }

    private fun client(
        auth: CountingFirebaseAuth = CountingFirebaseAuth(),
        appCheck: CountingAppCheck = CountingAppCheck(),
        transport: RecordingTransport,
    ) = AuthorizedAnalysisApiClient(AuthCoordinator(auth, appCheck), transport)

    private fun request() = AnalysisApiRequest(
        requestId = "018f47a0-90c0-7d54-b92d-4285f7fb3312",
        mode = "new",
        jpegBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
    )

    private fun successResponse() = AnalysisHttpResponse(
        200,
        """{"requestId":"018f47a0-90c0-7d54-b92d-4285f7fb3312","status":"succeeded","candidates":[],"warnings":[]}""",
    )

    private fun successResponseWithName(name: String) = AnalysisHttpResponse(
        200,
        """{"requestId":"018f47a0-90c0-7d54-b92d-4285f7fb3312","status":"succeeded","candidates":[{"name":"$name","quantity":null,"unit":null,"evidence":"UNKNOWN","requiresReview":true}],"warnings":[]}""",
    )

    private fun errorResponse(code: String, extra: String = "") =
        """{"requestId":"018f47a0-90c0-7d54-b92d-4285f7fb3312","status":"failed","error":{"code":"$code","retryable":true$extra}}"""

    private class CountingFirebaseAuth : FirebaseAuthGateway {
        override suspend fun deleteCurrentAnonymousUser() = Unit
        private val user = AnonymousUser("anonymous-user")
        var idTokenCalls = 0
        override fun currentAnonymousUser(): AnonymousUser = user
        override suspend fun signInAnonymously(): AnonymousUser = user
        override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean): String {
            idTokenCalls++
            assertTrue(forceRefresh)
            return "id-token-$idTokenCalls"
        }
    }

    private class CountingAppCheck : AppCheckGateway {
        var tokenCalls = 0
        override suspend fun getLimitedUseToken(): String {
            tokenCalls++
            return "app-check-token-$tokenCalls"
        }
    }

    private class RecordingTransport(
        private val response: AnalysisHttpResponse? = null,
        private val failure: Throwable? = null,
    ) : AnalysisHttpTransport {
        val requests = mutableListOf<AnalysisHttpRequest>()
        var attempts = 0
        override suspend fun post(request: AnalysisHttpRequest): AnalysisHttpResponse {
            attempts++
            requests += request
            failure?.let { throw it }
            return checkNotNull(response)
        }
    }
}
