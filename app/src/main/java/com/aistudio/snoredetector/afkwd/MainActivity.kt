package com.aistudio.snoredetector.afkwd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.aistudio.snoredetector.afkwd.data.ErrorLog
import com.aistudio.snoredetector.afkwd.data.ErrorLogger
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice
import com.aistudio.snoredetector.afkwd.audio.AudioInputManager
import com.aistudio.snoredetector.afkwd.data.AudioExportManager
import com.aistudio.snoredetector.afkwd.data.ExportSummary
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.dsp.AmplitudePoint
import androidx.core.content.ContextCompat
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.ui.GuideTab
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: SnoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val dynamicColor by viewModel.dynamicColor.collectAsState()

            MyApplicationTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor
            ) {
                var selectedTab by remember { mutableStateOf(0) }
                val context = LocalContext.current
                
                // Permission Request Logic
                var hasMicPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                var hasNotificationPermission by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        } else true
                    )
                }

                var hasBluetoothPermission by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        } else true
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasMicPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hasBluetoothPermission = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
                    }
                    viewModel.refreshAvailableInputDevices()
                }

                // Auto request on launch
                LaunchedEffect(Unit) {
                    val list = mutableListOf<String>()
                    if (!hasMicPermission) list.add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                        list.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (list.isNotEmpty()) {
                        permissionLauncher.launch(list.toTypedArray())
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            modifier = Modifier.testTag("app_bottom_bar")
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Dashboard", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = "Dashboard"
                                    )
                                }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("History", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = "History Log"
                                    )
                                }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                label = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Configurations"
                                    )
                                }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                label = { Text("Guide", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "User Guide"
                                    )
                                },
                                modifier = Modifier.testTag("nav_guide_tab")
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(innerPadding)
                    ) {
                        if (!hasMicPermission) {
                            PermissionExplanationScreen {
                                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }
                        } else {
                            when (selectedTab) {
                                0 -> DashboardTab(viewModel)
                                1 -> HistoryTab(viewModel)
                                2 -> SettingsTab(viewModel)
                                3 -> GuideTab()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionExplanationScreen(onTriggerRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Mic access alert",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Microphone Permission Required",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "To detect snoring sounds and compute live acoustic decibel rates, this app must access the device's microphone local streams.\n\nAll analytical processing is conducted 100% offline, locally, and privately. Zero tracking is stored.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onTriggerRequest,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("request_permission_button")
        ) {
            Text("Grant Microphone Access", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DashboardTab(viewModel: SnoreViewModel) {
    val isRunning by SnoreDetectionService.isServiceRunning.collectAsState()
    val isCurrentlySnoring by SnoreDetectionService.isCurrentlySnoring.collectAsState()
    val timelineData by viewModel.timelineDisplayState.collectAsState()
    val errorMsg by SnoreDetectionService.serviceError.collectAsState()
    val configuredInputDeviceName by SnoreDetectionService.configuredInputDeviceName.collectAsState()
    val activeInputDeviceName by SnoreDetectionService.activeInputDeviceName.collectAsState()
    val selectedAudioInputName by viewModel.selectedAudioInputName.collectAsState()
    
    val sessionStartTime by SnoreDetectionService.sessionStartTime.collectAsState()
    val sessionEventCount by SnoreDetectionService.sessionEventCount.collectAsState()
    
    val rmsDbThreshold by viewModel.rmsDbThreshold.collectAsState()
    val zcrThreshold by viewModel.zcrThreshold.collectAsState()
    val bandEnergyThreshold by viewModel.bandEnergyThreshold.collectAsState()
    val lowFreqRatioThreshold by viewModel.lowFreqRatioThreshold.collectAsState()
    val useRms by viewModel.useRms.collectAsState()
    val useZcr by viewModel.useZcr.collectAsState()
    val useBandEnergy by viewModel.useBandEnergy.collectAsState()
    val useLowFreqRatio by viewModel.useLowFreqRatio.collectAsState()
    
    val context = LocalContext.current

    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title Header
        item(key = "dashboard_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Snore Detector",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontFamily = FontFamily.SansSerif
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "FOSS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "FOSS Real-time Acoustic Analysis",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Status Indicator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(
                            if (isRunning) {
                                if (isCurrentlySnoring) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                            } else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) {
                                        if (isCurrentlySnoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                     } else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRunning) {
                                if (isCurrentlySnoring) "SNORE!" else "MONITORING"
                            } else "OFF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) {
                                if (isCurrentlySnoring) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                            } else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Circular dynamic meter (isolated so 50ms audio ticks only recompose the meter canvas)
        item(key = "dashboard_meter") {
            DecibelMeterSection(isRunning = isRunning, isCurrentlySnoring = isCurrentlySnoring)
        }

        // Running session stats cards (isolated so 1s timer ticks only recompose this card)
        if (isRunning) {
            item(key = "dashboard_session_stats") {
                ActiveSessionStatsSection(
                    sessionStartTime = sessionStartTime,
                    sessionEventCount = sessionEventCount
                )
            }
        }

        // Real-time parameters inspection list (visible when running, isolated live spectrum observer)
        if (isRunning) {
            item(key = "dashboard_diagnostics") {
                LiveSpectrumDiagnosticsSection(
                    rmsDbThreshold = rmsDbThreshold,
                    zcrThreshold = zcrThreshold,
                    bandEnergyThreshold = bandEnergyThreshold,
                    lowFreqRatioThreshold = lowFreqRatioThreshold,
                    useRms = useRms,
                    useZcr = useZcr,
                    useBandEnergy = useBandEnergy,
                    useLowFreqRatio = useLowFreqRatio
                )
            }
        }

        // Flashing Alert banner when snoring detected (isolated animation loop)
        if (isCurrentlySnoring) {
            item(key = "dashboard_snore_alert") {
                ActiveSnoreAlertBanner()
            }
        }

        // Master Control Start/Stop button
        item(key = "dashboard_control_button") {
            if (isRunning) {
                Button(
                    onClick = { viewModel.stopServiceDetection() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("dashboard_stop_button"),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("STOP SESSION", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { viewModel.startServiceDetection() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("dashboard_start_button"),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("START BEDROOM SNORE MONITOR", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Amplitude Graph of sessions
        item(key = "dashboard_amplitude_graph") {
            Column {
                Text(
                    text = if (isRunning) "Active Session Timeline Profile" else "Last Completed Session Profile",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                AmplitudeGraph(points = timelineData)
                Text(
                    text = "X-axis: Timeline progress | Y-axis: relative decibel peaks. Red regions indicate intervals satisfying current snoring criteria.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Audio Input Routing Information (at very bottom below Session Profile)
        item(key = "dashboard_audio_input_routing_card") {
            val configuredDisplay = if (isRunning) {
                configuredInputDeviceName
            } else {
                if (selectedAudioInputName.isNotBlank()) selectedAudioInputName else "Phone microphone"
            }
            val activeDisplay = if (isRunning) {
                activeInputDeviceName
            } else {
                configuredDisplay
            }
            val isFallback = isRunning && !configuredDisplay.equals(activeDisplay, ignoreCase = true)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_audio_input_routing_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Audio Input Routing",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        !isRunning -> MaterialTheme.colorScheme.surface
                                        isFallback -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.primaryContainer
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    !isRunning -> "Ready"
                                    isFallback -> "Fallback Active"
                                    else -> "Direct Route Active"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    !isRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                                    isFallback -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Configured input:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = configuredDisplay,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active input:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = activeDisplay,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DecibelMeterSection(isRunning: Boolean, isCurrentlySnoring: Boolean) {
    val rawAnalysisResult by SnoreDetectionService.liveAnalysis.collectAsState()
    val currentDb = rawAnalysisResult?.db ?: 30.0f

    val arcTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val arcPrimaryColor = MaterialTheme.colorScheme.primary
    val arcErrorColor = MaterialTheme.colorScheme.error

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.size(240.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Canvas(modifier = Modifier.size(190.dp)) {
                    // Track Arc background
                    drawArc(
                        color = arcTrackColor,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    )
                    // Dynamic active sweep mapping from 30dB (silent) to 110dB (loud)
                    val normalizedRatio = ((currentDb - 30f).coerceAtLeast(0f) / 80f)
                    val sweepAngle = (normalizedRatio * 270f).coerceIn(0f, 270f)
                    
                    drawArc(
                        color = if (isCurrentlySnoring) arcErrorColor else arcPrimaryColor,
                        startAngle = 135f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Center decibel label text
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${currentDb.toInt()}",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isCurrentlySnoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "DECIBELS (dB)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveSessionStatsSection(
    sessionStartTime: Long,
    sessionEventCount: Int
) {
    var sessionDurationStr by remember { mutableStateOf("00h 00m 00s") }
    LaunchedEffect(sessionStartTime) {
        if (sessionStartTime > 0L) {
            while (true) {
                val elapsedMs = System.currentTimeMillis() - sessionStartTime
                val elapsedSec = elapsedMs / 1000
                val totalMin = elapsedSec / 60
                val h = totalMin / 60
                val m = totalMin % 60
                val s = elapsedSec % 60
                sessionDurationStr = String.format(Locale.US, "%02dh %02dm %02ds", h, m, s)
                kotlinx.coroutines.delay(1000)
            }
        } else {
            sessionDurationStr = "00h 00m 00s"
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active duration
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "ACTIVE SESSION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
                Text(
                    text = sessionDurationStr,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Snores logged
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "SNORES LOGGED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (sessionEventCount == 1) "1 incident" else "$sessionEventCount incidents",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun LiveSpectrumDiagnosticsSection(
    rmsDbThreshold: Float,
    zcrThreshold: Float,
    bandEnergyThreshold: Float,
    lowFreqRatioThreshold: Float,
    useRms: Boolean,
    useZcr: Boolean,
    useBandEnergy: Boolean,
    useLowFreqRatio: Boolean
) {
    val rawAnalysisResult by SnoreDetectionService.liveAnalysis.collectAsState()
    val analysis = rawAnalysisResult ?: return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Live DSP Spectrum Diagnostics:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp
            )
            
            // RMS / dB Row
            MetricRow(
                label = "Decibels Level (RMS)",
                value = "${String.format(Locale.US, "%.1f", analysis.db)} dB",
                thresholdStr = ">= ${String.format(Locale.US, "%.1f", rmsDbThreshold)} dB",
                isMet = analysis.rmsThresholdMet,
                isActive = useRms
            )

            // ZCR Row
            MetricRow(
                label = "Zero-Crossing Rate",
                value = String.format(Locale.US, "%.3f", analysis.zcr),
                thresholdStr = "<= ${String.format(Locale.US, "%.2f", zcrThreshold)}",
                isMet = analysis.zcrThresholdMet,
                isActive = useZcr
            )

            // Band-Energy
            MetricRow(
                label = "Snore Band Energy (100-1k Hz)",
                value = String.format(Locale.US, "%.4f", analysis.bandEnergy),
                thresholdStr = ">= ${String.format(Locale.US, "%.3f", bandEnergyThreshold)}",
                isMet = analysis.bandThresholdMet,
                isActive = useBandEnergy
            )

            // Low-Frequency ratio
            MetricRow(
                label = "Low Frequency Ratio (<500 Hz)",
                value = "${(analysis.lowFreqEnergyRatio * 100).toInt()}%",
                thresholdStr = ">= ${(lowFreqRatioThreshold * 100).toInt()}%",
                isMet = analysis.lowFreqThresholdMet,
                isActive = useLowFreqRatio
            )
        }
    }
}

@Composable
fun ActiveSnoreAlertBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(12.dp)
            .alpha(alphaAnim),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⚠️ ACTIVE SNORE PATTERN DETECTED & SAVING...",
            color = MaterialTheme.colorScheme.onError,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun MetricRow(
    label: String,
    value: String,
    thresholdStr: String,
    isMet: Boolean,
    isActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = if (isActive) "Threshold: $thresholdStr" else "Method Disabled",
                fontSize = 11.sp,
                color = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF888888)
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = if (!isActive) MaterialTheme.colorScheme.outline else if (isMet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 6.dp)
            )
            
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (!isActive) MaterialTheme.colorScheme.outline
                        else if (isMet) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    Icon(
                        imageVector = if (isMet) Icons.Default.Warning else Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun AmplitudeGraph(points: List<AmplitudePoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No metrics logged in this session.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(28.dp))
            .padding(14.dp)
    ) {
        val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        val primaryColor = MaterialTheme.colorScheme.primary
        val errorColor = MaterialTheme.colorScheme.error

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val minDb = 40f
            val maxDb = (points.maxOfOrNull { it.dbValue } ?: 90f).coerceAtLeast(80f)
            val dbRange = (maxDb - minDb).coerceAtLeast(15f)

            val pointCount = points.size
            if (pointCount < 2) {
                drawCircle(color = primaryColor, radius = 6f, center = Offset(width / 2, height / 2))
                return@Canvas
            }

            // Draw grid baselines
            val gridLines = 4
            for (i in 0..gridLines) {
                val yGrid = height * i / gridLines
                drawLine(
                    color = outlineColor,
                    start = Offset(0f, yGrid),
                    end = Offset(width, yGrid),
                    strokeWidth = 1f
                )
            }

            // Render high density vertical bars
            val spacingWidth = width / pointCount
            val barWidth = spacingWidth.coerceAtLeast(1.5f)

            for (idx in 0 until pointCount) {
                val pt = points[idx]
                val x = idx.toFloat() / (pointCount - 1) * width
                val yRatio = ((pt.dbValue - minDb) / dbRange).coerceIn(0f, 1f)
                val y = height - (yRatio * height)

                // Snoring detections highlighted in Red, quiet breathing zones in Soft Primary tint
                val barColor = if (pt.isSnore) errorColor else primaryColor.copy(alpha = 0.3f)
                
                drawLine(
                    color = barColor,
                    start = Offset(x, height),
                    end = Offset(x, y.coerceAtMost(height - 1f)),
                    strokeWidth = barWidth
                )
            }
        }
    }
}

@Composable
fun HistoryTab(viewModel: SnoreViewModel) {
    val events by viewModel.hLogs.collectAsState()
    val errorLogs by viewModel.errorLogs.collectAsState()
    val playingEventId by viewModel.playingEventId.collectAsState()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsState()
    val selectedEventIds by viewModel.selectedEventIds.collectAsState()
    val exportInProgress by viewModel.exportInProgress.collectAsState()
    val exportProgressText by viewModel.exportProgressText.collectAsState()
    val exportSummary by viewModel.exportSummary.collectAsState()
    val minDurationSeconds by viewModel.minDurationSeconds.collectAsState()

    val context = LocalContext.current
    val dateSdf = remember { SimpleDateFormat("EEEE, MMM dd — hh:mm:ss a", Locale.getDefault()) }

    var historyTabFilter by remember { mutableStateOf(0) } // 0 = Episodes, 1 = Error Logs
    var showExportDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearErrorsDialog by remember { mutableStateOf(false) }
    var pendingSingleExportEvent by remember { mutableStateOf<SnoreEvent?>(null) }
    var pendingExportErrorLog by remember { mutableStateOf<ErrorLog?>(null) }
    var selectedErrorLogForDetails by remember { mutableStateOf<ErrorLog?>(null) }

    // Active subset for export (either selected items or all items)
    val activeExportEvents = remember(events, isMultiSelectMode, selectedEventIds) {
        if (isMultiSelectMode && selectedEventIds.isNotEmpty()) {
            events.filter { selectedEventIds.contains(it.id) }
        } else {
            events
        }
    }

    // SAF Document Creation Launchers
    val singleAudioExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        if (uri != null && pendingSingleExportEvent != null) {
            val path = pendingSingleExportEvent?.audioFilePath
            if (path != null) {
                viewModel.exportSingleAudio(path, uri) { success ->
                    if (success) {
                        Toast.makeText(context, "Audio recording saved successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save audio recording", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        pendingSingleExportEvent = null
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsv(activeExportEvents, uri) { success ->
                if (success) {
                    Toast.makeText(context, "CSV logs saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save CSV file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val zipBundleExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportZipBundle(
                events = activeExportEvents,
                targetUri = uri,
                includeCsv = true,
                includeAudio = true,
                onComplete = { /* summary handled via StateFlow */ }
            )
        }
    }

    val zipAudioOnlyExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportZipBundle(
                events = activeExportEvents,
                targetUri = uri,
                includeCsv = false,
                includeAudio = true,
                onComplete = { /* summary handled via StateFlow */ }
            )
        }
    }

    // SAF Plain Text (.txt) Error Log Export Launcher
    val txtErrorLogExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && pendingExportErrorLog != null) {
            viewModel.exportErrorLogToUri(pendingExportErrorLog!!, uri) { success ->
                if (success) {
                    Toast.makeText(context, "Error log exported as plain text (.txt)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to export error log", Toast.LENGTH_SHORT).show()
                }
            }
        }
        pendingExportErrorLog = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Category switch chips (only displayed if error logs exist in database)
        if (errorLogs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = historyTabFilter == 0,
                    onClick = { historyTabFilter = 0 },
                    label = { Text("Episodes (${events.size})") },
                    modifier = Modifier.testTag("filter_episodes_tab")
                )
                FilterChip(
                    selected = historyTabFilter == 1,
                    onClick = { historyTabFilter = 1 },
                    label = { Text("⚠️ System Errors (${errorLogs.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.testTag("filter_errors_tab")
                )
            }
        }

        // Show Error Logs View if chosen
        if (historyTabFilter == 1 && errorLogs.isNotEmpty()) {
            // Error logs top action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Error Logs (${errorLogs.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                IconButton(
                    onClick = { showClearErrorsDialog = true },
                    modifier = Modifier.testTag("clear_errors_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear all error logs",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(errorLogs, key = { it.id }) { logItem ->
                    ErrorLogCard(
                        log = logItem,
                        onExportTxtClick = {
                            pendingExportErrorLog = logItem
                            val fileName = "SnoreDetector_Error_${logItem.id}_${logItem.timestamp}.txt"
                            txtErrorLogExportLauncher.launch(fileName)
                        },
                        onShareTxtClick = {
                            val shareIntent = viewModel.getShareErrorLogIntent(logItem)
                            context.startActivity(shareIntent)
                        },
                        onViewDetailsClick = {
                            selectedErrorLogForDetails = logItem
                        },
                        onDeleteClick = {
                            viewModel.deleteErrorLog(logItem)
                        },
                        dateFormatter = dateSdf
                    )
                }
            }
        } else {
            // Standard Snoring Episodes View
            // Top Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier.size(36.dp).testTag("exit_selection_button")
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Exit selection")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${selectedEventIds.size} Selected",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                if (selectedEventIds.size == events.size) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAllEvents(events)
                                }
                            },
                            modifier = Modifier.testTag("select_all_button")
                        ) {
                            Text(
                                text = if (selectedEventIds.size == events.size) "Deselect All" else "Select All",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Export Selected
                        IconButton(
                            onClick = { showExportDialog = true },
                            enabled = selectedEventIds.isNotEmpty(),
                            modifier = Modifier.testTag("export_selected_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export Selected",
                                tint = if (selectedEventIds.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Tracked Episodes (${events.size})",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    if (events.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Multi-selection mode toggle
                            IconButton(
                                onClick = { viewModel.toggleMultiSelectMode(true) },
                                modifier = Modifier.testTag("multi_select_toggle_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.List,
                                    contentDescription = "Select episodes",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Export Data & Audio Dialog
                            IconButton(
                                onClick = { showExportDialog = true },
                                modifier = Modifier.testTag("export_csv_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Export Data & Recordings",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Clear Database
                            IconButton(
                                onClick = { showClearHistoryDialog = true },
                                modifier = Modifier.testTag("clear_history_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear logs",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Empty",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "History logs are currently empty.",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Turn on the acoustics monitor on dashboard. Valid continuous snoring incidents exceeding ${String.format(Locale.US, "%.1f", minDurationSeconds)}s are registered here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(events, key = { it.id }) { item ->
                        val isSelected = selectedEventIds.contains(item.id)
                        SnoreEventCard(
                            event = item,
                            isPlaying = playingEventId == item.id,
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = isSelected,
                            onSelectionToggle = { viewModel.toggleEventSelection(item.id) },
                            onPlayClick = { viewModel.togglePlayback(item) },
                            onShareAudioClick = {
                                val shareIntent = viewModel.getShareSingleAudioIntent(item)
                                if (shareIntent != null) {
                                    context.startActivity(shareIntent)
                                } else {
                                    Toast.makeText(context, "Audio recording unavailable for this event", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onExportAudioClick = {
                                val path = item.audioFilePath
                                if (!path.isNullOrEmpty()) {
                                    pendingSingleExportEvent = item
                                    val defaultName = AudioExportManager.formatAudioFileName(item.timestamp, item.id)
                                    singleAudioExportLauncher.launch(defaultName)
                                } else {
                                    Toast.makeText(context, "Audio file is not available locally", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDeleteClick = { viewModel.deleteEvent(item) },
                            dateFormatter = dateSdf
                        )
                    }
                }
            }
        }
    }

    // Export Options Dialog
    if (showExportDialog) {
        val todayStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        val targetCount = activeExportEvents.size

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Text(
                    text = if (isMultiSelectMode) "Export $targetCount Selected Events" else "Export Recordings & Data",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose an export format for personal analysis or optional review with a healthcare professional:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Option 1: Complete Bundle (.zip)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showExportDialog = false
                                zipBundleExportLauncher.launch("SnoreDetector_Export_${todayStr}.zip")
                            }
                            .testTag("export_bundle_option"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "📦", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Complete Package (.zip)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Includes CSV logs + original WAV audio files in /audio folder",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // Option 2: Audio Only (.zip)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showExportDialog = false
                                zipAudioOnlyExportLauncher.launch("SnoreDetector_Audio_${todayStr}.zip")
                            }
                            .testTag("export_audio_only_option"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🎵", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Audio Recordings (.zip)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Export original unaltered WAV audio files only",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // Option 3: CSV Logs (.csv)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showExportDialog = false
                                csvExportLauncher.launch("SnoreDetector_Logs_${todayStr}.csv")
                            }
                            .testTag("export_csv_file_option"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "📄", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "CSV Session Log (.csv)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Tabular log with timestamps, dB, RMS, ZCR, and audio references",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // Option 4: Quick Share CSV
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showExportDialog = false
                                val shareIntent = viewModel.getCsvShareIntent(activeExportEvents)
                                if (shareIntent != null) {
                                    context.startActivity(shareIntent)
                                }
                            }
                            .testTag("quick_share_csv_option"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "📤", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Quick Share CSV Log",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Send CSV text directly via standard Android share sheet",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Privacy & Medical Notice
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "🔒 Privacy & Health Notice",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "• Audio files are stored locally on your device and never uploaded to any remote server.\n• Recordings are provided for personal information and optional discussion with a healthcare professional. Snore Detector is not a clinical diagnostic device.",
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Export Progress Dialog
    if (exportInProgress) {
        AlertDialog(
            onDismissRequest = { /* non-cancellable during write */ },
            title = {
                Text(text = "Exporting Data", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    Text(
                        text = exportProgressText ?: "Writing export files...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Export Summary Dialog
    if (exportSummary != null) {
        val summary = exportSummary!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissExportSummary() },
            title = {
                Text(
                    text = if (summary.success) "Export Completed" else "Export Issue",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (summary.success) {
                        Text(
                            text = "✅ Export finished successfully.",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "• ${summary.exportedAudioCount} audio recording(s) exported.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (summary.missingAudioCount > 0) {
                            Text(
                                text = "• ${summary.missingAudioCount} event(s) had no local audio clip available.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "❌ Export encountered an error:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = summary.errorMessage ?: "Unknown storage error.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissExportSummary() }) {
                    Text("Done")
                }
            }
        )
    }

    // Confirmation Dialog for Clearing History Logs
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Clear All History?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete all recorded snoring events and associated local audio recordings? This action cannot be undone.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearHistoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog for Clearing Error Logs
    if (showClearErrorsDialog) {
        AlertDialog(
            onDismissRequest = { showClearErrorsDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Clear All System Error Logs?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to clear all error diagnostic entries? This will not affect your tracked snoring sessions.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllErrorLogs()
                        showClearErrorsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Error Logs")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearErrorsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error Log Details Dialog
    if (selectedErrorLogForDetails != null) {
        val log = selectedErrorLogForDetails!!
        ErrorLogDetailsDialog(
            log = log,
            onDismiss = { selectedErrorLogForDetails = null },
            onExportTxt = {
                pendingExportErrorLog = log
                selectedErrorLogForDetails = null
                val fileName = "SnoreDetector_Error_${log.id}_${log.timestamp}.txt"
                txtErrorLogExportLauncher.launch(fileName)
            },
            onShareTxt = {
                val shareIntent = viewModel.getShareErrorLogIntent(log)
                context.startActivity(shareIntent)
            },
            dateFormatter = dateSdf
        )
    }
}

@Composable
fun ErrorLogCard(
    log: ErrorLog,
    onExportTxtClick: () -> Unit,
    onShareTxtClick: () -> Unit,
    onViewDetailsClick: () -> Unit,
    onDeleteClick: () -> Unit,
    dateFormatter: SimpleDateFormat
) {
    val formattedDate = remember(log.timestamp) { dateFormatter.format(Date(log.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_log_card_${log.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Error Type Badge + Timestamp + Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = log.errorType,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onError
                            )
                        }
                        if (log.component.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = log.component,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formattedDate,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Export to .txt
                    IconButton(
                        onClick = onExportTxtClick,
                        modifier = Modifier.size(32.dp).testTag("export_error_log_${log.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export Error Log (.txt)",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Share .txt
                    IconButton(
                        onClick = onShareTxtClick,
                        modifier = Modifier.size(32.dp).testTag("share_error_log_${log.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Share Error Log",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Delete error log
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(32.dp).testTag("delete_error_log_${log.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Error Log",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Error Message
            Text(
                text = log.message,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp
            )

            // Diagnostic summary
            if (log.diagnosticDetails.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val firstLine = log.diagnosticDetails.lines().firstOrNull { it.isNotBlank() } ?: ""
                Text(
                    text = firstLine,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action row to view full details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onViewDetailsClick,
                    modifier = Modifier.testTag("details_error_btn_${log.id}")
                ) {
                    Text(
                        text = "View Full Diagnostics & Trace",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorLogDetailsDialog(
    log: ErrorLog,
    onDismiss: () -> Unit,
    onExportTxt: () -> Unit,
    onShareTxt: () -> Unit,
    dateFormatter: SimpleDateFormat
) {
    val formattedDate = remember(log.timestamp) { dateFormatter.format(Date(log.timestamp)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Error Diagnostics #${log.id}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Type & Timestamp
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Error Type: ${log.errorType}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Timestamp: $formattedDate",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (log.component.isNotEmpty()) {
                            Text(
                                text = "Component: ${log.component}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Message
                Text(
                    text = "Message:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = log.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Diagnostics Details
                if (log.diagnosticDetails.isNotBlank()) {
                    Text(
                        text = "Diagnostic Details & Trace:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = log.diagnosticDetails,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShareTxt) {
                    Text("Share Text")
                }
                Button(onClick = onExportTxt) {
                    Text("Export .txt")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SnoreEventCard(
    event: SnoreEvent,
    isPlaying: Boolean,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectionToggle: () -> Unit = {},
    onPlayClick: () -> Unit,
    onShareAudioClick: () -> Unit = {},
    onExportAudioClick: () -> Unit = {},
    onDeleteClick: () -> Unit,
    dateFormatter: SimpleDateFormat
) {
    val hasAudio = !event.audioFilePath.isNullOrEmpty()
    val formattedDate = remember(event.timestamp) { dateFormatter.format(Date(event.timestamp)) }
    val durationText = remember(event.durationSeconds) { "Duration: ${String.format(Locale.US, "%.1f", event.durationSeconds)}s" }
    val peakDbText = remember(event.maxDb) { "Peak: ${event.maxDb.toInt()} dB" }
    val maxRmsText = remember(event.maxRms) { String.format(Locale.US, "%.4f", event.maxRms) }
    val meanZcrText = remember(event.meanZcr) { String.format(Locale.US, "%.3f", event.meanZcr) }
    val bandEnergyText = remember(event.meanBandEnergy) { String.format(Locale.US, "%.4f", event.meanBandEnergy) }
    val lowFreqRatioText = remember(event.meanLowFreqRatio) { "${(event.meanLowFreqRatio * 100).toInt()}%" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snore_event_card_${event.id}")
            .clickable(enabled = isMultiSelectMode) { onSelectionToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Selection Checkbox / Time and Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isMultiSelectMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onSelectionToggle() },
                            modifier = Modifier.size(32.dp).testTag("event_checkbox_${event.id}"),
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Column {
                        Text(
                            text = formattedDate,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = durationText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = peakDbText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Audio snippet player button
                    if (hasAudio) {
                        IconButton(
                            onClick = onPlayClick,
                            modifier = Modifier.size(32.dp).testTag("play_button_${event.id}")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Warning else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Stop Recording" else "Play Recording",
                                tint = if (isPlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Share single audio
                        IconButton(
                            onClick = onShareAudioClick,
                            modifier = Modifier.size(32.dp).testTag("share_audio_button_${event.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Audio Recording",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Direct save/export single audio
                        IconButton(
                            onClick = onExportAudioClick,
                            modifier = Modifier.size(32.dp).testTag("export_audio_button_${event.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save Audio File",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(32.dp).testTag("delete_button_${event.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Event",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub details metrics breakdown grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AcousticLabel(
                    title = "Max. RMS",
                    value = maxRmsText
                )
                AcousticLabel(
                    title = "Mean ZCR",
                    value = meanZcrText
                )
                AcousticLabel(
                    title = "Band Energy",
                    value = bandEnergyText
                )
                AcousticLabel(
                    title = "Low Freq.",
                    value = lowFreqRatioText
                )
            }
        }
    }
}

// Wrapper for custom borders to avoid complex XML imports
@Composable
fun BorderStroke(width: androidx.compose.ui.unit.Dp, color: Color) = 
    remember(width, color) { androidx.compose.foundation.BorderStroke(width, color) }

@Composable
fun AcousticLabel(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(
            text = value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
fun SettingsTab(viewModel: SnoreViewModel) {
    // Collect Configuration states
    val useRms by viewModel.useRms.collectAsState()
    val rmsDbThreshold by viewModel.rmsDbThreshold.collectAsState()

    val useZcr by viewModel.useZcr.collectAsState()
    val zcrThreshold by viewModel.zcrThreshold.collectAsState()

    val useBandEnergy by viewModel.useBandEnergy.collectAsState()
    val bandEnergyThreshold by viewModel.bandEnergyThreshold.collectAsState()

    val useLowFreqRatio by viewModel.useLowFreqRatio.collectAsState()
    val lowFreqRatioThreshold by viewModel.lowFreqRatioThreshold.collectAsState()

    val minDurationSeconds by viewModel.minDurationSeconds.collectAsState()
    val saveAudioClips by viewModel.saveAudioClips.collectAsState()
    val notifyOnSnore by viewModel.notifyOnSnore.collectAsState()

    val availableInputDevices by viewModel.availableInputDevices.collectAsState()
    val selectedAudioInputId by viewModel.selectedAudioInputId.collectAsState()
    val selectedAudioInputName by viewModel.selectedAudioInputName.collectAsState()
    val isRunning by SnoreDetectionService.isServiceRunning.collectAsState()
    val activeInputDeviceName by SnoreDetectionService.activeInputDeviceName.collectAsState()

    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()

    val context = LocalContext.current
    var showResetSettingsDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Material You / Material 3 Theme Appearance Card
        item(key = "settings_theme_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().testTag("theme_settings_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Appearance & Theming",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Default: System",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Customize the app theme mode and Material You dynamic color scheme.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    // Theme Mode Selector Chips
                    Text(
                        text = "Theme Mode",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val isSelected = themeMode == mode
                            val label = when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.updateThemeMode(mode) },
                                label = { Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                            )
                        }
                    }

                    // Dynamic Color Switch (Android 12+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dynamic Color (Material You)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Derive color palette from your Android wallpaper and system palette. (Default: On)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = dynamicColor,
                                onCheckedChange = { viewModel.updateDynamicColor(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.testTag("dynamic_color_switch")
                            )
                        }
                    }
                }
            }
        }

        // Audio Input Source Microphone Selection Card (Bluetooth Sleep Masks / External Microphones)
        item(key = "settings_audio_input_source_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().testTag("audio_input_source_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Audio Input Source (Microphone)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Target: ${if (selectedAudioInputName.isBlank()) "Default Phone Microphone" else selectedAudioInputName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.refreshAvailableInputDevices()
                                Toast.makeText(context, "Scanning audio devices...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("refresh_audio_inputs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Scan for connected audio devices",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = "Choose which microphone hardware captures audio for snoring detection. Supports Bluetooth sleep masks and wireless headsets without interrupting Bluetooth media playback (audiobooks, podcasts, music) or headphone buttons.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    // Active Service Routing status
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    text = "Live Active Input: $activeInputDeviceName",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Disconnected device fallback notice
                    val isSelectedDeviceDisconnected = selectedAudioInputId != -1 &&
                        availableInputDevices.none { it.id == selectedAudioInputId }
                    if (isSelectedDeviceDisconnected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Selected device \"$selectedAudioInputName\" is not currently connected. System is falling back to the default Phone Microphone automatically.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // List of available audio input devices
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    ) {
                        availableInputDevices.forEachIndexed { index, device ->
                            val isSelected = (selectedAudioInputId == device.id) ||
                                (selectedAudioInputId == -1 && device.id == -1)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateSelectedAudioInput(device)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("audio_input_device_${device.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.updateSelectedAudioInput(device) },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Column {
                                        Text(
                                            text = device.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = device.typeDescription,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                device.isBluetooth -> MaterialTheme.colorScheme.primaryContainer
                                                device.isUsb -> MaterialTheme.colorScheme.tertiaryContainer
                                                device.isWired -> MaterialTheme.colorScheme.secondaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = when {
                                            device.isBluetooth -> "Bluetooth"
                                            device.isUsb -> "USB"
                                            device.isWired -> "Wired"
                                            else -> "Built-in"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            device.isBluetooth -> MaterialTheme.colorScheme.onPrimaryContainer
                                            device.isUsb -> MaterialTheme.colorScheme.onTertiaryContainer
                                            device.isWired -> MaterialTheme.colorScheme.onSecondaryContainer
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            if (index < availableInputDevices.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }

                    if (availableInputDevices.size == 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 Tip: To use a Bluetooth sleep mask or headset mic, connect the device in Android Settings -> Bluetooth, then tap the Refresh button above.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "settings_dsp_header") {
            Column {
                Text(
                    text = "DSP Acoustics & Detection Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Configure the 4 digital signal processing (DSP) acoustic filters and the duration threshold. A snore is logged when all enabled filters match simultaneously (Logical AND) for the required duration.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // Method 1: RMS dB Volume
        item(key = "settings_rms_method") {
            ConfigureMethodCard(
                title = "1. Sound Volume (Decibels)",
                thresholdType = "Minimum threshold",
                comparisonSymbol = "≥",
                description = "Minimum threshold (Value ≥ Threshold). The incoming sound must reach or exceed this decibel level to be considered. Normal quiet sleep acoustics are typically below 45 dB.",
                isActive = useRms,
                onActiveChange = { viewModel.updateUseRms(it) },
                value = rmsDbThreshold,
                onValueChange = { viewModel.updateRmsDbThreshold(it) },
                valueRange = 40.0f..85.0f,
                labelFormatter = { "${it.toInt()} dB" },
                defaultValueLabel = "55 dB (Enabled)",
                testTag = "rms_method"
            )
        }

        // Method 2: Zero crossing rate
        item(key = "settings_zcr_method") {
            ConfigureMethodCard(
                title = "2. Zero-Crossing Rate (Pitch)",
                thresholdType = "Maximum threshold",
                comparisonSymbol = "≤",
                description = "Maximum threshold (Value ≤ Threshold). Snoring is a low-pitched rumble with few zero-crossings. Sounds with higher frequencies (speech, whispers, hisses) exceed this limit and are filtered out.",
                isActive = useZcr,
                onActiveChange = { viewModel.updateUseZcr(it) },
                value = zcrThreshold,
                onValueChange = { viewModel.updateZcrThreshold(it) },
                valueRange = 0.05f..0.35f,
                labelFormatter = { String.format("%.3f", it) },
                defaultValueLabel = "0.150 (Enabled)",
                testTag = "zcr_method"
            )
        }

        // Method 3: Core Snoring Band frequency energy
        item(key = "settings_band_method") {
            ConfigureMethodCard(
                title = "3. Snoring Frequency Band Energy",
                thresholdType = "Minimum threshold",
                comparisonSymbol = "≥",
                description = "Minimum threshold (Value ≥ Threshold). Measures energy concentrated in the 100 Hz – 1,000 Hz human snoring frequency band. Filters out flat background ambient noise.",
                isActive = useBandEnergy,
                onActiveChange = { viewModel.updateUseBandEnergy(it) },
                value = bandEnergyThreshold,
                onValueChange = { viewModel.updateBandEnergyThreshold(it) },
                valueRange = 0.005f..0.04f,
                labelFormatter = { String.format("%.4f", it) },
                defaultValueLabel = "0.0150 (Enabled)",
                testTag = "band_method"
            )
        }

        // Method 4: Low-Frequency Energy ratio
        item(key = "settings_low_freq_method") {
            ConfigureMethodCard(
                title = "4. Low-Frequency Energy Ratio",
                thresholdType = "Minimum threshold",
                comparisonSymbol = "≥",
                description = "Minimum threshold (Value ≥ Threshold). Percentage of total sound energy located below 500 Hz. Deep airway snoring vibrations are concentrated in this low-frequency zone.",
                isActive = useLowFreqRatio,
                onActiveChange = { viewModel.updateUseLowFreqRatio(it) },
                value = lowFreqRatioThreshold,
                onValueChange = { viewModel.updateLowFreqRatioThreshold(it) },
                valueRange = 0.40f..0.90f,
                labelFormatter = { "${(it * 100).toInt()}%" },
                defaultValueLabel = "65% (Enabled)",
                testTag = "low_freq_method"
            )
        }

        // Condition 5: Minimum Event Duration (Time Filter)
        item(key = "settings_duration_method") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.testTag("duration_method")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "5. Minimum Event Duration (Time Filter)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Time Condition",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Duration threshold (Continuous persistence)",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Default: 1.0s",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Minimum threshold (Duration ≥ Threshold). The sound must satisfy all active acoustic filters continuously for at least this duration to be logged as a snore incident. Filters out brief noises like coughs, bed creaks, or throat clearing.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Min: Duration ≥ ${String.format("%.1f", minDurationSeconds)} s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(140.dp)
                        )
                        Slider(
                            value = minDurationSeconds,
                            onValueChange = { viewModel.updateMinDurationSeconds(it) },
                            valueRange = 0.5f..3.0f,
                            steps = 24, // 0.1s increments between 0.5s and 3.0s
                            modifier = Modifier
                                .weight(1f)
                                .testTag("duration_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }

        // Simplified Formula Card directly below the DSP settings
        item(key = "settings_dsp_formula") {
            DspFormulaCard(
                useRms = useRms,
                rmsDbThreshold = rmsDbThreshold,
                useZcr = useZcr,
                zcrThreshold = zcrThreshold,
                useBandEnergy = useBandEnergy,
                bandEnergyThreshold = bandEnergyThreshold,
                useLowFreqRatio = useLowFreqRatio,
                lowFreqRatioThreshold = lowFreqRatioThreshold,
                minDurationSeconds = minDurationSeconds
            )
        }

        // WAV Audio Clip recorder toggle
        item(key = "settings_save_audio") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Save Audio Recordings (.WAV)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "(Default: Enabled)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Extract and persist short audio clippings of detected snoring incidents locally to play back in history logs.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = saveAudioClips,
                        onCheckedChange = { viewModel.updateSaveAudioClips(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.testTag("save_audio_clips_switch")
                    )
                }
            }
        }

        // Real-Time Snoring Event Notifications toggle (for smartwatches / Gadgetbridge / companion devices)
        item(key = "settings_notify_on_snore") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().testTag("notify_on_snore_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Real-Time Snore Notifications",
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
                        Text(
                            text = "Send an immediate high-priority Android notification when a snoring incident is confirmed. Ideal for vibrating a connected smartwatch via Gadgetbridge or companion notification sync apps.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = notifyOnSnore,
                        onCheckedChange = { viewModel.updateNotifyOnSnore(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.testTag("notify_on_snore_switch")
                    )
                }
            }
        }

        // Restore / Reset All Settings and Parameters Card
        item(key = "settings_reset_defaults_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reset_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reset Settings & Parameters",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Restore all 4 DSP acoustic filters, threshold parameters, minimum duration condition (1.0s), audio recording preference, microphone input source selection, and appearance settings back to their source-of-truth defaults.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showResetSettingsDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("restore_defaults_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Restore All Defaults",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // About & Version Information
        item(key = "settings_about_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_app_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "About Snore Detector",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Open Source (FOSS) • 100% On-Device Acoustic Signal Processing",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Confirmation Dialog for Resetting Settings & Parameters
    if (showResetSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showResetSettingsDialog = false },
            title = {
                Text(
                    text = "Restore Default Settings?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This will reset all detection parameters, acoustic thresholds, audio recording preferences, microphone input selection, and theming options to their original default values. Your recorded history logs will not be affected.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetAllSettingsToDefaults()
                        showResetSettingsDialog = false
                        Toast.makeText(context, "All settings restored to defaults", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Restore Defaults")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DspFormulaCard(
    useRms: Boolean,
    rmsDbThreshold: Float,
    useZcr: Boolean,
    zcrThreshold: Float,
    useBandEnergy: Boolean,
    bandEnergyThreshold: Float,
    useLowFreqRatio: Boolean,
    lowFreqRatioThreshold: Float,
    minDurationSeconds: Float
) {
    val activeMethodsCount = (if (useRms) 1 else 0) +
            (if (useZcr) 1 else 0) +
            (if (useBandEnergy) 1 else 0) +
            (if (useLowFreqRatio) 1 else 0)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dsp_formula_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Detection Formula",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$activeMethodsCount of 4 DSP Filters Active",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "How snore events are determined in the detection algorithm:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            // Formula Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SNORE DETECTED & LOGGED =",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )

                    if (activeMethodsCount == 0) {
                        Text(
                            text = "⚠️ All acoustic filters disabled (Snore detection inactive)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        var isFirstItem = true

                        // 1. RMS Condition
                        if (useRms) {
                            FormulaConditionRow(
                                isFirst = isFirstItem,
                                label = "Volume",
                                condition = "≥ ${rmsDbThreshold.toInt()} dB",
                                direction = "Minimum threshold"
                            )
                            isFirstItem = false
                        }

                        // 2. ZCR Condition
                        if (useZcr) {
                            FormulaConditionRow(
                                isFirst = isFirstItem,
                                label = "Zero-Crossing Rate",
                                condition = "≤ ${String.format("%.3f", zcrThreshold)}",
                                direction = "Maximum threshold"
                            )
                            isFirstItem = false
                        }

                        // 3. Band Energy Condition
                        if (useBandEnergy) {
                            FormulaConditionRow(
                                isFirst = isFirstItem,
                                label = "Snore Band Energy",
                                condition = "≥ ${String.format("%.4f", bandEnergyThreshold)}",
                                direction = "Minimum threshold"
                            )
                            isFirstItem = false
                        }

                        // 4. Low Freq Ratio Condition
                        if (useLowFreqRatio) {
                            FormulaConditionRow(
                                isFirst = isFirstItem,
                                label = "Low-Freq Ratio (<500Hz)",
                                condition = "≥ ${(lowFreqRatioThreshold * 100).toInt()}%",
                                direction = "Minimum threshold"
                            )
                            isFirstItem = false
                        }

                        // 5. Time Condition
                        FormulaConditionRow(
                            isFirst = false,
                            label = "Continuous Duration",
                            condition = "≥ ${String.format("%.1f", minDurationSeconds)}s",
                            direction = "Time condition"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Logical Rule: Every enabled acoustic feature must be satisfied simultaneously (AND). Once met, the snoring audio must persist continuously for at least the configured duration to be recorded.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun FormulaConditionRow(
    isFirst: Boolean,
    label: String,
    condition: String,
    direction: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isFirst) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "AND",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        } else {
            Spacer(modifier = Modifier.width(36.dp))
        }

        Text(
            text = "( $label ",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = condition,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = " )",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = direction,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ConfigureMethodCard(
    title: String,
    thresholdType: String,
    comparisonSymbol: String,
    description: String,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    labelFormatter: (Float) -> String,
    defaultValueLabel: String? = null,
    testTag: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag(testTag)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
                Switch(
                    checked = isActive,
                    onCheckedChange = onActiveChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            // Direction and type badge + Default value badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$thresholdType (Value $comparisonSymbol Threshold)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }

                if (defaultValueLabel != null) {
                    Text(
                        text = "Default: $defaultValueLabel",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = description,
                fontSize = 11.sp,
                color = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            if (isActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Min: $comparisonSymbol ${labelFormatter(value)}".let {
                            if (comparisonSymbol == "≤") "Max: $comparisonSymbol ${labelFormatter(value)}" else it
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(125.dp)
                    )
                    Slider(
                        value = value,
                        onValueChange = onValueChange,
                        valueRange = valueRange,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }
}

