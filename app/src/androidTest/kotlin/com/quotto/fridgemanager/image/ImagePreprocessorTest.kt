package com.quotto.fridgemanager.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class ImagePreprocessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preprocessor = ImagePreprocessor(context)

    @Test
    fun `JPEGを送信上限内へ正規化してメタデータを除去する`() {
        val source = jpeg(width = 2400, height = 1800, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        var released = false

        val result = preprocess(ImageInputAsset(Uri.fromFile(source)) { released = true; source.delete() })
        result.use {
            val bounds = bounds(it.file)
            assertTrue(maxOf(bounds.first, bounds.second) <= 2048)
            assertTrue(bounds.first.toLong() * bounds.second <= 4_000_000L)
            assertTrue(it.file.length() <= 3_145_728L)
            assertEquals(1536, bounds.first)
            assertEquals(2048, bounds.second)
            val exif = ExifInterface(it.file)
            assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertEquals(0, exif.rotationDegrees)
            assertFalse(exif.isFlipped)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        }
        assertTrue(released)
        assertFalse(source.exists())
    }

    @Test
    fun `PNGの透過領域を白背景のRGB JPEGへ変換する`() {
        val source = File.createTempFile("transparent-", ".png", context.cacheDir)
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            FileOutputStream(source).use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() }).use {
            assertTrue(it.file.inputStream().use { input -> input.read() == 0xff && input.read() == 0xd8 })
            val decoded = BitmapFactory.decodeFile(it.file.path)
            assertEquals(Color.WHITE, decoded.getPixel(0, 0))
            assertTrue(it.lowResolutionWarning)
            decoded.recycle()
        }
    }

    @Test
    fun `短辺479は警告し480境界は警告しない`() {
        listOf(479 to 700, 700 to 479).forEach { (width, height) ->
            val source = jpeg(width, height, ExifInterface.ORIENTATION_NORMAL)
            preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() }).use {
                assertTrue(it.lowResolutionWarning)
            }
        }
        val source = jpeg(480, 700, ExifInterface.ORIENTATION_NORMAL)
        preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() }).use {
            assertFalse(it.lowResolutionWarning)
        }
    }

    @Test
    fun `APNGとanimated WebPをデコード前に拒否する`() {
        val apng = File.createTempFile("animated-", ".png", context.cacheDir).apply {
            outputStream().use { out ->
                out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                out.write(byteArrayOf(0, 0, 0, 0))
                out.write("acTL".encodeToByteArray())
                out.write(byteArrayOf(0, 0, 0, 0))
            }
        }
        val webp = File.createTempFile("animated-", ".webp", context.cacheDir).apply {
            outputStream().use { out ->
                out.write("RIFF".encodeToByteArray())
                out.write(byteArrayOf(22, 0, 0, 0))
                out.write("WEBPVP8X".encodeToByteArray())
                out.write(byteArrayOf(10, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            }
        }
        listOf(apng, webp).forEach { source ->
            val failure = runCatching {
                preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() })
            }.exceptionOrNull()
            assertTrue(failure is ImagePreprocessingException.UnsupportedOrCorrupt)
            assertFalse(source.exists())
        }
    }

    @Test
    fun `末尾が切れたJPEGを部分デコードせず拒否する`() {
        val source = jpeg(64, 64, ExifInterface.ORIENTATION_NORMAL)
        java.io.RandomAccessFile(source, "rw").use { it.setLength(it.length() - 2) }
        val failure = runCatching {
            preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() })
        }.exceptionOrNull()
        assertTrue(failure is ImagePreprocessingException.UnsupportedOrCorrupt)
        assertFalse(source.exists())
    }

    @Test
    fun `PNG chunkのCRC破損を拒否する`() {
        val source = File.createTempFile("crc-", ".png", context.cacheDir)
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
            FileOutputStream(source).use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        java.io.RandomAccessFile(source, "rw").use { file ->
            file.seek(8)
            while (file.filePointer + 12 <= file.length()) {
                val length = file.readInt()
                val type = ByteArray(4).also(file::readFully).decodeToString()
                file.seek(file.filePointer + length)
                if (type == "IDAT") {
                    val crcPosition = file.filePointer
                    file.seek(crcPosition + 3)
                    val value = file.read()
                    file.seek(crcPosition + 3)
                    file.write(value.xor(0x01))
                    break
                }
                file.seek(file.filePointer + 4)
            }
        }
        val failure = runCatching {
            preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() })
        }.exceptionOrNull()
        assertTrue(failure is ImagePreprocessingException.UnsupportedOrCorrupt)
        assertFalse(source.exists())
    }

    @Test
    fun `成果物完成直後にcallerがcancelされても出力一時ファイルを残さない`() = runBlocking {
        val source = jpeg(64, 64, ExifInterface.ORIENTATION_NORMAL)
        val job = Job()
        val processor = ImagePreprocessor(context, afterProcessing = { job.cancel() })
        val before = uploadFiles()
        CoroutineScope(job + Dispatchers.Default).launch {
            processor.process(ImageInputAsset(Uri.fromFile(source)) { source.delete() })
        }.join()
        assertFalse(source.exists())
        assertEquals(before, uploadFiles())
    }

    @Test
    fun `worker開始前にcancelされても入力所有権を解放する`() = runBlocking {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued += block
            }
        }
        var releases = 0
        val asset = ImageInputAsset(Uri.EMPTY) { releases++ }
        val job = Job()
        CoroutineScope(job + Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            ImagePreprocessor(context, workerDispatcher = dispatcher).process(asset)
        }
        assertTrue(queued.isNotEmpty())
        job.cancel()
        assertEquals(1, releases)
        queued.forEach(Runnable::run)
        assertEquals(1, releases)
    }

    @Test
    fun `MIME偽装と破損画像を拒否して入力を解放する`() {
        val source = File.createTempFile("fake-", ".jpg", context.cacheDir).apply { writeText("not an image") }
        var released = false
        val failure = runCatching {
            preprocess(ImageInputAsset(Uri.fromFile(source)) { released = true; source.delete() })
        }.exceptionOrNull()
        assertTrue(failure is ImagePreprocessingException.UnsupportedOrCorrupt)
        assertTrue(released)
        assertFalse(source.exists())
    }

    @Test
    fun `実JPEGでも宣言MIMEがPNGなら拒否する`() {
        val directory = File(context.cacheDir, "image-capture").apply { mkdirs() }
        val source = File(directory, "spoof.png")
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            FileOutputStream(source).use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
            recycle()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val failure = runCatching {
            preprocess(ImageInputAsset(uri) { source.delete() })
        }.exceptionOrNull()
        assertTrue(failure is ImagePreprocessingException.UnsupportedOrCorrupt)
        assertFalse(source.exists())
    }

    @Test
    fun `EXIF orientation全8値を画素へ反映する`() {
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        for (orientation in 1..8) {
            val source = quadrantJpeg(orientation)
            preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() }).use { result ->
                val bitmap = BitmapFactory.decodeFile(result.file.path)
                val actual = listOf(
                    bitmap.getPixel(4, 4),
                    bitmap.getPixel(bitmap.width - 5, 4),
                    bitmap.getPixel(4, bitmap.height - 5),
                    bitmap.getPixel(bitmap.width - 5, bitmap.height - 5),
                ).map(::nearestPrimary)
                assertEquals("orientation=$orientation", expected[orientation - 1], actual)
                val swapsAxes = orientation >= 5
                assertEquals(swapsAxes, bitmap.height > bitmap.width)
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `20MiB超過をデコード前に拒否する`() {
        val source = File.createTempFile("large-", ".jpg", context.cacheDir)
        source.outputStream().use { out ->
            out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            out.channel.position(20L * 1024 * 1024)
            out.write(0)
        }
        val failure = runCatching {
            preprocess(ImageInputAsset(Uri.fromFile(source)) { source.delete() })
        }.exceptionOrNull()
        assertTrue(failure is ImagePreprocessingException.SourceTooLarge)
        assertFalse(source.exists())
    }

    private fun jpeg(width: Int, height: Int, orientation: Int): File {
        val file = File.createTempFile("source-", ".jpg", context.cacheDir)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            FileOutputStream(file).use { compress(Bitmap.CompressFormat.JPEG, 92, it) }
            recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_MAKE, "private-device")
            setLatLong(35.0, 139.0)
            saveAttributes()
        }
        return file
    }

    private fun preprocess(asset: ImageInputAsset): PreprocessedImage = runBlocking {
        preprocessor.process(asset)
    }

    private fun uploadFiles(): Set<String> = context.cacheDir.listFiles()
        .orEmpty()
        .filter { it.name.startsWith("image-upload-") }
        .map { it.canonicalPath }
        .toSet()

    private fun quadrantJpeg(orientation: Int): File {
        val file = File.createTempFile("orientation-", ".jpg", context.cacheDir)
        Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val paint = Paint()
            paint.color = Color.RED; canvas.drawRect(0f, 0f, 40f, 30f, paint)
            paint.color = Color.GREEN; canvas.drawRect(40f, 0f, 80f, 30f, paint)
            paint.color = Color.BLUE; canvas.drawRect(0f, 30f, 40f, 60f, paint)
            paint.color = Color.YELLOW; canvas.drawRect(40f, 30f, 80f, 60f, paint)
            FileOutputStream(file).use { compress(Bitmap.CompressFormat.JPEG, 100, it) }
            recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun nearestPrimary(color: Int): Int = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        .minBy { candidate ->
            kotlin.math.abs(Color.red(color) - Color.red(candidate)) +
                kotlin.math.abs(Color.green(color) - Color.green(candidate)) +
                kotlin.math.abs(Color.blue(color) - Color.blue(candidate))
        }

    private fun bounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth to options.outHeight
    }
}
