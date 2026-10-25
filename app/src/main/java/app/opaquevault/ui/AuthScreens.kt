package app.opaquevault.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opaquevault.VaultController

@Composable
fun SetupScreen(
    state: VaultController.UiState,
    onCreate: (CharArray, CharArray) -> Unit,
    onRestore: () -> Unit,
) {
    val passphrase = rememberSecureTextHandle()
    val confirmation = rememberSecureTextHandle()
    var passphraseLength by remember { mutableIntStateOf(0) }
    var confirmationLength by remember { mutableIntStateOf(0) }
    val canCreate = state.busy == null &&
        passphraseLength >= VaultController.MIN_PASSPHRASE_CHARS &&
        confirmationLength > 0
    val create = { if (canCreate) onCreate(passphrase.takeAndClear(), confirmation.takeAndClear()) }

    AuthScaffold {
        HeroIcon(Icons.Rounded.Shield)
        Text("Create your vault", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            "Photos and videos are encrypted on this phone only. No account, no cloud, no recovery backdoor.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PendingImportsBanner(state)
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
        SecurePassphraseField(
            label = "Passphrase",
            handle = passphrase,
            enabled = state.busy == null,
            showStrength = true,
            supportingText = "At least ${VaultController.MIN_PASSPHRASE_CHARS} characters. Six random words is ideal.",
            imeAction = EditorInfo.IME_ACTION_NEXT,
            onLengthChanged = { passphraseLength = it },
        )
        SecurePassphraseField(
            label = "Confirm passphrase",
            handle = confirmation,
            enabled = state.busy == null,
            isError = state.error != null,
            supportingText = state.error,
            onImeAction = create,
            onLengthChanged = { confirmationLength = it },
        )
        Button(
            onClick = create,
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Create vault") }
        OutlinedButton(
            onClick = onRestore,
            enabled = state.busy == null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Rounded.Unarchive, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Restore from encrypted backup")
        }
        Text(
            "Forget the passphrase and nobody can recover the vault, so write it down somewhere safe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        ProtectionSummary()
        state.environmentWarning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun LockedScreen(
    state: VaultController.UiState,
    onUnlock: (CharArray) -> Unit,
    onDeviceAuthentication: () -> Unit,
    onNfcKey: () -> Unit,
    onEmergencyErase: () -> Unit,
) {
    val passphrase = rememberSecureTextHandle()
    var length by remember { mutableIntStateOf(0) }
    val needsNfc = state.settings.nfcKeyEnabled && !state.nfcKeySatisfied
    val unlock = { if (length > 0 && state.busy == null && !needsNfc) onUnlock(passphrase.takeAndClear()) }

    AuthScaffold {
        HeroIcon(Icons.Rounded.Lock)
        Text("Vault locked", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            if (needsNfc) "Scan your enrolled NFC key, then use a biometric or your passphrase." else "Authenticate with secure hardware or enter your passphrase.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PendingImportsBanner(state)
        if (needsNfc) {
            Button(onClick = onNfcKey, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.Nfc, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Scan NFC key")
            }
        }
        if (state.settings.biometricKeyEnabled && !needsNfc) {
            Button(onClick = onDeviceAuthentication, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Unlock with hardware-bound biometric")
            }
        }
        SecurePassphraseField(
            label = "Passphrase",
            handle = passphrase,
            enabled = state.busy == null && !needsNfc,
            isError = state.error != null,
            supportingText = state.error,
            onImeAction = unlock,
            onLengthChanged = { length = it },
        )
        Button(
            onClick = unlock,
            enabled = length > 0 && state.busy == null && !needsNfc,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Unlock with passphrase")
        }
        if (state.failedUnlockCount > 0) {
            Text(
                buildString {
                    append("Wrong attempts: ${state.failedUnlockCount}")
                    if (state.settings.wipeAfterTenFailures) {
                        append(" of ${state.settings.wipeFailureThreshold} before the vault erases itself")
                    }
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onEmergencyErase, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Emergency erase")
        }
        state.environmentWarning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AuthScaffold(content: @Composable () -> Unit) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).size(96.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun PendingImportsBanner(state: VaultController.UiState) {
    val count = state.pendingImports.size
    if (count == 0) return
    InfoBanner(
        icon = Icons.Rounded.PhotoLibrary,
        title = "$count item${if (count == 1) "" else "s"} waiting",
        body = if (state.phase == VaultController.Phase.SETUP) {
            "Create your vault to encrypt ${if (count == 1) "it" else "them"}."
        } else {
            "Unlock to encrypt ${if (count == 1) "it" else "them"} into the vault."
        },
    )
}

@Composable
private fun ProtectionSummary() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ProtectionRow(Icons.Rounded.Key, "AES-256 with a separate key for every item")
            ProtectionRow(Icons.Rounded.Memory, "Master key bound to this phone's secure hardware")
            ProtectionRow(Icons.Rounded.Shield, "Argon2id makes passphrase guessing slow and costly")
            ProtectionRow(Icons.Rounded.WifiOff, "No internet permission at all")
        }
    }
}

@Composable
private fun ProtectionRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
