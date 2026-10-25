package app.opaquevault.vault

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.webkit.MimeTypeMap
import app.opaquevault.security.CryptoEngine
import app.opaquevault.security.wipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.math.min

/**
 * Private, encrypted object store. Per item:
 *  - `<id>.blob`  content under a random per-file key, padded to 64 KiB.
 *       format 1 (older items): one AES-256-GCM message.
 *       format 2 (new items):   Tink AES-256-GCM-HKDF streaming AEAD, 1 MiB authenticated segments,
 *                               so photos and multi-GB videos use bounded memory and videos can be
 *                               played with seeking straight from the ciphertext.
 *  - `<id>.meta`  AES-256-GCM metadata (name, type, sizes, duration, file key, digest) under the master key
 *  - `<id>.thumb` optional AES-256-GCM thumbnail under the file key (regenerated if absent)
 */
class VaultRepository(context: Context) {
    private data class SourceInfo(
        val displayName: String,
        val mimeType: String,
        val sizeHint: Long = -1,
    )

    private data class EncryptedContent(
        val plaintextSize: Long,
        val paddedSize: Long,
        val sha256: ByteArray,
    )

    private data class DecodedMetadata(
        val entry: VaultEntry,
        val fileKey: ByteArray,
        val contentSha256: ByteArray,
        val contentFormat: Int,
    )

    private val appContext: Context = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val objectsDirectory = File(appContext.filesDir, "$VAULT_DIRECTORY/$OBJECTS_DIRECTORY")
    private val activeImportInputs = ConcurrentHashMap.newKeySet<InputStream>()

    /** Closes provider streams from another thread so a blocked Gallery read actually cancels. */
    fun cancelActiveImports() {
        activeImportInputs.toList().forEach { input ->
            try {
                input.close()
            } catch (_: Exception) {
                // It may already have closed after finishing normally.
            }
        }
    }

    // Deliberately NOT @Synchronized: an import can run for minutes, and holding the repository
    // monitor that long froze deletes, thumbnails and listing. Each import only writes files named
    // after its own random 128-bit id, so it needs no shared lock until the atomic renames.
    fun importUri(
        masterKey: ByteArray,
        sourceUri: Uri,
        durationMillis: Long = 0,
        shouldContinue: () -> Boolean = { true },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        onVerifyProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): VaultImportResult {
        val source = querySourceInfo(sourceUri)
        if (!isSupportedMediaType(source.mimeType)) throw UnsupportedImportTypeException(source.mimeType)
        // Samsung Gallery serves shares from its own process, which the system freezes while the
        // vault is in front. When the exact same file exists in MediaStore (served by the system,
        // never frozen), read that instead. Removing the original later then also works directly.
        val readUri = mediaStoreTwin(sourceUri, source) ?: sourceUri
        if (source.sizeHint > MAX_IMPORT_BYTES) throw ImportTooLargeException(MAX_IMPORT_BYTES)
        ensureSpaceFor(source.sizeHint)
        return importFromStream(
            masterKey = masterKey,
            source = source,
            sourceUri = readUri,
            importedAt = System.currentTimeMillis(),
            durationMillis = durationMillis,
            expectedSha256 = null,
            shouldContinue = shouldContinue,
            onProgress = onProgress,
            onVerifyProgress = onVerifyProgress,
        ) {
            ProviderSourceStream.open(contentResolver, readUri, shouldContinue)
        }
    }

    /**
     * Re-encrypts content recovered from a backup under a fresh file key. When [expectedSha256] is
     * given, the item is only committed if the bytes match it exactly.
     */
    fun importStream(
        masterKey: ByteArray,
        input: InputStream,
        displayName: String,
        mimeType: String,
        importedAt: Long,
        durationMillis: Long,
        expectedSize: Long,
        expectedSha256: ByteArray?,
        shouldContinue: () -> Boolean = { true },
    ): VaultImportResult {
        ensureSpaceFor(expectedSize)
        return importFromStream(
            masterKey = masterKey,
            source = SourceInfo(sanitizeName(displayName), sanitizeMime(mimeType), expectedSize),
            sourceUri = Uri.EMPTY,
            importedAt = importedAt,
            durationMillis = durationMillis,
            expectedSha256 = expectedSha256,
            shouldContinue = shouldContinue,
            onProgress = { _, _ -> },
            onVerifyProgress = { _, _ -> },
        ) { input }
    }

