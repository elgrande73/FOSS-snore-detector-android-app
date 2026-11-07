package com.aistudio.snoredetector.afkwd.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService

/**
 * Receives the reminder alarm, and every system event that loses or invalidates it:
 * reboot, app update, clock or time zone change, and a change of the exact alarm permission.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ScheduleAlarms.ACTION_START_REMINDER) {
            if (!SnoreDetectionService.isServiceRunning.value) {
                ScheduleAlarms.showStartReminder(context)
            }
            // Skip past this occurrence even if the alarm ran slightly early after a clock adjustment.
            ScheduleAlarms.reschedule(context, System.currentTimeMillis() + RESCHEDULE_MARGIN_MS)
        } else {
            ScheduleAlarms.reschedule(context)
        }
    }

    private companion object {
        const val RESCHEDULE_MARGIN_MS = 60_000L
    }
}
