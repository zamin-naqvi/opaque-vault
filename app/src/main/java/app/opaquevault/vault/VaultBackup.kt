package app.opaquevault.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.opaquevault.security.CryptoEngine
import app.opaquevault.security.VaultKeyManager
import app.opaquevault.security.wipe
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Portable single-file backup that opens on any device with the passphrase alone.
 *
 * File layout:
 *   header (plaintext): magic "OVBK", version, Argon2id parameters, 32-byte random salt
 *   body: Tink AES-256-GCM-HKDF streaming AEAD (1 MiB segments) keyed by Argon2id(passphrase, salt).
 *         The header is the associated data, so tampering with KDF parameters breaks decryption.
 *
 * Everything else (count, names, dates, types, durations, digests, content) is inside the
 * encrypted body. Every segment is authenticated; reordering, truncation or bit flips are detected.
 * Items are streamed, so multi-GB videos never have to fit in memory.
 */
class VaultBackup(context: Context, private val repository: VaultRepository) {
    data class ExportResult(val exported: Int, val skipped: Int)

    data class RestoreResult(
        val restored: Int,
        val duplicates: Int,
        val skipped: Int,
        /** True when authenticated entries were recovered but the end of the file was damaged. */
        val incomplete: Boolean,
    )

    class WrongBackupPassphraseException : Exception("Wrong passphrase, or the backup is damaged")

    class NotABackupException : Exception("This file is not an Opaque Vault backup")

    private val resolver: ContentResolver = context.contentResolver

    fun export(
        masterKey: ByteArray,
        passphrase: CharArray,
        destination: Uri,
        kdf: VaultKeyManager.KdfParameters,
        entries: List<VaultEntry>,
        onProgress: (done: Int, total: Int) -> Unit,
        shouldContinue: () -> Boolean,
    ): ExportResult {
        val salt = CryptoEngine.randomBytes(SALT_BYTES)
        val header = encodeHeader(kdf, salt)
        val backupKey = CryptoEngine.deriveArgon2id(passphrase, salt, kdf.memoryKiB, kdf.iterations, kdf.parallelism)
        val buffer = ByteArray(IO_BUFFER_BYTES)
        try {
            val aead = CryptoEngine.streamingAead(backupKey)
            val raw = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot write the backup file")
            var exported = 0
            var skipped = 0
            raw.use { output ->
                output.write(header)
                aead.newEncryptingStream(NonClosingOutputStream(output), header).use { encrypted ->
                    val data = DataOutputStream(BufferedOutputStream(encrypted, IO_BUFFER_BYTES))
                    data.writeInt(PAYLOAD_MAGIC)
                    data.writeInt(PAYLOAD_VERSION)
                    data.writeLong(System.currentTimeMillis())
                    for ((index, entry) in entries.withIndex()) {
                        if (!shouldContinue()) throw InterruptedIOException("Backup cancelled")
                        onProgress(index, entries.size)
                        val digest = try {
                            repository.contentDigest(masterKey, entry.id)
                        } catch (_: Exception) {
                            skipped++
                            continue
                        }
                        val reader = try {
                            repository.openReader(masterKey, entry.id)
                        } catch (_: Exception) {
                            digest.wipe()
                            skipped++
                            continue
                        }
                        try {
                            data.writeByte(STREAMED_ENTRY_TAG)
                            data.writeUTF(entry.displayName)
                            data.writeUTF(entry.mimeType)
                            data.writeLong(entry.importedAtEpochMillis)
                            data.writeLong(entry.durationMillis)
                            data.writeLong(reader.size)
                            data.write(digest)
                            var position = 0L
                            while (position < reader.size) {
                                if (!shouldContinue()) throw InterruptedIOException("Backup cancelled")
                                val count = reader.read(position, buffer, 0, buffer.size)
                                if (count <= 0) throw IOException("Encrypted item ended early")
                                data.write(buffer, 0, count)
                                position += count
                            }
                            exported++
                        } finally {
                            reader.close()
                            digest.wipe()
                        }
                    }
                    data.writeByte(END_TAG)
                    data.writeInt(exported)
                    data.flush()
                }
                output.flush()
                (output as? FileOutputStream)?.fd?.sync()
            }
            onProgress(entries.size, entries.size)
            return ExportResult(exported, skipped)
        } finally {
            buffer.wipe()
            backupKey.wipe()
            salt.wipe()
        }
    }

