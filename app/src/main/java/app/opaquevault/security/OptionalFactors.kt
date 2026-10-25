package app.opaquevault.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest

/** Argon2id verifier for the optional destructive duress passphrase. */
class DuressManager(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.contains(KEY_SALT) && preferences.contains(KEY_VERIFIER)

    fun enroll(passphrase: CharArray) {
        require(passphrase.size in 16..256) { "Use at least 16 characters for the duress passphrase." }
        val salt = CryptoEngine.randomBytes(32)
        val verifier = CryptoEngine.deriveArgon2id(passphrase, salt, MEMORY_KIB, ITERATIONS, 1)
        try {
            preferences.edit(commit = true) {
                putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                putString(KEY_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
            }
        } finally {
            salt.wipe()
            verifier.wipe()
        }
    }

    fun matches(passphrase: CharArray): Boolean {
        val salt = decode(KEY_SALT) ?: return false
        val expected = decode(KEY_VERIFIER) ?: run { salt.wipe(); return false }
        val actual = CryptoEngine.deriveArgon2id(passphrase, salt, MEMORY_KIB, ITERATIONS, 1)
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            salt.wipe(); expected.wipe(); actual.wipe()
        }
    }

    fun clear() = preferences.edit(commit = true) { clear() }

    private fun decode(key: String): ByteArray? = try {
        preferences.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val FILE_NAME = "duress_factor"
        private const val KEY_SALT = "salt"
        private const val KEY_VERIFIER = "verifier"
        private const val MEMORY_KIB = 65_536
        private const val ITERATIONS = 3
    }
}

/**
 * Optional NFC possession factor. Only a salted verifier of the tag identifier is stored.
 * Dedicated tags are recommended; an NFC UID can be cloned by specialist hardware, so this is
 * defense in depth rather than FIDO2-grade challenge-response.
 */
class NfcKeyManager(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnrolled(): Boolean = preferences.contains(KEY_SALT) && preferences.contains(KEY_VERIFIER)

    fun enroll(tagId: ByteArray) {
        require(tagId.isNotEmpty()) { "This NFC tag did not expose an identifier." }
        val salt = CryptoEngine.randomBytes(32)
        val verifier = CryptoEngine.hmacSha256(salt, tagId)
        try {
            preferences.edit(commit = true) {
                putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                putString(KEY_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
            }
        } finally {
            salt.wipe(); verifier.wipe()
        }
    }

    fun matches(tagId: ByteArray): Boolean {
        if (tagId.isEmpty()) return false
        val salt = decode(KEY_SALT) ?: return false
        val expected = decode(KEY_VERIFIER) ?: run { salt.wipe(); return false }
        val actual = CryptoEngine.hmacSha256(salt, tagId)
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            salt.wipe(); expected.wipe(); actual.wipe()
        }
    }

    fun clear() = preferences.edit(commit = true) { clear() }

    private fun decode(key: String): ByteArray? = try {
        preferences.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val FILE_NAME = "nfc_factor"
        private const val KEY_SALT = "salt"
        private const val KEY_VERIFIER = "verifier"
    }
}

data class IntruderEvent(val epochMillis: Long, val reason: String)
