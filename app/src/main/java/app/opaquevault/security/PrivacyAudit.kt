package app.opaquevault.security

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A permission/capability exposure audit using public Android APIs only. It reports what another app
 * is empowered to do; it never labels an app as malware merely because it has a powerful permission.
 * Results remain in memory and this class performs no network access.
 */
class PrivacyAudit(private val context: Context) {
    enum class Severity { CRITICAL, HIGH, MEDIUM, REVIEW }

    enum class Remediation {
        APP_DETAILS,
        ACCESSIBILITY,
        NOTIFICATION_LISTENERS,
        DEVELOPMENT_SETTINGS,
        SECURITY_SETTINGS,
        VPN_SETTINGS,
        DEVICE_ADMINS,
    }

    data class Finding(
        val id: String,
        val severity: Severity,
        val title: String,
        val detail: String,
        val packageName: String? = null,
        val remediation: Remediation,
    )

    data class Report(
        val findings: List<Finding>,
        val visibleAppsReviewed: Int,
        val checksPassed: List<String>,
        val limitations: List<String>,
    )

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val resolver = appContext.contentResolver
    private val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

    /** Never throws: a vendor-restricted system setting must not take the whole audit (or app) down. */
    fun scan(): Report {
        val findings = mutableListOf<Finding>()
        val passed = mutableListOf<String>()
        var reviewed = 0
        val notes = mutableListOf<String>()
        try {
            reviewed = runChecks(findings, passed)
        } catch (error: Throwable) {
            notes += "Some checks couldn't run on this phone (${error.javaClass.simpleName}); the results below are partial."
        }
        return Report(
            findings = findings.distinctBy(Finding::id).sortedBy(Finding::severity),
            visibleAppsReviewed = reviewed,
            checksPassed = passed,
            limitations = notes + LIMITATIONS,
        )
    }

    private fun globalInt(name: String): Int = try {
        Settings.Global.getInt(resolver, name, 0)
    } catch (_: Exception) {
        0
    }

    private fun secureString(name: String): String = try {
        Settings.Secure.getString(resolver, name).orEmpty()
    } catch (_: Exception) {
        // Android restricts some keys to system apps and may throw a SecurityException.
        ""
    }

    private fun runChecks(findings: MutableList<Finding>, passed: MutableList<String>): Int {
        val packages = installedPackages()
        val packageByName = packages.associateBy(PackageInfo::packageName)

        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            findings += Finding(
                "device-lock",
                Severity.CRITICAL,
                "No secure phone lock",
                "Anyone holding the phone can reach apps and device-backed keys. Set a strong PIN or password; biometrics should supplement it.",
                remediation = Remediation.SECURITY_SETTINGS,
            )
        } else {
            passed += "Secure screen lock is enabled"
        }

        val adbEnabled = globalInt(Settings.Global.ADB_ENABLED) == 1
        val developerEnabled = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        if (adbEnabled || developerEnabled) {
            findings += Finding(
                "developer-options",
                if (adbEnabled) Severity.HIGH else Severity.MEDIUM,
                if (adbEnabled) "USB or wireless debugging is enabled" else "Developer options are enabled",
                "A computer authorized for debugging has unusually powerful access. Turn debugging off when it is not actively needed and revoke old authorizations.",
                remediation = Remediation.DEVELOPMENT_SETTINGS,
            )
        } else {
            passed += "Developer options and ADB are off"
        }

