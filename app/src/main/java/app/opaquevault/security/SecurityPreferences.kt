package app.opaquevault.security

import android.content.Context
import androidx.core.content.edit

enum class AfterImportAction { ASK, DELETE, KEEP }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class DisguiseMode { OFF, CALCULATOR }

enum class LauncherIdentity { VAULT, NOTES, CALCULATOR }

enum class GridSize(val minCellDp: Int) { COMPACT(84), COMFORTABLE(108), LARGE(150) }

enum class SortOrder { NEWEST, OLDEST, NAME, LARGEST }

enum class PanicAction { OFF, LOCK, ERASE }

/** User-facing settings. These values are intentionally non-secret; cryptographic keys live elsewhere. */
data class VaultSettings(
    val requireDeviceAuthentication: Boolean,
    val autoLockSeconds: Int,
    val inactivityLockMinutes: Int,
    val wipeAfterTenFailures: Boolean,
    val wipeFailureThreshold: Int,
    val afterImport: AfterImportAction,
    val confirmSharedImports: Boolean,
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val homeTitle: String,
    val disguiseMode: DisguiseMode,
    val launcherIdentity: LauncherIdentity,
    val autoPlayVideos: Boolean,
    val loopVideos: Boolean,
    val startVideosMuted: Boolean,
    val lockOnScreenOff: Boolean,
    val gridSize: GridSize,
    val sortOrder: SortOrder,
    val skipSeconds: Int,
    val keepScreenOnDuringPlayback: Boolean,
    val confirmVaultDeletion: Boolean,
    val biometricKeyEnabled: Boolean,
    val nfcKeyEnabled: Boolean,
    val strictDeviceIntegrity: Boolean,
    val signaturePinningEnabled: Boolean,
    val panicGestureAction: PanicAction,
    val panicTileAction: PanicAction,
    val deadManDays: Int,
    val intruderLogEnabled: Boolean,
    val captureIntruderPhoto: Boolean,
    val stripMetadataOnExport: Boolean,
)

class SecurityPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var failedUnlockCount: Int
        get() = preferences.getInt(KEY_FAILED_COUNT, 0)
        set(value) = preferences.edit { putInt(KEY_FAILED_COUNT, value.coerceAtLeast(0)) }

    var lockoutUntilEpochMillis: Long
        get() = preferences.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = preferences.edit { putLong(KEY_LOCKOUT_UNTIL, value.coerceAtLeast(0L)) }

    /** Retains the old property/key name so upgrades preserve the user's existing setting. */
    var wipeAfterTenFailures: Boolean
        get() = preferences.getBoolean(KEY_WIPE_AFTER_FAILURES, false)
        set(value) = preferences.edit { putBoolean(KEY_WIPE_AFTER_FAILURES, value) }

    var wipeFailureThreshold: Int
        get() = preferences.getInt(KEY_WIPE_FAILURE_THRESHOLD, DEFAULT_FAILURES_BEFORE_WIPE)
            .coerceIn(MIN_FAILURES_BEFORE_WIPE, MAX_FAILURES_BEFORE_WIPE)
        set(value) = preferences.edit {
            putInt(KEY_WIPE_FAILURE_THRESHOLD, value.coerceIn(MIN_FAILURES_BEFORE_WIPE, MAX_FAILURES_BEFORE_WIPE))
        }

    var requireDeviceAuthentication: Boolean
        get() = preferences.getBoolean(KEY_DEVICE_AUTH, false)
        set(value) = preferences.edit { putBoolean(KEY_DEVICE_AUTH, value) }

    var autoLockSeconds: Int
        get() = preferences.getInt(KEY_AUTO_LOCK_SECONDS, 0)
        set(value) = preferences.edit { putInt(KEY_AUTO_LOCK_SECONDS, value.coerceIn(0, 300)) }

    /** 0 = never lock while the app stays open and idle. */
    var inactivityLockMinutes: Int
        get() = preferences.getInt(KEY_INACTIVITY_MINUTES, 5)
        set(value) = preferences.edit { putInt(KEY_INACTIVITY_MINUTES, value.coerceIn(0, 60)) }

    var afterImport: AfterImportAction
        get() = enumValue(KEY_AFTER_IMPORT, AfterImportAction.ASK)
        set(value) = preferences.edit { putString(KEY_AFTER_IMPORT, value.name) }

    var confirmSharedImports: Boolean
        get() = preferences.getBoolean(KEY_CONFIRM_SHARED, true)
        set(value) = preferences.edit { putBoolean(KEY_CONFIRM_SHARED, value) }

    var themeMode: ThemeMode
        get() = enumValue(KEY_THEME_MODE, ThemeMode.SYSTEM)
        set(value) = preferences.edit { putString(KEY_THEME_MODE, value.name) }

    var dynamicColor: Boolean
        get() = preferences.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = preferences.edit { putBoolean(KEY_DYNAMIC_COLOR, value) }

    var homeTitle: String
        get() = sanitizeHomeTitle(preferences.getString(KEY_HOME_TITLE, null).orEmpty())
        set(value) = preferences.edit { putString(KEY_HOME_TITLE, sanitizeHomeTitle(value)) }

    var disguiseMode: DisguiseMode
        get() = enumValue(KEY_DISGUISE_MODE, DisguiseMode.OFF)
        set(value) = preferences.edit { putString(KEY_DISGUISE_MODE, value.name) }

    var launcherIdentity: LauncherIdentity
        get() = enumValue(KEY_LAUNCHER_IDENTITY, LauncherIdentity.VAULT)
        set(value) = preferences.edit { putString(KEY_LAUNCHER_IDENTITY, value.name) }

    var autoPlayVideos: Boolean
        get() = preferences.getBoolean(KEY_AUTO_PLAY, true)
        set(value) = preferences.edit { putBoolean(KEY_AUTO_PLAY, value) }

    var loopVideos: Boolean
        get() = preferences.getBoolean(KEY_LOOP, false)
        set(value) = preferences.edit { putBoolean(KEY_LOOP, value) }

    var startVideosMuted: Boolean
        get() = preferences.getBoolean(KEY_START_MUTED, false)
        set(value) = preferences.edit { putBoolean(KEY_START_MUTED, value) }

    var lockOnScreenOff: Boolean
        get() = preferences.getBoolean(KEY_LOCK_SCREEN_OFF, true)
        set(value) = preferences.edit { putBoolean(KEY_LOCK_SCREEN_OFF, value) }

    var gridSize: GridSize
        get() = enumValue(KEY_GRID_SIZE, GridSize.COMFORTABLE)
        set(value) = preferences.edit { putString(KEY_GRID_SIZE, value.name) }

    var sortOrder: SortOrder
        get() = enumValue(KEY_SORT_ORDER, SortOrder.NEWEST)
        set(value) = preferences.edit { putString(KEY_SORT_ORDER, value.name) }

    var skipSeconds: Int
        get() = preferences.getInt(KEY_SKIP_SECONDS, 10).takeIf { it in setOf(5, 10, 30) } ?: 10
        set(value) = preferences.edit { putInt(KEY_SKIP_SECONDS, value.takeIf { it in setOf(5, 10, 30) } ?: 10) }

    var keepScreenOnDuringPlayback: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON_PLAYBACK, true)
        set(value) = preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON_PLAYBACK, value) }

    var confirmVaultDeletion: Boolean
        get() = preferences.getBoolean(KEY_CONFIRM_VAULT_DELETE, true)
        set(value) = preferences.edit { putBoolean(KEY_CONFIRM_VAULT_DELETE, value) }

    var biometricKeyEnabled: Boolean
        get() = preferences.getBoolean(KEY_BIOMETRIC_KEY, false)
        set(value) = preferences.edit { putBoolean(KEY_BIOMETRIC_KEY, value) }

    var nfcKeyEnabled: Boolean
        get() = preferences.getBoolean(KEY_NFC_KEY, false)
        set(value) = preferences.edit { putBoolean(KEY_NFC_KEY, value) }

    var strictDeviceIntegrity: Boolean
        get() = preferences.getBoolean(KEY_STRICT_DEVICE_INTEGRITY, false)
        set(value) = preferences.edit { putBoolean(KEY_STRICT_DEVICE_INTEGRITY, value) }

    var signaturePinningEnabled: Boolean
        get() = preferences.getBoolean(KEY_SIGNATURE_PINNING, false)
        set(value) = preferences.edit { putBoolean(KEY_SIGNATURE_PINNING, value) }

    var panicGestureAction: PanicAction
        get() = enumValue(KEY_PANIC_GESTURE, PanicAction.OFF)
        set(value) = preferences.edit { putString(KEY_PANIC_GESTURE, value.name) }

    var panicTileAction: PanicAction
        get() = enumValue(KEY_PANIC_TILE, PanicAction.OFF)
        set(value) = preferences.edit { putString(KEY_PANIC_TILE, value.name) }

    var deadManDays: Int
        get() = preferences.getInt(KEY_DEAD_MAN_DAYS, 0).takeIf { it in setOf(0, 7, 30, 90) } ?: 0
        set(value) = preferences.edit { putInt(KEY_DEAD_MAN_DAYS, value.takeIf { it in setOf(0, 7, 30, 90) } ?: 0) }

    var lastSuccessfulUnlockEpochMillis: Long
        get() = preferences.getLong(KEY_LAST_UNLOCK, 0L)
        set(value) = preferences.edit { putLong(KEY_LAST_UNLOCK, value.coerceAtLeast(0L)) }

    var intruderLogEnabled: Boolean
        get() = preferences.getBoolean(KEY_INTRUDER_LOG, false)
        set(value) = preferences.edit { putBoolean(KEY_INTRUDER_LOG, value) }

    var captureIntruderPhoto: Boolean
        get() = preferences.getBoolean(KEY_INTRUDER_PHOTO, false)
        set(value) = preferences.edit { putBoolean(KEY_INTRUDER_PHOTO, value) }

    var stripMetadataOnExport: Boolean
        get() = preferences.getBoolean(KEY_STRIP_EXPORT_METADATA, false)
        set(value) = preferences.edit { putBoolean(KEY_STRIP_EXPORT_METADATA, value) }

    fun snapshot() = VaultSettings(
        requireDeviceAuthentication = requireDeviceAuthentication,
        autoLockSeconds = autoLockSeconds,
        inactivityLockMinutes = inactivityLockMinutes,
        wipeAfterTenFailures = wipeAfterTenFailures,
        wipeFailureThreshold = wipeFailureThreshold,
        afterImport = afterImport,
        confirmSharedImports = confirmSharedImports,
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        homeTitle = homeTitle,
        disguiseMode = disguiseMode,
        launcherIdentity = launcherIdentity,
        autoPlayVideos = autoPlayVideos,
        loopVideos = loopVideos,
        startVideosMuted = startVideosMuted,
        lockOnScreenOff = lockOnScreenOff,
        gridSize = gridSize,
        sortOrder = sortOrder,
        skipSeconds = skipSeconds,
        keepScreenOnDuringPlayback = keepScreenOnDuringPlayback,
        confirmVaultDeletion = confirmVaultDeletion,
        biometricKeyEnabled = biometricKeyEnabled,
        nfcKeyEnabled = nfcKeyEnabled,
        strictDeviceIntegrity = strictDeviceIntegrity,
        signaturePinningEnabled = signaturePinningEnabled,
        panicGestureAction = panicGestureAction,
        panicTileAction = panicTileAction,
        deadManDays = deadManDays,
        intruderLogEnabled = intruderLogEnabled,
        captureIntruderPhoto = captureIntruderPhoto,
        stripMetadataOnExport = stripMetadataOnExport,
    )

    fun recordFailedUnlock(nowEpochMillis: Long): FailedUnlockState {
        val failures = (failedUnlockCount + 1).coerceAtMost(100)
        failedUnlockCount = failures
        val delaySeconds = when {
            failures < 3 -> 0L
            else -> (1L shl (failures - 3).coerceAtMost(8)).coerceAtMost(300L)
        }
        val until = if (delaySeconds == 0L) 0L else nowEpochMillis + delaySeconds * 1_000L
        lockoutUntilEpochMillis = until
        return FailedUnlockState(
            failures = failures,
            lockoutUntilEpochMillis = until,
            mustWipe = wipeAfterTenFailures && failures >= wipeFailureThreshold,
        )
    }

    fun clearFailedUnlocks() {
        failedUnlockCount = 0
        lockoutUntilEpochMillis = 0L
    }

    fun clearAll() = preferences.edit(commit = true) { clear() }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        preferences.getString(key, null)?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

    private fun sanitizeHomeTitle(value: String): String = value
        .filterNot(Char::isISOControl)
        .trim()
        .take(MAX_HOME_TITLE_CHARS)
        .ifBlank { DEFAULT_HOME_TITLE }

    data class FailedUnlockState(
        val failures: Int,
        val lockoutUntilEpochMillis: Long,
        val mustWipe: Boolean,
    )

    companion object {
        const val MIN_FAILURES_BEFORE_WIPE = 3
        const val MAX_FAILURES_BEFORE_WIPE = 20
        const val DEFAULT_FAILURES_BEFORE_WIPE = 10
        const val DEFAULT_HOME_TITLE = "Opaque Vault"
        private const val MAX_HOME_TITLE_CHARS = 32
        private const val FILE_NAME = "local_security_state"
        private const val KEY_FAILED_COUNT = "failed_unlock_count"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until_epoch_millis"
        private const val KEY_WIPE_AFTER_FAILURES = "wipe_after_ten_failures"
        private const val KEY_WIPE_FAILURE_THRESHOLD = "wipe_failure_threshold"
        private const val KEY_DEVICE_AUTH = "require_device_authentication"
        private const val KEY_AUTO_LOCK_SECONDS = "auto_lock_seconds"
        private const val KEY_INACTIVITY_MINUTES = "inactivity_lock_minutes"
        private const val KEY_AFTER_IMPORT = "after_import_action"
        private const val KEY_CONFIRM_SHARED = "confirm_shared_imports"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_HOME_TITLE = "home_title"
        private const val KEY_DISGUISE_MODE = "disguise_mode"
        private const val KEY_LAUNCHER_IDENTITY = "launcher_identity"
        private const val KEY_AUTO_PLAY = "auto_play_videos"
        private const val KEY_LOOP = "loop_videos"
        private const val KEY_START_MUTED = "start_videos_muted"
        private const val KEY_LOCK_SCREEN_OFF = "lock_on_screen_off"
        private const val KEY_GRID_SIZE = "grid_size"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_SKIP_SECONDS = "video_skip_seconds"
        private const val KEY_KEEP_SCREEN_ON_PLAYBACK = "keep_screen_on_playback"
        private const val KEY_CONFIRM_VAULT_DELETE = "confirm_vault_delete"
        private const val KEY_BIOMETRIC_KEY = "biometric_key_enabled"
        private const val KEY_NFC_KEY = "nfc_key_enabled"
        private const val KEY_STRICT_DEVICE_INTEGRITY = "strict_device_integrity"
        private const val KEY_SIGNATURE_PINNING = "signature_pinning_enabled"
        private const val KEY_PANIC_GESTURE = "panic_gesture_action"
        private const val KEY_PANIC_TILE = "panic_tile_action"
        private const val KEY_DEAD_MAN_DAYS = "dead_man_days"
        private const val KEY_LAST_UNLOCK = "last_successful_unlock_epoch_millis"
        private const val KEY_INTRUDER_LOG = "intruder_log_enabled"
        private const val KEY_INTRUDER_PHOTO = "capture_intruder_photo"
        private const val KEY_STRIP_EXPORT_METADATA = "strip_export_metadata"
    }
}
