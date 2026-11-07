package com.aistudio.snoredetector.afkwd.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aistudio.snoredetector.afkwd.schedule.AutoStopMode
import com.aistudio.snoredetector.afkwd.schedule.ScheduleAlarms
import com.aistudio.snoredetector.afkwd.schedule.ScheduleSettings
import com.aistudio.snoredetector.afkwd.schedule.ScheduleTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Content of the "Schedule & Auto-Stop" section in Settings. */
@Composable
fun ScheduleSettingsCard(settings: ScheduleSettings, onChange: (ScheduleSettings) -> Unit) {
    // Permissions and the clock can change while the user is away from this screen.
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().testTag("schedule_settings_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ScheduleToggleRow(
                title = "Remind me to start monitoring",
                description = "Android doesn't let apps turn on the microphone from the background. " +
                    "At this time you get a notification instead: tap it and monitoring starts, " +
                    "using your current detection settings and microphone.",
                checked = settings.startReminderEnabled,
                onCheckedChange = { onChange(settings.copy(startReminderEnabled = it)) },
                testTag = "schedule_start_switch"
            )
            if (settings.startReminderEnabled) {
                ScheduleTimeRow("Reminder time", settings.startMinuteOfDay, "schedule_start_time") {
                    onChange(settings.copy(startMinuteOfDay = it))
                }
                ScheduleDayChips(settings, onChange)
                key(resumeCount) {
                    val context = LocalContext.current
                    ScheduleNote(nextReminderText(context, settings), "schedule_next_reminder")
                    ReminderPermissionWarnings(context)
                }
            }

            HorizontalDivider()

            ScheduleToggleRow(
                title = "Stop monitoring automatically",
                description = "Applies to every session, including sessions you start by hand.",
                checked = settings.autoStopEnabled,
                onCheckedChange = { onChange(settings.copy(autoStopEnabled = it)) },
                testTag = "schedule_stop_switch"
            )
            if (settings.autoStopEnabled) {
                ScheduleModeRow("At a set time", settings.stopMode == AutoStopMode.AT_TIME, "schedule_stop_at_time") {
                    onChange(settings.copy(stopMode = AutoStopMode.AT_TIME))
                }
                ScheduleModeRow("After a duration", settings.stopMode == AutoStopMode.AFTER_DURATION, "schedule_stop_after_duration") {
                    onChange(settings.copy(stopMode = AutoStopMode.AFTER_DURATION))
                }
                if (settings.stopMode == AutoStopMode.AT_TIME) {
                    ScheduleTimeRow("Stop time", settings.stopMinuteOfDay, "schedule_stop_time") {
                        onChange(settings.copy(stopMinuteOfDay = it))
                    }
                } else {
                    ScheduleDurationSlider(settings, onChange)
                }
            }

            HorizontalDivider()

            ScheduleNote(
                "Monitoring always stops after 12 hours at the latest, regardless of the settings above. " +
                    "This prevents unnecessary battery drain if you forget to stop it, for example after waking up.",
                "schedule_safety_note"
            )
        }
    }
}

@Composable
private fun ScheduleToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun ScheduleTimeRow(label: String, minuteOfDay: Int, testTag: String, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        OutlinedButton(
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onPicked(hour * 60 + minute) },
                    minuteOfDay / 60,
                    minuteOfDay % 60,
                    DateFormat.is24HourFormat(context)
                ).show()
            },
            modifier = Modifier.testTag(testTag)
        ) {
            Text(formatMinuteOfDay(context, minuteOfDay))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDayChips(settings: ScheduleSettings, onChange: (ScheduleSettings) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = settings.startDays == ScheduleSettings.ALL_DAYS,
            onClick = { onChange(settings.copy(startDays = ScheduleSettings.ALL_DAYS)) },
            label = { Text("Every day") },
            modifier = Modifier.testTag("schedule_every_day")
        )
        ScheduleSettings.WEEK_DAYS.forEachIndexed { index, day ->
            val selected = day in settings.startDays
            FilterChip(
                selected = selected,
                onClick = {
                    onChange(settings.copy(startDays = if (selected) settings.startDays - day else settings.startDays + day))
                },
                label = { Text(DAY_LABELS[index]) },
                modifier = Modifier.testTag("schedule_day_$day")
            )
        }
    }
}

@Composable
private fun ScheduleModeRow(label: String, selected: Boolean, testTag: String, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.testTag(testTag))
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ScheduleDurationSlider(settings: ScheduleSettings, onChange: (ScheduleSettings) -> Unit) {
    val min = ScheduleSettings.MIN_STOP_DURATION_MINUTES
    val max = ScheduleSettings.MAX_STOP_DURATION_MINUTES
    val step = ScheduleSettings.STOP_DURATION_STEP_MINUTES
    Column {
        Text(
            text = "Stop after ${formatDuration(settings.stopDurationMinutes)}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = settings.stopDurationMinutes.toFloat(),
            onValueChange = { onChange(settings.copy(stopDurationMinutes = it.roundToStep(step))) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / step - 1,
            modifier = Modifier.testTag("schedule_stop_duration")
        )
    }
}

@Composable
private fun ReminderPermissionWarnings(context: Context) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        ScheduleNote(
            "Notifications are turned off for this app, so the reminder can't be shown. Allow them in the system settings.",
            "schedule_notifications_warning"
        )
    }
    if (!ScheduleAlarms.canUseExactAlarms(context)) {
        ScheduleNote(
            "Exact alarms aren't allowed, so the reminder can arrive later than the set time.",
            "schedule_exact_alarm_warning"
        )
        TextButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    )
                }
            },
            modifier = Modifier.testTag("schedule_exact_alarm_button")
        ) {
            Text("Allow exact alarms")
        }
    }
}

@Composable
private fun ScheduleNote(text: String, testTag: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(testTag)
    )
}

private fun nextReminderText(context: Context, settings: ScheduleSettings): String {
    val next = ScheduleTime.nextStartMillis(settings, System.currentTimeMillis(), TimeZone.getDefault())
        ?: return "No days selected, so no reminder will be sent."
    val date = Date(next)
    val day = SimpleDateFormat("EEE", Locale.getDefault()).format(date)
    return "Next reminder: $day ${DateFormat.getTimeFormat(context).format(date)}"
}

private fun formatMinuteOfDay(context: Context, minuteOfDay: Int): String {
    // A fixed date, so that today's daylight saving change can't alter the displayed time.
    val time = Calendar.getInstance().apply {
        set(2000, Calendar.JANUARY, 1, minuteOfDay / 60, minuteOfDay % 60, 0)
    }.time
    return DateFormat.getTimeFormat(context).format(time)
}

// The slider reports floats, which can land a hair below a step.
private fun Float.roundToStep(step: Int): Int = Math.round(this / step) * step

internal fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val hourText = if (hours == 1) "1 hour" else "$hours hours"
    return if (minutes % 60 == 0) hourText else "$hourText ${minutes % 60} min"
}
