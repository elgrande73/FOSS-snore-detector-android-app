package com.aistudio.snoredetector.afkwd.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aistudio.snoredetector.afkwd.service.MonitoringSessionStore
import java.util.Locale

private const val VENDOR_GUIDE_ROOT = "https://dontkillmyapp.com/"

// Phone makers with a page of their own on dontkillmyapp.com.
private val VENDORS_WITH_GUIDE = setOf(
    "asus", "google", "huawei", "lenovo", "meizu", "motorola", "nokia",
    "oneplus", "oppo", "realme", "samsung", "sony", "vivo", "xiaomi"
)

internal fun vendorGuideUrl(manufacturer: String): String {
    val vendor = manufacturer.trim().lowercase(Locale.ROOT)
    return if (vendor in VENDORS_WITH_GUIDE) VENDOR_GUIDE_ROOT + vendor else VENDOR_GUIDE_ROOT
}

@Stable
internal class MonitoringReliabilityState(private val context: Context) {
    private val store = MonitoringSessionStore(context)

    var notice by mutableStateOf(store.pendingNotice)
        private set
    var isBatteryOptimizationDisabled by mutableStateOf(readBatteryOptimizationDisabled())
        private set
    private var isBatteryHintDismissed by mutableStateOf(store.isBatteryHintDismissed)

    val showBatteryHint: Boolean get() = !isBatteryOptimizationDisabled && !isBatteryHintDismissed
    val hasBanner: Boolean get() = notice != null || showBatteryHint

    /** The user changes the setting in another screen, so this has to be re-read on return. */
    fun refresh() {
        notice = store.pendingNotice
        isBatteryOptimizationDisabled = readBatteryOptimizationDisabled()
    }

    fun dismissNotice() {
        store.pendingNotice = null
        notice = null
    }

    fun dismissBatteryHint() {
        store.isBatteryHintDismissed = true
        isBatteryHintDismissed = true
    }

    private fun readBatteryOptimizationDisabled(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}

@Composable
internal fun rememberMonitoringReliabilityState(): MonitoringReliabilityState {
    val context = LocalContext.current
    val state = remember(context) { MonitoringReliabilityState(context.applicationContext) }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}

/**
 * Links to the system list rather than asking directly: the direct dialog needs
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which app stores restrict. Not every phone ships that list.
 */
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        openOrToast(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        )
    }
}

private fun openVendorGuide(context: Context) {
    openOrToast(context, Intent(Intent.ACTION_VIEW, vendorGuideUrl(Build.MANUFACTURER).toUri()))
}

private fun openOrToast(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to open this", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatteryActions(trailing: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { openBatterySettings(context) }) { Text("Battery settings") }
        TextButton(onClick = { openVendorGuide(context) }) { Text("Tips for this phone") }
        trailing()
    }
}

@Composable
private fun ReliabilityCardBody(title: String, body: String, color: Color, actions: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)) {
        Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = body, fontSize = 12.sp, color = color.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
        actions()
    }
}

/** Dashboard warnings. Only compose this when [MonitoringReliabilityState.hasBanner] is true. */
@Composable
internal fun MonitoringReliabilityBanner(state: MonitoringReliabilityState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("monitoring_reliability_banner")) {
        state.notice?.let { notice ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ReliabilityCardBody(
                    title = "Monitoring was interrupted",
                    body = "$notice Snoring during that time was not recorded. Details are in History under System Errors.",
                    color = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    BatteryActions { TextButton(onClick = state::dismissNotice) { Text("Dismiss") } }
                }
            }
        }
        if (state.showBatteryHint) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ReliabilityCardBody(
                    title = "Keep monitoring running all night",
                    body = "Battery optimization can stop monitoring while you sleep, and the night would then look quiet. In Battery settings, show all apps, choose Snore Detector and select \"Don't optimize\".",
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    BatteryActions { TextButton(onClick = state::dismissBatteryHint) { Text("Not now") } }
                }
            }
        }
    }
}

/** Settings entry that stays available after the dashboard hint has been dismissed. */
@Composable
internal fun MonitoringReliabilityCard() {
    val state = rememberMonitoringReliabilityState()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("monitoring_reliability_card")
    ) {
        ReliabilityCardBody(
            title = "Overnight Reliability",
            body = if (state.isBatteryOptimizationDisabled) {
                "Battery optimization is off for Snore Detector. Some phones have extra background limits of their own."
            } else {
                "Battery optimization is on for Snore Detector and can stop monitoring while you sleep. In Battery settings, show all apps, choose Snore Detector and select \"Don't optimize\"."
            },
            color = MaterialTheme.colorScheme.onSurface
        ) {
            BatteryActions()
        }
    }
}
