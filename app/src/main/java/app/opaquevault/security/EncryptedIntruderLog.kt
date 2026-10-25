package app.opaquevault.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

/** Small Android-Keystore-encrypted log. It never records attempted passphrases or media names. */
class EncryptedIntruderLog(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val photoFile = File(context.filesDir, PHOTO_FILE_NAME)
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun append(reason: String) {
        val events = runCatching(::read).getOrDefault(emptyList()).takeLast(MAX_EVENTS - 1).toMutableList()
        events += IntruderEvent(System.currentTimeMillis(), sanitize(reason))
        write(events)
    }

    @Synchronized
    fun read(): List<IntruderEvent> {
        if (!file.isFile) return emptyList()
        if (file.length() !in 1..MAX_FILE_BYTES.toLong()) throw IllegalStateException("Invalid intruder log")
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IllegalStateException("Invalid intruder log")
            val nonceSize = input.readInt()
            if (nonceSize != CryptoEngine.GCM_NONCE_BYTES) throw IllegalStateException("Invalid intruder log nonce")
            val nonce = ByteArray(nonceSize).also(input::readFully)
            val cipherSize = input.readInt()
            if (cipherSize !in 16..MAX_FILE_BYTES) { nonce.wipe(); throw IllegalStateException("Invalid intruder log size") }
            val ciphertext = ByteArray(cipherSize).also(input::readFully)
            val plaintext = try {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, nonce))
                    updateAAD(AAD)
                    doFinal(ciphertext)
                }
            } finally {
                nonce.wipe(); ciphertext.wipe()
            }
            return try { decode(plaintext) } finally { plaintext.wipe() }
        }
    }

    @Synchronized
    fun saveLatestPhoto(jpeg: ByteArray) {
        require(jpeg.size in 1..MAX_PHOTO_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey()); updateAAD(PHOTO_AAD)
        }
        val ciphertext = cipher.doFinal(jpeg)
        val temporary = File(photoFile.parentFile, "${photoFile.name}.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(raw.buffered()).use { output ->
                    output.writeInt(PHOTO_MAGIC); output.writeInt(VERSION)
                    output.writeInt(cipher.iv.size); output.write(cipher.iv)
                    output.writeInt(ciphertext.size); output.write(ciphertext)
                    output.flush(); raw.fd.sync()
                }
            }
            if (!temporary.renameTo(photoFile)) throw IllegalStateException("Unable to commit intruder photo")
        } finally { ciphertext.wipe(); temporary.delete() }
    }

    @Synchronized
    fun readLatestPhoto(): ByteArray? {
        if (!photoFile.isFile || photoFile.length() !in 1..(MAX_PHOTO_BYTES + 1024).toLong()) return null
        return DataInputStream(FileInputStream(photoFile).buffered()).use { input ->
            if (input.readInt() != PHOTO_MAGIC || input.readInt() != VERSION) return@use null
            val nonceSize = input.readInt()
            if (nonceSize != CryptoEngine.GCM_NONCE_BYTES) return@use null
            val nonce = ByteArray(nonceSize).also(input::readFully)
            val cipherSize = input.readInt()
            if (cipherSize !in 16..MAX_PHOTO_BYTES + 32) { nonce.wipe(); return@use null }
            val ciphertext = ByteArray(cipherSize).also(input::readFully)
            try {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, nonce))
                    updateAAD(PHOTO_AAD); doFinal(ciphertext)
                }
            } finally { nonce.wipe(); ciphertext.wipe() }
        }
    }

    @Synchronized
    fun clear(deleteKey: Boolean = false) {
        file.delete()
        photoFile.delete()
        if (deleteKey) {
            val store = keyStore
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun write(events: List<IntruderEvent>) {
        val plaintext = encode(events)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(AAD)
        }
        val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.wipe() }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(raw.buffered()).use { output ->
                    output.writeInt(MAGIC); output.writeInt(VERSION)
                    output.writeInt(cipher.iv.size); output.write(cipher.iv)
                    output.writeInt(ciphertext.size); output.write(ciphertext)
                    output.flush(); raw.fd.sync()
                }
            }
            if (!temporary.renameTo(file)) throw IllegalStateException("Unable to commit intruder log")
        } finally {
            ciphertext.wipe(); temporary.delete()
        }
    }

    private fun encode(events: List<IntruderEvent>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(events.size)
            events.forEach { event -> output.writeLong(event.epochMillis); output.writeUTF(sanitize(event.reason)) }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<IntruderEvent> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val count = input.readInt()
        require(count in 0..MAX_EVENTS)
        List(count) { IntruderEvent(input.readLong(), input.readUTF().take(MAX_REASON_CHARS)) }
            .also { require(input.read() == -1) }
    }

    private fun sanitize(reason: String): String = reason.filterNot(Char::isISOControl).take(MAX_REASON_CHARS).ifBlank { "Failed unlock" }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec); generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opaque_vault_intruder_log_v1"
        private const val FILE_NAME = "intruder-events.enc"
        private const val PHOTO_FILE_NAME = "intruder-photo.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAGIC = 0x4F56494C // OVIL
        private const val PHOTO_MAGIC = 0x4F564950 // OVIP
        private const val VERSION = 1
        private const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
        private const val MAX_EVENTS = 50
        private const val MAX_REASON_CHARS = 120
        private const val MAX_FILE_BYTES = 32 * 1024
        private val AAD = "opaque-vault/intruder-log/v1".toByteArray(Charsets.UTF_8)
        private val PHOTO_AAD = "opaque-vault/intruder-photo/v1".toByteArray(Charsets.UTF_8)
    }
}