    fun restore(
        masterKey: ByteArray,
        passphrase: CharArray,
        source: Uri,
        onRestored: (VaultImportResult) -> Unit,
        onProgress: (restored: Int) -> Unit,
        shouldContinue: () -> Boolean,
    ): RestoreResult {
        val raw = resolver.openInputStream(source) ?: throw IOException("Cannot read the backup file")
        raw.use { input ->
            val buffered = BufferedInputStream(input, IO_BUFFER_BYTES)
            val (kdf, salt, header) = decodeHeader(buffered)
            val backupKey = CryptoEngine.deriveArgon2id(passphrase, salt, kdf.memoryKiB, kdf.iterations, kdf.parallelism)
            var restored = 0
            var duplicates = 0
            var skipped = 0
            try {
                val decrypted = openPayload(buffered, header, backupKey)
                decrypted.readLong() // created-at, informational
                val known = repository.contentDigests(masterKey).toMutableSet()
                try {
                    while (true) {
                        if (!shouldContinue()) throw InterruptedIOException("Restore cancelled")
                        when (decrypted.readByte().toInt()) {
                            END_TAG -> {
                                decrypted.readInt() // exported count, informational
                                // Reaching EOF forces Tink to authenticate the final segment.
                                if (decrypted.read() != -1) throw IOException("Trailing data after backup end")
                                return RestoreResult(restored, duplicates, skipped, incomplete = false)
                            }
                            STREAMED_ENTRY_TAG -> {
                                val name = decrypted.readUTF()
                                val mime = decrypted.readUTF()
                                val importedAt = decrypted.readLong()
                                val duration = decrypted.readLong()
                                val size = decrypted.readLong()
                                if (size < 0 || size > VaultRepository.MAX_IMPORT_BYTES) throw IOException("Invalid entry size")
                                val digest = ByteArray(SHA256_BYTES).also(decrypted::readFully)
                                try {
                                    val content = LimitedInputStream(decrypted, size)
                                    when {
                                        digest.toHex() in known -> {
                                            skipFully(content, size)
                                            duplicates++
                                        }
                                        !isSupportedMediaType(mime) -> {
                                            skipFully(content, size)
                                            skipped++
                                        }
                                        else -> {
                                            val result = repository.importStream(
                                                masterKey, content, name, mime, importedAt, duration, size, digest, shouldContinue,
                                            )
                                            // The item must consume exactly its recorded length.
                                            if (content.read() != -1) throw IOException("Entry longer than recorded")
                                            onRestored(result)
                                            restored++
                                            known += digest.toHex()
                                        }
                                    }
                                } finally {
                                    digest.wipe()
                                }
                                onProgress(restored + duplicates + skipped)
                            }
                            LEGACY_ENTRY_TAG -> {
                                restoreLegacyEntry(decrypted, masterKey, known, shouldContinue)?.let {
                                    onRestored(it)
                                    restored++
                                } ?: duplicates++
                                onProgress(restored + duplicates + skipped)
                            }
                            else -> throw IOException("Unknown backup record")
                        }
                    }
                } catch (error: InterruptedIOException) {
                    throw error
                } catch (error: IOException) {
                    // Everything restored so far came from authenticated segments; the rest is unreadable.
                    if (restored == 0 && duplicates == 0) throw WrongBackupPassphraseException()
                    return RestoreResult(restored, duplicates, skipped, incomplete = true)
                }
            } finally {
                backupKey.wipe()
                salt.wipe()
            }
        }
    }

    /** Proves the passphrase opens [source] before anything is created or changed. */
    fun verifyPassphrase(passphrase: CharArray, source: Uri) {
        val raw = resolver.openInputStream(source) ?: throw IOException("Cannot read the backup file")
        raw.use { input ->
            val buffered = BufferedInputStream(input, IO_BUFFER_BYTES)
            val (kdf, salt, header) = decodeHeader(buffered)
            val backupKey = CryptoEngine.deriveArgon2id(passphrase, salt, kdf.memoryKiB, kdf.iterations, kdf.parallelism)
            try {
                openPayload(buffered, header, backupKey)
            } finally {
                backupKey.wipe()
                salt.wipe()
            }
        }
    }

