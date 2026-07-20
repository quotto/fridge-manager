package com.quotto.fridgemanager.data.remote

import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import java.util.Base64
import kotlinx.coroutines.CancellationException
import com.quotto.fridgemanager.domain.analysis.AnalysisClient
import com.quotto.fridgemanager.domain.analysis.AnalysisApiRequest as DomainAnalysisApiRequest
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult as DomainAnalysisApiResult
import com.quotto.fridgemanager.domain.analysis.AnalysisFailureKind as DomainAnalysisFailureKind
import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

typealias AnalysisApiRequest = DomainAnalysisApiRequest
typealias AnalysisFailureKind = DomainAnalysisFailureKind

data class AnalysisHttpRequest(
    val headers: Map<String, String>,
    val body: String,
    val timeoutMillis: Long,
    val onUploadComplete: () -> Unit = {},
)

data class AnalysisHttpResponse(val statusCode: Int, val body: String)

fun interface AnalysisHttpTransport {
    suspend fun post(request: AnalysisHttpRequest): AnalysisHttpResponse
}

class AnalysisTransportTimeoutException : Exception()

/** 認証取得とHTTP送信を一つの境界に閉じ、透過的な再送を行わないAPI client。 */
class AuthorizedAnalysisApiClient(
    private val authCoordinator: AuthCoordinator,
    private val transport: AnalysisHttpTransport,
) : AnalysisClient {
    override suspend fun analyze(request: AnalysisApiRequest, onUploadComplete: () -> Unit): DomainAnalysisApiResult {
        val result = try {
            authCoordinator.withFreshAuthorization { authorization ->
                transport.post(
                    AnalysisHttpRequest(
                        headers = mapOf(
                            "Authorization" to "Bearer ${authorization.idToken}",
                            "X-Firebase-AppCheck" to authorization.appCheckToken,
                            "Content-Type" to "application/json",
                            "Cache-Control" to "no-store",
                        ),
                        body = requestBody(request),
                        timeoutMillis = REQUEST_TIMEOUT_MILLIS,
                        onUploadComplete = onUploadComplete,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AnalysisTransportTimeoutException) {
            return DomainAnalysisApiResult.Failure(AnalysisFailureKind.Timeout)
        } catch (_: Exception) {
            return DomainAnalysisApiResult.Failure(AnalysisFailureKind.Network)
        }
        return result?.let { classify(it, request.requestId) } ?: DomainAnalysisApiResult.Failure(AnalysisFailureKind.Unauthorized)
    }

    private fun classify(response: AnalysisHttpResponse, requestId: String): DomainAnalysisApiResult = try {
        if (response.statusCode == 200) parseSuccess(response.body, requestId) else parseFailure(response)
    } catch (_: Exception) {
        DomainAnalysisApiResult.Failure(AnalysisFailureKind.InvalidResponse)
    }

    private fun parseSuccess(body: String, requestId: String): DomainAnalysisApiResult {
        val value = JSON.decodeFromString<SuccessDto>(body)
        require(value.requestId == requestId && value.status == "succeeded")
        require(value.candidates.size <= 30 && value.warnings.size <= 30 && value.warnings.all { it.length <= 100 })
        val candidates = value.candidates.map { candidate ->
            require(candidate.name == null || candidate.name.isValidCandidateName())
            require((candidate.quantity == null) == (candidate.unit == null))
            require(candidate.quantity == null || QUANTITY.matches(candidate.quantity))
            require(candidate.unit == null || candidate.unit in UNITS)
            require(candidate.evidence in EVIDENCE)
            require(candidate.requiresReview || (candidate.name != null && candidate.quantity != null && candidate.evidence != "UNKNOWN"))
            AnalysisCandidate(candidate.name, candidate.quantity, candidate.unit, candidate.evidence, candidate.requiresReview)
        }
        return DomainAnalysisApiResult.Success(value.requestId, candidates, value.warnings)
    }

    private fun String.isValidCandidateName(): Boolean {
        val codePointCount = codePointCount(0, length)
        return this == trim() &&
            codePointCount in 1..30 &&
            codePoints().noneMatch { it in 0x00..0x1f || it in 0x7f..0x9f }
    }

    private fun parseFailure(response: AnalysisHttpResponse): DomainAnalysisApiResult {
        val value = JSON.decodeFromString<FailureDto>(response.body)
        require(value.status == "failed")
        return when {
            response.statusCode in listOf(401, 403) && value.error.code == "UNAUTHORIZED" ->
                DomainAnalysisApiResult.Failure(AnalysisFailureKind.Unauthorized)
            response.statusCode in listOf(400, 413, 415) && value.error.code in setOf("INVALID_IMAGE", "INVALID_REQUEST") ->
                DomainAnalysisApiResult.Failure(AnalysisFailureKind.InvalidImage)
            response.statusCode == 422 && value.error.code == "UNANALYZABLE_IMAGE" ->
                DomainAnalysisApiResult.Failure(AnalysisFailureKind.UnanalyzableImage)
            response.statusCode == 429 && value.error.code == "QUOTA_EXCEEDED" -> {
                require(value.error.quotaType in QUOTA_TYPES)
                val retryAt = value.error.retryAt?.also { Instant.parse(it) }
                DomainAnalysisApiResult.Failure(AnalysisFailureKind.QuotaExceeded, value.error.quotaType, retryAt)
            }
            response.statusCode == 504 && value.error.code == "TIMEOUT" ->
                DomainAnalysisApiResult.Failure(AnalysisFailureKind.Timeout)
            response.statusCode in listOf(500, 502, 503, 504) && value.error.code in SERVICE_CODES ->
                DomainAnalysisApiResult.Failure(AnalysisFailureKind.ServiceUnavailable)
            else -> DomainAnalysisApiResult.Failure(AnalysisFailureKind.InvalidResponse)
        }
    }

    private fun requestBody(request: AnalysisApiRequest): String = JSON.encodeToString(
        RequestDto(request.requestId, request.mode, ImageDto("image/jpeg", Base64.getEncoder().encodeToString(request.jpegBytes))),
    )

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 55_000L
        val JSON = Json { ignoreUnknownKeys = false; explicitNulls = true }
        val QUOTA_TYPES = setOf("SHORT", "DAILY", "MONTHLY", "GLOBAL", "BUDGET")
        val SERVICE_CODES = setOf("PROVIDER_UNAVAILABLE", "QUOTA_UNAVAILABLE", "SERVICE_STOPPED", "INTERNAL_ERROR")
        val QUANTITY = Regex("^(?:(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,2})?|100)$")
        val UNITS = setOf("g", "kg", "ml", "L", "個", "本", "枚", "袋", "パック", "箱", "缶", "瓶", "束", "株", "玉", "丁", "尾", "切れ", "房", "合", "食")
        val EVIDENCE = setOf("VISIBLE_COUNT", "PACKAGE_LABEL", "VISUAL_ESTIMATE", "UNKNOWN")
    }
}

@Serializable private data class RequestDto(val requestId: String, val mode: String, val image: ImageDto)
@Serializable private data class ImageDto(val mediaType: String, val base64: String)
@Serializable private data class SuccessDto(val requestId: String, val status: String, val candidates: List<CandidateDto>, val warnings: List<String>)
@Serializable private data class CandidateDto(val name: String?, val quantity: String?, val unit: String?, val evidence: String, val requiresReview: Boolean)
@Serializable private data class FailureDto(val requestId: String? = null, val status: String, val error: ErrorDto)
@Serializable private data class ErrorDto(val code: String, val retryable: Boolean, val retryAt: String? = null, val quotaType: String? = null)
