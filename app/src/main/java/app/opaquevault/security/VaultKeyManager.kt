package app.opaquevault.security

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

class VaultKeyManager(context: Context) {
    data class KdfParameters(
        val memoryKiB: Int,
        val iterations: Int,
        val parallelism: Int,
    )

    data class UnlockedVault(
        val masterKey: ByteArray,
        val hardwareLevel: KeystoreManager.HardwareLevel,
        val kdfParameters: KdfParameters,
    )

    private data class VaultHeader(
        val createdAtEpochMillis: Long,
        val kdf: KdfParameters,
        val salt: ByteArray,
        val devicePacket: CryptoEngine.AeadPacket,
        val masterPacket: CryptoEngine.AeadPacket,
        val integrityTag: ByteArray,
    )

    private val vaultDirectory = File(context.filesDir, VAULT_DIRECTORY)
    private val headerFile = File(vaultDirectory, HEADER_FILE)
    private val keystore = KeystoreManager()

    fun isInitialized(): Boolean = headerFile.isFile

    fun createVault(passphrase: CharArray, kdf: KdfParameters): UnlockedVault {
        check(!isInitialized()) { "A vault already exists" }
        validateKdf(kdf)
        vaultDirectory.mkdirsOrThrow()

        val salt = CryptoEngine.randomBytes(SALT_BYTES)
        val deviceFactor = CryptoEngine.randomBytes(CryptoEngine.KEY_BYTES)
        val masterKey = CryptoEngine.randomBytes(CryptoEngine.KEY_BYTES)
        var passwordKey: ByteArray? = null
        var wrappingKey: ByteArray? = null
        try {
            val protectedDevice = keystore.createAndProtect(deviceFactor, deviceAad())
            passwordKey = CryptoEngine.deriveArgon2id(
                passphrase,
                salt,
                kdf.memoryKiB,
                kdf.iterations,
                kdf.parallelism,
            )
            wrappingKey = CryptoEngine.hkdfSha256(
                listOf(passwordKey, deviceFactor),
                salt,
                MASTER_WRAP_INFO,
            )
            val createdAt = System.currentTimeMillis()
            val aad = masterAad(createdAt, kdf, salt, protectedDevice.packet)
            val masterPacket = CryptoEngine.encrypt(wrappingKey, masterKey, aad)
            val integrityTag = headerIntegrityTag(
                deviceFactor,
                createdAt,
                kdf,
                salt,
                protectedDevice.packet,
                masterPacket,
            )
            try {
                val header = VaultHeader(
                    createdAt,
                    kdf,
                    salt,
                    protectedDevice.packet,
                    masterPacket,
                    integrityTag,
                )
                writeHeaderAtomically(header)
            } finally {
                integrityTag.wipe()
            }
            return UnlockedVault(masterKey, protectedDevice.hardwareLevel, kdf)
        } catch (error: Throwable) {
            masterKey.wipe()
            runCatching { keystore.deleteKey() }
            headerFile.delete()
            throw error
        } finally {
            passwordKey?.wipe()
            wrappingKey?.wipe()
            deviceFactor.wipe()
            salt.wipe()
        }
    }

    @Throws(WrongPassphraseException::class, VaultCorruptException::class)
    fun unlock(passphrase: CharArray): UnlockedVault {
        val header = readHeader()
        var deviceFactor: ByteArray? = null
        var passwordKey: ByteArray? = null
        var wrappingKey: ByteArray? = null
        try {
            deviceFactor = try {
                keystore.unprotect(header.devicePacket, deviceAad())
            } catch (error: DeviceKeyUnavailableException) {
                throw error
            } catch (error: Throwable) {
                throw VaultCorruptException("The device-bound key could not authenticate the vault", error)
            }
            val actualIntegrityTag = headerIntegrityTag(
                deviceFactor,
                header.createdAtEpochMillis,
                header.kdf,
                header.salt,
                header.devicePacket,
                header.masterPacket,
            )
            try {
                if (!MessageDigest.isEqual(actualIntegrityTag, header.integrityTag)) {
                    throw VaultCorruptException("The vault header was modified")
                }
            } finally {
                actualIntegrityTag.wipe()
            }
            passwordKey = CryptoEngine.deriveArgon2id(
                passphrase,
                header.salt,
                header.kdf.memoryKiB,
                header.kdf.iterations,
                header.kdf.parallelism,
            )
            wrappingKey = CryptoEngine.hkdfSha256(
                listOf(passwordKey, deviceFactor),
                header.salt,
                MASTER_WRAP_INFO,
            )
            val masterKey = try {
                CryptoEngine.decrypt(
                    wrappingKey,
                    header.masterPacket,
                    masterAad(
                        header.createdAtEpochMillis,
                        header.kdf,
                        header.salt,
                        header.devicePacket,
                    ),
                )
            } catch (error: AEADBadTagException) {
                throw WrongPassphraseException()
            }
            if (masterKey.size != CryptoEngine.KEY_BYTES) {
                masterKey.wipe()
                throw VaultCorruptException("Invalid master-key length")
            }
            return UnlockedVault(masterKey, keystore.hardwareLevel(), header.kdf)
        } finally {
            deviceFactor?.wipe()
            passwordKey?.wipe()
            wrappingKey?.wipe()
            header.salt.wipe()
            header.devicePacket.nonce.wipe()
            header.devicePacket.ciphertext.wipe()
            header.masterPacket.nonce.wipe()
            header.masterPacket.ciphertext.wipe()
            header.integrityTag.wipe()
        }
    }