    /** Removes a partially written backup after a failure or cancel. */
    fun discardPartial(destination: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, destination)
        } catch (_: Exception) {
            // Some providers refuse deletes; the partial file is unreadable without a final segment.
        }
    }

    private fun openPayload(input: InputStream, header: ByteArray, key: ByteArray): DataInputStream {
        val decrypted = try {
            DataInputStream(
                BufferedInputStream(CryptoEngine.streamingAead(key).newDecryptingStream(input, header), IO_BUFFER_BYTES),
            )
        } catch (_: GeneralSecurityException) {
            throw WrongBackupPassphraseException()
        }
        val magic = try {
            decrypted.readInt()
        } catch (_: IOException) {
            throw WrongBackupPassphraseException()
        }
        if (magic != PAYLOAD_MAGIC) throw NotABackupException()
        val version = decrypted.readInt()
        if (version != 1 && version != PAYLOAD_VERSION) throw NotABackupException()
        return decrypted
    }

    /** First backup format: photo bytes held in memory with an int length. */
    private fun restoreLegacyEntry(
        input: DataInputStream,
        masterKey: ByteArray,
        known: MutableSet<String>,
        shouldContinue: () -> Boolean,
    ): VaultImportResult? {
        val name = input.readUTF()
        val mime = input.readUTF()
        val importedAt = input.readLong()
        val size = input.readInt()
        if (size < 0 || size > VaultRepository.DEFAULT_OPEN_LIMIT) throw IOException("Invalid entry size")
        val content = ByteArray(size)
        var digest: ByteArray? = null
        try {
            input.readFully(content)
            digest = MessageDigest.getInstance("SHA-256").digest(content)
            if (digest.toHex() in known) return null
            val result = repository.importStream(
                masterKey, ByteArrayInputStream(content), name, mime, importedAt, 0, size.toLong(), digest, shouldContinue,
            )
            known += digest.toHex()
            return result
        } finally {
            digest?.wipe()
            content.wipe()
        }
    }

    private fun skipFully(input: InputStream, length: Long) {
        val scratch = ByteArray(IO_BUFFER_BYTES)
        try {
            var remaining = length
            while (remaining > 0) {
                val count = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (count < 0) throw IOException("Backup ended inside an entry")
                remaining -= count
            }
        } finally {
            scratch.wipe()
        }
    }

    private fun encodeHeader(kdf: VaultKeyManager.KdfParameters, salt: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(HEADER_MAGIC)
                output.writeInt(HEADER_VERSION)
                output.writeInt(kdf.memoryKiB)
                output.writeInt(kdf.iterations)
                output.writeInt(kdf.parallelism)
                output.writeInt(salt.size)
                output.write(salt)
            }
            bytes.toByteArray()
        }

    private fun decodeHeader(input: InputStream): Triple<VaultKeyManager.KdfParameters, ByteArray, ByteArray> {
        val data = DataInputStream(input)
        val magic = try {
            data.readInt()
        } catch (_: IOException) {
            throw NotABackupException()
        }
        if (magic != HEADER_MAGIC) throw NotABackupException()
        if (data.readInt() != HEADER_VERSION) throw NotABackupException()
        val kdf = VaultKeyManager.KdfParameters(data.readInt(), data.readInt(), data.readInt())
        // Bounds stop a crafted file from demanding absurd memory or time.
        if (kdf.memoryKiB !in 65_536..262_144 || kdf.iterations !in 3..6 || kdf.parallelism !in 1..4) {
            throw NotABackupException()
        }
        val saltSize = data.readInt()
        if (saltSize != SALT_BYTES) throw NotABackupException()
        val salt = ByteArray(saltSize).also(data::readFully)
        return Triple(kdf, salt, encodeHeader(kdf, salt))
    }

    companion object {
        const val FILE_EXTENSION = "ovbk"

        private const val HEADER_MAGIC = 0x4F56424B // OVBK
        private const val HEADER_VERSION = 1
        private const val PAYLOAD_MAGIC = 0x4F564250 // OVBP
        private const val PAYLOAD_VERSION = 2
        private const val LEGACY_ENTRY_TAG = 1
        private const val STREAMED_ENTRY_TAG = 2
        private const val END_TAG = 0
        private const val SALT_BYTES = 32
        private const val SHA256_BYTES = 32
        private const val IO_BUFFER_BYTES = 256 * 1024
    }
}
