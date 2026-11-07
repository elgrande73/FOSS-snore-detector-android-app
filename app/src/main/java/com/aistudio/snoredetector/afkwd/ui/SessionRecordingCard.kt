package com.aistudio.snoredetector.afkwd.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.snoredetector.afkwd.data.SessionRecording
import com.aistudio.snoredetector.afkwd.data.SessionRecordingStore
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings card for the opt-in full-session recording: the switch, its storage cost and the
 * recordings kept on the device.
 */
@Composable
fun SessionRecordingCard(viewModel: SnoreViewModel) {
    val enabled by viewModel.recordFullSession.collectAsState()
    val recordings by viewModel.sessionRecordings.collectAsState()
    val freeBytes by viewModel.sessionStorageFreeBytes.collectAsState()
    val isMonitoring by SnoreDetectionService.isServiceRunning.collectAsState()
    val exportProgress by viewModel.exportProgressText.collectAsState()

    val context = LocalContext.current
    var pendingExportPath by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        val path = pendingExportPath
        if (uri != null && path != null) {
            viewModel.exportSingleAudio(path, uri) { success ->
                val message = if (success) "Session recording saved" else "Failed to save session recording"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportPath = null
    }

    LaunchedEffect(isMonitoring) {
        viewModel.refreshSessionRecordings()
    }

    SessionRecordingCardContent(
        enabled = enabled,
        isMonitoring = isMonitoring,
        recordings = recordings,
        freeBytes = freeBytes,
        exportProgress = exportProgress,
        onEnabledChange = viewModel::updateRecordFullSession,
        onSave = { recording ->
            pendingExportPath = recording.file.absolutePath
            exportLauncher.launch(recording.exportFileName)
        },
        onDelete = viewModel::deleteSessionRecording
    )
}

@Composable
fun SessionRecordingCardContent(
    enabled: Boolean,
    isMonitoring: Boolean,
    recordings: List<SessionRecording>,
    freeBytes: Long,
    exportProgress: String?,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (SessionRecording) -> Unit,
    onDelete: (SessionRecording) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SessionRecording?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().testTag("session_recording_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Record Full Session (.WAV)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "(Default: Disabled)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !isMonitoring,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("record_full_session_switch")
                )
            }

            Text(
                text = "Saves all the audio of a session for analysing it later and tuning the detector, on top of normal detection. Everything the microphone hears is recorded, including speech. Files stay on this device unless you save them elsewhere.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = storageSummary(freeBytes),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("session_storage_summary")
            )
            if (isMonitoring) {
                Text(
                    text = "Stop monitoring to change this setting or manage recordings.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            recordings.forEach { recording ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SessionRecordingRow(
                    recording = recording,
                    actionsEnabled = !isMonitoring && exportProgress == null,
                    onSave = { onSave(recording) },
                    onDelete = { pendingDelete = recording }
                )
            }

            if (exportProgress != null) {
                Text(
                    text = exportProgress,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    pendingDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    text = "Delete Recording?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This permanently deletes the ${SessionRecordingStore.formatSize(recording.sizeBytes)} recording from ${formatStartTime(recording)}. Your snoring events are not affected.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(recording)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_session_recording")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SessionRecordingRow(
    recording: SessionRecording,
    actionsEnabled: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatStartTime(recording),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${SessionRecordingStore.formatDuration(recording.durationSeconds)} • ${SessionRecordingStore.formatSize(recording.sizeBytes)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onSave,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("save_session_recording_${recording.startTimeMillis}")
        ) {
            Text("Save")
        }
        TextButton(
            onClick = onDelete,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("delete_session_recording_${recording.startTimeMillis}")
        ) {
            Text("Delete")
        }
    }
}

internal fun storageSummary(freeBytes: Long): String {
    val eightHours = SessionRecordingStore.formatSize(8 * SessionRecordingStore.BYTES_PER_HOUR)
    val twelveHours = SessionRecordingStore.formatSize(12 * SessionRecordingStore.BYTES_PER_HOUR)
    val reserve = SessionRecordingStore.formatSize(SessionRecordingStore.MIN_FREE_BYTES)
    val free = SessionRecordingStore.formatSize(freeBytes)
    return "Uses about ${SessionRecordingStore.formatSize(SessionRecordingStore.BYTES_PER_HOUR)} per hour: " +
        "$eightHours for 8 hours, $twelveHours for the 12-hour limit. Free now: $free. " +
        "Recording stops when less than $reserve is free. Only the last " +
        "${SessionRecordingStore.MAX_KEPT_RECORDINGS} recordings are kept, so save the ones you need."
}

private fun formatStartTime(recording: SessionRecording): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(recording.startTimeMillis))
