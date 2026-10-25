package app.opaquevault.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes vault images in memory only. Honors EXIF orientation, bounds output size, and rejects
 * decompression bombs before allocating pixels.
 */
object ImageCodec {
    const val THUMBNAIL_MAX_DIMENSION = 384
    private const val VIEWER_MAX_DIMENSION = 4096
    private const val MAX_SOURCE_PIXELS = 150_000_000L
    private const val THUMBNAIL_JPEG_QUALITY = 82

    /** Full-screen bitmap for the viewer; hardware-backed when possible so it stays off the Java heap. */
    fun decodeForViewer(bytes: ByteArray): Bitmap = decode(bytes, VIEWER_MAX_DIMENSION, allowHardware = true)

    /** Decodes a stored (already small) thumbnail JPEG for the grid. */
    fun decodeThumbnail(jpeg: ByteArray): Bitmap = decode(jpeg, THUMBNAIL_MAX_DIMENSION * 2, allowHardware = true)

    fun thumbnailFromBytes(bytes: ByteArray): ByteArray =
        encodeThumbnail(decode(bytes, THUMBNAIL_MAX_DIMENSION, allowHardware = false))

    /** Builds the thumbnail straight from the source app while importing, avoiding a second decrypt. */
    fun thumbnailFromUri(resolver: ContentResolver, uri: Uri, maxSourceBytes: Long): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                configure(decoder, info.size.width, info.size.height, THUMBNAIL_MAX_DIMENSION, allowHardware = false)
            }
            return encodeThumbnail(bitmap)
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxSourceBytes) throw IOException("Image too large for a thumbnail")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Image cannot be opened")
        return thumbnailFromBytes(bytes).also { bytes.fill(0) }
    }

    private fun decode(bytes: ByteArray, maxDimension: Int, allowHardware: Boolean): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                configure(decoder, info.size.width, info.size.height, maxDimension, allowHardware)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkDimensions(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Unsupported image encoding")
        return applyExifOrientation(decoded, bytes)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun configure(decoder: ImageDecoder, width: Int, height: Int, maxDimension: Int, allowHardware: Boolean) {
        checkDimensions(width, height)
        decoder.setTargetSampleSize(sampleSize(width, height, maxDimension))
        decoder.allocator = if (allowHardware) ImageDecoder.ALLOCATOR_DEFAULT else ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }

    private fun checkDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) throw IOException("Invalid image dimensions")
        if (width.toLong() * height.toLong() > MAX_SOURCE_PIXELS) throw IOException("Image dimensions exceed the safety limit")
    }

    /** Largest power-of-two sample that keeps the longest side at or above [maxDimension]. */
    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    private fun encodeThumbnail(source: Bitmap): ByteArray {
        val longest = max(source.width, source.height)
        val scaled = if (longest > THUMBNAIL_MAX_DIMENSION) {
            val factor = THUMBNAIL_MAX_DIMENSION.toFloat() / longest
            source.scale(
                (source.width * factor).roundToInt().coerceAtLeast(1),
                (source.height * factor).roundToInt().coerceAtLeast(1),
            )
        } else {
            source
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, output)) {
                    throw IOException("Thumbnail encoding failed")
                }
                output.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
