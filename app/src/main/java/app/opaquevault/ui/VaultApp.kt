package app.opaquevault.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.opaquevault.VaultController
import app.opaquevault.security.AfterImportAction
import app.opaquevault.security.DisguiseMode
import app.opaquevault.vault.ExportTarget
import app.opaquevault.vault.VaultBackup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DeviceAuthenticationPurpose { UNLOCK, ENABLE_SETTING }
enum class NfcKeyPurpose { UNLOCK, ENROLL }

private enum class Screen { HOME, SETTINGS, SECURITY_AUDIT }

@Composable
fun OpaqueVaultApp(
    controller: VaultController,
    allowInsecureQaScreenshots: Boolean = false,
    onRequestDeviceAuthentication: (DeviceAuthenticationPurpose) -> Unit,
    onRequestNfcKey: (NfcKeyPurpose) -> Unit,
) {
    val state = controller.state
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var disguiseRevealed by remember {
        mutableStateOf(state.settings.disguiseMode != DisguiseMode.CALCULATOR)
    }
    var previousPhase by remember { mutableStateOf(state.phase) }
    var viewerId by remember { mutableStateOf<String?>(null) }
    var showEmergencyErase by remember { mutableStateOf(false) }
    var exportDestination by remember { mutableStateOf<Uri?>(null) }
    var restoreSource by remember { mutableStateOf<Uri?>(null) }
    // Export survives an auto-lock while the folder picker is open; it resumes after unlocking.
    var exportIds by remember { mutableStateOf<List<String>?>(null) }
    var exportToGallery by remember { mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) }
    var exportFolder by remember { mutableStateOf<Uri?>(null) }
    var restoreIntoNewVault by remember { mutableStateOf(false) }
    var deletionAwaitingPhotoAccess by remember { mutableStateOf(false) }
    var resumeDeletion by remember { mutableStateOf(false) }
    val busy = state.busy != null
    val unlocked = state.phase == VaultController.Phase.UNLOCKED

    // Long imports, backups and wipes must not be interrupted by the screen turning off.
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.phase, state.settings.disguiseMode) {
        if (!unlocked) {
            screen = Screen.HOME
            viewerId = null
        }
        if (state.settings.disguiseMode == DisguiseMode.OFF) {
            disguiseRevealed = true
        } else if (previousPhase == VaultController.Phase.UNLOCKED && !unlocked) {
            // Every real lock returns to the cover instead of exposing the authentication screen.
            disguiseRevealed = false
        }
        previousPhase = state.phase
    }
    LaunchedEffect(state.message, state.error, unlocked) {
        if (!unlocked) return@LaunchedEffect
        val text = listOfNotNull(state.message, state.error).joinToString("\n")
        if (text.isEmpty()) return@LaunchedEffect
        val important = state.error != null
        controller.clearTransientMessage()
        // Launched outside this effect so clearing the message doesn't cancel the snackbar.
        scope.launch {
            snackbarHostState.showSnackbar(
                message = text,
                withDismissAction = true,
                duration = if (important) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    // --- Activity launchers -------------------------------------------------------------------
    val onPicked: (List<Uri>) -> Unit = { uris ->
        controller.externalActivityPending = false
        if (uris.isNotEmpty()) scope.launch { controller.importDocuments(uris) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), onPicked)
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onPicked)
    val systemDeleteConfirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        controller.externalActivityPending = false
        scope.launch { controller.onSystemDeletionResult(approved = result.resultCode == Activity.RESULT_OK) }
    }
    val photoAccessRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.externalActivityPending = false
        controller.refreshPermissions()
        if (deletionAwaitingPhotoAccess) {
            deletionAwaitingPhotoAccess = false
            resumeDeletion = true
        } else if (!controller.state.canSearchPhotos) {
            controller.reportMessage("Media access wasn't granted. Allow it in Android Settings → Apps → Opaque Vault → Permissions.")
        }
    }
    val cameraPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.externalActivityPending = false
        controller.setCaptureIntruderPhoto(granted)
        if (!granted) controller.reportMessage("Camera permission was not granted; failed-attempt photos remain off.")
    }
    val createBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        controller.externalActivityPending = false
        if (uri != null) exportDestination = uri
    }
    val chooseExportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        controller.externalActivityPending = false
        if (uri != null) {
            exportFolder = uri
            exportToGallery = false
        }
    }
    val openBackupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        controller.externalActivityPending = false
        if (uri != null) restoreSource = uri
    }

    fun launchSafely(launch: () -> Unit) {
        controller.clearTransientMessage()
        controller.externalActivityPending = true
        try {
            launch()
        } catch (error: Exception) {
            controller.externalActivityPending = false
            controller.reportPickerLaunchFailure(error)
        }
    }

    fun launchPhotoPicker() = launchSafely {
        try {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } catch (_: Exception) {
            documentPicker.launch(arrayOf("image/*", "video/*"))
        }
    }

    fun launchRestorePicker(intoNewVault: Boolean) {
        restoreIntoNewVault = intoNewVault
        launchSafely { openBackupFile.launch(arrayOf("*/*")) }
    }

    fun runDeletion(step: suspend () -> VaultController.DeletionStep) {
        scope.launch {
            when (val next = step()) {
                is VaultController.DeletionStep.NeedsPhotoAccess -> {
                    deletionAwaitingPhotoAccess = true
                    controller.externalActivityPending = true
                    try {
                        photoAccessRequest.launch(next.permissions)
                    } catch (_: Exception) {
                        deletionAwaitingPhotoAccess = false
                        controller.externalActivityPending = false
                        resumeDeletion = true
                    }
                }
                is VaultController.DeletionStep.NeedsSystemConfirmation -> {
                    controller.externalActivityPending = true
                    try {
                        systemDeleteConfirmation.launch(IntentSenderRequest.Builder(next.intentSender).build())
                    } catch (_: Exception) {
                        controller.externalActivityPending = false
                        controller.onSystemDeletionResult(approved = false)
                    }
                }
                VaultController.DeletionStep.Done -> Unit
            }
        }
    }

    LaunchedEffect(resumeDeletion) {
        if (resumeDeletion) {
            resumeDeletion = false
            runDeletion { controller.continueOriginalDeletion() }
        }
    }
    // "Don't ask again" chose Delete: remove originals as soon as an import finishes.
    LaunchedEffect(state.pendingSourceDeletion, state.settings.afterImport, busy) {
        if (state.pendingSourceDeletion.isNotEmpty() && !busy && state.settings.afterImport == AfterImportAction.DELETE) {
            runDeletion { controller.beginOriginalDeletion() }
        }
    }
    val readyToImport = unlocked && state.pendingImports.isNotEmpty() && state.pendingSourceDeletion.isEmpty() && !busy
    LaunchedEffect(readyToImport, state.settings.confirmSharedImports) {
        if (readyToImport && !state.settings.confirmSharedImports) controller.importPendingImports()
    }

    // --- Screens ------------------------------------------------------------------------------
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            val disguiseShouldCover = state.settings.disguiseMode == DisguiseMode.CALCULATOR &&
                (!disguiseRevealed || (previousPhase == VaultController.Phase.UNLOCKED && !unlocked))
            if (disguiseShouldCover) {
                CalculatorDisguiseScreen(
                    onRevealAuthentication = {
                        // The cover is never an authentication factor. Always present the real locks.
                        controller.lock()
                        disguiseRevealed = true
                    },
                )
            } else when (state.phase) {
                VaultController.Phase.SETUP -> SetupScreen(
                    state = state,
                    onCreate = { passphrase, confirmation -> scope.launch { controller.createVault(passphrase, confirmation) } },
                    onRestore = { launchRestorePicker(intoNewVault = true) },
                )
                VaultController.Phase.LOCKED -> LockedScreen(
                    state = state,
                    onUnlock = { scope.launch { controller.unlock(it) } },
                    onDeviceAuthentication = { onRequestDeviceAuthentication(DeviceAuthenticationPurpose.UNLOCK) },
                    onNfcKey = { onRequestNfcKey(NfcKeyPurpose.UNLOCK) },
                    onEmergencyErase = { showEmergencyErase = true },
                )
                VaultController.Phase.UNLOCKED -> when (screen) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        controller = controller,
                        snackbarHostState = snackbarHostState,
                        onAddFromPhotoPicker = ::launchPhotoPicker,
                        onAddFromFiles = { launchSafely { documentPicker.launch(arrayOf("image/*", "video/*")) } },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenPhoto = { viewerId = it },
                        onExport = { ids -> exportIds = ids },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        controller = controller,
                        snackbarHostState = snackbarHostState,
                        actions = SettingsActions(
                            onBack = { screen = Screen.HOME },
                            onEnableDeviceAuthentication = {
                                onRequestDeviceAuthentication(DeviceAuthenticationPurpose.ENABLE_SETTING)
                            },
                            onEnrollNfcKey = { onRequestNfcKey(NfcKeyPurpose.ENROLL) },
                            onConfigureDuress = { current, duress, confirmation ->
                                scope.launch { controller.configureDuressPassphrase(current, duress, confirmation) }
                            },
                            onVerifyIntegrity = { scope.launch { controller.verifyVaultIntegrity() } },
                            onEnableIntruderPhoto = { launchSafely { cameraPermissionRequest.launch(android.Manifest.permission.CAMERA) } },
                            onRequestPhotoAccess = {
                                launchSafely { photoAccessRequest.launch(controller.photoAccessPermissions()) }
                            },
                            onOpenMediaManagement = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    launchSafely {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null)),
                                        )
                                    }
                                }
                            },
                            onExportBackup = {
                                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                launchSafely { createBackupFile.launch("OpaqueVault-$stamp.${VaultBackup.FILE_EXTENSION}") }
                            },
                            onRestoreBackup = { launchRestorePicker(intoNewVault = false) },
                            onWipeFreeSpace = { passes -> scope.launch { controller.wipeFreeSpace(passes) } },
                            onChangePassphrase = { current, passphrase, confirmation ->
                                scope.launch { controller.changePassphrase(current, passphrase, confirmation) }
                            },
                            onOpenSecurityAudit = { screen = Screen.SECURITY_AUDIT },
                            onDestroyVault = { scope.launch { controller.cryptoErase() } },
                        ),
                    )
                    Screen.SECURITY_AUDIT -> SecurityAuditScreen(onBack = { screen = Screen.SETTINGS })
                }
            }

            val openPhoto = viewerId
            if (unlocked && openPhoto != null) {
                ViewerScreen(
                    entries = state.entries,
                    startId = openPhoto,
                    controller = controller,
                    allowInsecureQaScreenshots = allowInsecureQaScreenshots,
                    onDelete = { entry -> scope.launch { controller.deleteEntries(listOf(entry.id)) } },
                    onExport = { entry -> exportIds = listOf(entry.id) },
                    onClose = { viewerId = null },
                )
            }
            state.busy?.let { BusyOverlay(it, onCancel = controller::cancelCurrentOperation) }
        }
    }

    // --- Dialogs ------------------------------------------------------------------------------
    if (readyToImport && state.settings.confirmSharedImports) {
        SharedImportDialog(
            count = state.pendingImports.size,
            onImport = { dontAskAgain ->
                if (dontAskAgain) controller.setConfirmSharedImports(false)
                scope.launch { controller.importPendingImports() }
            },
            onDiscard = controller::discardPendingImports,
        )
    }

    if (unlocked && state.pendingSourceDeletion.isNotEmpty() && !busy && state.settings.afterImport == AfterImportAction.ASK) {
        RemoveOriginalsDialog(
            count = state.pendingSourceDeletion.size,
            androidWillAsk = !state.canDeleteWithoutConfirmation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            onDelete = { dontAskAgain ->
                if (dontAskAgain) controller.setAfterImportAction(AfterImportAction.DELETE)
                runDeletion { controller.beginOriginalDeletion() }
            },
            onKeep = { dontAskAgain -> controller.keepPendingOriginals(remember = dontAskAgain) },
        )
    }

    if (unlocked) exportDestination?.let { destination ->
        PassphrasePromptDialog(
            title = "Export encrypted backup",
            message = "Enter your vault passphrase. The backup is sealed with it, so keep it safe: the file opens " +
                "on any phone with this passphrase alone.",
            confirmLabel = "Create backup",
            icon = Icons.Rounded.Archive,
            onConfirm = { passphrase ->
                exportDestination = null
                scope.launch { controller.exportBackup(destination, passphrase) }
            },
            onDismiss = {
                exportDestination = null
                controller.discardUnusedBackupFile(destination)
            },
        )
    }

    if (restoreIntoNewVault || unlocked) restoreSource?.let { source ->
        PassphrasePromptDialog(
            title = "Restore backup",
            message = if (restoreIntoNewVault) {
                "Enter the backup's passphrase. It also becomes this vault's passphrase."
            } else {
                "Enter the passphrase this backup was created with."
            },
            confirmLabel = "Restore",
            icon = Icons.Rounded.Unarchive,
            onConfirm = { passphrase ->
                restoreSource = null
                scope.launch {
                    if (restoreIntoNewVault) controller.createVaultAndRestore(passphrase, source)
                    else controller.restoreBackup(source, passphrase)
                }
            },
            onDismiss = { restoreSource = null },
        )
    }

    val pendingExport = exportIds
    if (unlocked && !busy && pendingExport != null) {
        val folderName = remember(exportFolder) { exportFolder?.let { treeDisplayName(context, it) } }
        ExportMediaDialog(
            count = pendingExport.size,
            galleryAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            toGallery = exportToGallery,
            onToGalleryChange = { exportToGallery = it },
            folderName = folderName,
            onChooseFolder = { launchSafely { chooseExportFolder.launch(null) } },
            initialStripMetadata = state.settings.stripMetadataOnExport,
            onExport = { removeAfter, stripMetadata, passphrase ->
                val folder = exportFolder
                val target = if (exportToGallery || folder == null) ExportTarget.Gallery else ExportTarget.Folder(folder)
                exportIds = null
                scope.launch { controller.exportMedia(pendingExport, target, removeAfter, stripMetadata, passphrase) }
            },
            onDismiss = { exportIds = null },
        )
    }

    if (showEmergencyErase) {
        CryptoEraseDialog(
            onErase = {
                showEmergencyErase = false
                scope.launch { controller.cryptoErase() }
            },
            onDismiss = { showEmergencyErase = false },
        )
    }
}

