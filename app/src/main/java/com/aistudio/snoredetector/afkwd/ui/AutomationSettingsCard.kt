package com.aistudio.snoredetector.afkwd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.snoredetector.afkwd.service.MonitorControl
import com.aistudio.snoredetector.afkwd.service.MonitorControlActivity

/**
 * Opt-in for [MonitorControlActivity], showing the values automation apps need once it is enabled.
 */
@Composable
fun AutomationSettingsCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().testTag("automation_control_card")
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Automation Control",
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
                        text = "Let automation apps such as Tasker or MacroDroid start and stop monitoring, using your saved settings. While this is on, any app on this phone can do the same.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("automation_control_switch")
                )
            }

            if (enabled) {
                Text(
                    text = "Send an intent with the target set to Activity:",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Column(
                        modifier = Modifier.testTag("automation_control_details"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AutomationValue("Package", LocalContext.current.packageName)
                        AutomationValue("Class", MonitorControlActivity::class.java.name)
                        AutomationValue("Start action", MonitorControl.ACTION_START_MONITORING)
                        AutomationValue("Stop action", MonitorControl.ACTION_STOP_MONITORING)
                    }
                }
                Text(
                    text = "The automation app needs the \"Display over other apps\" permission, otherwise Android blocks the request. Starting from a sleeping phone turns the screen on for a moment.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AutomationValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
