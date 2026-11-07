package com.aistudio.snoredetector.afkwd.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.snoredetector.afkwd.MainActivity
import java.util.TimeZone

/**
 * Schedules the start reminder and shows it.
 *
 * It is a notification rather than a direct start because Android does not allow it: a foreground
 * service that uses the microphone must be started while the app is visible or from a user action
 * on a notification. An exact alarm is not an exception. Android 14+ throws a SecurityException,
 * and Android 11 to 13 start the service without microphone access, which would record a silent night.
 * Tapping the notification opens [MainActivity], which starts monitoring.
 */
object ScheduleAlarms {

    private const val TAG = "ScheduleAlarms"

    const val ACTION_START_REMINDER = "com.aistudio.snoredetector.afkwd.action.START_REMINDER"
    const val ACTION_START_MONITORING = "com.aistudio.snoredetector.afkwd.action.START_MONITORING"
    const val REMINDER_NOTIFICATION_ID = 54324
    const val CHANNEL_REMINDER_ID = "snore_detector_schedule_channel"

    /** Replaces any pending reminder with the next one after [afterMillis] according to the saved settings. */
    fun reschedule(context: Context, afterMillis: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = reminderAlarmIntent(context)
        alarmManager.cancel(operation)

        val triggerAt = ScheduleTime.nextStartMillis(ScheduleSettings.load(context), afterMillis, TimeZone.getDefault())
            ?: return

        if (canUseExactAlarms(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                return
            } catch (e: SecurityException) {
                // The user can revoke the permission at any moment.
                Log.w(TAG, "Exact alarm refused, using an inexact one: ${e.message}")
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }

    fun canUseExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun showStartReminder(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted; skipping start reminder")
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDER_ID,
                    "Scheduled Start Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Reminder at your scheduled time, tap it to start monitoring"
                }
            )
        }

        val startIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_START_MONITORING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            2,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER_ID)
            .setContentTitle("Snore Detector")
            .setContentText("Tap to start snore monitoring")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    /** True for a tap on the reminder, but not when Recents later reopens the same intent. */
    fun isStartMonitoringIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_START_MONITORING &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    private fun reminderAlarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ScheduleReceiver::class.java).setAction(ACTION_START_REMINDER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
