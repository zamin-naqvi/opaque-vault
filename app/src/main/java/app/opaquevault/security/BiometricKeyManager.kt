package app.opaquevault.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional biometric-only path to the vault master key.
 *
 * The envelope contains only AES-GCM ciphertext. Its non-exportable Android Keystore key requires a
 * fresh BIOMETRIC_STRONG authentication for every decrypt operation. The normal passphrase remains
 * available as recovery if biometric enrollment changes and invalidates this key.
 */
class BiometricKeyManager(context: Context) {
    private val envelope = File(context.filesDir, ENVELOPE_FILE)
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun isEnrolled(): Boolean = envelope.isFile && keyStore.containsAlias(KEY_ALIAS)

    /** Cipher passed to BiometricPrompt.CryptoObject while enabling the feature. */
    fun enrollmentCipher(): Cipher {
        clear()
        val key = generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Commits the encrypted master-key envelope only after BiometricPrompt succeeds. */
    fun finishEnrollment(authenticatedCipher: Cipher, masterKey: ByteArray) {
        require(masterKey.size == CryptoEngine.KEY_BYTES)
        val ciphertext = authenticatedCipher.doFinal(masterKey)
        val temporary = File(envelope.parentFile, "${envelope.name}.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(raw.buffered()).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(authenticatedCipher.iv.size)
                    output.write(authenticatedCipher.iv)
                    output.writeInt(ciphertext.size)
                    output.write(ciphertext)
                    output.flush()
                    raw.fd.sync()
                }
            }
            if (!temporary.renameTo(envelope)) throw IllegalStateException("Unable to commit biometric key envelope")
        } finally {
            ciphertext.wipe()
            temporary.delete()
        }
    }

    /** Cipher passed to BiometricPrompt.CryptoObject while unlocking. */
    fun unlockCipher(): Cipher {
        val packet = readEnvelope()
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw BiometricKeyUnavailableException("Biometric key is missing")
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, packet.first))
            }
        } catch (error: KeyPermanentlyInvalidatedException) {
            clear()
            throw BiometricKeyUnavailableException("Biometric enrollment changed; use the passphrase and enable it again", error)
        } finally {
            packet.first.wipe()
            packet.second.wipe()
        }
    }

    fun finishUnlock(authenticatedCipher: Cipher): ByteArray {
        val packet = readEnvelope()
        return try {
            authenticatedCipher.doFinal(packet.second).also {
                if (it.size != CryptoEngine.KEY_BYTES) {
                    it.wipe()
                    throw BiometricKeyUnavailableException("Invalid biometric envelope")
                }
            }
        } finally {
            packet.first.wipe()
            packet.second.wipe()
        }
    }

    fun clear() {
        envelope.delete()
        val store = keyStore
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun generateKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun readEnvelope(): Pair<ByteArray, ByteArray> {
        if (!envelope.isFile || envelope.length() !in 1..MAX_ENVELOPE_BYTES.toLong()) {
            throw BiometricKeyUnavailableException("Biometric key is not enrolled")
        }
        return try {
            DataInputStream(FileInputStream(envelope).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw BiometricKeyUnavailableException("Invalid biometric envelope")
                val nonceSize = input.readInt()
                if (nonceSize != CryptoEngine.GCM_NONCE_BYTES) throw BiometricKeyUnavailableException("Invalid biometric nonce")
                val nonce = ByteArray(nonceSize).also(input::readFully)
                val cipherSize = input.readInt()
                if (cipherSize !in 16..128) {
                    nonce.wipe()
                    throw BiometricKeyUnavailableException("Invalid biometric ciphertext")
                }
                val ciphertext = ByteArray(cipherSize).also(input::readFully)
                if (input.read() != -1) {
                    nonce.wipe(); ciphertext.wipe()
                    throw BiometricKeyUnavailableException("Trailing biometric envelope data")
                }
                nonce to ciphertext
            }
        } catch (error: BiometricKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw BiometricKeyUnavailableException("Unable to read biometric key envelope", error)
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opaque_vault_biometric_master_v1"
        private const val ENVELOPE_FILE = "biometric-master.envelope"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAGIC = 0x4F56424D // OVBM
        private const val VERSION = 1
        private const val MAX_ENVELOPE_BYTES = 512
    }
}

class BiometricKeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
