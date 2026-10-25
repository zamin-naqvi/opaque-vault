package app.opaquevault.security

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.util.SecretBytes
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Cryptographic primitives used by the vault. No provider is globally installed. */
object CryptoEngine {
    const val KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    data class AeadPacket(
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    /**
     * Tink's audited streaming AEAD: AES-256-GCM over [segmentBytes] segments, each with its own
     * HKDF-derived key schedule and authentication tag. Memory stays bounded for any file size, and
     * a seekable channel can decrypt (and authenticate) only the segments a video player needs.
     */
    fun streamingAead(key: ByteArray, segmentBytes: Int = STREAM_SEGMENT_BYTES): StreamingAead {
        require(key.size == KEY_BYTES) { "Streaming AEAD requires a 32-byte key" }
        val parameters = AesGcmHkdfStreamingParameters.Builder()
            .setKeySizeBytes(KEY_BYTES)
            .setDerivedAesGcmKeySizeBytes(KEY_BYTES)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(segmentBytes)
            .build()
        return AesGcmHkdfStreaming.create(
            AesGcmHkdfStreamingKey.create(parameters, SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get())),
        )
    }

    const val STREAM_SEGMENT_BYTES = 1024 * 1024

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun randomize(bytes: ByteArray) = secureRandom.nextBytes(bytes)

    fun newAesGcmCipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher {
        require(key.size == KEY_BYTES) { "AES-256 requires a 32-byte key" }
        require(nonce.size == GCM_NONCE_BYTES) { "GCM requires a 12-byte nonce" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): AeadPacket {
        val nonce = randomBytes(GCM_NONCE_BYTES)
        val cipher = newAesGcmCipher(Cipher.ENCRYPT_MODE, key, nonce, aad)
        return AeadPacket(nonce, cipher.doFinal(plaintext))
    }

    @Throws(AEADBadTagException::class)
    fun decrypt(key: ByteArray, packet: AeadPacket, aad: ByteArray): ByteArray {
        val cipher = newAesGcmCipher(Cipher.DECRYPT_MODE, key, packet.nonce, aad)
        return cipher.doFinal(packet.ciphertext)
    }

    fun deriveArgon2id(
        passphrase: CharArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        require(salt.size >= 16) { "Argon2id salt is too short" }
        require(memoryKiB in 65_536..262_144) { "Unsupported Argon2id memory cost" }
        require(iterations in 3..6) { "Unsupported Argon2id iteration count" }
        require(parallelism in 1..4) { "Unsupported Argon2id parallelism" }

        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val output = ByteArray(KEY_BYTES)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passphrase, output)
        return output
    }

    /** RFC 5869 HKDF-SHA-256 over independently supplied input factors. */
    fun hkdfSha256(
        factors: List<ByteArray>,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray {
        require(factors.isNotEmpty())
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        factors.forEach(extract::update)
        val pseudorandomKey = extract.doFinal()
        return try {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            expand.update(info)
            expand.update(1.toByte())
            expand.doFinal().copyOf(KEY_BYTES)
        } finally {
            pseudorandomKey.wipe()
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
        var difference = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val l = if (index < left.size) left[index].code else 0
            val r = if (index < right.size) right[index].code else 0
            difference = difference or (l xor r)
        }
        return difference == 0
    }
}

fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')