    private fun importFromStream(
        masterKey: ByteArray,
        source: SourceInfo,
        sourceUri: Uri,
        importedAt: Long,
        durationMillis: Long,
        expectedSha256: ByteArray?,
        shouldContinue: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        onVerifyProgress: (Long, Long) -> Unit,
        openStream: () -> InputStream,
    ): VaultImportResult {
        require(masterKey.size == CryptoEngine.KEY_BYTES)
        ensureObjectsDirectory()
        val id = uniqueObjectId()
        val blobFile = blobFile(id)
        val metadataFile = metadataFile(id)
        val temporaryBlob = File(objectsDirectory, ".$id.blob.part")
        val temporaryMetadata = File(objectsDirectory, ".$id.meta.part")
        val fileKey = CryptoEngine.randomBytes(CryptoEngine.KEY_BYTES)
        var encryptedContent: EncryptedContent? = null
        try {
            encryptedContent = try {
                val input = openStream()
                activeImportInputs += input
                try {
                    input.use { encryptContent(it, temporaryBlob, id, fileKey, source.sizeHint, shouldContinue, onProgress) }
                } finally {
                    activeImportInputs -= input
                }
            } catch (error: IOException) {
                if (error !is InterruptedIOException && availableBytes() < LOW_SPACE_BYTES) {
                    throw InsufficientStorageException(source.sizeHint)
                }
                throw error
            }
            if (expectedSha256 != null && !MessageDigest.isEqual(expectedSha256, encryptedContent.sha256)) {
                throw VaultIntegrityException("Content does not match its recorded digest")
            }
            verifyContentV2(
                file = temporaryBlob,
                id = id,
                fileKey = fileKey,
                plaintextSize = encryptedContent.plaintextSize,
                paddedSize = encryptedContent.paddedSize,
                expectedSha256 = encryptedContent.sha256,
                output = null,
                shouldContinue = shouldContinue,
                onProgress = onVerifyProgress,
            )
            val entry = VaultEntry(
                id = id,
                displayName = source.displayName,
                mimeType = source.mimeType,
                plaintextSize = encryptedContent.plaintextSize,
                paddedSize = encryptedContent.paddedSize,
                importedAtEpochMillis = importedAt,
                durationMillis = durationMillis.coerceAtLeast(0),
            )
            writeMetadata(temporaryMetadata, entry, fileKey, encryptedContent.sha256, CONTENT_FORMAT_STREAMING, masterKey)
            readMetadata(temporaryMetadata, id, masterKey).also { decoded ->
                decoded.fileKey.wipe()
                decoded.contentSha256.wipe()
            }

            Os.rename(temporaryBlob.absolutePath, blobFile.absolutePath)
            try {
                Os.rename(temporaryMetadata.absolutePath, metadataFile.absolutePath)
            } catch (error: Throwable) {
                blobFile.delete()
                throw error
            }
            return VaultImportResult(
                entry = entry,
                original = ImportedOriginal(
                    uri = sourceUri,
                    displayName = source.displayName,
                    mimeType = source.mimeType,
                    sizeBytes = encryptedContent.plaintextSize,
                    sha256 = encryptedContent.sha256.copyOf(),
                ),
            )
        } catch (error: Throwable) {
            temporaryBlob.delete()
            temporaryMetadata.delete()
            throw error
        } finally {
            fileKey.wipe()
            encryptedContent?.sha256?.wipe()
        }
    }

    @Synchronized
    fun listEntries(masterKey: ByteArray): VaultListing {
        require(masterKey.size == CryptoEngine.KEY_BYTES)
        ensureObjectsDirectory()
        removeInterruptedImports()
        val entries = mutableListOf<VaultEntry>()
        var corrupted = 0
        forEachMetadataFile { id, file ->
            if (id == null) {
                corrupted++
                return@forEachMetadataFile
            }
            try {
                val decoded = readMetadata(file, id, masterKey)
                try {
                    if (!blobFile(id).isFile) throw VaultIntegrityException("Encrypted content is missing")
                    entries += decoded.entry
                } finally {
                    decoded.fileKey.wipe()
                    decoded.contentSha256.wipe()
                }
            } catch (_: Exception) {
                corrupted++
            }
        }
        return VaultListing(entries.sortedByDescending(VaultEntry::importedAtEpochMillis), corrupted)
    }

    /** Atomically stores duration discovered from the authenticated encrypted copy. */
    @Synchronized
    fun updateDuration(masterKey: ByteArray, id: String, durationMillis: Long): VaultEntry {
        validateObjectId(id)
        val decoded = readMetadata(metadataFile(id), id, masterKey)
        val temporary = File(objectsDirectory, ".$id.meta.part")
        try {
            val duration = durationMillis.coerceAtLeast(0L)
            if (decoded.entry.durationMillis == duration) return decoded.entry
            val updated = decoded.entry.copy(durationMillis = duration)
            writeMetadata(
                temporary,
                updated,
                decoded.fileKey,
                decoded.contentSha256,
                decoded.contentFormat,
                masterKey,
            )
            readMetadata(temporary, id, masterKey).also { verified ->
                verified.fileKey.wipe()
                verified.contentSha256.wipe()
            }
            Os.rename(temporary.absolutePath, metadataFile(id).absolutePath)
            return updated
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            decoded.fileKey.wipe()
            decoded.contentSha256.wipe()
        }
    }