    fun changePassphrase(masterKey: ByteArray, newPassphrase: CharArray, kdf: KdfParameters) {
        require(masterKey.size == CryptoEngine.KEY_BYTES)
        validateKdf(kdf)
        val oldHeader = readHeader()
        val newSalt = CryptoEngine.randomBytes(SALT_BYTES)
        var deviceFactor: ByteArray? = null
        var passwordKey: ByteArray? = null
        var wrappingKey: ByteArray? = null
        try {
            deviceFactor = keystore.unprotect(oldHeader.devicePacket, deviceAad())
            val currentIntegrityTag = headerIntegrityTag(
                deviceFactor,
                oldHeader.createdAtEpochMillis,
                oldHeader.kdf,
                oldHeader.salt,
                oldHeader.devicePacket,
                oldHeader.masterPacket,
            )
            try {
                if (!MessageDigest.isEqual(currentIntegrityTag, oldHeader.integrityTag)) {
                    throw VaultCorruptException("The current vault header was modified")
                }
            } finally {
                currentIntegrityTag.wipe()
            }
            passwordKey = CryptoEngine.deriveArgon2id(
                newPassphrase,
                newSalt,
                kdf.memoryKiB,
                kdf.iterations,
                kdf.parallelism,
            )
            wrappingKey = CryptoEngine.hkdfSha256(
                listOf(passwordKey, deviceFactor),
                newSalt,
                MASTER_WRAP_INFO,
            )
            val aad = masterAad(oldHeader.createdAtEpochMillis, kdf, newSalt, oldHeader.devicePacket)
            val packet = CryptoEngine.encrypt(wrappingKey, masterKey, aad)
            val integrityTag = headerIntegrityTag(
                deviceFactor,
                oldHeader.createdAtEpochMillis,
                kdf,
                newSalt,
                oldHeader.devicePacket,
                packet,
            )
            try {
                writeHeaderAtomically(
                    VaultHeader(
                        oldHeader.createdAtEpochMillis,
                        kdf,
                        newSalt,
                        oldHeader.devicePacket,
                        packet,
                        integrityTag,
                    ),
                )
            } finally {
                integrityTag.wipe()
            }
        } finally {
            deviceFactor?.wipe()
            passwordKey?.wipe()
            wrappingKey?.wipe()
            newSalt.wipe()
            oldHeader.salt.wipe()
            oldHeader.devicePacket.nonce.wipe()
            oldHeader.devicePacket.ciphertext.wipe()
            oldHeader.masterPacket.nonce.wipe()
            oldHeader.masterPacket.ciphertext.wipe()
            oldHeader.integrityTag.wipe()
        }
    }

    /** Deletes the non-exportable key first; remaining ciphertext then has no usable key path. */
    fun cryptoErase() {
        keystore.deleteKey()
        vaultDirectory.deleteRecursively()
    }

    fun hardwareLevel(): KeystoreManager.HardwareLevel = keystore.hardwareLevel()

