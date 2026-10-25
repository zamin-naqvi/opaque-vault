package app.opaquevault.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.provider.Settings
import app.opaquevault.BuildConfig
import java.io.File

class DeviceRiskAssessment(private val context: Context) {
    private val signatureVerifier = AppSignatureVerifier(context)
    private val hardwareAttestation = HardwareAttestation(context)

    data class Snapshot(
        val blockUnlock: Boolean,
        val warning: String?,
        val signatureStatus: String,
        val attestation: HardwareAttestationResult,
    )

    fun snapshot(strictIntegrity: Boolean = false, enforceSignature: Boolean = false): Snapshot {
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val packageDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val rootSignal = Build.TAGS?.contains("test-keys", ignoreCase = true) == true ||
            KNOWN_ROOT_PATHS.any { File(it).exists() }
        val accessibilityPackages = enabledThirdPartyAccessibilityPackages()
        val signatureOk = signatureVerifier.isOfficialOrDebug()
        val attestation = hardwareAttestation.assess()

        // Release builds always reject repackaging and active process-control risks. Hardware
        // attestation is fail-closed only when the user explicitly enables strict mode.
        val blockUnlock = !BuildConfig.DEBUG && (
            (enforceSignature && !signatureOk) || debuggerAttached || packageDebuggable || rootSignal || accessibilityPackages.isNotEmpty() ||
                (strictIntegrity && !attestation.trustedForStrictMode)
            )
        val warning = when {
            enforceSignature && !signatureOk && !BuildConfig.DEBUG ->
                "Unlock blocked: this APK was signed by an unrecognized key and may be repackaged."
            BuildConfig.DEBUG ->
                "Debug build active. Signature pinning and strict release blocking are bypassed for development."
            debuggerAttached || packageDebuggable ->
                "Unlock blocked: this process is debug-enabled or has a debugger attached."
            !BuildConfig.DEBUG && rootSignal ->
                "Unlock blocked: root or non-production OS indicators can defeat app isolation."
            !BuildConfig.DEBUG && accessibilityPackages.isNotEmpty() ->
                "Unlock blocked while a third-party accessibility service is enabled (${accessibilityPackages.joinToString()}). Disable it, then try again."
            strictIntegrity && !attestation.trustedForStrictMode ->
                "Unlock blocked by strict device integrity: ${attestation.summary}"
            else -> null
        }
        return Snapshot(blockUnlock, warning, signatureVerifier.status(), attestation)
    }

    private fun enabledThirdPartyAccessibilityPackages(): List<String> {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .map { it.packageName }
            .distinct()
            .filter { packageName ->
                if (packageName == context.packageName) return@filter false
                try {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getApplicationInfo(packageName, 0)
                    info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                } catch (_: Exception) {
                    // If an enabled service is hidden from package visibility, treat it as untrusted.
                    true
                }
            }
    }

    companion object {
        private val KNOWN_ROOT_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/magisk",
        )
    }
}
