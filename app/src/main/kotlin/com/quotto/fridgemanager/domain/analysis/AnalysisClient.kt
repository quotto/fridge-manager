package com.quotto.fridgemanager.domain.analysis

data class AnalysisCurrentItem(val name: String, val quantity: String, val unit: String) {
    init {
        require(name == name.trim() && name.codePointCount(0, name.length) in 1..30)
        require(name.codePoints().noneMatch { it in 0x00..0x1f || it in 0x7f..0x9f })
        com.quotto.fridgemanager.domain.inventory.IngredientName.from(name)
        com.quotto.fridgemanager.domain.inventory.InventoryQuantity.from(quantity)
        com.quotto.fridgemanager.domain.inventory.InventoryUnit.fromSymbol(unit)
    }
}

data class AnalysisApiRequest(
    val requestId: String,
    val mode: String,
    val jpegBytes: ByteArray,
    val currentItems: List<AnalysisCurrentItem>? = null,
) {
    init {
        require(
            (mode == "new" && currentItems == null) ||
                (mode == "update" && currentItems?.size in 1..30),
        )
        require(jpegBytes.isNotEmpty())
    }

    companion object {
        fun singleItemUpdate(
            requestId: String,
            jpegBytes: ByteArray,
            currentItem: AnalysisCurrentItem,
        ): AnalysisApiRequest = AnalysisApiRequest(
            requestId = requestId,
            mode = "update",
            jpegBytes = jpegBytes,
            currentItems = listOf(currentItem),
        )
    }
}

data class AnalysisCandidate(
    val name: String?, val quantity: String?, val unit: String?, val evidence: String, val requiresReview: Boolean,
)

sealed interface AnalysisApiResult {
    data class Success(
        val requestId: String,
        val candidates: List<AnalysisCandidate>,
        val warnings: List<String>,
    ) : AnalysisApiResult
    data class Failure(
        val kind: AnalysisFailureKind,
        val quotaType: String? = null,
        val retryAt: String? = null,
    ) : AnalysisApiResult
}

enum class AnalysisFailureKind {
    Unauthorized, InvalidImage, UnanalyzableImage, QuotaExceeded, Timeout, Network, ServiceUnavailable, InvalidResponse,
}

interface AnalysisClient {
    suspend fun analyze(request: AnalysisApiRequest, onUploadComplete: () -> Unit = {}): AnalysisApiResult
}

class AnalysisRequestException(val failure: AnalysisApiResult.Failure) : Exception() {
    val userMessage: String = when (failure.kind) {
        AnalysisFailureKind.Unauthorized -> "認証を確認できませんでした。再試行してください"
        AnalysisFailureKind.InvalidImage -> "この画像は送信できません。別の画像を選んでください"
        AnalysisFailureKind.UnanalyzableImage -> "画像を解析できませんでした。選び直すか手動で入力してください"
        AnalysisFailureKind.QuotaExceeded -> "AI解析の利用上限に達しました。再利用日時を確認してください"
        AnalysisFailureKind.Timeout -> "解析が時間内に完了しませんでした。再試行してください"
        AnalysisFailureKind.Network -> "通信できませんでした。接続を確認して再試行してください"
        AnalysisFailureKind.ServiceUnavailable -> "AI解析を一時的に利用できません。再試行するか手動で入力してください"
        AnalysisFailureKind.InvalidResponse -> "解析結果を確認できませんでした。再試行してください"
    }
    companion object {
        fun unavailable() = AnalysisRequestException(AnalysisApiResult.Failure(AnalysisFailureKind.ServiceUnavailable))
    }
}
