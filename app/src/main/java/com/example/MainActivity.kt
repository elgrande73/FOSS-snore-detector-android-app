package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.SnoreEvent
import com.example.dsp.AmplitudePoint
import com.example.service.SnoreDetectionService
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SnoreViewModel
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
            MyApplicationTheme {
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

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasMicPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
                    }
                }

                // Auto request on launch
                LaunchedEffect(Unit) {
                    val list = mutableListOf<String>()
                    if (!hasMicPermission) list.add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
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
                                        imageVector = Icons.Default.Info,
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
                                        imageVector = Icons.Default.List,
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
    val rawAnalysisResult by SnoreDetectionService.liveAnalysis.collectAsState()
    val timelineData by viewModel.timelineDisplayState.collectAsState()
    val errorMsg by SnoreDetectionService.serviceError.collectAsState()
    
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
    val currentDb = rawAnalysisResult?.db ?: 30.0f

    var sessionDurationStr by remember { mutableStateOf("00h 00m 00s") }
    LaunchedEffect(isRunning, sessionStartTime) {
        if (isRunning && sessionStartTime > 0L) {
            while (true) {
                val elapsedMs = System.currentTimeMillis() - sessionStartTime
                val elapsedSec = elapsedMs / 1000
                val totalMin = elapsedSec / 60
                val h = totalMin / 60
                val m = totalMin % 60
                val s = elapsedSec % 60
                sessionDurationStr = String.format("%02dh %02dm %02ds", h, m, s)
                kotlinx.coroutines.delay(1000)
            }
        } else {
            sessionDurationStr = "00h 00m 00s"
        }
    }

    // Flashing Animation for Snoring indicator
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
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SnoreLog",
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
                                if (isCurrentlySnoring) Color(0x33B3261E) else MaterialTheme.colorScheme.primaryContainer
                            } else Color(0x3349454F)
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
                                if (isCurrentlySnoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            } else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Circular dynamic meter
        item {
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
                        // Progress Arc
                        val arcPrimaryColor = MaterialTheme.colorScheme.primary
                        val arcErrorColor = MaterialTheme.colorScheme.error
                        Canvas(modifier = Modifier.size(190.dp)) {
                            // Track Arc background
                            drawArc(
                                color = Color(0xFFE8DEF8),
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

        // Running session stats cards
        if (isRunning) {
            item {
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
        }

        // Real-time parameters inspection list (visible when running)
        if (isRunning && rawAnalysisResult != null) {
            item {
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
                            value = "${String.format("%.1f", rawAnalysisResult!!.db)} dB",
                            thresholdStr = ">= ${String.format("%.1f", rmsDbThreshold)} dB",
                            isMet = rawAnalysisResult!!.rmsThresholdMet,
                            isActive = useRms
                        )

                        // ZCR Row
                        MetricRow(
                            label = "Zero-Crossing Rate",
                            value = String.format("%.3f", rawAnalysisResult!!.zcr),
                            thresholdStr = "<= ${String.format("%.2f", zcrThreshold)}",
                            isMet = rawAnalysisResult!!.zcrThresholdMet,
                            isActive = useZcr
                        )

                        // Band-Energy
                        MetricRow(
                            label = "Snore Band Energy (100-1k Hz)",
                            value = String.format("%.4f", rawAnalysisResult!!.bandEnergy),
                            thresholdStr = ">= ${String.format("%.3f", bandEnergyThreshold)}",
                            isMet = rawAnalysisResult!!.bandThresholdMet,
                            isActive = useBandEnergy
                        )

                        // Low-Frequency ratio
                        MetricRow(
                            label = "Low Frequency Ratio (<500 Hz)",
                            value = "${(rawAnalysisResult!!.lowFreqEnergyRatio * 100).toInt()}%",
                            thresholdStr = ">= ${(lowFreqRatioThreshold * 100).toInt()}%",
                            isMet = rawAnalysisResult!!.lowFreqThresholdMet,
                            isActive = useLowFreqRatio
                        )
                    }
                }
            }
        }

        // Flashing Alert banner when snoring detected
        if (isCurrentlySnoring) {
            item {
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
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // Master Control Start/Stop button
        item {
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
        item {
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
    val playingEventId by viewModel.playingEventId.collectAsState()
    val context = LocalContext.current
    
    val dateSdf = remember { SimpleDateFormat("EEEE, MMM dd — hh:mm:ss a", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tracked Episodes",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            if (events.isNotEmpty()) {
                Row {
                    // Export CSV
                    IconButton(
                        onClick = {
                            val intent = viewModel.getCsvShareIntent(events)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Export generation failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("export_csv_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Export CSV", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Clear Database
                    IconButton(
                        onClick = { viewModel.clearAllHistory() },
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear logs", tint = MaterialTheme.colorScheme.error)
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
                        imageVector = Icons.Default.List,
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
                        text = "Turn on the acoustics monitor on dashboard. Valid continuous snoring incidents exceeding 1.0s are registered here.",
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
                    SnoreEventCard(
                        event = item,
                        isPlaying = playingEventId == item.id,
                        onPlayClick = { viewModel.togglePlayback(item) },
                        onDeleteClick = { viewModel.deleteEvent(item) },
                        dateFormatter = dateSdf
                    )
                }
            }
        }
    }
}

@Composable
fun SnoreEventCard(
    event: SnoreEvent,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    dateFormatter: SimpleDateFormat
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snore_event_card_${event.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Time and delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateFormatter.format(Date(event.timestamp)),
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
                                text = "Duration: ${String.format("%.1f", event.durationSeconds)}s",
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
                                text = "Peak: ${event.maxDb.toInt()} dB",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Row {
                    // Microphonic snippet player button if audio cached
                    if (event.audioFilePath != null) {
                        IconButton(
                            onClick = onPlayClick,
                            modifier = Modifier.size(28.dp).testTag("play_button_${event.id}")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Warning else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Stop" else "Play Recording",
                                tint = if (isPlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(28.dp).testTag("delete_button_${event.id}")
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

            Spacer(modifier = Modifier.height(12.dp))

            // Sub details metrics breakdown grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AcousticLabel(
                    title = "Max. RMS",
                    value = String.format("%.4f", event.maxRms)
                )
                AcousticLabel(
                    title = "Mean ZCR",
                    value = String.format("%.3f", event.meanZcr)
                )
                AcousticLabel(
                    title = "Band Energy",
                    value = String.format("%.4f", event.meanBandEnergy)
                )
                AcousticLabel(
                    title = "Low Freq.",
                    value = "${(event.meanLowFreqRatio * 100).toInt()}%"
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

    val saveAudioClips by viewModel.saveAudioClips.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Acoustics Configuration",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Configure and select the active real-time algorithmic snoring validation metrics. Detections operate on logical matching configurations.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Clip recorder toggle
        item {
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
                        Text(
                            text = "Save Audio Recordings (.WAV)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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

        // Method 1: RMS dB Volume
        item {
            ConfigureMethodCard(
                title = "1. RMS Sound Amplitude (dB)",
                description = "Validates the incoming signal strength against standard bedroom physical acoustics dB SPL. Quiet sleep is typically below 45 dB.",
                isActive = useRms,
                onActiveChange = { viewModel.updateUseRms(it) },
                value = rmsDbThreshold,
                onValueChange = { viewModel.updateRmsDbThreshold(it) },
                valueRange = 40.0f..85.0f,
                labelFormatter = { "${it.toInt()} dB" },
                testTag = "rms_method"
            )
        }

        // Method 2: Zero crossing rate
        item {
            ConfigureMethodCard(
                title = "2. Zero-Crossing Rate (ZCR)",
                description = "Measures high-frequency vs. low-frequency rumbles. Low-pitch breathing snoring logs low crossing frequencies (typically ZCR <= 0.15).",
                isActive = useZcr,
                onActiveChange = { viewModel.updateUseZcr(it) },
                value = zcrThreshold,
                onValueChange = { viewModel.updateZcrThreshold(it) },
                valueRange = 0.05f..0.35f,
                labelFormatter = { String.format("%.3f ZCR", it) },
                testTag = "zcr_method"
            )
        }

        // Method 3: Core Snoring Band frequency energy
        item {
            ConfigureMethodCard(
                title = "3. Snoring Core Band Energy (FFT)",
                description = "Extracts energy in specific snoring audio spectrum bands (100 Hz to 1000 Hz) using the Cooley-Tukey FFT. Threshold prevents background flat ambient hiss.",
                isActive = useBandEnergy,
                onActiveChange = { viewModel.updateUseBandEnergy(it) },
                value = bandEnergyThreshold,
                onValueChange = { viewModel.updateBandEnergyThreshold(it) },
                valueRange = 0.005f..0.04f,
                labelFormatter = { String.format("%.4f power", it) },
                testTag = "band_method"
            )
        }

        // Method 4: Low-Frequency Energy ratio
        item {
            ConfigureMethodCard(
                title = "4. Low-Frequency Energy Ratio",
                description = "Evaluates spectral concentrate below 500 Hz. Deep guttural airway snoring consists of highly concentrated low-pitch vibrations.",
                isActive = useLowFreqRatio,
                onActiveChange = { viewModel.updateUseLowFreqRatio(it) },
                value = lowFreqRatioThreshold,
                onValueChange = { viewModel.updateLowFreqRatioThreshold(it) },
                valueRange = 0.40f..0.90f,
                labelFormatter = { "${(it * 100).toInt()}% ratio" },
                testTag = "low_freq_method"
            )
        }
    }
}

@Composable
fun ConfigureMethodCard(
    title: String,
    description: String,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    labelFormatter: (Float) -> String,
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
            
            Text(
                text = description,
                fontSize = 11.sp,
                color = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF888888),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            if (isActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Threshold: ${labelFormatter(value)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(115.dp)
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
