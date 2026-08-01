package com.quotto.fridgemanager.ui.feature.image

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quotto.fridgemanager.image.CameraImageStore
import com.quotto.fridgemanager.image.PreprocessedImage
import com.quotto.fridgemanager.presentation.image.ImageAnalysisState
import com.quotto.fridgemanager.ui.component.ScreenHeader
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import com.quotto.fridgemanager.presentation.candidate.ReviewedCandidate
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidatePresenter
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CameraMessage {
    None,
    Unavailable,
}

/** #19 の前処理へ一時画像の所有権を移す境界。利用側は処理終了時に必ず close する。 */
typealias ImageInputAsset = com.quotto.fridgemanager.image.ImageInputAsset

@Composable
fun ImageAnalysisScreen(
    candidateReviewPresenter: CandidateReviewPresenter,
    onCandidatesValidated: (List<ReviewedCandidate>) -> Unit,
    onManualFallback: () -> Unit,
    updateIngredient: StoredIngredient? = null,
    aiUpdateCandidatePresenter: AiUpdateCandidatePresenter? = null,
    onUpdateSaved: () -> Unit = {},
    onSendImage: suspend (PreprocessedImage, String, () -> Unit) -> com.quotto.fridgemanager.domain.analysis.AnalysisApiResult.Success,
) {
    val context = LocalContext.current
    val analysis: ImageAnalysisViewModel = viewModel(
        factory = remember(context.applicationContext, onSendImage) {
            ImageAnalysisViewModel.Factory(context.applicationContext, onSendImage)
        },
    )
    val analysisState by analysis.state.collectAsState()
    val activity = context.findActivity()
    DisposableEffect(activity, analysis) {
        onDispose {
            // ViewModelは構成変更を跨ぐが、画面から離れた場合は成果物を即時破棄する。
            if (activity?.isChangingConfigurations != true) analysis.cancel()
        }
    }
    val store = androidx.compose.runtime.remember(context.applicationContext) {
        CameraImageStore(context.applicationContext)
    }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMessage by rememberSaveable { mutableStateOf(CameraMessage.None) }
    var ownershipTransferred by androidx.compose.runtime.remember { mutableStateOf(false) }
    val latestOwnershipTransferred by rememberUpdatedState(ownershipTransferred)

    var cameraImage by androidx.compose.runtime.remember(pendingPath, pendingUri) {
        mutableStateOf(
            if (pendingPath != null && pendingUri != null) {
                store.restore(checkNotNull(pendingPath), checkNotNull(pendingUri))
            } else {
                null
            },
        )
    }

    fun clearCameraImage() {
        cameraImage?.close()
        cameraImage = null
        pendingPath = null
        pendingUri = null
        ownershipTransferred = false
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImage?.file?.length()?.let { it > 0L } == true) {
            selectedUri = cameraImage?.uri?.toString()
            cameraMessage = CameraMessage.None
        } else {
            clearCameraImage()
            selectedUri = null
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            clearCameraImage()
            selectedUri = uri.toString()
            cameraMessage = CameraMessage.None
        }
    }

    LaunchedEffect(store) {
        store.cleanupOrphans(excluding = cameraImage?.file)
    }
    DisposableEffect(activity, cameraImage) {
        onDispose {
            if (activity?.isChangingConfigurations != true && !latestOwnershipTransferred) cameraImage?.close()
        }
    }

    val currentAnalysisState = analysisState
    if (currentAnalysisState is ImageAnalysisState.Succeeded && currentAnalysisState.result != null) {
        if (updateIngredient == null) {
            CandidateReviewScreen(
                result = currentAnalysisState.result,
                presenter = candidateReviewPresenter,
                onValidated = onCandidatesValidated,
            )
        } else {
            val matchingCandidates = currentAnalysisState.result.candidates.filter { candidate ->
                try {
                    candidate.name != null &&
                        IngredientName.from(candidate.name).normalizedValue == updateIngredient.name.normalizedValue
                } catch (_: DomainValidationException) {
                    false
                }
            }
            val presenter = aiUpdateCandidatePresenter
            if (matchingCandidates.size == 1 && presenter != null) {
                AiUpdateCandidateScreen(
                    requestId = currentAnalysisState.result.requestId,
                    ingredient = updateIngredient,
                    candidate = matchingCandidates.single(),
                    presenter = presenter,
                    onSaved = onUpdateSaved,
                    onBack = analysis::cancel,
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(title = "AI更新候補の確認")
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("更新対象に対応する候補を1件に特定できませんでした。画像を選び直してください")
                        Button(onClick = analysis::cancel, modifier = Modifier.fillMaxWidth()) { Text("画像を選び直す") }
                    }
                }
            }
        }
        return
    }
    if (currentAnalysisState is ImageAnalysisState.Ready || currentAnalysisState is ImageAnalysisState.Sending || currentAnalysisState is ImageAnalysisState.Analyzing ||
        (currentAnalysisState is ImageAnalysisState.Failed && currentAnalysisState.image != null)
    ) {
        val preview = when (val state = currentAnalysisState) {
            is ImageAnalysisState.Ready -> state.image
            is ImageAnalysisState.Sending -> state.image
            is ImageAnalysisState.Analyzing -> state.image
            is ImageAnalysisState.Failed -> checkNotNull(state.image)
        }
        ImagePreviewContent(
            image = preview,
            onSend = analysis::send,
            onReselect = analysis::cancel,
            sending = currentAnalysisState is ImageAnalysisState.Sending || currentAnalysisState is ImageAnalysisState.Analyzing,
            analyzing = currentAnalysisState is ImageAnalysisState.Analyzing,
            failure = currentAnalysisState as? ImageAnalysisState.Failed,
            onManualFallback = onManualFallback,
        )
        return
    }

    ImageInputContent(
        cameraMessage = cameraMessage,
        hasSelection = selectedUri != null,
        analysisState = analysisState,
        onPickImage = {
            runCatching {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.onFailure {
                cameraMessage = CameraMessage.Unavailable
            }
        },
        onTakePhoto = {
            clearCameraImage()
            val pending = runCatching { store.create() }.getOrElse {
                cameraMessage = CameraMessage.Unavailable
                return@ImageInputContent
            }
            cameraImage = pending
            pendingPath = pending.file.absolutePath
            pendingUri = pending.uri.toString()
            selectedUri = null
            runCatching { takePicture.launch(pending.uri) }.onFailure {
                clearCameraImage()
                cameraMessage = CameraMessage.Unavailable
            }
        },
        onOpenCameraSettings = {
            val intent = Intent(Settings.ACTION_SETTINGS)
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }
                    .onFailure { cameraMessage = CameraMessage.Unavailable }
            } else {
                cameraMessage = CameraMessage.Unavailable
            }
        },
        onDiscardSelection = {
            analysis.cancel()
            clearCameraImage()
            selectedUri = null
        },
        onUseSelection = {
            selectedUri?.let { uri ->
                val parsed = runCatching { Uri.parse(uri) }.getOrNull()
                if (parsed == null) {
                    clearCameraImage()
                    selectedUri = null
                    return@ImageInputContent
                }
                val captured = cameraImage
                ownershipTransferred = captured != null
                runCatching {
                    analysis.select(ImageInputAsset(parsed) { captured?.close() })
                }.onSuccess {
                    // 以降はImageInputAssetだけが所有し、close時に削除する。
                    cameraImage = null
                    pendingPath = null
                    pendingUri = null
                    selectedUri = null
                }
                    .onFailure {
                        captured?.close()
                        cameraImage = null
                        pendingPath = null
                        pendingUri = null
                        selectedUri = null
                        ownershipTransferred = false
                    }
            }
        },
    )
}

