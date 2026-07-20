package com.quotto.fridgemanager.presentation.image

import com.quotto.fridgemanager.image.PreprocessedImage
import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.quotto.fridgemanager.domain.analysis.AnalysisRequestException
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult

sealed interface ImageAnalysisState {
    data object Idle : ImageAnalysisState
    data object Processing : ImageAnalysisState
    data class Ready(val image: PreprocessedImage) : ImageAnalysisState
    data class Sending(val image: PreprocessedImage) : ImageAnalysisState
    data class Analyzing(val image: PreprocessedImage) : ImageAnalysisState
    data class Succeeded(val result: AnalysisApiResult.Success? = null) : ImageAnalysisState
    data class Failed(
        val message: String,
        val image: PreprocessedImage? = null,
        val retryAt: String? = null,
        val quotaType: String? = null,
    ) : ImageAnalysisState
}

/** 前処理済み画像の唯一の所有者。送信コールバックは呼出中だけ画像を参照できる。 */
class ImageAnalysisSession<Input : Closeable>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val process: suspend (Input) -> PreprocessedImage,
    private val sendImage: suspend (PreprocessedImage, () -> Unit) -> AnalysisApiResult.Success?,
) : Closeable {
    private val mutableState = MutableStateFlow<ImageAnalysisState>(ImageAnalysisState.Idle)
    val state: StateFlow<ImageAnalysisState> = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var ownedImage: PreprocessedImage? = null
    private var sendInFlight = false
    private var closed = false

    @Synchronized
    fun select(asset: Input) {
        if (closed) {
            asset.close()
            return
        }
        if (sendInFlight) {
            asset.close()
            return
        }
        val request = ++generation
        job?.cancel()
        releaseImage()
        mutableState.value = ImageAnalysisState.Processing
        job = scope.launch(dispatcher) {
            var result: PreprocessedImage? = null
            try {
                result = process(asset)
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) {
                        ownedImage = result
                        mutableState.value = ImageAnalysisState.Ready(result)
                        result = null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) {
                        mutableState.value = ImageAnalysisState.Failed("画像を準備できませんでした。選び直してください")
                    }
                }
            } finally {
                result?.close()
            }
        }.also { processingJob -> processingJob.invokeOnCompletion { asset.close() } }
    }

    @Synchronized
    fun send() {
        if (closed || job?.isActive == true) return
        val image = ownedImage ?: return
        val request = generation
        sendInFlight = true
        mutableState.value = ImageAnalysisState.Sending(image)
        job = scope.launch(dispatcher) {
            var retainForRetry = false
            try {
                val result = sendImage(image) {
                    synchronized(this@ImageAnalysisSession) {
                        if (!closed && request == generation) mutableState.value = ImageAnalysisState.Analyzing(image)
                    }
                }
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) mutableState.value = ImageAnalysisState.Succeeded(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AnalysisRequestException) {
                retainForRetry = true
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) {
                        mutableState.value = ImageAnalysisState.Failed(
                            message = failure.userMessage,
                            image = image,
                            retryAt = failure.failure.retryAt,
                            quotaType = failure.failure.quotaType,
                        )
                    }
                }
            } catch (_: Exception) {
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) {
                        mutableState.value = ImageAnalysisState.Failed("画像を送信できませんでした。選び直してください")
                    }
                }
            } finally {
                completeSend(image, request, release = !retainForRetry)
            }
        }.also { sendJob -> sendJob.invokeOnCompletion { completeSend(image, request, release = true) } }
    }

    @Synchronized
    fun cancel() {
        if (closed) return
        generation++
        val sending = sendInFlight
        job?.cancel()
        if (!sending) {
            job = null
            releaseImage()
            mutableState.value = ImageAnalysisState.Idle
        } else {
            // HTTP境界が取消を観測するまでfileは保持するが、画面は直ちに取消状態へ戻す。
            mutableState.value = ImageAnalysisState.Idle
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        generation++
        val sending = sendInFlight
        job?.cancel()
        job = null
        if (!sending) releaseImage()
    }

    private fun releaseImage() {
        ownedImage?.close()
        ownedImage = null
    }

    @Synchronized
    private fun completeSend(image: PreprocessedImage, request: Long, release: Boolean) {
        if (!sendInFlight) return
        val mustRelease = release || closed || request != generation
        if (mustRelease && ownedImage === image) {
            ownedImage = null
            image.close()
        }
        sendInFlight = false
        if (!closed && request != generation) mutableState.value = ImageAnalysisState.Idle
    }
}
