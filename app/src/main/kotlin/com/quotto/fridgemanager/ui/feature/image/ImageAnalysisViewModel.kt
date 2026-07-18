package com.quotto.fridgemanager.ui.feature.image

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quotto.fridgemanager.image.ImageInputAsset
import com.quotto.fridgemanager.image.ImagePreprocessor
import com.quotto.fridgemanager.image.ImageTemporaryFileCleaner
import com.quotto.fridgemanager.image.PreprocessedImage
import com.quotto.fridgemanager.presentation.image.ImageAnalysisSession

class ImageAnalysisViewModel(
    context: Context,
    sendImage: suspend (PreprocessedImage) -> Unit,
) : ViewModel() {
    init { ImageTemporaryFileCleaner(context.applicationContext.cacheDir).cleanup() }
    private val session = ImageAnalysisSession(
        scope = viewModelScope,
        process = ImagePreprocessor(context.applicationContext)::process,
        sendImage = sendImage,
    )
    val state = session.state
    fun select(asset: ImageInputAsset) = session.select(asset)
    fun send() = session.send()
    fun cancel() = session.cancel()
    override fun onCleared() = session.close()

    class Factory(
        private val context: Context,
        private val sendImage: suspend (PreprocessedImage) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ImageAnalysisViewModel(context, sendImage) as T
    }
}
