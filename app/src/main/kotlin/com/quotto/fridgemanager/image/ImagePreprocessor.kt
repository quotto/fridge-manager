package com.quotto.fridgemanager.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class ImagePreprocessingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class SourceTooLarge : ImagePreprocessingException("画像ファイルは20 MiB以下にしてください")
    class TooManyPixels : ImagePreprocessingException("画像の総画素数は40 MP以下にしてください")
    class UnsupportedOrCorrupt : ImagePreprocessingException("JPEG、PNG、WebPの静止画像を選択してください")
    class ProcessingFailed(cause: Throwable? = null) : ImagePreprocessingException("画像を処理できませんでした", cause)
}

class PreprocessedImage internal constructor(
    val file: File,
    val width: Int,
    val height: Int,
    val lowResolutionWarning: Boolean,
) : Closeable {
    override fun close() {
        file.delete()
    }
}

/** 信頼できない Content URI を、送信可能なメタデータなし RGB JPEG へ正規化する。 */
class ImagePreprocessor(
    private val context: Context,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val afterProcessing: () -> Unit = {},
) {
    suspend fun process(asset: ImageInputAsset): PreprocessedImage = suspendCancellableCoroutine { continuation ->
        // キュー待ち中のキャンセルでも撮影一時ファイルの所有権を確実に返す。
        continuation.invokeOnCancellation { asset.close() }
        CoroutineScope(continuation.context + workerDispatcher).launch {
            val processed = runCatching { processOnWorker(asset) }
            processed.fold(
                onSuccess = { result ->
                    var resumed = false
                    try {
                        afterProcessing()
                        continuation.resume(result) { _, undelivered, _ -> undelivered.close() }
                        resumed = true
                    } finally {
                        if (!resumed) result.close()
                    }
                },
                onFailure = { error -> continuation.resumeWith(Result.failure(error)) },
            )
        }
    }

    private fun processOnWorker(asset: ImageInputAsset): PreprocessedImage {
        var source: File? = null
        var output: File? = null
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var normalized: Bitmap? = null
        try {
            source = File.createTempFile("image-source-", ".bin", context.cacheDir)
            copySource(asset.uri, source)
            val format = validateFormat(asset.uri, source)
            rejectAnimation(source, format)
            val bounds = decodeBounds(source)
            val sourcePixels = bounds.first.toLong() * bounds.second.toLong()
            if (sourcePixels > MAX_SOURCE_PIXELS) throw ImagePreprocessingException.TooManyPixels()

            val sample = calculateDecodeSample(bounds.first, bounds.second)
            decoded = decode(source, sample)
            val orientation = runCatching { ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            oriented = orient(decoded, orientation)
            if (oriented !== decoded) decoded.recycle().also { decoded = null }
            normalized = normalizeDimensionsAndColor(oriented)
            if (normalized !== oriented) oriented.recycle().also { oriented = null }

            output = File.createTempFile("image-upload-", ".jpg", context.cacheDir)
            val encoded = encodeWithinLimit(normalized, output)
            if (encoded !== normalized) normalized.recycle().also { normalized = null }
            return PreprocessedImage(
                file = output,
                width = encoded.width,
                height = encoded.height,
                lowResolutionWarning = min(encoded.width, encoded.height) < MIN_RECOMMENDED_SHORT_EDGE,
            ).also {
                encoded.recycle()
                output = null
            }
        } catch (expected: ImagePreprocessingException) {
            throw expected
        } catch (error: OutOfMemoryError) {
            throw ImagePreprocessingException.ProcessingFailed()
        } catch (error: Exception) {
            throw ImagePreprocessingException.ProcessingFailed(error)
        } finally {
            listOfNotNull(decoded, oriented, normalized).distinct().forEach { if (!it.isRecycled) it.recycle() }
            source?.delete()
            output?.delete()
            asset.close()
        }
    }

    private fun copySource(uri: Uri, destination: File) {
        val declaredLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredLength != null && declaredLength > MAX_SOURCE_BYTES) throw ImagePreprocessingException.SourceTooLarge()
        val input = context.contentResolver.openInputStream(uri) ?: throw ImagePreprocessingException.UnsupportedOrCorrupt()
        input.use { source ->
            FileOutputStream(destination).use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SOURCE_BYTES) throw ImagePreprocessingException.SourceTooLarge()
                    target.write(buffer, 0, read)
                }
            }
        }
    }

    private fun validateFormat(uri: Uri, source: File): ImageFormat {
        val header = ByteArray(16)
        val count = source.inputStream().use { it.read(header) }
        val format = when {
            count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> ImageFormat.Jpeg
            count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> ImageFormat.Png
            count >= 16 && header.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
                header.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) &&
                WEBP_CHUNKS.any { header.copyOfRange(12, 16).contentEquals(it) } -> ImageFormat.WebP
            else -> throw ImagePreprocessingException.UnsupportedOrCorrupt()
        }
        val declared = context.contentResolver.getType(uri)?.lowercase()
        if (declared != null && declared !in format.mimeTypes) throw ImagePreprocessingException.UnsupportedOrCorrupt()
        return format
    }

    private fun rejectAnimation(source: File, format: ImageFormat) {
        when (format) {
            ImageFormat.Jpeg -> validateJpegEnd(source)
            ImageFormat.Png -> validatePng(source)
            ImageFormat.WebP -> validateWebP(source)
        }
    }

    private fun validateJpegEnd(source: File) {
        if (source.length() < 4) throw ImagePreprocessingException.UnsupportedOrCorrupt()
        RandomAccessFile(source, "r").use { file ->
            file.seek(file.length() - 2)
            if (file.readUnsignedByte() != 0xff || file.readUnsignedByte() != 0xd9) {
                throw ImagePreprocessingException.UnsupportedOrCorrupt()
            }
        }
    }

    private fun validatePng(source: File) {
        RandomAccessFile(source, "r").use { file ->
            file.seek(PNG_SIGNATURE.size.toLong())
            var foundEnd = false
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (file.filePointer + 12 <= file.length()) {
                val length = file.readInt().toLong() and 0xffff_ffffL
                val typeBytes = ByteArray(4).also(file::readFully)
                val type = typeBytes.decodeToString()
                val end = file.filePointer + length + 4 // CRC
                if (length > MAX_SOURCE_BYTES || end > file.length()) throw ImagePreprocessingException.UnsupportedOrCorrupt()
                val crc = CRC32().apply { update(typeBytes) }
                var remaining = length
                while (remaining > 0) {
                    val read = minOf(remaining, buffer.size.toLong()).toInt()
                    file.readFully(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    remaining -= read
                }
                val storedCrc = file.readInt().toLong() and 0xffff_ffffL
                if (storedCrc != crc.value) throw ImagePreprocessingException.UnsupportedOrCorrupt()
                if (type == "acTL") throw ImagePreprocessingException.UnsupportedOrCorrupt()
                if (type == "IEND") {
                    foundEnd = length == 0L && file.filePointer == file.length()
                    break
                }
            }
            if (!foundEnd) throw ImagePreprocessingException.UnsupportedOrCorrupt()
        }
    }

    private fun validateWebP(source: File) {
        RandomAccessFile(source, "r").use { file ->
            if (file.length() < 20) throw ImagePreprocessingException.UnsupportedOrCorrupt()
            file.seek(4)
            val declaredLength = readLittleEndianUInt(file) + 8
            if (declaredLength != file.length()) throw ImagePreprocessingException.UnsupportedOrCorrupt()
            file.seek(12)
            var imageChunkFound = false
            while (file.filePointer + 8 <= file.length()) {
                val type = ByteArray(4).also(file::readFully).decodeToString()
                val length = readLittleEndianUInt(file)
                val dataStart = file.filePointer
                val paddedEnd = dataStart + length + (length and 1L)
                if (length > MAX_SOURCE_BYTES || paddedEnd > file.length()) throw ImagePreprocessingException.UnsupportedOrCorrupt()
                if (type == "ANIM" || type == "ANMF") throw ImagePreprocessingException.UnsupportedOrCorrupt()
                if (type == "VP8X") {
                    if (length < 10) throw ImagePreprocessingException.UnsupportedOrCorrupt()
                    if (file.readUnsignedByte() and WEBP_ANIMATION_FLAG != 0) throw ImagePreprocessingException.UnsupportedOrCorrupt()
                }
                if (type in setOf("VP8 ", "VP8L")) imageChunkFound = true
                file.seek(paddedEnd)
            }
            if (!imageChunkFound && file.length() > 16 && runCatching {
                    file.seek(12); ByteArray(4).also(file::readFully).decodeToString() == "VP8X"
                }.getOrDefault(false).not()
            ) throw ImagePreprocessingException.UnsupportedOrCorrupt()
            if (file.filePointer != file.length()) throw ImagePreprocessingException.UnsupportedOrCorrupt()
        }
    }

    private fun readLittleEndianUInt(file: RandomAccessFile): Long {
        var result = 0L
        repeat(4) { shift -> result = result or (file.readUnsignedByte().toLong() shl (shift * 8)) }
        return result
    }

    private fun decodeBounds(source: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw ImagePreprocessingException.UnsupportedOrCorrupt()
        return options.outWidth to options.outHeight
    }

    private fun calculateDecodeSample(width: Int, height: Int): Int {
        var sample = 1
        while (ceil(width.toDouble() / sample).toLong() * ceil(height.toDouble() / sample).toLong() > MAX_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
    }

    private fun decode(source: File, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeFile(source.path, options) ?: throw ImagePreprocessingException.UnsupportedOrCorrupt()
    }

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun normalizeDimensionsAndColor(source: Bitmap): Bitmap {
        val pixels = source.width.toLong() * source.height
        val scale = minOf(
            1.0,
            MAX_OUTPUT_EDGE.toDouble() / maxOf(source.width, source.height),
            sqrt(MAX_OUTPUT_PIXELS.toDouble() / pixels),
        )
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), null)
        }
        return result
    }

    private fun encodeWithinLimit(initial: Bitmap, output: File): Bitmap {
        var current = initial
        var succeeded = false
        try {
            repeat(MAX_RESIZE_ATTEMPTS + 1) { attempt ->
                for (quality in JPEG_QUALITIES) {
                    if (compressCapped(current, output, quality)) {
                        succeeded = true
                        return current
                    }
                }
                if (attempt == MAX_RESIZE_ATTEMPTS || current.width == 1 && current.height == 1) {
                    throw ImagePreprocessingException.ProcessingFailed()
                }
                val smaller = Bitmap.createScaledBitmap(
                    current,
                    maxOf(1, (current.width * RESIZE_FACTOR).toInt()),
                    maxOf(1, (current.height * RESIZE_FACTOR).toInt()),
                    true,
                )
                if (current !== initial) current.recycle()
                current = smaller
            }
            throw ImagePreprocessingException.ProcessingFailed()
        } finally {
            if (!succeeded && current !== initial && !current.isRecycled) current.recycle()
        }
    }

    private fun compressCapped(bitmap: Bitmap, output: File, quality: Int): Boolean {
        return try {
            FileOutputStream(output, false).use { stream ->
                val capped = CappedOutputStream(stream, MAX_OUTPUT_BYTES)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, capped)) return false
            }
            output.length() in 1..MAX_OUTPUT_BYTES
        } catch (_: OutputLimitExceeded) {
            output.delete()
            false
        }
    }

    private enum class ImageFormat(val mimeTypes: Set<String>) {
        Jpeg(setOf("image/jpeg", "image/jpg")),
        Png(setOf("image/png")),
        WebP(setOf("image/webp")),
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
        const val MAX_SOURCE_PIXELS = 40_000_000L
        const val MAX_DECODE_PIXELS = 8_000_000L
        const val MAX_OUTPUT_BYTES = 3L * 1024 * 1024
        const val MAX_OUTPUT_EDGE = 2048
        const val MAX_OUTPUT_PIXELS = 4_000_000L
        const val MIN_RECOMMENDED_SHORT_EDGE = 480
        const val MAX_RESIZE_ATTEMPTS = 12
        const val RESIZE_FACTOR = 0.9
        const val WEBP_ANIMATION_FLAG = 0x02
        val JPEG_QUALITIES = intArrayOf(85, 80, 75)
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val WEBP_CHUNKS = listOf("VP8 ", "VP8L", "VP8X").map(String::encodeToByteArray)
    }
}

private class OutputLimitExceeded : IOException()

private class CappedOutputStream(output: OutputStream, private val maximum: Long) : FilterOutputStream(output) {
    private var count = 0L
    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        count++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        out.write(buffer, offset, length)
        count += length
    }

    private fun ensureCapacity(additional: Int) {
        if (count + additional > maximum) throw OutputLimitExceeded()
    }
}
