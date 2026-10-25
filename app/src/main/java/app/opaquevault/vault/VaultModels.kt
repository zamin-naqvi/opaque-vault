package app.opaquevault.vault

import android.net.Uri
import java.io.Closeable

const val UNKNOWN_MIME_TYPE = "application/octet-stream"

data class VaultEntry(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val plaintextSize: Long,
    val paddedSize: Long,
    val importedAtEpochMillis: Long,
    /** 0 for photos, or when a video's length couldn't be read. */
    val durationMillis: Long = 0,
)

fun VaultEntry.isVideo(): Boolean = mimeType.startsWith("video/")

/** Images, plus items whose provider reported no type (decoding still validates them). */
fun VaultEntry.isViewableImage(): Boolean =
    mimeType.startsWith("image/") || mimeType == UNKNOWN_MIME_TYPE

fun VaultEntry.isViewable(): Boolean = isVideo() || isViewableImage()

fun isSupportedMediaType(mimeType: String): Boolean =
    mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType == UNKNOWN_MIME_TYPE

data class VaultListing(
    val entries: List<VaultEntry>,
    val corruptedObjectCount: Int,
)

/**
 * The source of a verified import. [sha256] is the digest of exactly the bytes that were encrypted,
 * so an original found later in MediaStore is only deleted when its content is provably identical.
 * Kept in memory only, for the delete-originals step.
 */
class ImportedOriginal(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: ByteArray,
)

class VaultImportResult(
    val entry: VaultEntry,
    val original: ImportedOriginal,
)

/**
 * Random-access view of one decrypted item, used for video playback and video thumbnails.
 * Every read authenticates the encrypted segments it touches; nothing is written to storage.
 */
interface VaultMediaReader : Closeable {
    /** Size of the original content in bytes (padding excluded). */
    val size: Long

    /** Reads up to [length] bytes at [position]; returns -1 at the end of the content. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

class VaultIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)

class VaultOpenLimitException(val limitBytes: Long) : Exception("File is too large to open safely in memory")

class UnsupportedImportTypeException(val mimeType: String) : Exception("Only photos and videos can be imported")

class ImportTooLargeException(val limitBytes: Long) : Exception("File exceeds the import size limit")

class InsufficientStorageException(val neededBytes: Long) : Exception("Not enough free storage for the encrypted copy")