/** Display name of a SAF tree without pulling in androidx.documentfile. Never throws. */
private fun treeDisplayName(context: android.content.Context, treeUri: Uri): String = try {
    val documentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        android.provider.DocumentsContract.getTreeDocumentId(treeUri),
    )
    context.contentResolver.query(
        documentUri,
        arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "Chosen folder"
} catch (_: Exception) {
    "Chosen folder"
}

@Composable
private fun SharedImportDialog(count: Int, onImport: (Boolean) -> Unit, onDiscard: () -> Unit) {
    var dontAskAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
        title = { Text("Encrypt $count item${if (count == 1) "" else "s"}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Opaque Vault received ${if (count == 1) "this item" else "these items"} from another app. Nothing is imported until you confirm.")
                CheckboxRow(dontAskAgain, { dontAskAgain = it }, "Don't ask again")
            }
        },
        confirmButton = { Button(onClick = { onImport(dontAskAgain) }) { Text("Encrypt") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
    )
}

@Composable
private fun RemoveOriginalsDialog(
    count: Int,
    androidWillAsk: Boolean,
    onDelete: (Boolean) -> Unit,
    onKeep: (Boolean) -> Unit,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onKeep(false) },
        icon = { Icon(Icons.Rounded.AutoDelete, contentDescription = null) },
        title = { Text("Remove ${if (count == 1) "the original" else "$count originals"} from your phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${if (count == 1) "The encrypted copy was" else "All $count encrypted copies were"} read back and verified, " +
                        "so the originals can go." + if (androidWillAsk) " Android will ask you to confirm." else "",
                )
                CheckboxRow(dontAskAgain, { dontAskAgain = it }, "Don't ask again")
            }
        },
        confirmButton = { Button(onClick = { onDelete(dontAskAgain) }) { Text("Delete originals") } },
        dismissButton = { TextButton(onClick = { onKeep(dontAskAgain) }) { Text("Keep") } },
    )
}
