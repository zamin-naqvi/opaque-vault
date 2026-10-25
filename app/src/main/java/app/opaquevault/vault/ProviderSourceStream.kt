package app.opaquevault.vault

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.math.min

/** The app that shared the item stopped sending data (for example its process was frozen). */
class SourceStalledException(message: String) : IOException(message)

/**
 * Reads a shared item without ever blocking indefinitely.
 *
 * Why this exists: on Samsung phones the system freezer ("Freecess") freezes Samsung Gallery as soon
 * as the vault is in front. Gallery serves shared files through a pipe written by its own process,
 * so a frozen Gallery stops the stream mid-file and a plain read() blocks forever; closing the
 * stream from another thread does not wake it, which is why Cancel used to hang.
 *
 * Here the provider is held through a *stable* ContentProviderClient (binding its process to this
 * foreground app), reads wait with poll() in short slices so cancellation is checked every 250 ms,
 * a cheap synchronous binder call nudges a frozen provider awake while waiting, and a stall that
 * persists is reported as [SourceStalledException] instead of hanging.
 */
internal class ProviderSourceStream private constructor(
    private val resolver: ContentResolver,
    private val client: ContentProviderClient?,
    private val uri: Uri,
    private val descriptor: AssetFileDescriptor,
    private val shouldContinue: () -> Boolean,
) : InputStream() {
    private val fileDescriptor = descriptor.parcelFileDescriptor.fileDescriptor
    private var remaining: Long =
        if (descriptor.declaredLength >= 0) descriptor.declaredLength else Long.MAX_VALUE
    private var lastDataAt = SystemClock.elapsedRealtime()
    private var lastNudgeAt = 0L

    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return -1
        val wanted = min(length.toLong(), remaining).toInt()
        while (true) {
            if (closed) throw InterruptedIOException("Import stopped")
            if (!shouldContinue()) throw InterruptedIOException("Import stopped")
            val poll = StructPollfd().apply {
                fd = fileDescriptor
                events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), POLL_SLICE_MILLIS)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) 0 else throw IOException("Unable to read the shared item", error)
            }
            if (ready > 0) {
                val count = try {
                    Os.read(fileDescriptor, buffer, offset, wanted)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR || error.errno == OsConstants.EAGAIN) continue
                    if (closed) throw InterruptedIOException("Import stopped")
                    throw IOException("Unable to read the shared item", error)
                }
                if (count <= 0) return -1
                remaining -= count
                lastDataAt = SystemClock.elapsedRealtime()
                return count
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastNudgeAt >= NUDGE_EVERY_MILLIS) {
                lastNudgeAt = now
                nudgeProvider()
            }
            if (now - lastDataAt >= STALL_TIMEOUT_MILLIS) {
                throw SourceStalledException("The sharing app stopped sending this file")
            }
        }
    }

    /** A synchronous binder call; Samsung's freezer briefly unfreezes the target to serve it. */
    private fun nudgeProvider() {
        try {
            client?.getType(uri) ?: resolver.getType(uri)
        } catch (_: Exception) {
            // Only a nudge.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            descriptor.close()
        } catch (_: Exception) {
            // Already closed.
        } finally {
            try {
                client?.close()
            } catch (_: Exception) {
                // Already released.
            }
        }
    }

    companion object {
        private const val POLL_SLICE_MILLIS = 250
        private const val NUDGE_EVERY_MILLIS = 500L
        private const val STALL_TIMEOUT_MILLIS = 30_000L
        private const val OPEN_TIMEOUT_MILLIS = 60_000L

        fun open(resolver: ContentResolver, uri: Uri, shouldContinue: () -> Boolean): ProviderSourceStream {
            val client = try {
                resolver.acquireContentProviderClient(uri)
            } catch (_: Exception) {
                null
            }
            val signal = CancellationSignal()
            val startedAt = SystemClock.elapsedRealtime()
            val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            // Cancels a slow open (some providers convert the file before handing it over).
            val watchdog = Thread({
                while (!opened.get() && !signal.isCanceled) {
                    val expired = SystemClock.elapsedRealtime() - startedAt > OPEN_TIMEOUT_MILLIS
                    if (!shouldContinue() || expired) signal.cancel()
                    try {
                        Thread.sleep(POLL_SLICE_MILLIS.toLong())
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "vault-open-watchdog").apply { isDaemon = true }
            watchdog.start()
            try {
                val descriptor = (client?.openAssetFile(uri, "r", signal) ?: resolver.openAssetFileDescriptor(uri, "r", signal))
                    ?: throw FileNotFoundException("The shared item could not be opened")
                if (descriptor.startOffset > 0L) {
                    Os.lseek(descriptor.parcelFileDescriptor.fileDescriptor, descriptor.startOffset, OsConstants.SEEK_SET)
                }
                return ProviderSourceStream(resolver, client, uri, descriptor, shouldContinue)
            } catch (error: OperationCanceledException) {
                client?.close()
                if (!shouldContinue()) throw InterruptedIOException("Import stopped")
                throw SourceStalledException("The sharing app did not hand over the file")
            } catch (error: Throwable) {
                client?.close()
                throw error
            } finally {
                opened.set(true)
                watchdog.interrupt()
            }
        }
    }
}
