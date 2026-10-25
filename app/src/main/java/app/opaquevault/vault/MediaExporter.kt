package app.opaquevault.vault

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import app.opaquevault.security.wipe
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale

/** Where decrypted copies go. */
sealed interface ExportTarget {
    /** Pictures/Opaque Vault and Movies/Opaque Vault, visible in Gallery (Android 10+). */
    data object Gallery : ExportTarget

    /** A folder the user picked with the system file picker (any Android version). */
    class Folder(val treeUri: Uri) : ExportTarget
}

/**
 * Writes vault items back out as ordinary files.
 *
 * Content is streamed from the authenticated decrypting reader straight into the destination, so a
 * multi-GB video never sits in memory and nothing is staged in app storage. The written bytes are
 * hashed and must equal the digest recorded at import; otherwise the partial copy is deleted, so a
 * damaged or tampered item can never be exported silently.
 */
class MediaExporter(context: Context, private val repository: VaultRepository) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun export(
        masterKey: ByteArray,
        entry: VaultEntry,
        target: ExportTarget,
        shouldContinue: () -> Boolean,
        onBytes: (written: Long) -> Unit,
        stripSensitiveMetadata: Boolean = false,
    ): Uri {
        val expected = repository.contentDigest(masterKey, entry.id)
        val name = exportName(entry)
        val mime = entry.mimeType.takeIf { it != UNKNOWN_MIME_TYPE } ?: mimeFromName(name)
        var created: Uri? = null
        val buffer = ByteArray(BUFFER_BYTES)
        var actual: ByteArray? = null
        try {
            created = when (target) {
                ExportTarget.Gallery -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("Saving to Gallery needs Android 10 or newer")
                    createInGallery(name, mime)
                }
                is ExportTarget.Folder -> createInFolder(target.treeUri, name, mime)
            }
            val output = resolver.openOutputStream(created, "w") ?: throw IOException("Cannot write the exported file")
            val digest = MessageDigest.getInstance("SHA-256")
            repository.openReader(masterKey, entry.id).use { reader ->
                output.use { stream ->
                    var position = 0L
                    while (position < reader.size) {
                        if (!shouldContinue()) throw InterruptedIOException("Export stopped")
                        val count = reader.read(position, buffer, 0, buffer.size)
                        if (count <= 0) throw IOException("Encrypted item ended early")
                        digest.update(buffer, 0, count)
                        stream.write(buffer, 0, count)
                        position += count
                        onBytes(position)
                    }
                    stream.flush()
                    (stream as? FileOutputStream)?.fd?.sync()
                }
            }
            actual = digest.digest()
            if (!MessageDigest.isEqual(actual, expected)) {
                throw VaultIntegrityException("Exported content doesn't match the vault's record")
            }
            if (stripSensitiveMetadata && mime in EXIF_WRITABLE_MIME_TYPES) {
                stripSensitiveExif(created)
            }
            if (target == ExportTarget.Gallery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return created
        } catch (error: Throwable) {
            created?.let(::discard)
            throw error
        } finally {
            buffer.wipe()
            expected.wipe()
            actual?.wipe()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createInGallery(name: String, mime: String): Uri {
        val video = mime.startsWith("video/")
        val image = mime.startsWith("image/")
        val (collection, folder) = when {
            video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            image -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$EXPORT_FOLDER")
            // Hidden from Gallery until the copy is complete and verified.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            resolver.insert(collection, values)
        } catch (_: IllegalArgumentException) {
            // A type MediaStore won't file under Pictures/Movies: fall back to Downloads.
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER")
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } ?: throw IOException("Android refused to create the Gallery item")
    }

    private fun createInFolder(treeUri: Uri, name: String, mime: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        // The provider renames on collision ("photo (1).jpg"), so nothing is overwritten.
        return DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("The folder refused the new file")
    }

    private fun discard(uri: Uri) {
        try {
            if (uri.authority == MediaStore.AUTHORITY) resolver.delete(uri, null, null) else DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            // Best effort: a pending MediaStore item is never shown and is purged by Android.
        }
    }

    private fun stripSensitiveExif(uri: Uri) {
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot sanitize exported photo metadata")
        descriptor.use {
            val exif = ExifInterface(it.fileDescriptor)
            SENSITIVE_EXIF_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
            exif.saveAttributes()
        }
    }

    /** A safe file name with an extension, never a path. */
    private fun exportName(entry: VaultEntry): String {
        var base = entry.displayName
            .replace('/', '_')
            .replace('\\', '_')
            .filterNot(Char::isISOControl)
            .trim()
            .trimStart('.')
            .take(MAX_NAME_CHARS)
            .ifBlank { if (entry.isVideo()) "video" else "photo" }
        if (!base.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(entry.mimeType)?.let { base = "$base.$it" }
        }
        return base
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: UNKNOWN_MIME_TYPE

    companion object {
        const val EXPORT_FOLDER = "Opaque Vault"
        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_NAME_CHARS = 200
        private val EXIF_WRITABLE_MIME_TYPES = setOf("image/jpeg", "image/heic", "image/heif", "image/webp")
        private val SENSITIVE_EXIF_TAGS = arrayOf(
            "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef",
            "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod", "GPSAreaInformation", "Make", "Model", "Software",
            "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "BodySerialNumber", "CameraOwnerName", "LensMake", "LensModel", "LensSerialNumber", "ImageUniqueID", "UserComment",
        )
    }
}
