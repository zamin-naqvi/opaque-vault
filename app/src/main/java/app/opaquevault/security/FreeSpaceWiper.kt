package app.opaquevault.security

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * Overwrites free space on internal storage so blocks left behind by deleted photos are filled with
 * random data. App storage and shared photo storage live on the same /data partition, so no special
 * access is needed.
 *
 * Limits, stated honestly in the UI: flash controllers remap writes (wear leveling) and keep spare
 * blocks no app can address, so this is defense in depth on top of Android's per-file encryption,
 * not a forensic guarantee. One pass on flash is as effective as several; more passes only add wear.
 *
 * Fill files live in the cache directory, so if the app is killed mid-wipe Android reclaims them
 * automatically under storage pressure, and [cleanupLeftovers] removes them on the next launch.
 */
class FreeSpaceWiper(context: Context) {
    data class Progress(
        val pass: Int,
        val passes: Int,
        val writtenBytes: Long,
        val targetBytes: Long,
        val bytesPerSecond: Long,
    )

    data class Result(
        val passesCompleted: Int,
        val totalWrittenBytes: Long,
        val cancelled: Boolean,
    )

    private val directory = File(context.cacheDir, "free-space-wipe")

    fun cleanupLeftovers() {
        directory.deleteRecursively()
    }

    fun wipe(passes: Int, onProgress: (Progress) -> Unit, shouldContinue: () -> Boolean): Result {
        require(passes in 1..MAX_PASSES)
        var total = 0L
        var completed = 0
        try {
            for (pass in 1..passes) {
                if (!shouldContinue()) return Result(completed, total, cancelled = true)
                cleanupLeftovers()
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create wipe directory")
                val stat = StatFs(directory.path)
                // Keep a reserve so other apps can still save data while storage is nearly full.
                val reserve = max(MIN_RESERVE_BYTES, stat.totalBytes / 100)
                val target = stat.availableBytes - reserve
                if (target <= 0) break
                total += fillPass(pass, passes, target, onProgress, shouldContinue)
                if (!shouldContinue()) return Result(completed, total, cancelled = true)
                completed++
            }
            return Result(completed, total, cancelled = false)
        } finally {
            cleanupLeftovers()
        }
    }

    private fun fillPass(
        pass: Int,
        passes: Int,
        target: Long,
        onProgress: (Progress) -> Unit,
        shouldContinue: () -> Boolean,
    ): Long {
        // AES-256-CTR keystream under a throwaway key: indistinguishable from random, and fast.
        val key = CryptoEngine.randomBytes(CryptoEngine.KEY_BYTES)
        val iv = CryptoEngine.randomBytes(16)
        val zeros = ByteArray(CHUNK_BYTES)
        val random = ByteArray(CHUNK_BYTES)
        try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            val started = SystemClock.elapsedRealtime()
            var written = 0L
            var lastReport = 0L
            var fileIndex = 0
            var storageFull = false

            fun report() {
                val seconds = max(1L, (SystemClock.elapsedRealtime() - started) / 1000L)
                onProgress(Progress(pass, passes, written, target, written / seconds))
            }

            while (!storageFull && written < target && shouldContinue()) {
                val file = File(directory, "fill-$pass-${fileIndex++}.bin")
                try {
                    FileOutputStream(file).use { stream ->
                        var fileBytes = 0L
                        try {
                            while (fileBytes < FILE_LIMIT_BYTES && written < target && shouldContinue()) {
                                val length = min(CHUNK_BYTES.toLong(), target - written).toInt()
                                cipher.update(zeros, 0, length, random, 0)
                                stream.write(random, 0, length)
                                fileBytes += length
                                written += length
                                if (written - lastReport >= REPORT_EVERY_BYTES) {
                                    lastReport = written
                                    report()
                                }
                            }
                        } catch (_: IOException) {
                            storageFull = true // ENOSPC: this pass reached every free block it could.
                        } finally {
                            try {
                                stream.fd.sync()
                            } catch (_: IOException) {
                                // Best effort; the kernel still writes back dirty pages on close.
                            }
                        }
                    }
                } catch (_: IOException) {
                    storageFull = true
                }
            }
            report()
            return written
        } finally {
            key.wipe()
            iv.wipe()
            zeros.wipe()
            random.wipe()
        }
    }

    companion object {
        const val MAX_PASSES = 3
        private const val CHUNK_BYTES = 1024 * 1024
        private const val FILE_LIMIT_BYTES = 1024L * 1024L * 1024L
        private const val REPORT_EVERY_BYTES = 32L * 1024L * 1024L
        private const val MIN_RESERVE_BYTES = 512L * 1024L * 1024L
    }
}