    /** Hex SHA-256 of every item's plaintext; lets a restore skip items already in the vault. */
    @Synchronized
    fun contentDigests(masterKey: ByteArray): Set<String> {
        val digests = HashSet<String>()
        forEachMetadataFile { id, file ->
            if (id == null) return@forEachMetadataFile
            try {
                val decoded = readMetadata(file, id, masterKey)
                digests += decoded.contentSha256.toHex()
                decoded.fileKey.wipe()
                decoded.contentSha256.wipe()
            } catch (_: Exception) {
                // Corrupted entries are reported by listEntries.
            }
        }
        return digests
    }

    /** The authenticated SHA-256 of an item's content (caller wipes it). */
    @Synchronized
    fun contentDigest(masterKey: ByteArray, id: String): ByteArray {
        validateObjectId(id)
        val decoded = readMetadata(metadataFile(id), id, masterKey)
        decoded.fileKey.wipe()
        return decoded.contentSha256
    }

    /** Decrypts a photo completely into memory, verifying every byte. */
    fun openInMemory(masterKey: ByteArray, id: String, maximumBytes: Long = DEFAULT_OPEN_LIMIT): Pair<VaultEntry, ByteArray> {
        validateObjectId(id)
        val decoded = readMetadata(metadataFile(id), id, masterKey)
        try {
            if (decoded.entry.plaintextSize > maximumBytes || decoded.entry.plaintextSize > Int.MAX_VALUE) {
                throw VaultOpenLimitException(maximumBytes)
            }
            val plaintext = ByteArray(decoded.entry.plaintextSize.toInt())
            try {
                if (decoded.contentFormat == CONTENT_FORMAT_STREAMING) {
                    verifyContentV2(
                        blobFile(id), id, decoded.fileKey, decoded.entry.plaintextSize,
                        decoded.entry.paddedSize, decoded.contentSha256, plaintext,
                    )
                } else {
                    verifyContentV1(
                        blobFile(id), id, decoded.fileKey, decoded.entry.plaintextSize,
                        decoded.entry.paddedSize, decoded.contentSha256, plaintext,
                    )
                }
                return decoded.entry to plaintext
            } catch (error: Throwable) {
                plaintext.wipe()
                throw error
            }
        } finally {
            decoded.fileKey.wipe()
            decoded.contentSha256.wipe()
        }
    }

    /**
     * Opens an item for random access (video playback, video thumbnails). Segments are decrypted and
     * authenticated on demand; a modified segment makes reads fail instead of returning bad data.
     */
    fun openReader(masterKey: ByteArray, id: String): VaultMediaReader {
        validateObjectId(id)
        val decoded = readMetadata(metadataFile(id), id, masterKey)
        try {
            if (decoded.contentFormat != CONTENT_FORMAT_STREAMING) {
                // Older single-message items are photos within the in-memory limit.
                return ByteArrayMediaReader(openInMemory(masterKey, id).second)
            }
            val raw = FileInputStream(blobFile(id))
            try {
                val channel = CryptoEngine.streamingAead(decoded.fileKey)
                    .newSeekableDecryptingChannel(raw.channel, contentAadV2(id))
                return SeekableMediaReader(decoded.entry.plaintextSize, channel, raw)
            } catch (error: Exception) {
                raw.close()
                throw VaultIntegrityException("Unable to open encrypted content", error)
            }
        } finally {
            decoded.fileKey.wipe()
            decoded.contentSha256.wipe()
        }
    }

    @Synchronized
    fun deleteEntry(masterKey: ByteArray, id: String): Boolean {
        validateObjectId(id)
        val metadata = metadataFile(id)
        if (!metadata.isFile) return false
        val decoded = readMetadata(metadata, id, masterKey)
        decoded.fileKey.wipe()
        decoded.contentSha256.wipe()

        // Remove the only live wrapped file-key record before deleting bulk ciphertext.
        if (!metadata.delete()) throw IOException("Unable to remove encrypted key metadata")
        thumbnailFile(id).delete()
        val blob = blobFile(id)
        return !blob.exists() || blob.delete()
    }

