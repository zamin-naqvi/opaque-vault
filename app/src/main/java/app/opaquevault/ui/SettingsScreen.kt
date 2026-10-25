package app.opaquevault.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.opaquevault.BuildConfig
import app.opaquevault.VaultController
import app.opaquevault.security.AfterImportAction
import app.opaquevault.security.DisguiseMode
import app.opaquevault.security.GridSize
import app.opaquevault.security.LauncherIdentity
import app.opaquevault.security.PanicAction
import app.opaquevault.security.SortOrder
import app.opaquevault.security.ThemeMode
import java.text.DateFormat
import java.util.Date

/** Actions that need an Activity or navigation owner. */
class SettingsActions(
    val onBack: () -> Unit,
    val onEnableDeviceAuthentication: () -> Unit,
    val onEnrollNfcKey: () -> Unit,
    val onConfigureDuress: (CharArray, CharArray, CharArray) -> Unit,
    val onVerifyIntegrity: () -> Unit,
    val onEnableIntruderPhoto: () -> Unit,
    val onRequestPhotoAccess: () -> Unit,
    val onOpenMediaManagement: () -> Unit,
    val onExportBackup: () -> Unit,
    val onRestoreBackup: () -> Unit,
    val onWipeFreeSpace: (Int) -> Unit,
    val onChangePassphrase: (CharArray, CharArray, CharArray) -> Unit,
    val onOpenSecurityAudit: () -> Unit,
    val onDestroyVault: () -> Unit,
)

