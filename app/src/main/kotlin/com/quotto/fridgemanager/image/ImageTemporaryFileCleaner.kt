package com.quotto.fridgemanager.image

import java.io.File

/** 強制終了でcloseできなかった画像一時ファイルだけを、別の実行を妨げない期限後に回収する。 */
class ImageTemporaryFileCleaner(private val cacheDirectory: File) {
    fun cleanup(nowMillis: Long = System.currentTimeMillis(), minimumAgeMillis: Long = DEFAULT_MINIMUM_AGE) {
        val root = runCatching { cacheDirectory.canonicalFile }.getOrNull() ?: return
        root.listFiles().orEmpty().forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            val recognized = PREFIXES.any(canonical.name::startsWith)
            val oldEnough = nowMillis - canonical.lastModified() >= minimumAgeMillis
            if (canonical.parentFile == root && canonical.isFile && recognized && oldEnough) canonical.delete()
        }
    }

    private companion object {
        const val DEFAULT_MINIMUM_AGE = 60L * 60 * 1_000
        val PREFIXES = listOf("image-source-", "image-upload-")
    }
}
