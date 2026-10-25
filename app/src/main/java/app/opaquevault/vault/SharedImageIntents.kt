package app.opaquevault.vault

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Extracts media URIs from ACTION_SEND / ACTION_SEND_MULTIPLE intents sent by Gallery and other apps.
 * OEM galleries do not all encode EXTRA_STREAM identically, so ClipData, ArrayList, List, array and
 * single-URI forms are accepted. Every value remains untrusted and is validated before use.
 */
object SharedImageIntents {
    fun extractImageUris(intent: Intent?, ownPackage: String): List<Uri> {
        if (intent == null) return emptyList()
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()

        val candidates = LinkedHashSet<Uri>()
        try {
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let(candidates::add)
                }
            }
        } catch (_: RuntimeException) {
            // Malformed ClipData from an untrusted sender.
        }

        try {
            if (action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(candidates::add)
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(candidates::addAll)
            }
        } catch (_: RuntimeException) {
            // Wrong typed extra or BadParcelableException; raw OEM variants are tried below.
        }

        try {
            @Suppress("DEPRECATION")
            when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
                is Uri -> candidates += raw
                is Iterable<*> -> raw.filterIsInstanceTo(candidates)
                is Array<*> -> raw.filterIsInstanceTo(candidates)
            }
        } catch (_: RuntimeException) {
            // Refuse malformed parcelables without crashing the exported share target.
        }

        // A few providers put a single shared item in Intent.data rather than EXTRA_STREAM.
        try {
            intent.data?.let(candidates::add)
        } catch (_: RuntimeException) {
            // Malformed URI parcelable.
        }
        return candidates.filter { isAcceptable(it, ownPackage) }
    }

    private fun isAcceptable(uri: Uri, ownPackage: String): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        val authority = uri.authority ?: return false
        return authority != ownPackage && !authority.startsWith("$ownPackage.")
    }
}