private enum class SettingsDialog {
    AUTO_LOCK,
    IDLE_LOCK,
    AFTER_IMPORT,
    THEME,
    ARM_WIPE,
    ARM_STRICT_INTEGRITY,
    DURESS,
    PANIC_GESTURE,
    PANIC_TILE,
    DEAD_MAN,
    INTRUDER_EVENTS,
    WIPE_THRESHOLD,
    HOME_TITLE,
    LAUNCHER,
    GRID,
    SORT,
    VIDEO_SKIP,
    CHANGE_PASSPHRASE,
    WIPE_FREE_SPACE,
    DESTROY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: VaultController.UiState,
    controller: VaultController,
    snackbarHostState: SnackbarHostState,
    actions: SettingsActions,
) {
    val settings = state.settings
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(onBack = actions.onBack)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SectionHeader("Security") }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Fingerprint,
                    title = "Hardware-bound biometric unlock",
                    summary = "A non-exportable Android Keystore key decrypts a master-key envelope only after a strong biometric. Passphrase remains recovery",
                    checked = settings.biometricKeyEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) actions.onEnableDeviceAuthentication() else controller.disableBiometricKey()
                    },
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Key,
                    title = "NFC key second factor",
                    summary = "Require a dedicated enrolled NFC tag before biometric or passphrase unlock. Tag identifiers can be cloned; this is not FIDO2",
                    checked = settings.nfcKeyEnabled,
                    onCheckedChange = { enabled -> if (enabled) actions.onEnrollNfcKey() else controller.disableNfcKey() },
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.DeleteForever,
                    title = "Duress passphrase",
                    summary = "A separate passphrase silently destroys all vault key material when entered on the lock screen",
                    checked = state.duressEnabled,
                    onCheckedChange = { enabled -> if (enabled) dialog = SettingsDialog.DURESS else controller.disableDuressPassphrase() },
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.AdminPanelSettings,
                    title = "Strict hardware attestation",
                    summary = "Block release unlock unless secure hardware reports verified boot and a locked bootloader",
                    checked = settings.strictDeviceIntegrity,
                    onCheckedChange = { enabled -> if (enabled) dialog = SettingsDialog.ARM_STRICT_INTEGRITY else controller.setStrictDeviceIntegrity(false) },
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Security,
                    title = "Block repackaged APKs",
                    summary = "Refuse release unlock when the signing certificate differs from this project's private release key",
                    checked = settings.signaturePinningEnabled,
                    onCheckedChange = controller::setSignaturePinningEnabled,
                )
            }
            item {
                SettingRow(Icons.Rounded.LockClock, "Lock when you leave the app", autoLockLabel(settings.autoLockSeconds)) {
                    dialog = SettingsDialog.AUTO_LOCK
                }
            }
            item {
                SettingRow(Icons.Rounded.HourglassBottom, "Lock when idle", idleLockLabel(settings.inactivityLockMinutes)) {
                    dialog = SettingsDialog.IDLE_LOCK
                }
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.GppMaybe,
                    title = "Erase after ${settings.wipeFailureThreshold} wrong passphrases",
                    summary = "Destroys the only vault key. A person holding the phone could intentionally trigger it",
                    checked = settings.wipeAfterTenFailures,
                    onCheckedChange = { enabled ->
                        if (enabled) dialog = SettingsDialog.ARM_WIPE else controller.setWipeAfterTenFailures(false)
                    },
                )
            }
            item {
                SettingRow(
                    Icons.Rounded.Pin,
                    "Automatic erase threshold",
                    "${settings.wipeFailureThreshold} consecutive wrong passphrases (choose 3–20)",
                ) { dialog = SettingsDialog.WIPE_THRESHOLD }
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.ScreenLockPortrait,
                    title = "Lock when the screen turns off",
                    summary = "Locks the instant the power button is pressed or the screen times out",
                    checked = settings.lockOnScreenOff,
                    onCheckedChange = controller::setLockOnScreenOff,
                )
            }
            item {
                SettingRow(Icons.Rounded.Password, "Change passphrase", "Re-wraps the master key; encrypted items stay as they are") {
                    dialog = SettingsDialog.CHANGE_PASSPHRASE
                }
            }
            item {
                SettingRow(Icons.Rounded.GppMaybe, "Shake / face-down panic action", panicActionLabel(settings.panicGestureAction)) {
                    dialog = SettingsDialog.PANIC_GESTURE
                }
            }
            item {
                SettingRow(Icons.Rounded.GppMaybe, "Quick Settings panic tile", panicActionLabel(settings.panicTileAction) +
                    " · add “Vault panic” from Android Quick Settings") { dialog = SettingsDialog.PANIC_TILE }
            }
            item {
                SettingRow(Icons.Rounded.AutoDelete, "Dead-man erase", deadManLabel(settings.deadManDays)) {
                    dialog = SettingsDialog.DEAD_MAN
                }
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Security,
                    title = "Encrypted intruder log",
                    summary = "Record failed passphrase and unrecognized NFC attempts; never records attempted secrets",
                    checked = settings.intruderLogEnabled,
                    onCheckedChange = controller::setIntruderLogEnabled,
                )
            }
            if (settings.intruderLogEnabled) {
                item {
                    SettingSwitch(
                        icon = Icons.Rounded.PhotoLibrary,
                        title = "Front-camera photo after failed unlock",
                        summary = "Best effort while the lock screen is foreground; encrypted locally. Android shows the camera privacy indicator",
                        checked = settings.captureIntruderPhoto,
                        onCheckedChange = { enabled -> if (enabled) actions.onEnableIntruderPhoto() else controller.setCaptureIntruderPhoto(false) },
                    )
                }
                item {
                    SettingRow(Icons.Rounded.Info, "Review intruder log", "${state.intruderEvents.size} loaded event(s)") {
                        controller.loadIntruderLog(); dialog = SettingsDialog.INTRUDER_EVENTS
                    }
                }
            }
            item {
                SettingRow(Icons.Rounded.Security, "Verify complete vault integrity",
                    "Decrypt and authenticate every byte without exporting or changing any item", actions.onVerifyIntegrity)
            }

            item { SectionHeader("Library & playback") }
            item {
                SettingRow(Icons.Rounded.GridView, "Thumbnail size", gridLabel(settings.gridSize)) {
                    dialog = SettingsDialog.GRID
                }
            }
            item {
                SettingRow(Icons.Rounded.SortByAlpha, "Sort by", sortLabel(settings.sortOrder)) {
                    dialog = SettingsDialog.SORT
                }
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Autoplay videos",
                    summary = "Start playing as soon as a video opens",
                    checked = settings.autoPlayVideos,
                    onCheckedChange = controller::setAutoPlayVideos,
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Repeat,
                    title = "Loop videos",
                    summary = "Replay a video automatically when it ends",
                    checked = settings.loopVideos,
                    onCheckedChange = controller::setLoopVideos,
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    title = "Start videos muted",
                    summary = "Open every video silent; unmute from the player",
                    checked = settings.startVideosMuted,
                    onCheckedChange = controller::setStartVideosMuted,
                )
            }

            item {
                SettingRow(
                    Icons.Rounded.Forward10,
                    "Skip interval",
                    "${settings.skipSeconds} seconds for double-tap and player buttons",
                ) { dialog = SettingsDialog.VIDEO_SKIP }
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.ScreenLockPortrait,
                    title = "Keep screen awake while playing",
                    summary = "Prevent screen timeout only while a video is actively playing",
                    checked = settings.keepScreenOnDuringPlayback,
                    onCheckedChange = controller::setKeepScreenOnDuringPlayback,
                )
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Delete,
                    title = "Confirm vault deletions",
                    summary = "Ask before destroying an item's encryption key",
                    checked = settings.confirmVaultDeletion,
                    onCheckedChange = controller::setConfirmVaultDeletion,
                )
            }

            item { SectionHeader("Disguise") }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Calculate,
                    title = "Calculator cover",
                    summary = "Show a working calculator while locked. Hold the = key to reveal authentication",
                    checked = settings.disguiseMode == DisguiseMode.CALCULATOR,
                    onCheckedChange = { enabled ->
                        controller.setDisguiseMode(if (enabled) DisguiseMode.CALCULATOR else DisguiseMode.OFF)
                    },
                )
            }
            item {
                SettingRow(
                    Icons.Rounded.Apps,
                    "Launcher name and icon",
                    launcherLabel(settings.launcherIdentity),
                ) { dialog = SettingsDialog.LAUNCHER }
            }
            item {
                SettingRow(
                    Icons.Rounded.DriveFileRenameOutline,
                    "Vault home title",
                    settings.homeTitle,
                ) { dialog = SettingsDialog.HOME_TITLE }
            }

            item { SectionHeader("Phone defense") }
            item {
                SettingRow(
                    Icons.Rounded.Security,
                    "Privacy & spyware exposure audit",
                    "Review photo access, overlays, accessibility, notification listeners, screen-capture capability, admins, VPN, ADB and updates",
                    onClick = actions.onOpenSecurityAudit,
                )
            }

            item { SectionHeader("Importing") }
            item {
                SettingRow(Icons.Rounded.AutoDelete, "After encrypting media", afterImportLabel(settings.afterImport)) {
                    dialog = SettingsDialog.AFTER_IMPORT
                }
            }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.Share,
                    title = "Ask before importing shared media",
                    summary = "Confirm photos and videos shared from Gallery or other apps",
                    checked = settings.confirmSharedImports,
                    onCheckedChange = controller::setConfirmSharedImports,
                )
            }
            item {
                SettingRow(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Photo and video access",
                    summary = when {
                        state.hasFullPhotoAccess -> "Allowed. Originals selected with Photo Picker can be found and removed"
                        state.canSearchPhotos -> "Partial access. Allow all media so every original can be matched by hash"
                        else -> "Not allowed. Needed only when you ask to remove Photo Picker originals"
                    },
                    onClick = actions.onRequestPhotoAccess,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SettingRow(
                        icon = Icons.Rounded.AdminPanelSettings,
                        title = "Delete originals without asking",
                        summary = if (state.canDeleteWithoutConfirmation) {
                            "Allowed. Android won't ask before each verified deletion"
                        } else {
                            "Optional Media management access; otherwise Android confirms each deletion"
                        },
                        onClick = actions.onOpenMediaManagement,
                    )
                }
            }

            item { SectionHeader("Backup") }
            item {
                SettingRow(
                    Icons.Rounded.Archive,
                    "Export encrypted backup",
                    "All photos and videos in one streaming-encrypted file, protected by Argon2id and your passphrase",
                    onClick = actions.onExportBackup,
                )
            }
            item {
                SettingRow(
                    Icons.Rounded.Unarchive,
                    "Restore from backup",
                    "Adds authenticated photos and videos; exact duplicates are skipped",
                    onClick = actions.onRestoreBackup,
                )
            }

            item { SectionHeader("Storage privacy") }
            item {
                SettingSwitch(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Strip metadata on photo export",
                    summary = "Default to removing GPS, camera identity, serial numbers and capture times from JPEG, HEIC and WebP exports",
                    checked = settings.stripMetadataOnExport,
                    onCheckedChange = controller::setStripMetadataOnExport,
                )
            }
            item {
                SettingRow(
                    Icons.Rounded.DeleteSweep,
                    "Wipe app-cache free space",
                    "Best-effort overwrite of allocatable internal-storage space; flash wear-leveling can retain old physical cells",
                ) { dialog = SettingsDialog.WIPE_FREE_SPACE }
            }

            item { SectionHeader("Appearance") }
            item {
                SettingRow(Icons.Rounded.DarkMode, "Theme", themeLabel(settings.themeMode)) { dialog = SettingsDialog.THEME }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SettingSwitch(
                        icon = Icons.Rounded.ColorLens,
                        title = "Dynamic color",
                        summary = "Match your wallpaper colors (Material You)",
                        checked = settings.dynamicColor,
                        onCheckedChange = controller::setDynamicColor,
                    )
                }
            }

            item { SectionHeader("About this vault") }
            item { SettingRow(Icons.Rounded.Memory, "Key protection", state.hardwareLevel.label) }
            item { SettingRow(Icons.Rounded.Security, "APK signature", state.signatureStatus) }
            item { SettingRow(Icons.Rounded.AdminPanelSettings, "Hardware attestation", state.attestationSummary) }
            item {
                SettingRow(Icons.Rounded.Key, "Passphrase hardening", "Argon2id, ${state.kdfMemoryMiB} MB memory, 3 passes")
            }
            item { SettingRow(Icons.Rounded.WifiOff, "Network", "No internet permission. The app cannot open network sockets") }
            item {
                SettingRow(
                    Icons.Rounded.Info,
                    "Version ${BuildConfig.VERSION_NAME}",
                    state.environmentWarning ?: "Release build",
                )
            }

            item {
                HorizontalDivider(Modifier.padding(top = 16.dp))
                SectionHeader("Danger zone")
            }
            item {
                ListItem(
                    headlineContent = { Text("Destroy vault", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Permanently make every item unreadable by destroying the master key") },
                    leadingContent = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { dialog = SettingsDialog.DESTROY },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    when (dialog) {
        SettingsDialog.AUTO_LOCK -> ChoiceDialog(
            title = "Lock when you leave the app",
            icon = Icons.Rounded.LockClock,
            options = listOf(0, 15, 60, 300).map { it to autoLockLabel(it) },
            selected = settings.autoLockSeconds,
            onSelect = controller::setAutoLockSeconds,
            onDismiss = { dialog = null },
        )
        SettingsDialog.IDLE_LOCK -> ChoiceDialog(
            title = "Lock when idle",
            icon = Icons.Rounded.HourglassBottom,
            options = listOf(1, 2, 5, 10, 0).map { it to idleLockLabel(it) },
            selected = settings.inactivityLockMinutes,
            onSelect = controller::setInactivityLockMinutes,
            onDismiss = { dialog = null },
        )
        SettingsDialog.AFTER_IMPORT -> ChoiceDialog(
            title = "After encrypting media",
            icon = Icons.Rounded.AutoDelete,
            options = AfterImportAction.entries.map { it to afterImportLabel(it) },
            selected = settings.afterImport,
            onSelect = controller::setAfterImportAction,
            onDismiss = { dialog = null },
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = "Theme",
            icon = Icons.Rounded.DarkMode,
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = settings.themeMode,
            onSelect = controller::setThemeMode,
            onDismiss = { dialog = null },
        )
        SettingsDialog.WIPE_THRESHOLD -> ChoiceDialog(
            title = "Automatic erase threshold",
            icon = Icons.Rounded.Pin,
            options = listOf(3, 5, 7, 10, 15, 20).map { it to "$it wrong passphrases" },
            selected = settings.wipeFailureThreshold,
            onSelect = controller::setWipeFailureThreshold,
            onDismiss = { dialog = null },
        )
        SettingsDialog.LAUNCHER -> ChoiceDialog(
            title = "Launcher name and icon",
            icon = Icons.Rounded.Apps,
            options = LauncherIdentity.entries.map { it to launcherLabel(it) },
            selected = settings.launcherIdentity,
            onSelect = controller::setLauncherIdentity,
            onDismiss = { dialog = null },
        )
        SettingsDialog.GRID -> ChoiceDialog(
            title = "Thumbnail size",
            icon = Icons.Rounded.GridView,
            options = GridSize.entries.map { it to gridLabel(it) },
            selected = settings.gridSize,
            onSelect = controller::setGridSize,
            onDismiss = { dialog = null },
        )
        SettingsDialog.SORT -> ChoiceDialog(
            title = "Sort by",
            icon = Icons.Rounded.SortByAlpha,
            options = SortOrder.entries.map { it to sortLabel(it) },
            selected = settings.sortOrder,
            onSelect = controller::setSortOrder,
            onDismiss = { dialog = null },
        )
        SettingsDialog.VIDEO_SKIP -> ChoiceDialog(
            title = "Skip interval",
            icon = Icons.Rounded.Forward10,
            options = listOf(5, 10, 30).map { it to "$it seconds" },
            selected = settings.skipSeconds,
            onSelect = controller::setVideoSkipSeconds,
            onDismiss = { dialog = null },
        )
        SettingsDialog.PANIC_GESTURE -> ChoiceDialog(
            title = "Shake / face-down panic action",
            icon = Icons.Rounded.GppMaybe,
            options = PanicAction.entries.map { it to panicActionLabel(it) },
            selected = settings.panicGestureAction,
            onSelect = controller::setPanicGestureAction,
            onDismiss = { dialog = null },
        )
        SettingsDialog.PANIC_TILE -> ChoiceDialog(
            title = "Quick Settings panic tile",
            icon = Icons.Rounded.GppMaybe,
            options = PanicAction.entries.map { it to panicActionLabel(it) },
            selected = settings.panicTileAction,
            onSelect = controller::setPanicTileAction,
            onDismiss = { dialog = null },
        )
        SettingsDialog.DEAD_MAN -> ChoiceDialog(
            title = "Dead-man erase",
            icon = Icons.Rounded.AutoDelete,
            options = listOf(0, 7, 30, 90).map { it to deadManLabel(it) },
            selected = settings.deadManDays,
            onSelect = controller::setDeadManDays,
            onDismiss = { dialog = null },
        )
        SettingsDialog.HOME_TITLE -> HomeTitleDialog(
            initial = settings.homeTitle,
            onSave = {
                controller.setHomeTitle(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ARM_WIPE -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.GppMaybe, contentDescription = null) },
            title = { Text("Turn on automatic erase?") },
            text = {
                Text(
                    "After ${settings.wipeFailureThreshold} consecutive wrong passphrases the only vault key is destroyed. " +
                        "This protects confidentiality after theft, but anyone holding your phone could deliberately erase the vault. Keep an encrypted backup.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    controller.setWipeAfterTenFailures(true)
                    dialog = null
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        SettingsDialog.ARM_STRICT_INTEGRITY -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
            title = { Text("Enable strict device integrity?") },
            text = { Text("Future release unlocks will be blocked if Android cannot produce a hardware-backed attestation reporting verified boot and a locked bootloader. Some legitimate phones do not expose attestation reliably, so your passphrase cannot override this until you reinstall or clear the setting through an app update.") },
            confirmButton = { Button(onClick = { controller.setStrictDeviceIntegrity(true); dialog = null }) { Text("Enable strict mode") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        SettingsDialog.DURESS -> DuressPassphraseDialog(
            onConfigure = { current, duress, confirmation ->
                dialog = null; actions.onConfigureDuress(current, duress, confirmation)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.INTRUDER_EVENTS -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
            title = { Text("Encrypted intruder log") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.intruderPhoto?.let { photo ->
                        Text("Latest captured image", style = MaterialTheme.typography.labelLarge)
                        Image(photo.asImageBitmap(), contentDescription = "Latest failed-unlock camera image",
                            modifier = Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop)
                    }
                    if (state.intruderEvents.isEmpty()) Text("No failed attempts recorded.")
                    state.intruderEvents.asReversed().forEach { event ->
                        Text("${DateFormat.getDateTimeInstance().format(Date(event.epochMillis))} — ${event.reason}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
            dismissButton = if (state.intruderEvents.isNotEmpty()) {
                { TextButton(onClick = { controller.clearIntruderLog(); dialog = null }) { Text("Erase log") } }
            } else null,
        )
        SettingsDialog.CHANGE_PASSPHRASE -> ChangePassphraseDialog(
            onChange = { current, passphrase, confirmation ->
                dialog = null
                actions.onChangePassphrase(current, passphrase, confirmation)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.WIPE_FREE_SPACE -> WipeFreeSpaceDialog(
            onStart = { passes ->
                dialog = null
                actions.onWipeFreeSpace(passes)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DESTROY -> CryptoEraseDialog(
            onErase = {
                dialog = null
                actions.onDestroyVault()
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun HomeTitleDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
        title = { Text("Vault home title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This changes only the title shown after unlocking. The launcher identity is controlled separately.")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(32) },
                    label = { Text("Title") },
                    singleLine = true,
                )
                TextButton(onClick = { title = "My Applications" }) { Text("Use “My Applications”") }
                TextButton(onClick = { title = "Notes" }) { Text("Use “Notes”") }
            }
        },
        confirmButton = { Button(onClick = { onSave(title) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, summary: String, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun autoLockLabel(seconds: Int): String = when (seconds) {
    0 -> "Immediately"
    15 -> "After 15 seconds"
    60 -> "After 1 minute"
    300 -> "After 5 minutes"
    else -> "After $seconds seconds"
}

private fun idleLockLabel(minutes: Int): String = when (minutes) {
    0 -> "Never"
    1 -> "After 1 minute"
    else -> "After $minutes minutes"
}

private fun afterImportLabel(action: AfterImportAction): String = when (action) {
    AfterImportAction.ASK -> "Ask every time"
    AfterImportAction.DELETE -> "Delete verified originals from the phone"
    AfterImportAction.KEEP -> "Keep originals"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun gridLabel(size: GridSize): String = when (size) {
    GridSize.COMPACT -> "Small · more per row"
    GridSize.COMFORTABLE -> "Medium"
    GridSize.LARGE -> "Large · bigger previews"
}

private fun sortLabel(order: SortOrder): String = when (order) {
    SortOrder.NEWEST -> "Newest first"
    SortOrder.OLDEST -> "Oldest first"
    SortOrder.NAME -> "Name (A–Z)"
    SortOrder.LARGEST -> "Largest first"
}

private fun launcherLabel(identity: LauncherIdentity): String = when (identity) {
    LauncherIdentity.VAULT -> "Opaque Vault · shield icon"
    LauncherIdentity.NOTES -> "Notes · document icon"
    LauncherIdentity.CALCULATOR -> "Calculator · calculator icon"
}

private fun panicActionLabel(action: PanicAction): String = when (action) {
    PanicAction.OFF -> "Off"
    PanicAction.LOCK -> "Lock immediately"
    PanicAction.ERASE -> "Permanently erase vault keys"
}

private fun deadManLabel(days: Int): String = when (days) {
    0 -> "Off"
    7 -> "Erase at next app start after 7 days without a successful unlock"
    else -> "Erase at next app start after $days days without a successful unlock"
}
