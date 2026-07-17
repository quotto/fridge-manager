package com.quotto.fridgemanager.ui.feature.image

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.image.CameraImageStore
import com.quotto.fridgemanager.ui.component.ScreenHeader
import java.io.Closeable

enum class CameraMessage {
    None,
    Unavailable,
}

/** #19 の前処理へ一時画像の所有権を移す境界。利用側は処理終了時に必ず close する。 */
class ImageInputAsset(
    val uri: Uri,
    private val release: () -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}

@Composable
fun ImageAnalysisScreen(
    onManualRegistration: () -> Unit,
    onImageReadyForPreprocessing: (ImageInputAsset) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
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

    ImageInputContent(
        cameraMessage = cameraMessage,
        hasSelection = selectedUri != null,
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
        onManualRegistration = onManualRegistration,
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
                    onImageReadyForPreprocessing(ImageInputAsset(parsed) { captured?.close() })
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
    onManualRegistration: () -> Unit,
    onDiscardSelection: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onUseSelection: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "画像解析")
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("解析する画像を1枚選択してください")
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
            OutlinedButton(onClick = onManualRegistration, modifier = Modifier.fillMaxWidth()) {
                Text("手動で登録する")
            }
            if (hasSelection) {
                Text("画像を1枚選択しました")
                Button(onClick = onUseSelection, modifier = Modifier.fillMaxWidth()) {
                    Text("この画像を使用する")
                }
                OutlinedButton(onClick = onDiscardSelection, modifier = Modifier.fillMaxWidth()) {
                    Text("選び直す")
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
