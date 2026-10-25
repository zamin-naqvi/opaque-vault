package app.opaquevault.vault

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.opaquevault.security.wipe
import java.security.MessageDigest

/**
 * Removes the originals of verified imports from shared storage.
 *
 * Gallery shares hand over real MediaStore items, which Android can delete after the user confirms.
 * Photo Picker URIs are read-only, so their originals are located in MediaStore instead and deleted
 * only when the candidate's SHA-256 equals the digest of the bytes that were encrypted. A wrong
 * photo can never be deleted by a name or size coincidence.
 */
class OriginalCleaner(context: Context) {
    data class Classification(
        val directlyDeleted: Int,
        val mediaStoreItems: List<Uri>,
        val needsSearch: List<ImportedOriginal>,
        val unsupported: Int,
    )

    data class SearchResult(
        val found: List<Uri>,
        val notFound: Int,
    )

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /** Deletes writable documents immediately and sorts the rest. */
    fun classify(originals: List<ImportedOriginal>): Classification {
        var directlyDeleted = 0
        var unsupported = 0
        val mediaStoreItems = LinkedHashSet<Uri>()
        val needsSearch = mutableListOf<ImportedOriginal>()
        for (original in originals) {
            val uri = original.uri
            when {
                uri == Uri.EMPTY -> unsupported++
                isPhotoPickerUri(uri) -> needsSearch += original
                isMediaStoreItem(uri) -> mediaStoreItems += uri
                DocumentsContract.isDocumentUri(appContext, uri) -> when {
                    tryDeleteDocument(uri) -> directlyDeleted++
                    else -> mediaUriForDocument(uri)?.let(mediaStoreItems::add) ?: run { needsSearch += original }
                }
                // Another app's own provider (for example a gallery's private share URI).
                else -> needsSearch += original
            }
        }
        return Classification(directlyDeleted, mediaStoreItems.toList(), needsSearch, unsupported)
    }

    fun mediaReadPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when photos or videos are visible to this app (all, or the ones the user selected). */
    fun canSearchMediaStore(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasFullMediaAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 11+ can delete other apps' photos after a system confirmation. */
    fun supportsSystemDeletion(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** "Media management" special access: Android then skips its per-deletion confirmation. */
    fun canDeleteWithoutConfirmation(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(appContext)

    fun searchMediaStore(
        originals: List<ImportedOriginal>,
        onProgress: (done: Int, total: Int) -> Unit,
        shouldContinue: () -> Boolean,
    ): SearchResult {
        val found = LinkedHashSet<Uri>()
        var notFound = 0
        for ((index, original) in originals.withIndex()) {
            if (!shouldContinue()) {
                notFound += originals.size - index
                break
            }
            onProgress(index, originals.size)
            val collection = collectionFor(original.mimeType)
            val byName = queryIds(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(original.displayName, original.sizeBytes.toString()),
            )
            val candidates = byName.ifEmpty {
                queryIds(collection, "${MediaStore.MediaColumns.SIZE} = ?", arrayOf(original.sizeBytes.toString()))
            }
            val match = candidates
                .asSequence()
                .map { ContentUris.withAppendedId(collection, it) }
                .firstOrNull { it !in found && contentMatches(it, original.sha256) }
            if (match != null) found += match else notFound++
        }
        return SearchResult(found.toList(), notFound)
    }

    fun createDeleteRequest(uris: List<Uri>): IntentSender? {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.createDeleteRequest(resolver, uris).intentSender
        } catch (_: Exception) {
            null
        }
    }

    /** How many of [uris] still exist, or null when this app can't see other apps' photos. */
    fun countStillPresent(uris: List<Uri>): Int? {
        if (!canSearchMediaStore()) return null
        return uris.count { uri ->
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() } == true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun queryIds(collection: Uri, selection: String, args: Array<String>): List<Long> {
        val ids = mutableListOf<Long>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                resolver.query(
                    collection,
                    projection,
                    Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_CANDIDATES)
                    },
                    null,
                )
            } else {
                resolver.query(collection, projection, selection, args, null)
            }
            cursor?.use {
                val column = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (it.moveToNext() && ids.size < MAX_CANDIDATES) ids += it.getLong(column)
            }
        } catch (_: Exception) {
            // Missing permission or provider error: treated as "not found".
        }
        return ids
    }

    private fun contentMatches(uri: Uri, expected: ByteArray): Boolean {
        val buffer = ByteArray(256 * 1024)
        var actual: ByteArray? = null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
                val computed = digest.digest()
                actual = computed
                MessageDigest.isEqual(computed, expected)
            } ?: false
        } catch (_: Exception) {
            false
        } finally {
            buffer.wipe()
            actual?.wipe()
        }
    }

    private fun collectionFor(mimeType: String): Uri {
        val video = mimeType.startsWith("video/")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            }
        } else {
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun tryDeleteDocument(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) {
        // SecurityException (read-only grant), FileNotFoundException, or provider refusal.
        false
    }

    private fun mediaUriForDocument(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            MediaStore.getMediaUri(appContext, uri)?.takeIf(::isMediaStoreItem)
        } catch (_: Exception) {
            null
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun isPhotoPickerUri(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull()?.startsWith("picker") == true

    /** content://media/<volume>/<images|video|file|downloads>/media/<id> */
    private fun isMediaStoreItem(uri: Uri): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        if (uri.authority != MediaStore.AUTHORITY) return false
        val segments = uri.pathSegments
        if (segments.size < 3 || segments.first().startsWith("picker")) return false
        val id = segments.last()
        return id.isNotEmpty() && id.all(Char::isDigit)
    }

    private companion object {
        const val MAX_CANDIDATES = 25
    }
}
