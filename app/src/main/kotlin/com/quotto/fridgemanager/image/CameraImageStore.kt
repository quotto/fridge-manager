package com.quotto.fridgemanager.image

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.util.UUID

class CameraImageStore(private val context: Context) {
    private val captureDirectory = File(context.cacheDir, DIRECTORY)

    fun create(): CameraImage {
        check(captureDirectory.exists() || captureDirectory.mkdirs()) {
            "撮影用一時領域を作成できません"
        }
        val file = File(captureDirectory, "${UUID.randomUUID()}.jpg")
        check(file.createNewFile()) { "撮影用一時ファイルを作成できません" }
        return CameraImage(
            uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
            file = file,
        )
    }

    fun restore(path: String, uri: String): CameraImage? {
        val file = File(path)
        val canonicalDirectory = captureDirectory.canonicalFile
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (canonicalFile.parentFile != canonicalDirectory || !canonicalFile.exists()) return null
        val restoredUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", canonicalFile)
        }.getOrNull() ?: return null
        if (restoredUri.toString() != uri) return null
        return CameraImage(restoredUri, canonicalFile)
    }

    fun cleanupOrphans(excluding: File? = null, nowMillis: Long = System.currentTimeMillis()) {
        val excludedPath = excluding?.runCatching { canonicalPath }?.getOrNull()
        captureDirectory.listFiles()?.forEach { file ->
            if (file.runCatching { canonicalPath }.getOrNull() != excludedPath &&
                nowMillis - file.lastModified() >= ORPHAN_MAX_AGE_MILLIS
            ) {
                file.delete()
            }
        }
    }

    private companion object {
        const val DIRECTORY = "image-capture"
        const val ORPHAN_MAX_AGE_MILLIS = 60L * 60L * 1_000L
    }
}

data class CameraImage(val uri: Uri, val file: File) : Closeable {
    override fun close() {
        file.delete()
    }
}
