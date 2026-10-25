package app.opaquevault.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.opaquevault.security.PrivacyAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAuditScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var uninstallPackage by remember { mutableStateOf<String?>(null) }
    val report by produceState<PrivacyAudit.Report?>(initialValue = null, refresh) {
        value = withContext(Dispatchers.IO) { PrivacyAudit(context).scan() }
    }

    fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // A device vendor may remove a settings panel. App details remains available per finding.
        }
    }

    fun review(finding: PrivacyAudit.Finding) {
        val intent = when (finding.remediation) {
            PrivacyAudit.Remediation.APP_DETAILS -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", finding.packageName, null),
            )
            PrivacyAudit.Remediation.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PrivacyAudit.Remediation.NOTIFICATION_LISTENERS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            PrivacyAudit.Remediation.DEVELOPMENT_SETTINGS -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            PrivacyAudit.Remediation.SECURITY_SETTINGS -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            PrivacyAudit.Remediation.VPN_SETTINGS -> Intent(Settings.ACTION_VPN_SETTINGS)
            PrivacyAudit.Remediation.DEVICE_ADMINS -> Intent("android.settings.DEVICE_ADMIN_SETTINGS")
        }
        launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy exposure audit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Scan again")
                    }
                },
            )
        },
    ) { padding ->
        val result = report
        if (result == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text("Reviewing Android permissions and powerful services…", modifier = Modifier.padding(top = 16.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item {
                    InfoBanner(
                        icon = Icons.Rounded.Security,
                        title = "Local, permission-based audit",
                        body = "Reviewed ${result.visibleAppsReviewed} apps visible through Android's privacy-safe package rules. " +
                            "Nothing is uploaded. A finding means review the access—it is not an accusation of malware.",
                        isError = result.findings.any { it.severity <= PrivacyAudit.Severity.HIGH },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (result.findings.isEmpty()) {
                    item {
                        ListItem(
                            headlineContent = { Text("No exposed high-impact capability found") },
                            supportingContent = { Text("Keep Android and every installed app updated, and scan again after installing software.") },
                            leadingContent = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                } else {
                    item { SectionHeader("Review these exposures") }
                    items(result.findings, key = PrivacyAudit.Finding::id) { finding ->
                        AuditFindingCard(
                            finding = finding,
                            onReview = { review(finding) },
                            onUninstall = finding.packageName?.let { packageName -> { uninstallPackage = packageName } },
                        )
                    }
                }
                item { SectionHeader("Checks passed") }
                items(result.checksPassed) { check ->
                    ListItem(
                        headlineContent = { Text(check) },
                        leadingContent = {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                item { SectionHeader("What Android cannot prove") }
                items(result.limitations) { limitation ->
                    ListItem(
                        headlineContent = { Text(limitation) },
                        leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    uninstallPackage?.let { packageName ->
        AlertDialog(
            onDismissRequest = { uninstallPackage = null },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
            title = { Text("Ask Android to uninstall this app?") },
            text = { Text("Package: $packageName\n\nAndroid will show the official confirmation screen. System and administrator apps may need their special access removed first.") },
            confirmButton = {
                Button(
                    onClick = {
                        uninstallPackage = null
                        launch(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { uninstallPackage = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AuditFindingCard(
    finding: PrivacyAudit.Finding,
    onReview: () -> Unit,
    onUninstall: (() -> Unit)?,
) {
    val container = when (finding.severity) {
        PrivacyAudit.Severity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        PrivacyAudit.Severity.HIGH -> MaterialTheme.colorScheme.tertiaryContainer
        PrivacyAudit.Severity.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer
        PrivacyAudit.Severity.REVIEW -> MaterialTheme.colorScheme.surfaceContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .background(container, RoundedCornerShape(20.dp))
            .clickable(onClick = onReview)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.GppMaybe, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(finding.severity.name, style = MaterialTheme.typography.labelSmall)
                Text(finding.title, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(finding.detail, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReview) { Text("Review settings") }
            if (onUninstall != null) {
                TextButton(onClick = onUninstall) { Text("Uninstall") }
            }
        }
    }
}