    /** Encrypts a JPEG thumbnail under the item's own file key, padded to hide its exact size. */
    @Synchronized
    fun storeThumbnail(masterKey: ByteArray, id: String, jpeg: ByteArray) {
        validateObjectId(id)
        require(jpeg.size in 1..MAX_THUMBNAIL_JPEG_BYTES) { "Thumbnail has an invalid size" }
        val decoded = readMetadata(metadataFile(id), id, masterKey)
        val paddedLength = ((jpeg.size + Int.SIZE_BYTES) / THUMBNAIL_PADDING_BYTES + 1) * THUMBNAIL_PADDING_BYTES
        val padded = ByteArray(paddedLength)
        var packet: CryptoEngine.AeadPacket? = null
        val temporary = File(objectsDirectory, ".$id.thumb.part")
        try {
            ByteBuffer.wrap(padded).putInt(jpeg.size)
            System.arraycopy(jpeg, 0, padded, Int.SIZE_BYTES, jpeg.size)
            val padding = CryptoEngine.randomBytes(paddedLength - Int.SIZE_BYTES - jpeg.size)
            System.arraycopy(padding, 0, padded, Int.SIZE_BYTES + jpeg.size, padding.size)
            padding.wipe()
            packet = CryptoEngine.encrypt(decoded.fileKey, padded, thumbnailAad(id))
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(fileOutput).use { output ->
                    output.writeInt(THUMBNAIL_MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeSized(packet.nonce)
                    output.writeSized(packet.ciphertext)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            Os.rename(temporary.absolutePath, thumbnailFile(id).absolutePath)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            padded.wipe()
            packet?.nonce?.wipe()
            packet?.ciphertext?.wipe()
            decoded.fileKey.wipe()
            decoded.contentSha256.wipe()
        }
    }

    /** Returns the decrypted thumbnail JPEG, or null when none exists (or it failed authentication). */
    fun readThumbnail(masterKey: ByteArray, id: String): ByteArray? {
        validateObjectId(id)
        val file = thumbnailFile(id)
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_THUMBNAIL_FILE_BYTES.toLong()) {
            file.delete()
            return null
        }
        val decoded = readMetadata(metadataFile(id), id, masterKey)
        var plaintext: ByteArray? = null
        try {
            val packet = DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != THUMBNAIL_MAGIC || input.readInt() != FORMAT_VERSION) return null.also { file.delete() }
                val nonce = input.readSized(CryptoEngine.GCM_NONCE_BYTES, CryptoEngine.GCM_NONCE_BYTES)
                val ciphertext = input.readSized(16, MAX_THUMBNAIL_FILE_BYTES)
                CryptoEngine.AeadPacket(nonce, ciphertext)
            }
            plaintext = try {
                CryptoEngine.decrypt(decoded.fileKey, packet, thumbnailAad(id))
            } catch (_: AEADBadTagException) {
                // Not authoritative: drop it and let the caller regenerate from authenticated content.
                file.delete()
                return null
            }
            val length = ByteBuffer.wrap(plaintext).int
            if (length !in 1..(plaintext.size - Int.SIZE_BYTES)) {
                file.delete()
                return null
            }
            return plaintext.copyOfRange(Int.SIZE_BYTES, Int.SIZE_BYTES + length)
        } catch (_: IOException) {
            file.delete()
            return null
        } finally {
            plaintext?.wipe()
            decoded.fileKey.wipe()
            decoded.contentSha256.wipe()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Content format 2: streaming AEAD
    // ---------------------------------------------------------------------------------------------

    private fun encryptContent(
        input: InputStream,
        destination: File,
        id: String,
        fileKey: ByteArray,
        sizeHint: Long,
        shouldContinue: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): EncryptedContent {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var plaintextSize = 0L
        var lastReport = 0L
        var lastSpaceCheck = 0L
        try {
            FileOutputStream(destination).use { fileOutput ->
                val aead = CryptoEngine.streamingAead(fileKey)
                aead.newEncryptingStream(NonClosingOutputStream(fileOutput), contentAadV2(id)).use { encrypted ->
                    while (true) {
                        if (!shouldContinue()) throw InterruptedIOException("Import stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        plaintextSize = Math.addExact(plaintextSize, count.toLong())
                        if (plaintextSize > MAX_IMPORT_BYTES) throw ImportTooLargeException(MAX_IMPORT_BYTES)
                        if (plaintextSize - lastSpaceCheck >= SPACE_CHECK_EVERY_BYTES) {
                            lastSpaceCheck = plaintextSize
                            val minimumFree = STORAGE_RESERVE_BYTES + PADDING_BLOCK_BYTES + CryptoEngine.STREAM_SEGMENT_BYTES
                            if (availableBytes() < minimumFree) throw InsufficientStorageException(minimumFree)
                        }
                        digest.update(buffer, 0, count)
                        encrypted.write(buffer, 0, count)
                        if (plaintextSize - lastReport >= PROGRESS_EVERY_BYTES) {
                            lastReport = plaintextSize
                            onProgress(plaintextSize, sizeHint)
                        }
                    }
                    // Random padding to a 64 KiB boundary hides the exact size.
                    var remainingPadding = nextPaddingBoundary(plaintextSize) - plaintextSize
                    while (remainingPadding > 0) {
                        val count = min(remainingPadding, buffer.size.toLong()).toInt()
                        CryptoEngine.randomize(buffer)
                        encrypted.write(buffer, 0, count)
                        remainingPadding -= count
                    }
                }
                fileOutput.flush()
                fileOutput.fd.sync()
            }
            onProgress(plaintextSize, sizeHint)
            return EncryptedContent(plaintextSize, nextPaddingBoundary(plaintextSize), digest.digest())
        } finally {
            buffer.wipe()
        }
    }

    private fun verifyContentV2(
        file: File,
        id: String,
        fileKey: ByteArray,
        plaintextSize: Long,
        paddedSize: Long,
        expectedSha256: ByteArray,
        output: ByteArray?,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        if (!file.isFile) throw VaultIntegrityException("Encrypted content is missing")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        var lastReport = 0L
        try {
            FileInputStream(file).use { raw ->
                CryptoEngine.streamingAead(fileKey).newDecryptingStream(raw, contentAadV2(id)).use { input ->
                    while (true) {
                        if (!shouldContinue()) throw InterruptedIOException("Import stopped")
                        if (total - lastReport >= PROGRESS_EVERY_BYTES) {
                            lastReport = total
                            onProgress(total, paddedSize)
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val contentBytes = (plaintextSize - total).coerceIn(0, count.toLong()).toInt()
                        if (contentBytes > 0) {
                            digest.update(buffer, 0, contentBytes)
                            output?.let { System.arraycopy(buffer, 0, it, total.toInt(), contentBytes) }
                        }
                        total += count
                        if (total > paddedSize) throw VaultIntegrityException("Encrypted content is longer than recorded")
                    }
                }
            }
            if (total != paddedSize) throw VaultIntegrityException("Encrypted content length does not match metadata")
            val actualHash = digest.digest()
            try {
                if (!MessageDigest.isEqual(actualHash, expectedSha256)) {
                    throw VaultIntegrityException("Encrypted content digest does not match metadata")
                }
            } finally {
                actualHash.wipe()
            }
        } catch (error: VaultIntegrityException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: Exception) {
            throw VaultIntegrityException("Encrypted content failed authentication", error)
        } finally {
            buffer.wipe()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Content format 1: single AES-GCM message (read-only, for items imported by older builds)
    // ---------------------------------------------------------------------------------------------

    private fun verifyContentV1(
        file: File,
        id: String,
        fileKey: ByteArray,
        plaintextSize: Long,
        paddedSize: Long,
        expectedSha256: ByteArray,
        output: ByteArray?,
    ) {
        if (!file.isFile) throw VaultIntegrityException("Encrypted content is missing")
        val digest = MessageDigest.getInstance("SHA-256")
        val encryptedBuffer = ByteArray(STREAM_BUFFER_BYTES)
        var totalPlaintext = 0L
        var originalBytes = 0L
        try {
            FileInputStream(file).use { fileInput ->
                val input = DataInputStream(fileInput)
                if (input.readInt() != BLOB_MAGIC) throw VaultIntegrityException("Unknown content format")
                if (input.readInt() != FORMAT_VERSION) throw VaultIntegrityException("Unsupported content version")
                val nonceSize = input.readInt()
                if (nonceSize != CryptoEngine.GCM_NONCE_BYTES) throw VaultIntegrityException("Invalid content nonce")
                val nonce = ByteArray(nonceSize)
                try {
                    input.readFully(nonce)
                    val cipher = CryptoEngine.newAesGcmCipher(Cipher.DECRYPT_MODE, fileKey, nonce, contentAadV1(id))
                    while (true) {
                        val count = input.read(encryptedBuffer)
                        if (count < 0) break
                        if (count == 0) continue
                        cipher.update(encryptedBuffer, 0, count)?.let { plaintext ->
                            originalBytes += consumeVerifiedPlaintext(plaintext, plaintextSize, totalPlaintext, digest, output)
                            totalPlaintext += plaintext.size
                            plaintext.wipe()
                        }
                    }
                    val finalPlaintext = try {
                        cipher.doFinal()
                    } catch (error: AEADBadTagException) {
                        throw VaultIntegrityException("Encrypted content authentication failed", error)
                    }
                    originalBytes += consumeVerifiedPlaintext(finalPlaintext, plaintextSize, totalPlaintext, digest, output)
                    totalPlaintext += finalPlaintext.size
                    finalPlaintext.wipe()
                } finally {
                    nonce.wipe()
                }
            }
            if (totalPlaintext != paddedSize || originalBytes != plaintextSize) {
                throw VaultIntegrityException("Encrypted content length does not match authenticated metadata")
            }
            val actualHash = digest.digest()
            try {
                if (!MessageDigest.isEqual(actualHash, expectedSha256)) {
                    throw VaultIntegrityException("Encrypted content digest does not match metadata")
                }
            } finally {
                actualHash.wipe()
            }
        } catch (error: VaultIntegrityException) {
            throw error
        } catch (error: EOFException) {
            throw VaultIntegrityException("Encrypted content is truncated", error)
        } catch (error: Exception) {
            throw VaultIntegrityException("Unable to authenticate encrypted content", error)
        } finally {
            encryptedBuffer.wipe()
        }
    }

    private fun consumeVerifiedPlaintext(
        plaintext: ByteArray,
        originalSize: Long,
        currentOffset: Long,
        digest: MessageDigest,
        output: ByteArray?,
    ): Long {
        val remainingOriginal = (originalSize - currentOffset).coerceAtLeast(0)
        val originalCount = minOf(remainingOriginal, plaintext.size.toLong()).toInt()
        if (originalCount > 0) {
            digest.update(plaintext, 0, originalCount)
            output?.let { System.arraycopy(plaintext, 0, it, currentOffset.toInt(), originalCount) }
        }
        return originalCount.toLong()
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------------------------

    private fun writeMetadata(
        destination: File,
        entry: VaultEntry,
        fileKey: ByteArray,
        contentSha256: ByteArray,
        contentFormat: Int,
        masterKey: ByteArray,
    ) {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(METADATA_PAYLOAD_VERSION)
                output.writeString(entry.id)
                output.writeString(entry.displayName)
                output.writeString(entry.mimeType)
                output.writeLong(entry.plaintextSize)
                output.writeLong(entry.paddedSize)
                output.writeLong(entry.importedAtEpochMillis)
                output.writeSized(fileKey)
                output.writeSized(contentSha256)
                output.writeInt(contentFormat)
                output.writeLong(entry.durationMillis)
            }
            bytes.toByteArray()
        }
        val packet = CryptoEngine.encrypt(masterKey, payload, metadataAad(entry.id))
        try {
            FileOutputStream(destination).use { fileOutput ->
                DataOutputStream(fileOutput).use { output ->
                    output.writeInt(METADATA_MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeSized(packet.nonce)
                    output.writeSized(packet.ciphertext)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        } finally {
            payload.wipe()
            packet.nonce.wipe()
            packet.ciphertext.wipe()
        }
    }

    private fun readMetadata(file: File, expectedId: String, masterKey: ByteArray): DecodedMetadata {
        if (!file.isFile || file.length() !in 1..MAX_METADATA_FILE_BYTES.toLong()) {
            throw VaultIntegrityException("Encrypted metadata is missing or malformed")
        }
        var packet: CryptoEngine.AeadPacket? = null
        var payload: ByteArray? = null
        try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != METADATA_MAGIC) throw VaultIntegrityException("Unknown metadata format")
                if (input.readInt() != FORMAT_VERSION) throw VaultIntegrityException("Unsupported metadata version")
                val nonce = input.readSized(CryptoEngine.GCM_NONCE_BYTES, CryptoEngine.GCM_NONCE_BYTES)
                val ciphertext = input.readSized(16, MAX_METADATA_FILE_BYTES)
                if (input.read() != -1) throw VaultIntegrityException("Trailing encrypted metadata")
                packet = CryptoEngine.AeadPacket(nonce, ciphertext)
            }
            payload = try {
                CryptoEngine.decrypt(masterKey, packet!!, metadataAad(expectedId))
            } catch (error: AEADBadTagException) {
                throw VaultIntegrityException("Metadata authentication failed", error)
            }
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val payloadVersion = input.readInt()
                if (payloadVersion != 1 && payloadVersion != METADATA_PAYLOAD_VERSION) {
                    throw VaultIntegrityException("Unsupported metadata payload")
                }
                val id = input.readString(MAX_ID_BYTES)
                if (id != expectedId || !OBJECT_ID.matches(id)) throw VaultIntegrityException("Metadata identity mismatch")
                val displayName = input.readString(MAX_NAME_BYTES)
                val mimeType = input.readString(MAX_MIME_BYTES)
                val plaintextSize = input.readLong()
                val paddedSize = input.readLong()
                val importedAt = input.readLong()
                if (plaintextSize !in 0..MAX_STORED_BYTES || paddedSize != nextPaddingBoundary(plaintextSize)) {
                    throw VaultIntegrityException("Invalid authenticated content size")
                }
                val fileKey = input.readSized(CryptoEngine.KEY_BYTES, CryptoEngine.KEY_BYTES)
                val digest = input.readSized(SHA256_BYTES, SHA256_BYTES)
                var contentFormat = CONTENT_FORMAT_SINGLE_MESSAGE
                var duration = 0L
                if (payloadVersion >= 2) {
                    contentFormat = input.readInt()
                    duration = input.readLong()
                }
                if (input.read() != -1 || contentFormat !in CONTENT_FORMAT_SINGLE_MESSAGE..CONTENT_FORMAT_STREAMING) {
                    fileKey.wipe()
                    digest.wipe()
                    throw VaultIntegrityException("Invalid metadata payload")
                }
                return DecodedMetadata(
                    VaultEntry(id, displayName, mimeType, plaintextSize, paddedSize, importedAt, duration.coerceAtLeast(0)),
                    fileKey,
                    digest,
                    contentFormat,
                )
            }
        } catch (error: VaultIntegrityException) {
            throw error
        } catch (error: Exception) {
            throw VaultIntegrityException("Unable to read authenticated metadata", error)
        } finally {
            packet?.nonce?.wipe()
            packet?.ciphertext?.wipe()
            payload?.wipe()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun querySourceInfo(uri: Uri): SourceInfo {
        var name: String? = uri.lastPathSegment?.substringAfterLast('/')
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: RuntimeException) {
            // Some OEM share providers allow opening content but reject metadata queries.
        }
        val safeName = sanitizeName(name.orEmpty())
        val reportedMime = try {
            contentResolver.getType(uri).orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
        return SourceInfo(safeName, normalizeSourceMime(reportedMime, safeName), size.coerceAtLeast(-1L))
    }

    /** The unique MediaStore item with the same name and byte size, if this app may read media. */
    private fun mediaStoreTwin(sourceUri: Uri, source: SourceInfo): Uri? {
        if (sourceUri.authority == MediaStore.AUTHORITY) return null
        if (source.sizeHint <= 0L || source.displayName.isBlank()) return null
        val canRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (!canRead) return null
        val video = source.mimeType.startsWith("video/")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(source.displayName, source.sizeHint.toString()),
                null,
            )?.use { cursor ->
                // Only an unambiguous match is used; otherwise read what the sharing app provided.
                if (cursor.count == 1 && cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun normalizeSourceMime(reportedMime: String, displayName: String): String {
        val reported = sanitizeMime(reportedMime)
        if (isSupportedMediaType(reported) && reported != UNKNOWN_MIME_TYPE) return reported
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val inferred = extension.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?.let(::sanitizeMime)
        return inferred?.takeIf(::isSupportedMediaType) ?: reported
    }

    private fun sanitizeName(name: String): String = name
        .filterNot(Char::isISOControl)
        .take(MAX_DISPLAY_NAME_CHARS)
        .ifBlank { "Encrypted item" }

    private fun sanitizeMime(mime: String): String = mime
        .filterNot(Char::isISOControl)
        .take(MAX_MIME_CHARS)
        .ifBlank { UNKNOWN_MIME_TYPE }

    /** The encrypted copy needs the original's size plus a reserve so the phone keeps working. */
    private fun ensureSpaceFor(bytes: Long) {
        if (bytes <= 0) return
        val needed = bytes + bytes / 50 + STORAGE_RESERVE_BYTES
        if (availableBytes() < needed) throw InsufficientStorageException(needed)
    }

    private fun availableBytes(): Long = try {
        ensureObjectsDirectory()
        StatFs(objectsDirectory.path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    private inline fun forEachMetadataFile(action: (id: String?, file: File) -> Unit) {
        objectsDirectory.listFiles { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX) }
            .orEmpty()
            .forEach { file ->
                val id = file.name.removeSuffix(METADATA_SUFFIX)
                action(id.takeIf(OBJECT_ID::matches), file)
            }
    }

    private fun removeInterruptedImports() {
        val staleBefore = System.currentTimeMillis() - STALE_PART_MILLIS
        objectsDirectory.listFiles().orEmpty().forEach { file ->
            // Recent partial files may belong to an import that is still running.
            if (file.isFile && file.name.endsWith(PART_SUFFIX) && file.lastModified() < staleBefore) file.delete()
        }
        objectsDirectory.listFiles { file ->
            file.isFile && (file.name.endsWith(BLOB_SUFFIX) || file.name.endsWith(THUMBNAIL_SUFFIX))
        }
            .orEmpty()
            .forEach { orphan ->
                val id = orphan.name.substringBefore('.')
                if (OBJECT_ID.matches(id) && !metadataFile(id).exists()) orphan.delete()
            }
    }

    private fun ensureObjectsDirectory() {
        if (!objectsDirectory.isDirectory && !objectsDirectory.mkdirs()) {
            throw IOException("Unable to create private encrypted-object storage")
        }
    }

    private fun uniqueObjectId(): String {
        repeat(8) {
            val candidate = randomObjectId()
            if (!blobFile(candidate).exists() && !metadataFile(candidate).exists()) return candidate
        }
        throw IOException("Unable to allocate a unique encrypted-object identifier")
    }

    private fun randomObjectId(): String = CryptoEngine.randomBytes(16).let { bytes ->
        try {
            bytes.toHex()
        } finally {
            bytes.wipe()
        }
    }

    private fun nextPaddingBoundary(size: Long): Long {
        if (size !in 0..MAX_STORED_BYTES) throw VaultIntegrityException("Invalid file size")
        return Math.multiplyExact(Math.addExact(size / PADDING_BLOCK_BYTES, 1L), PADDING_BLOCK_BYTES)
    }

    private fun validateObjectId(id: String) {
        require(OBJECT_ID.matches(id)) { "Invalid object identifier" }
    }

    private fun blobFile(id: String) = File(objectsDirectory, id + BLOB_SUFFIX)
    private fun metadataFile(id: String) = File(objectsDirectory, id + METADATA_SUFFIX)
    private fun thumbnailFile(id: String) = File(objectsDirectory, id + THUMBNAIL_SUFFIX)

    private fun contentAadV1(id: String) = (CONTENT_AAD_PREFIX_V1 + id).toByteArray(StandardCharsets.UTF_8)
    private fun contentAadV2(id: String) = (CONTENT_AAD_PREFIX_V2 + id).toByteArray(StandardCharsets.UTF_8)
    private fun metadataAad(id: String) = (METADATA_AAD_PREFIX + id).toByteArray(StandardCharsets.UTF_8)
    private fun thumbnailAad(id: String) = (THUMBNAIL_AAD_PREFIX + id).toByteArray(StandardCharsets.UTF_8)

    private fun DataOutputStream.writeSized(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSized(minimum: Int, maximum: Int): ByteArray {
        val size = readInt()
        if (size !in minimum..maximum) throw VaultIntegrityException("Invalid encrypted field size")
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            writeSized(bytes)
        } finally {
            bytes.wipe()
        }
    }

    private fun DataInputStream.readString(maximumBytes: Int): String {
        val bytes = readSized(0, maximumBytes)
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.wipe()
        }
    }

    private class SeekableMediaReader(
        override val size: Long,
        private val channel: SeekableByteChannel,
        private val source: Closeable,
    ) : VaultMediaReader {
        @Synchronized
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val wanted = min(length.toLong(), size - position).toInt()
            if (wanted <= 0) return 0
            channel.position(position)
            val target = ByteBuffer.wrap(buffer, offset, wanted)
            while (target.hasRemaining()) {
                if (channel.read(target) <= 0) break
            }
            val read = wanted - target.remaining()
            return if (read == 0) -1 else read
        }

        override fun close() {
            try {
                channel.close()
            } finally {
                source.close()
            }
        }
    }

    private class ByteArrayMediaReader(private val bytes: ByteArray) : VaultMediaReader {
        override val size: Long = bytes.size.toLong()

        @Synchronized
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = min(length.toLong(), size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() = bytes.wipe()
    }

    companion object {
        /** Photos are decoded from memory; videos stream from disk and are not limited by this. */
        const val DEFAULT_OPEN_LIMIT = 100L * 1024L * 1024L
        const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L * 1024L

        private const val VAULT_DIRECTORY = "opaque_vault"
        private const val OBJECTS_DIRECTORY = "objects"
        private const val BLOB_SUFFIX = ".blob"
        private const val METADATA_SUFFIX = ".meta"
        private const val THUMBNAIL_SUFFIX = ".thumb"
        private const val PART_SUFFIX = ".part"
        private const val STALE_PART_MILLIS = 10L * 60L * 1000L
        private const val BLOB_MAGIC = 0x4F424C42 // OBLB (format 1)
        private const val METADATA_MAGIC = 0x4F4D4554 // OMET
        private const val THUMBNAIL_MAGIC = 0x4F544842 // OTHB
        private const val FORMAT_VERSION = 1
        private const val METADATA_PAYLOAD_VERSION = 2
        private const val CONTENT_FORMAT_SINGLE_MESSAGE = 1
        private const val CONTENT_FORMAT_STREAMING = 2
        private const val PADDING_BLOCK_BYTES = 64L * 1024L
        private const val THUMBNAIL_PADDING_BYTES = 16 * 1024
        private const val STREAM_BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_EVERY_BYTES = 4L * 1024L * 1024L
        private const val SPACE_CHECK_EVERY_BYTES = 32L * 1024L * 1024L
        private const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val LOW_SPACE_BYTES = 64L * 1024L * 1024L
        private const val SHA256_BYTES = 32
        private const val MAX_METADATA_FILE_BYTES = 32 * 1024
        private const val MAX_THUMBNAIL_JPEG_BYTES = 1024 * 1024
        private const val MAX_THUMBNAIL_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_ID_BYTES = 64
        private const val MAX_NAME_BYTES = 4 * 1024
        private const val MAX_MIME_BYTES = 512
        private const val MAX_DISPLAY_NAME_CHARS = 240
        private const val MAX_MIME_CHARS = 200
        private const val MAX_STORED_BYTES = MAX_IMPORT_BYTES
        private const val CONTENT_AAD_PREFIX_V1 = "opaque-vault/content/v1/"
        private const val CONTENT_AAD_PREFIX_V2 = "opaque-vault/content/v2/"
        private const val METADATA_AAD_PREFIX = "opaque-vault/metadata/v1/"
        private const val THUMBNAIL_AAD_PREFIX = "opaque-vault/thumbnail/v1/"
        private val OBJECT_ID = Regex("^[0-9a-f]{32}$")
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
