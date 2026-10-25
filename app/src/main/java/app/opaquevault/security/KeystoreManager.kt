package app.opaquevault.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class KeystoreManager {
    enum class HardwareLevel(val label: String) {
        STRONGBOX("StrongBox secure hardware"),
        TRUSTED_ENVIRONMENT("Trusted execution environment"),
        SOFTWARE("Software-backed Keystore"),
        UNKNOWN("Keystore protection unknown"),
    }

    data class ProtectedDeviceFactor(
        val packet: CryptoEngine.AeadPacket,
        val hardwareLevel: HardwareLevel,
    )

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun createAndProtect(secret: ByteArray, aad: ByteArray): ProtectedDeviceFactor {
        require(secret.size == CryptoEngine.KEY_BYTES)
        deleteKey()
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateKey(requestStrongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                deleteKey()
                generateKey(requestStrongBox = false)
            } catch (_: ProviderException) {
                deleteKey()
                generateKey(requestStrongBox = false)
            }
        } else {
            generateKey(requestStrongBox = false)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(secret)
        return ProtectedDeviceFactor(
            packet = CryptoEngine.AeadPacket(cipher.iv.copyOf(), ciphertext),
            hardwareLevel = inspectHardwareLevel(key),
        )
    }

    fun unprotect(packet: CryptoEngine.AeadPacket, aad: ByteArray): ByteArray {
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw DeviceKeyUnavailableException("This vault's Android Keystore key is unavailable")
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, packet.nonce))
            updateAAD(aad)
            doFinal(packet.ciphertext)
        }
    }

    fun hardwareLevel(): HardwareLevel {
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return HardwareLevel.UNKNOWN
        return inspectHardwareLevel(key)
    }

    fun hasKey(): Boolean = keyStore.containsAlias(KEY_ALIAS)

    fun deleteKey() {
        val store = keyStore
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        if (store.containsAlias(KEY_ALIAS)) throw ProviderException("Android Keystore key deletion failed")
    }

    private fun generateKey(requestStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(requestStrongBox)
            builder.setUnlockedDeviceRequired(true)
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    @Suppress("DEPRECATION")
    private fun inspectHardwareLevel(key: SecretKey): HardwareLevel = runCatching {
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.getSecurityLevel()) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> HardwareLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> HardwareLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> HardwareLevel.SOFTWARE
                else -> HardwareLevel.UNKNOWN
            }
        } else if (info.isInsideSecureHardware()) {
            HardwareLevel.TRUSTED_ENVIRONMENT
        } else {
            HardwareLevel.SOFTWARE
        }
    }.getOrDefault(HardwareLevel.UNKNOWN)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opaque_vault_device_factor_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class DeviceKeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
