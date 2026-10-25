package app.opaquevault.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import app.opaquevault.vault.VaultMediaReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads a video's duration and a representative frame, either from the source app while
 * importing or straight from the encrypted vault (through [VaultMediaReader], so no plaintext file
 * is ever created).
 */
object VideoFrames {
    class Probe(val durationMillis: Long, val thumbnailJpeg: ByteArray?)

    fun probe(context: Context, uri: Uri): Probe = withRetriever { retriever ->
        retriever.setDataSource(context, uri)
        read(retriever)
    }

    fun probe(reader: VaultMediaReader): Probe = withRetriever { retriever ->
        retriever.setDataSource(ReaderDataSource(reader))
        read(retriever)
    }

    private fun read(retriever: MediaMetadataRetriever): Probe {
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        // A frame a little into the video avoids the black first frame many clips start with.
        val timeUs = (duration * 1000L / 10L).coerceIn(0L, 3_000_000L)
        val frame = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ImageCodec.THUMBNAIL_MAX_DIMENSION,
                    ImageCodec.THUMBNAIL_MAX_DIMENSION,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: RuntimeException) {
            null
        }
        return Probe(duration, frame?.let(::encode))
    }

    private fun encode(frame: Bitmap): ByteArray {
        val longest = max(frame.width, frame.height)
        val scaled = if (longest > ImageCodec.THUMBNAIL_MAX_DIMENSION) {
            val factor = ImageCodec.THUMBNAIL_MAX_DIMENSION.toFloat() / longest
            frame.scale(
                (frame.width * factor).roundToInt().coerceAtLeast(1),
                (frame.height * factor).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            frame
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)) throw IOException("Thumbnail encoding failed")
                output.toByteArray()
            }
        } finally {
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
        }
    }

    private inline fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        return try {
            block(retriever)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Nothing useful to do.
            }
        }
    }

    private class ReaderDataSource(private val reader: VaultMediaReader) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            reader.read(position, buffer, offset, size)

        override fun getSize(): Long = reader.size

        override fun close() = Unit // The caller owns the reader.
    }
}