    private fun writeHeaderAtomically(header: VaultHeader) {
        vaultDirectory.mkdirsOrThrow()
        val temporary = File(vaultDirectory, "$HEADER_FILE.tmp")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(fileOutput.buffered()).use { output ->
                    output.writeInt(HEADER_MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeLong(header.createdAtEpochMillis)
                    output.writeInt(header.kdf.memoryKiB)
                    output.writeInt(header.kdf.iterations)
                    output.writeInt(header.kdf.parallelism)
                    output.writeSized(header.salt)
                    output.writePacket(header.devicePacket)
                    output.writePacket(header.masterPacket)
                    output.writeSized(header.integrityTag)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            Os.rename(temporary.absolutePath, headerFile.absolutePath)
        } catch (error: Throwable) {
            temporary.delete()
            throw IOException("Unable to commit the vault header", error)
        }
    }

    private fun readHeader(): VaultHeader {
        if (!headerFile.isFile) throw VaultCorruptException("Vault header is missing")
        if (headerFile.length() !in 1..MAX_HEADER_BYTES.toLong()) {
            throw VaultCorruptException("Vault header has an invalid size")
        }
        try {
            DataInputStream(FileInputStream(headerFile).buffered()).use { input ->
                if (input.readInt() != HEADER_MAGIC) throw VaultCorruptException("Unknown vault header")
                if (input.readInt() != FORMAT_VERSION) throw VaultCorruptException("Unsupported vault version")
                val createdAt = input.readLong()
                val kdf = KdfParameters(input.readInt(), input.readInt(), input.readInt())
                validateKdf(kdf)
                val salt = input.readSized(SALT_BYTES, SALT_BYTES)
                val devicePacket = input.readPacket()
                val masterPacket = input.readPacket()
                val integrityTag = input.readSized(INTEGRITY_TAG_BYTES, INTEGRITY_TAG_BYTES)
                if (input.read() != -1) throw VaultCorruptException("Trailing vault-header data")
                return VaultHeader(createdAt, kdf, salt, devicePacket, masterPacket, integrityTag)
            }
        } catch (error: VaultCorruptException) {
            throw error
        } catch (error: EOFException) {
            throw VaultCorruptException("Truncated vault header", error)
        } catch (error: Throwable) {
            throw VaultCorruptException("Unable to read vault header", error)
        }
    }

    private fun masterAad(
        createdAt: Long,
        kdf: KdfParameters,
        salt: ByteArray,
        devicePacket: CryptoEngine.AeadPacket,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(HEADER_MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeLong(createdAt)
            output.writeInt(kdf.memoryKiB)
            output.writeInt(kdf.iterations)
            output.writeInt(kdf.parallelism)
            output.writeSized(salt)
            output.writeSized(CryptoEngine.sha256(devicePacket.nonce + devicePacket.ciphertext))
        }
        bytes.toByteArray()
    }

    private fun headerIntegrityTag(
        deviceFactor: ByteArray,
        createdAt: Long,
        kdf: KdfParameters,
        salt: ByteArray,
        devicePacket: CryptoEngine.AeadPacket,
        masterPacket: CryptoEngine.AeadPacket,
    ): ByteArray {
        val integrityKey = CryptoEngine.hkdfSha256(
            listOf(deviceFactor),
            salt,
            HEADER_INTEGRITY_INFO,
        )
        val masterBinding = masterAad(createdAt, kdf, salt, devicePacket)
        val authenticatedHeader = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeSized(masterBinding)
                output.writePacket(masterPacket)
            }
            bytes.toByteArray()
        }
        return try {
            CryptoEngine.hmacSha256(integrityKey, authenticatedHeader)
        } finally {
            integrityKey.wipe()
            masterBinding.wipe()
            authenticatedHeader.wipe()
        }
    }

    private fun deviceAad(): ByteArray = DEVICE_WRAP_INFO.copyOf()

    private fun validateKdf(kdf: KdfParameters) {
        require(kdf.memoryKiB in 65_536..262_144) { "Argon2id memory must be 64-256 MiB" }
        require(kdf.iterations in 3..6) { "Argon2id iterations must be 3-6" }
        require(kdf.parallelism in 1..4) { "Argon2id parallelism must be 1-4" }
    }

    private fun DataOutputStream.writePacket(packet: CryptoEngine.AeadPacket) {
        writeSized(packet.nonce)
        writeSized(packet.ciphertext)
    }

    private fun DataOutputStream.writeSized(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readPacket(): CryptoEngine.AeadPacket {
        val nonce = readSized(CryptoEngine.GCM_NONCE_BYTES, CryptoEngine.GCM_NONCE_BYTES)
        val ciphertext = readSized(16, MAX_PACKET_BYTES)
        return CryptoEngine.AeadPacket(nonce, ciphertext)
    }

    private fun DataInputStream.readSized(minimum: Int, maximum: Int): ByteArray {
        val size = readInt()
        if (size !in minimum..maximum) throw VaultCorruptException("Invalid vault-header field size")
        return ByteArray(size).also(::readFully)
    }

    private fun File.mkdirsOrThrow() {
        if (!isDirectory && !mkdirs()) throw IOException("Unable to create private vault storage")
    }

    companion object {
        private const val VAULT_DIRECTORY = "opaque_vault"
        private const val HEADER_FILE = "vault.header"
        private const val HEADER_MAGIC = 0x4F564C54 // OVLT
        private const val FORMAT_VERSION = 2
        private const val SALT_BYTES = 32
        private const val INTEGRITY_TAG_BYTES = 32
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_PACKET_BYTES = 4 * 1024
        private val MASTER_WRAP_INFO = "opaque-vault/master-wrap/v1".toByteArray(StandardCharsets.UTF_8)
        private val DEVICE_WRAP_INFO = "opaque-vault/device-factor/v1".toByteArray(StandardCharsets.UTF_8)
        private val HEADER_INTEGRITY_INFO = "opaque-vault/header-integrity/v2".toByteArray(StandardCharsets.UTF_8)
    }
}

class WrongPassphraseException : Exception("The passphrase is incorrect")

class VaultCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)
