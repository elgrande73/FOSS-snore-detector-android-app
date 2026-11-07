package com.aistudio.snoredetector.afkwd.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistudio.snoredetector.afkwd.audio.SetupCheckController
import com.aistudio.snoredetector.afkwd.audio.SetupCheckProblem
import com.aistudio.snoredetector.afkwd.audio.SetupCheckState
import com.aistudio.snoredetector.afkwd.dsp.CriterionResult
import com.aistudio.snoredetector.afkwd.dsp.CriterionStatus
import com.aistudio.snoredetector.afkwd.dsp.SetupCheckReport
import com.aistudio.snoredetector.afkwd.dsp.SetupCriterion
import com.aistudio.snoredetector.afkwd.dsp.SetupVerdict
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import java.util.Locale

/**
 * Stops the check when the app leaves the foreground or this composable leaves the screen.
 *
 * Call it from the tab, not from inside the LazyColumn item: scrolling the card out of view
 * disposes the item, and that mustn't end the check.
 */
@Composable
fun StopSetupCheckWhenLeaving(onLeave: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onLeave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onLeave()
        }
    }
}

@Composable
fun SetupCheckCard(viewModel: SnoreViewModel) {
    val state by viewModel.setupCheck.state.collectAsState()
    val isMonitoring by SnoreDetectionService.isServiceRunning.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSetupCheck()
    }

    SetupCheckCardContent(
        state = state,
        isMonitoring = isMonitoring,
        onStart = viewModel::startSetupCheck,
        onStop = viewModel.setupCheck::stop,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    )
}

@Composable
fun SetupCheckCardContent(
    state: SetupCheckState,
    isMonitoring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_check_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Test your setup",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            when (state) {
                is SetupCheckState.Running -> RunningContent(state, onStop)
                is SetupCheckState.Finished -> ReportContent(state.report)
                is SetupCheckState.Failed -> Hint(problemText(state.problem), isError = true)
                SetupCheckState.Idle -> Hint(
                    "Records for ${SetupCheckController.DURATION_SECONDS} seconds with the selected microphone and " +
                        "current settings. Nothing is saved. You'll be asked to stay quiet first, then to make " +
                        "a snore-like sound for a few seconds from where you sleep."
                )
            }

            if (state !is SetupCheckState.Running) {
                if (isMonitoring) {
                    Hint("Stop monitoring on the Dashboard to run the check.")
                }
                val needsPermission = state == SetupCheckState.Failed(SetupCheckProblem.PERMISSION_MISSING)
                Button(
                    onClick = if (needsPermission) onRequestPermission else onStart,
                    enabled = !isMonitoring,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_check_start")
                ) {
                    Text(
                        when {
                            needsPermission -> "Grant Microphone Access"
                            state == SetupCheckState.Idle -> "Run Setup Check"
                            else -> "Run Again"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningContent(state: SetupCheckState.Running, onStop: () -> Unit) {
    val quiet = state.elapsedSeconds < SetupCheckController.QUIET_SECONDS
    Text(
        text = if (quiet) "Stay quiet: measuring background noise" else "Make your test sound now",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("setup_check_prompt")
    )
    LinearProgressIndicator(
        progress = { state.elapsedSeconds.toFloat() / SetupCheckController.DURATION_SECONDS },
        modifier = Modifier.fillMaxWidth()
    )
    Hint("${SetupCheckController.DURATION_SECONDS - state.elapsedSeconds} s left")
    OutlinedButton(
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_check_cancel")
    ) {
        Text("Cancel")
    }
}

@Composable
private fun ReportContent(report: SetupCheckReport) {
    if (report.verdict != SetupVerdict.NO_SIGNAL) {
        Text(
            text = "Background noise: ${format(report.noiseFloorDb, "%.1f dB")}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("setup_check_noise_floor")
        )
    }
    Hint(
        verdictText(report.verdict),
        isError = report.verdict != SetupVerdict.DETECTED,
        modifier = Modifier.testTag("setup_check_verdict")
    )
    if (report.noiseFloorReachesVolumeThreshold) {
        Hint(
            "Background noise reaches the Sound Volume threshold " +
                "(${format(report.rmsDbThreshold, "%.0f dB")}), so that criterion can't tell it from snoring.",
            isError = true
        )
    }
    if (report.pausedForMedia) {
        Hint(
            "Media was playing, which pauses detection just as it does during monitoring. " +
                "Stop playback and run the check again, or play the test sound from another device.",
            isError = true
        )
    }
    if (report.verdict != SetupVerdict.NO_SIGNAL) {
        report.criteria.forEach { CriterionRow(it) }
    }
}

@Composable
private fun CriterionRow(result: CriterionResult) {
    val status = when (result.status) {
        CriterionStatus.PASS -> "Pass"
        CriterionStatus.FAIL -> "Fail"
        CriterionStatus.NOT_TESTED -> "Not tested"
        CriterionStatus.DISABLED -> "Off"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_check_row_${result.criterion.name}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = criterionName(result.criterion),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = criterionDetail(result),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = status,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            softWrap = false,
            color = when (result.status) {
                CriterionStatus.PASS -> MaterialTheme.colorScheme.primary
                CriterionStatus.FAIL -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

private fun criterionName(criterion: SetupCriterion): String = when (criterion) {
    SetupCriterion.VOLUME -> "Sound Volume"
    SetupCriterion.ZCR -> "Zero-Crossing Rate"
    SetupCriterion.BAND_ENERGY -> "Snoring Band Energy"
    SetupCriterion.LOW_FREQ_RATIO -> "Low-Frequency Ratio"
    SetupCriterion.DURATION -> "Minimum Duration"
}

private fun criterionDetail(result: CriterionResult): String {
    val (symbol, pattern, scale) = when (result.criterion) {
        SetupCriterion.VOLUME -> Triple("≥", "%.1f dB", 1f)
        SetupCriterion.ZCR -> Triple("≤", "%.3f", 1f)
        SetupCriterion.BAND_ENERGY -> Triple("≥", "%.4f", 1f)
        SetupCriterion.LOW_FREQ_RATIO -> Triple("≥", "%.0f%%", 100f)
        SetupCriterion.DURATION -> Triple("≥", "%.1f s", 1f)
    }
    val required = "needs $symbol ${format(result.threshold * scale, pattern)}"
    return result.measured?.let { "${format(it * scale, pattern)}, $required" } ?: required
}

private fun verdictText(verdict: SetupVerdict): String = when (verdict) {
    SetupVerdict.DETECTED -> "Your test sound would be logged as a snore."
    SetupVerdict.NOT_DETECTED ->
        "Your test sound would not be logged as a snore. Any Fail below shows which setting to look at."
    SetupVerdict.NO_TEST_SOUND ->
        "No test sound was heard above the background noise. Try again louder or closer to the microphone."
    SetupVerdict.BACKGROUND_DETECTED ->
        "Background noise alone would be logged as snoring. Tighten the detection settings below."
    SetupVerdict.NO_SIGNAL ->
        "The microphone only delivered silence. Check the selected input in Settings and that microphone " +
            "access isn't turned off in the system privacy settings."
}

private fun problemText(problem: SetupCheckProblem): String = when (problem) {
    SetupCheckProblem.PERMISSION_MISSING -> "The setup check needs microphone access."
    SetupCheckProblem.MONITORING_ACTIVE -> "The setup check can't run while monitoring is active."
    SetupCheckProblem.MIC_UNAVAILABLE -> "The microphone isn't available. It may be in use by another app."
}

private fun format(value: Float, pattern: String): String = String.format(Locale.US, pattern, value)
