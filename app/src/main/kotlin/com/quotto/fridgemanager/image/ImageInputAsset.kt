package com.quotto.fridgemanager.image

import android.net.Uri
import java.io.Closeable

/** 入力 URI と、撮影一時ファイルなど呼出元が所有する資源を一緒に受け渡す。 */
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
