package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.Icons.Default
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import crypto.*
import network.*

/**
 * Handles key backup enable/disable and restore operations
 */
@Composable
fun KeyBackupSection(
    scope: CoroutineScope,
    backupEnabled: Boolean,
    recoveryKey: String?,
    isEnablingBackup: Boolean,
    backupStatus: String?,
    onBackupEnabledChange: (Boolean) -> Unit,
    onRecoveryKeyChange: (String?) -> Unit,
    onBackupStatusChange: (String?) -> Unit,
    onShowRestoreDialog: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Key Backup", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            if (!backupEnabled) {
                Text(
                    "Enable key backup to securely store your room encryption keys on the server. " +
                    "This allows you to recover your message history if you lose access to your device.",
                    style = MaterialTheme.typography.body2
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        onBackupStatusChange(null)
                        scope.launch {
                            try {
                                val key = crypto.enableKeyBackup()
                                if (key != null) {
                                    onRecoveryKeyChange(key)
                                    onBackupEnabledChange(true)
                                    onBackupStatusChange("Key backup enabled successfully!")
                                } else {
                                    onBackupStatusChange("Failed to enable key backup. Please try again.")
                                }
                            } catch (e: Exception) {
                                onBackupStatusChange("Error: ${e.message}")
                            }
                        }
                    },
                    enabled = !isEnablingBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isEnablingBackup) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enabling...")
                    } else {
                        Text("Enable Key Backup")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✅ Key backup is enabled", color = MaterialTheme.colors.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onShowRestoreDialog) {
                        Text("Restore Keys")
                    }
                }

                recoveryKey?.let { key ->
                    RecoveryKeyDisplay(key)
                }
            }

            backupStatus?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    status,
                    color = if (status.contains("successfully")) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                    style = MaterialTheme.typography.body2
                )
            }
        }
    }
}

/**
 * Displays the recovery key with copy functionality
 */
@Composable
fun RecoveryKeyDisplay(key: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text("Recovery Key", style = MaterialTheme.typography.subtitle1)
    Spacer(modifier = Modifier.height(4.dp))

    Card(
        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Save this recovery key in a secure place. You will need it to restore your keys on a new device:",
                style = MaterialTheme.typography.caption
            )
            Spacer(modifier = Modifier.height(8.dp))
            val clipboardManager = LocalClipboardManager.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    key,
                    style = MaterialTheme.typography.body1.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(key))
                }) {
                    Text("Copy", style = MaterialTheme.typography.button)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "⚠️ Keep this key safe and private. Anyone with this key can read your encrypted messages.",
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.error
    )
}

/**
 * Displays account information (user ID, device ID, homeserver)
 */
@Composable
fun AccountInfoSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Account Information", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            currentUserId?.let { userId ->
                Text("User ID: $userId", style = MaterialTheme.typography.body2)
            }

            currentDeviceId?.let { deviceId ->
                Text("Device ID: $deviceId", style = MaterialTheme.typography.body2)
            }

            currentHomeserver.let { homeserver ->
                Text("Homeserver: $homeserver", style = MaterialTheme.typography.body2)
            }
        }
    }
}

/**
 * Displays device keys (Curve25519 and Ed25519)
 */
@Composable
fun DeviceKeysSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Device Keys", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            val identityKeys = crypto.getIdentityKeys()
            val clipboardManager = LocalClipboardManager.current

            if (identityKeys != null) {
                identityKeys["curve25519"]?.let { curveKey ->
                    KeyDisplayRow(
                        label = "Curve25519 Key",
                        key = curveKey,
                        onCopyClick = {
                            clipboardManager.setText(AnnotatedString(curveKey))
                        }
                    )
                }

                identityKeys["ed25519"]?.let { edKey ->
                    Spacer(modifier = Modifier.height(8.dp))
                    KeyDisplayRow(
                        label = "Ed25519 Key",
                        key = edKey,
                        onCopyClick = {
                            clipboardManager.setText(AnnotatedString(edKey))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "These keys uniquely identify your device for end-to-end encryption.",
                    style = MaterialTheme.typography.caption
                )
            } else {
                Text("Keys not available", style = MaterialTheme.typography.body2)
            }
        }
    }
}

/**
 * Displays encryption status and room key counts
 */
@Composable
fun EncryptionStatusSection(backupEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Encryption Status", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            val roomKeyCount = crypto.getRoomKeyCount()
            Text("Room keys stored: ${roomKeyCount.total}", style = MaterialTheme.typography.body2)
            Text("Room keys backed up: ${roomKeyCount.backedUp}", style = MaterialTheme.typography.body2)

            if (roomKeyCount.total > 0L && roomKeyCount.backedUp == 0L && backupEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "⚠️ You have room keys that haven't been backed up yet.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.secondary
                )
            }
        }
    }
}

/**
 * Dialog for restoring keys from backup
 */
@Composable
fun RestoreDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    scope: CoroutineScope
) {
    if (!showDialog) return

    var restoreKey by remember { mutableStateOf("") }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore Key Backup") },
        text = {
            Column {
                Text("Enter your recovery key to restore backed up room keys:")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = restoreKey,
                    onValueChange = { restoreKey = it },
                    label = { Text("Recovery Key") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRestoring,
                    isError = restoreStatus?.contains("Error") == true
                )
                restoreStatus?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        status,
                        color = if (status.contains("successfully")) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (restoreKey.isNotBlank()) {
                        isRestoring = true
                        restoreStatus = null
                        scope.launch {
                            try {
                                val success = crypto.restoreFromBackup(restoreKey)
                                if (success) {
                                    restoreStatus = "Keys restored successfully!"
                                    delay(1500)
                                    onDismiss()
                                } else {
                                    restoreStatus = "Failed to restore keys. Please check your recovery key."
                                }
                            } catch (e: Exception) {
                                restoreStatus = "Error: ${e.message}"
                            } finally {
                                isRestoring = false
                            }
                        }
                    }
                },
                enabled = restoreKey.isNotBlank() && !isRestoring
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restoring...")
                } else {
                    Text("Restore")
                }
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, enabled = !isRestoring) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    // Use rememberCoroutineScope for composition-aware coroutines
    val scope = rememberCoroutineScope()
    var backupEnabled by remember { mutableStateOf(false) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var isEnablingBackup by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }

    // Check backup status on load
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                backupEnabled = crypto.isKeyBackupEnabled()
                println("🔍 Settings: Key backup enabled: $backupEnabled")
            } catch (e: Exception) {
                println("❌ Failed to check backup status: ${e.message}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Key Backup Section
            KeyBackupSection(
                scope = scope,
                backupEnabled = backupEnabled,
                recoveryKey = recoveryKey,
                isEnablingBackup = isEnablingBackup,
                backupStatus = backupStatus,
                onBackupEnabledChange = { backupEnabled = it },
                onRecoveryKeyChange = { recoveryKey = it },
                onBackupStatusChange = { backupStatus = it },
                onShowRestoreDialog = { showRestoreDialog = true }
            )

            // Account Information Section
            AccountInfoSection()

            // Device Keys Section
            DeviceKeysSection()

            // Encryption Information Section
            EncryptionStatusSection(backupEnabled)
        }
    }

    // Restore Dialog
    RestoreDialog(
        showDialog = showRestoreDialog,
        onDismiss = { showRestoreDialog = false },
        scope = scope
    )
}

@Composable
fun KeyDisplayRow(
    label: String,
    key: String,
    onCopyClick: () -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.subtitle2)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                key,
                style = MaterialTheme.typography.body2.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopyClick) {
                Text("Copy", style = MaterialTheme.typography.button)
            }
        }
    }
}
