package com.recordsapp.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onSignInResult(result.resultCode, result.data)
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarDismissed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Google Account card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google Account", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.accountEmail != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(state.accountEmail!!, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::onSignOut) { Text("Sign out") }
                        }
                    } else {
                        Button(
                            onClick = { signInLauncher.launch(viewModel.signInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect Google Account")
                        }
                    }
                }
            }

            // Progress indicator
            if (state.isOperationInProgress) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.statusMessage.isNotEmpty()) {
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Backup card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Backup", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::onBackupClick,
                        enabled = !state.isOperationInProgress && state.accountEmail != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Backup to Google Drive")
                    }
                    Text(
                        text = state.lastBackupTime?.let { "Last backup: $it" } ?: "Never backed up",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Restore card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Restore", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = viewModel::onRestoreClick,
                        enabled = !state.isOperationInProgress && state.accountEmail != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore from Google Drive")
                    }
                }
            }
        }
    }

    if (state.showRestoreDialog) {
        RestoreDialog(
            onConfirm = viewModel::onRestoreConfirmed,
            onDismiss = viewModel::onRestoreDismissed
        )
    }
}

@Composable
private fun RestoreDialog(
    onConfirm: (RestoreMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf<RestoreMode>(RestoreMode.Replace) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup?") },
        text = {
            Column {
                RestoreOption(
                    label = "Replace",
                    description = "Delete all current records and replace with backup",
                    selected = selectedMode is RestoreMode.Replace,
                    onClick = { selectedMode = RestoreMode.Replace }
                )
                RestoreOption(
                    label = "Merge",
                    description = "Add albums from backup that don't already exist locally",
                    selected = selectedMode is RestoreMode.Merge,
                    onClick = { selectedMode = RestoreMode.Merge }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedMode) }) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RestoreOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
