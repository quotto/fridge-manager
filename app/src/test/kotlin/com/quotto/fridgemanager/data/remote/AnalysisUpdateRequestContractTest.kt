package com.quotto.fridgemanager.data.remote

import com.quotto.fridgemanager.domain.analysis.AnalysisApiRequest
import com.quotto.fridgemanager.domain.analysis.AnalysisCurrentItem
import com.quotto.fridgemanager.domain.auth.AnonymousUser
import com.quotto.fridgemanager.domain.auth.AppCheckGateway
import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import com.quotto.fridgemanager.domain.auth.FirebaseAuthGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisUpdateRequestContractTest {
    @Test
    fun `updateは対象1品目の現在値だけをcurrentItemsへ送る`() = runTest {
        val transport = BodyRecordingTransport()
        val client = AuthorizedAnalysisApiClient(AuthCoordinator(FakeAuth(), FakeAppCheck()), transport)

        client.analyze(
            request(
                mode = "update",
                currentItems = listOf(AnalysisCurrentItem(name = "牛乳", quantity = "2", unit = "本")),
            ),
        )

        assertTrue(transport.body.contains("\"mode\":\"update\""))
        assertTrue(transport.body.contains("\"currentItems\":[{\"name\":\"牛乳\",\"quantity\":\"2\",\"unit\":\"本\"}]"))
        assertFalse(transport.body.contains("豆腐"))
    }

    @Test
    fun `new requestにはcurrentItemsを含めない`() = runTest {
        val transport = BodyRecordingTransport()
        val client = AuthorizedAnalysisApiClient(AuthCoordinator(FakeAuth(), FakeAppCheck()), transport)

        client.analyze(request(mode = "new", currentItems = null))

        assertFalse(transport.body.contains("currentItems"))
    }

    @Test
    fun `modeとcurrentItemsの不整合は送信前に拒否する`() {
        assertThrows(IllegalArgumentException::class.java) { request("update", null) }
        assertThrows(IllegalArgumentException::class.java) {
            request("new", listOf(AnalysisCurrentItem("牛乳", "2", "本")))
        }
    }

    private fun request(mode: String, currentItems: List<AnalysisCurrentItem>?) = AnalysisApiRequest(
        requestId = "018f47a0-90c0-7d54-b92d-4285f7fb3312",
        mode = mode,
        jpegBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
        currentItems = currentItems,
    )
}

private class BodyRecordingTransport : AnalysisHttpTransport {
    lateinit var body: String
    override suspend fun post(request: AnalysisHttpRequest): AnalysisHttpResponse {
        body = request.body
        return AnalysisHttpResponse(
            200,
            """{"requestId":"018f47a0-90c0-7d54-b92d-4285f7fb3312","status":"succeeded","candidates":[],"warnings":[]}""",
        )
    }
}

private class FakeAuth : FirebaseAuthGateway {
    override suspend fun deleteCurrentAnonymousUser() = Unit
    private val user = AnonymousUser("anonymous-user")
    override fun currentAnonymousUser() = user
    override suspend fun signInAnonymously() = user
    override suspend fun getIdToken(user: AnonymousUser, forceRefresh: Boolean) = "id-token"
}

private class FakeAppCheck : AppCheckGateway {
    override suspend fun getLimitedUseToken() = "app-check-token"
}
