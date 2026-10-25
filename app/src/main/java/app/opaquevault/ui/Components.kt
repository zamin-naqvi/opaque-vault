package app.opaquevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opaquevault.VaultController

/** Blocks the UI during long operations, with determinate progress and Cancel when supported. */
@Composable
fun BusyOverlay(busy: VaultController.Busy, onCancel: () -> Unit) {
    BackHandler {} // Back must not leave a half-finished operation.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.padding(32.dp).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(busy.label, style = MaterialTheme.typography.titleLarge)
                val rawProgress = busy.progress
                val progress = rawProgress?.let {
                    androidx.compose.animation.core.animateFloatAsState(
                        targetValue = it.coerceIn(0f, 1f),
                        animationSpec = androidx.compose.animation.core.tween(300),
                        label = "busyProgress",
                    ).value
                }
                if (progress == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        busy.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        strokeCap = StrokeCap.Round,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            busy.detail.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            minLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                Text(
                    "Keep Opaque Vault open. Leaving the app locks the vault and stops this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy.cancellable) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun InfoBanner(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun CheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Material single-choice dialog with radio buttons. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .selectable(selected = value == selected, role = Role.RadioButton) {
                                onSelect(value)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Asks for the passphrase without putting it in Compose state. */
@Composable
fun PassphrasePromptDialog(
    title: String,
    message: String,
    confirmLabel: String,
    icon: ImageVector,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val handle = rememberSecureTextHandle()
    var length by remember { mutableIntStateOf(0) }
    val submit = {
        if (length > 0) onConfirm(handle.takeAndClear())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                SecurePassphraseField(
                    label = "Passphrase",
                    handle = handle,
                    onImeAction = submit,
                    onLengthChanged = { length = it },
                )
            }
        },
        confirmButton = { Button(onClick = submit, enabled = length > 0) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ChangePassphraseDialog(onChange: (CharArray, CharArray, CharArray) -> Unit, onDismiss: () -> Unit) {
    val current = rememberSecureTextHandle()
    val passphrase = rememberSecureTextHandle()
    val confirmation = rememberSecureTextHandle()
    var currentLength by remember { mutableIntStateOf(0) }
    var passphraseLength by remember { mutableIntStateOf(0) }
    var confirmationLength by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Password, contentDescription = null) },
        title = { Text("Change passphrase") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "The current passphrase is required so a person who finds an unlocked phone cannot create a new portable credential.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecurePassphraseField(
                    label = "Current passphrase",
                    handle = current,
                    imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,
                    onLengthChanged = { currentLength = it },
                )
                SecurePassphraseField(
                    label = "New passphrase",
                    handle = passphrase,
                    showStrength = true,
                    imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,
                    onLengthChanged = { passphraseLength = it },
                )
                SecurePassphraseField(
                    label = "Confirm new passphrase",
                    handle = confirmation,
                    onLengthChanged = { confirmationLength = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onChange(current.takeAndClear(), passphrase.takeAndClear(), confirmation.takeAndClear()) },
                enabled = currentLength > 0 &&
                    passphraseLength >= VaultController.MIN_PASSPHRASE_CHARS && confirmationLength > 0,
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CryptoEraseDialog(onErase: () -> Unit, onDismiss: () -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Destroy the vault?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "The hardware-bound key is destroyed first, making every item permanently unreadable. " +
                        "This can't be undone. Only an exported backup could bring them back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(5).uppercase() },
                    label = { Text("Type ERASE to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onErase,
                enabled = confirmation == "ERASE",
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Destroy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun WipeFreeSpaceDialog(onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    var passes by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
        title = { Text("Wipe free space") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Fills allocatable free space on the internal data partition with pseudorandom data, syncs it, then deletes it. " +
                        "This may replace filesystem blocks that once held deleted originals, but Android does not let an app target exact physical flash cells.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Honest limits: flash chips keep spare blocks no app can reach, so this adds to Android's " +
                        "file encryption rather than guaranteeing the impossible. On flash, 1 pass is as effective " +
                        "as 3; extra passes only add time and wear. A small reserve stays free so other apps keep working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.selectableGroup()) {
                    listOf(1 to "1 pass (recommended)", 3 to "3 passes (slow, more wear)").forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(selected = passes == value, role = Role.RadioButton) { passes = value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = passes == value, onClick = null)
                            Spacer(Modifier.size(16.dp))
                            Text(label)
                        }
                    }
                }
                Text(
                    "Also empty Gallery's Recycle bin and check cloud backups (Google Photos, Samsung Cloud): " +
                        "copies there are outside this phone's storage.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = { Button(onClick = { onStart(passes) }) { Text("Start wipe") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Export: destination, optional removal from the vault, and the passphrase (re-authentication).
 * [folderName] is null until a folder is picked; picking one happens outside via [onChooseFolder].
 */
@Composable
fun ExportMediaDialog(
    count: Int,
    galleryAvailable: Boolean,
    toGallery: Boolean,
    onToGalleryChange: (Boolean) -> Unit,
    folderName: String?,
    onChooseFolder: () -> Unit,
    initialStripMetadata: Boolean,
    onExport: (removeAfterExport: Boolean, stripSensitiveMetadata: Boolean, passphrase: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val handle = rememberSecureTextHandle()
    var length by remember { mutableIntStateOf(0) }
    var removeAfter by remember { mutableStateOf(false) }
    var stripMetadata by remember(initialStripMetadata) { mutableStateOf(initialStripMetadata) }
    val destinationReady = toGallery || folderName != null
    val submit = {
        if (length > 0 && destinationReady) onExport(removeAfter, stripMetadata, handle.takeAndClear())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = { Text("Export $count item${if (count == 1) "" else "s"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.selectableGroup()) {
                    if (galleryAvailable) {
                        ChoiceRow(
                            selected = toGallery,
                            title = "Save to Gallery",
                            summary = "Pictures and Movies › Opaque Vault",
                            onClick = { onToGalleryChange(true) },
                        )
                    }
                    ChoiceRow(
                        selected = !toGallery,
                        title = "Save to a folder",
                        summary = folderName ?: "Choose any folder, SD card or USB drive",
                        onClick = {
                            onToGalleryChange(false)
                            if (folderName == null) onChooseFolder()
                        },
                    )
                    if (!toGallery && folderName != null) {
                        TextButton(onClick = onChooseFolder, modifier = Modifier.padding(start = 40.dp)) { Text("Change folder") }
                    }
                }
                CheckboxRow(removeAfter, { removeAfter = it }, "Remove from the vault after exporting")
                CheckboxRow(stripMetadata, { stripMetadata = it }, "Strip GPS, camera and capture-time metadata from exported photos")
                Text(
                    "Exported copies are normal, unencrypted files. Other apps with photo access and cloud " +
                        "backup (Google Photos, Samsung Cloud) can see them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                SecurePassphraseField(
                    label = "Vault passphrase",
                    handle = handle,
                    onImeAction = submit,
                    onLengthChanged = { length = it },
                )
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = length > 0 && destinationReady) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DuressPassphraseDialog(
    onConfigure: (CharArray, CharArray, CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = rememberSecureTextHandle()
    val duress = rememberSecureTextHandle()
    val confirmation = rememberSecureTextHandle()
    var currentLength by remember { mutableIntStateOf(0) }
    var duressLength by remember { mutableIntStateOf(0) }
    var confirmationLength by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Set duress passphrase") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Entering the duress passphrase on the normal lock screen silently destroys all vault key material. " +
                        "It cannot be undone, and after the app restarts the missing vault may reveal that erasure occurred. Keep an encrypted backup.",
                    color = MaterialTheme.colorScheme.error,
                )
                SecurePassphraseField("Current vault passphrase", current, imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,
                    onLengthChanged = { currentLength = it })
                SecurePassphraseField("New duress passphrase", duress, showStrength = true,
                    imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT, onLengthChanged = { duressLength = it })
                SecurePassphraseField("Confirm duress passphrase", confirmation, onLengthChanged = { confirmationLength = it })
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfigure(current.takeAndClear(), duress.takeAndClear(), confirmation.takeAndClear()) },
                enabled = currentLength > 0 && duressLength >= VaultController.MIN_PASSPHRASE_CHARS && confirmationLength > 0,
            ) { Text("Arm duress erase") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
