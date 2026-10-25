package app.opaquevault.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.opaquevault.BuildConfig
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException

/** Pins official release builds to the private release certificate. */
class AppSignatureVerifier(private val context: Context) {
    fun isOfficialOrDebug(): Boolean = BuildConfig.DEBUG || currentSignerDigests().any {
        it.equals(EXPECTED_RELEASE_SHA256, ignoreCase = true)
    }

    fun status(): String = when {
        BuildConfig.DEBUG -> "Debug build (signature pin bypassed for development)"
        isOfficialOrDebug() -> "Official release signature verified"
        else -> "App signature does not match the official release key"
    }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(): List<String> = try {
        val packageInfo = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            else -> context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val EXPECTED_RELEASE_SHA256 = "2d1a07a0d65ede28d101005df2439dea2ecbf6da2647c1a1d3e578f75e275d85"
    }
}

data class HardwareAttestationResult(
    val available: Boolean,
    val hardwareBacked: Boolean,
    val deviceLocked: Boolean?,
    val verifiedBootState: String,
    val certificateChainLength: Int,
    val summary: String,
) {
    val trustedForStrictMode: Boolean
        get() = available && hardwareBacked && deviceLocked == true && verifiedBootState == "Verified"
}

/**
 * Generates an Android Keystore attestation key and parses Google's RootOfTrust extension locally.
 * No attestation identifiers are requested and no data leaves the phone.
 */
class HardwareAttestation(private val context: Context) {
    fun assess(): HardwareAttestationResult {
        return try {
            ensureKey()
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val chain = store.getCertificateChain(KEY_ALIAS).orEmpty()
            val leaf = chain.firstOrNull() as? java.security.cert.X509Certificate
                ?: return unavailable("Attestation certificate unavailable")
            val extension = leaf.getExtensionValue(ATTESTATION_OID) ?: return unavailable("Root-of-trust statement unavailable")
            val outer = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(extension)).octets
            val description = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(outer))
            val attestationSecurityLevel = ASN1Enumerated.getInstance(description.getObjectAt(1)).value.toInt()
            val keymasterSecurityLevel = ASN1Enumerated.getInstance(description.getObjectAt(3)).value.toInt()
            val teeEnforced = ASN1Sequence.getInstance(description.getObjectAt(7))
            val rootTag = teeEnforced.objects.toList().mapNotNull { it as? ASN1TaggedObject }.firstOrNull { it.tagNo == ROOT_OF_TRUST_TAG }
                ?: return unavailable("Hardware did not attest verified boot")
            val root = ASN1Sequence.getInstance(rootTag, true)
            val locked = ASN1Boolean.getInstance(root.getObjectAt(1)).isTrue
            val bootValue = ASN1Enumerated.getInstance(root.getObjectAt(2)).value.toInt()
            val boot = when (bootValue) { 0 -> "Verified"; 1 -> "Self-signed"; 2 -> "Unverified"; 3 -> "Failed"; else -> "Unknown" }
            val hardware = attestationSecurityLevel > 0 && keymasterSecurityLevel > 0
            HardwareAttestationResult(
                available = true,
                hardwareBacked = hardware,
                deviceLocked = locked,
                verifiedBootState = boot,
                certificateChainLength = chain.size,
                summary = "Verified boot: $boot · bootloader locked: ${if (locked) "yes" else "no"} · " +
                    "attestation: ${if (hardware) "secure hardware" else "software"} · patch ${Build.VERSION.SECURITY_PATCH}",
            )
        } catch (error: Exception) {
            unavailable("Attestation unavailable (${error.javaClass.simpleName})")
        }
    }

    private fun ensureKey() {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) return
        val challenge = CryptoEngine.randomBytes(32)
        try {
            try {
                generate(challenge, requestStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            } catch (_: StrongBoxUnavailableException) {
                generate(challenge, requestStrongBox = false)
            } catch (error: ProviderException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) generate(challenge, requestStrongBox = false) else throw error
            }
        } finally {
            challenge.wipe()
        }
    }

    private fun generate(challenge: ByteArray, requestStrongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setAttestationChallenge(challenge)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(requestStrongBox)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(builder.build()); generateKeyPair()
        }
    }

    private fun unavailable(reason: String) = HardwareAttestationResult(false, false, null, "Unknown", 0, reason)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opaque_vault_attestation_v1"
        private const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
        private const val ROOT_OF_TRUST_TAG = 704
    }
}
