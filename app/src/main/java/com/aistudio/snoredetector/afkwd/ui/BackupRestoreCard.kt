package com.aistudio.snoredetector.afkwd.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.snoredetector.afkwd.data.BackupResult
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings card for saving a full backup to a file and restoring one, through the system file
 * picker (Storage Access Framework), so no storage permission is needed.
 */
@Composable
fun BackupRestoreCard(viewModel: SnoreViewModel) {
    val context = LocalContext.current.applicationContext
    val isRunning by SnoreDetectionService.isServiceRunning.collectAsState()
    val progressText by viewModel.backupProgressText.collectAsState()

    var includeRecordings by rememberSaveable { mutableStateOf(true) }
    var showRestoreConfirmation by rememberSaveable { mutableStateOf(false) }

    val showResult: (BackupResult, String) -> Unit = { result, successText ->
        val text = if (result.success) successText else result.errorMessage ?: "Operation failed"
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri, includeRecordings) { result ->
                showResult(result, "Backup saved: ${result.eventCount} events, ${result.recordingCount} recordings")
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.restoreBackup(uri) { result ->
                showResult(result, "Backup restored: ${result.eventCount} events, ${result.recordingCount} recordings")
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_restore_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Backup & Restore",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Save your history and settings to a .zip file in a location you choose, and restore them after a reinstall or on a new phone. The file is not encrypted, so keep it somewhere private.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Include recordings",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = includeRecordings,
                    onCheckedChange = { includeRecordings = it },
                    modifier = Modifier.testTag("backup_include_recordings_switch")
                )
            }
            Button(
                onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("SnoreDetector_Backup_$date.zip")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_export_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Export Backup", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { showRestoreConfirmation = true },
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_restore_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Restore Backup", fontWeight = FontWeight.SemiBold)
            }
            if (isRunning) {
                Text(
                    text = "Stop detection to restore a backup.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = {
                Text(text = "Restore Backup?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Text(
                    text = "A valid backup replaces your current history, recordings, error logs and settings. Recordings that aren't in the backup are deleted. The file is checked first, and nothing changes if it can't be used. Your microphone selection is kept.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirmation = false
                        // Some file providers don't report ZIP files as application/zip
                        restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    modifier = Modifier.testTag("backup_restore_confirm_button")
                ) {
                    Text("Choose File")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal so that detection can't be started while a restore is replacing the data
    progressText?.let { text ->
        AlertDialog(
            onDismissRequest = { /* non-cancellable while working */ },
            title = {
                Text(text = "Backup & Restore", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            confirmButton = {}
        )
    }
}