@Composable
fun ImageInputContent(
    cameraMessage: CameraMessage,
    hasSelection: Boolean,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onDiscardSelection: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onUseSelection: () -> Unit = {},
    analysisState: ImageAnalysisState = ImageAnalysisState.Idle,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "画像解析")
        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("解析する画像を1枚選択してください")
            Text("食材以外や人物・個人情報が映り込んでいない画像を選んでください。")
            Text("画像はAWSとAmazon Bedrockへ解析目的で送信され、永続保存やモデル学習には使用されません。")
            Text("既存在庫を画像で更新する場合は、対象の食材名・現在数量・単位も解析目的で一時送信し、永続保存しません。")
            Text("解析画像と結果はクラウドへ永続保存しません。端末の一時画像は遅くとも1時間以内に削除します。")
            when (analysisState) {
                ImageAnalysisState.Processing -> Text("送信画像を準備しています")
                is ImageAnalysisState.Failed -> Text(analysisState.message)
                is ImageAnalysisState.Succeeded -> Text("${analysisState.result?.candidates?.size ?: 0}件の解析候補を取得しました")
                else -> Unit
            }
            if (cameraMessage == CameraMessage.Unavailable) {
                Text("端末のカメラ設定と、カメラアプリを利用できるか確認してください")
                OutlinedButton(onClick = onOpenCameraSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("端末の設定を開く")
                }
            }
            Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Text("端末から1枚選ぶ")
            }
            OutlinedButton(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
                Text("写真を撮る")
            }
            if (hasSelection) {
                Text("画像を1枚選択しました")
                Button(
                    onClick = onUseSelection,
                    enabled = analysisState !is ImageAnalysisState.Processing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("この画像を使用する")
                }
                OutlinedButton(onClick = onDiscardSelection, modifier = Modifier.fillMaxWidth()) {
                    Text("選び直す")
                }
            }
        }
    }
}