        val rooted = Build.TAGS?.contains("test-keys", ignoreCase = true) == true || ROOT_PATHS.any { File(it).exists() }
        if (rooted) {
            findings += Finding(
                "root-signals",
                Severity.CRITICAL,
                "Root or non-production OS indicators",
                "A privileged process can inspect an unlocked app despite normal Android isolation. Use a locked, production-signed OS for real secrets.",
                remediation = Remediation.SECURITY_SETTINGS,
            )
        } else {
            passed += "No common root indicators were found"
        }

        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            findings += Finding(
                "debugger",
                Severity.CRITICAL,
                "Debugger attached",
                "A debugger can inspect process memory while the vault is unlocked. Close it before opening private media.",
                remediation = Remediation.DEVELOPMENT_SETTINGS,
            )
        }

        checkSecurityPatch()?.let { ageDays ->
            if (ageDays > SECURITY_PATCH_WARNING_DAYS) {
                findings += Finding(
                    "security-patch",
                    Severity.HIGH,
                    "Security update is $ageDays days old",
                    "Install the newest Android security update offered by the manufacturer before relying on the phone against physical or spyware attacks.",
                    remediation = Remediation.SECURITY_SETTINGS,
                )
            } else {
                passed += "Android security patch is reasonably current"
            }
        }

        val enabledAccessibility = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (enabledAccessibility.isEmpty()) {
            passed += "No accessibility service is enabled"
        } else {
            enabledAccessibility.forEach { component ->
                val packageName = component.packageName
                findings += Finding(
                    "accessibility:$packageName",
                    Severity.HIGH,
                    "${appLabel(packageName)} has accessibility control",
                    "Enabled accessibility services may observe text and control the screen. Keep only services you explicitly trust.",
                    packageName,
                    Remediation.ACCESSIBILITY,
                )
            }
        }

        val notificationListeners = enabledComponents(ENABLED_NOTIFICATION_LISTENERS)
        if (notificationListeners.isEmpty()) {
            passed += "No third-party notification listener was found"
        } else {
            notificationListeners.forEach { component ->
                val packageName = component.packageName
                findings += Finding(
                    "notifications:$packageName",
                    Severity.MEDIUM,
                    "${appLabel(packageName)} can read notifications",
                    "Notification access can expose message content, one-time codes and activity. Revoke it from apps that do not need it.",
                    packageName,
                    Remediation.NOTIFICATION_LISTENERS,
                )
            }
        }

        val activeAdmins = try {
            (appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).activeAdmins.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (activeAdmins.isEmpty()) {
            passed += "No device-admin app is active"
        } else {
            activeAdmins.forEach { component ->
                findings += Finding(
                    "admin:${component.packageName}",
                    Severity.HIGH,
                    "${appLabel(component.packageName)} is a device administrator",
                    "Device administrators can enforce policies and resist removal. Keep only a known work-management or anti-theft administrator.",
                    component.packageName,
                    Remediation.DEVICE_ADMINS,
                )
            }
        }

        val alwaysOnVpn = secureString(ALWAYS_ON_VPN_APP)
        if (alwaysOnVpn.isNotBlank()) {
            findings += Finding(
                "vpn:$alwaysOnVpn",
                Severity.REVIEW,
                "Always-on VPN: ${appLabel(alwaysOnVpn)}",
                "A VPN can observe network metadata from other apps. Opaque Vault itself has no internet permission, but verify that this VPN is yours.",
                alwaysOnVpn,
                Remediation.VPN_SETTINGS,
            )
        } else {
            passed += "No always-on VPN is configured"
        }

        packages.asSequence()
            .filter { it.packageName != appContext.packageName }
            .filterNot(::isSystemPackage)
            .forEach { info ->
                // One misbehaving package must not abort the review of every other app.
                try {
                    inspectThirdPartyApp(info, findings)
                } catch (_: Exception) {
                    // Skip this package.
                }
            }
        return packages.size
    }

    private fun inspectThirdPartyApp(info: PackageInfo, findings: MutableList<Finding>) {
        val packageName = info.packageName
        val label = appLabel(packageName)
        val canReadImages = hasPermission(packageName, READ_MEDIA_IMAGES_PERMISSION) ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && hasPermission(packageName, Manifest.permission.READ_EXTERNAL_STORAGE))
        val canReadVideos = hasPermission(packageName, READ_MEDIA_VIDEO_PERMISSION) ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && hasPermission(packageName, Manifest.permission.READ_EXTERNAL_STORAGE))
        val selectedMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasPermission(packageName, READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION)
        if (canReadImages || canReadVideos || selectedMedia) {
            val scope = buildList {
                if (canReadImages) add("photos")
                if (canReadVideos) add("videos")
                if (!canReadImages && !canReadVideos && selectedMedia) add("user-selected media")
            }.joinToString(" and ")
            val sensors = buildList {
                if (hasPermission(packageName, Manifest.permission.CAMERA)) add("camera")
                if (hasPermission(packageName, Manifest.permission.RECORD_AUDIO)) add("microphone")
            }
            findings += Finding(
                "media:$packageName",
                Severity.REVIEW,
                "$label can read $scope",
                "Review whether it still needs this access." + if (sensors.isNotEmpty()) " It also has ${sensors.joinToString(" and ")} access." else "",
                packageName,
                Remediation.APP_DETAILS,
            )
        }

        if (appOpAllowed(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, info)) {
            findings += Finding(
                "overlay:$packageName",
                Severity.HIGH,
                "$label can draw over other apps",
                "Overlay access can be used for deceptive controls. Android 12+ blocks overlays over this vault, but revoke the permission unless essential.",
                packageName,
                Remediation.APP_DETAILS,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            info.requestedPermissions.orEmpty().contains(REQUEST_INSTALL_PACKAGES) &&
            appOpAllowed(OPSTR_REQUEST_INSTALL_PACKAGES, info)
        ) {
            findings += Finding(
                "installer:$packageName",
                Severity.HIGH,
                "$label can install unknown apps",
                "This access can introduce unreviewed software. Disable it unless this is a package manager you intentionally use.",
                packageName,
                Remediation.APP_DETAILS,
            )
        }

        if (info.requestedPermissions.orEmpty().contains(FOREGROUND_SERVICE_MEDIA_PROJECTION)) {
            findings += Finding(
                "capture:$packageName",
                Severity.REVIEW,
                "$label can request screen capture",
                "Android must still show a capture confirmation, and Opaque Vault marks its window secure. Remove the app if you do not trust why it requests capture capability.",
                packageName,
                Remediation.APP_DETAILS,
            )
        }

        if (appOpAllowed(AppOpsManager.OPSTR_GET_USAGE_STATS, info)) {
            findings += Finding(
                "usage:$packageName",
                Severity.MEDIUM,
                "$label can inspect app usage",
                "Usage access can reveal which apps you open and when. Revoke it unless the feature is necessary.",
                packageName,
                Remediation.APP_DETAILS,
            )
        }
    }

    @SuppressLint("QueryPermissionsNeeded") // Manifest <queries> intentionally returns only relevant visible apps.
    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun enabledComponents(setting: String): Set<ComponentName> =
        secureString(setting)
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .filterNot { it.packageName == appContext.packageName }
            .toSet()

    private fun hasPermission(packageName: String, permission: String): Boolean =
        packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED

    private fun appOpAllowed(operation: String, info: PackageInfo): Boolean {
        val applicationInfo = info.applicationInfo ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(operation, applicationInfo.uid, info.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(operation, applicationInfo.uid, info.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun appLabel(packageName: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
    } catch (_: Exception) {
        packageName
    }

    private fun isSystemPackage(info: PackageInfo): Boolean {
        val flags = info.applicationInfo?.flags ?: return true
        return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun checkSecurityPatch(): Long? {
        val patch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: return null
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(patch) ?: return null
            TimeUnit.MILLISECONDS.toDays((Date().time - date.time).coerceAtLeast(0L))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val LIMITATIONS = listOf(
            "A capability finding means “review this app,” not “this app is malware.” Signatures and permissions cannot prove what arbitrary code or an AI model does.",
            "Android does not reveal the owner of every active screen-capture session or let one ordinary app inspect another app's memory. The vault blocks screenshots/overlays at its own window boundary.",
            "Uninstalling or revoking access is intentionally confirmed by Android; this app never silently removes another app.",
        )
        private const val READ_MEDIA_IMAGES_PERMISSION = "android.permission.READ_MEDIA_IMAGES"
        private const val READ_MEDIA_VIDEO_PERMISSION = "android.permission.READ_MEDIA_VIDEO"
        private const val READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
        private const val OPSTR_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"
        private const val REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
        private const val FOREGROUND_SERVICE_MEDIA_PROJECTION = "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"
        private const val SECURITY_PATCH_WARNING_DAYS = 120L
        private val ROOT_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/magisk",
        )
    }
}
