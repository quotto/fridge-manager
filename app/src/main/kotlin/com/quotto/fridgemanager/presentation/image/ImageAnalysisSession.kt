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

sealed interface ImageAnalysisState {
    data object Idle : ImageAnalysisState
    data object Processing : ImageAnalysisState
    data class Ready(val image: PreprocessedImage) : ImageAnalysisState
    data class Sending(val image: PreprocessedImage) : ImageAnalysisState
    data object Succeeded : ImageAnalysisState
    data class Failed(val message: String) : ImageAnalysisState
}

/** 前処理済み画像の唯一の所有者。送信コールバックは呼出中だけ画像を参照できる。 */
class ImageAnalysisSession<Input : Closeable>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val process: suspend (Input) -> PreprocessedImage,
    private val sendImage: suspend (PreprocessedImage) -> Unit,
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
            try {
                sendImage(image)
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) mutableState.value = ImageAnalysisState.Succeeded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@ImageAnalysisSession) {
                    if (!closed && request == generation) {
                        mutableState.value = ImageAnalysisState.Failed("画像を送信できませんでした。選び直してください")
                    }
                }
            } finally {
                completeSend(image, request)
            }
        }.also { sendJob -> sendJob.invokeOnCompletion { completeSend(image, request) } }
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
    private fun completeSend(image: PreprocessedImage, request: Long) {
        if (!sendInFlight) return
        if (ownedImage === image) {
            ownedImage = null
            image.close()
        }
        sendInFlight = false
        if (!closed && request != generation) mutableState.value = ImageAnalysisState.Idle
    }
}