@Composable
fun ImagePreviewContent(
    image: PreprocessedImage,
    onSend: () -> Unit,
    onReselect: () -> Unit,
    onManualFallback: () -> Unit = {},
    sending: Boolean = false,
    analyzing: Boolean = false,
    failure: ImageAnalysisState.Failed? = null,
) {
    val bitmap by produceState<Bitmap?>(null, image.file) {
        value = withContext(Dispatchers.IO) { decodePreview(image.file.path) }
    }
    DisposableEffect(bitmap) {
        val bitmapToRecycle = bitmap
        onDispose { bitmapToRecycle?.recycle() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "送信画像の確認")
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AIへ実際に送信する変換後画像です")
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "AIへ送信する変換後画像のプレビュー",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        .aspectRatio(image.width.toFloat() / image.height),
                )
            } ?: Text("プレビューを読み込めませんでした")
            if (image.lowResolutionWarning) {
                Text(
                    "画像の短辺が480px未満のため、認識精度が低下する可能性があります",
                    modifier = Modifier.semantics {
                        contentDescription = "低解像度の警告。画像の短辺が480px未満のため、認識精度が低下する可能性があります"
                    },
                )
            }
            if (sending) Text(if (analyzing) "AIで画像を解析しています" else "画像を送信しています")
            failure?.let {
                Text(it.message)
                it.quotaType?.let { quota -> Text("上限種別: ${quotaLabel(quota)}") }
                it.retryAt?.let { retryAt -> Text("再利用日時: $retryAt") }
            }
            Button(onClick = onSend, enabled = !sending && bitmap != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (failure != null) "再試行する" else "この画像を送信する")
            }
            OutlinedButton(onClick = onReselect, modifier = Modifier.fillMaxWidth()) {
                Text(if (sending) "キャンセル" else "選び直す")
            }
            if (failure != null) {
                OutlinedButton(onClick = onManualFallback, modifier = Modifier.fillMaxWidth()) {
                    Text("手動入力に切り替える")
                }
            }
        }
    }
}

private fun decodePreview(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 1_000_000L) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}

private fun quotaLabel(value: String): String = when (value) {
    "SHORT" -> "短時間"
    "DAILY" -> "1日"
    "MONTHLY" -> "1か月"
    "GLOBAL" -> "全体"
    "BUDGET" -> "予算"
    else -> "不明"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
