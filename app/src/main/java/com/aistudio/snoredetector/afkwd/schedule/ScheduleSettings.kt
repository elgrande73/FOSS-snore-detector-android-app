package com.aistudio.snoredetector.afkwd.schedule

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

enum class AutoStopMode { AT_TIME, AFTER_DURATION }

/**
 * User configuration of the "Schedule & Auto-Stop" settings section.
 * Times are minutes after local midnight, days are [Calendar] day-of-week constants.
 */
data class ScheduleSettings(
    val startReminderEnabled: Boolean = false,
    val startMinuteOfDay: Int = 22 * 60 + 30,
    val startDays: Set<Int> = ALL_DAYS,
    val autoStopEnabled: Boolean = false,
    val stopMode: AutoStopMode = AutoStopMode.AT_TIME,
    val stopMinuteOfDay: Int = 7 * 60,
    val stopDurationMinutes: Int = 8 * 60
) {
    fun saveTo(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_START_ENABLED, startReminderEnabled)
            .putInt(KEY_START_MINUTE, startMinuteOfDay)
            .putStringSet(KEY_START_DAYS, startDays.map { it.toString() }.toSet())
            .putBoolean(KEY_STOP_ENABLED, autoStopEnabled)
            .putString(KEY_STOP_MODE, stopMode.name)
            .putInt(KEY_STOP_MINUTE, stopMinuteOfDay)
            .putInt(KEY_STOP_DURATION, stopDurationMinutes)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "snore_detector_preferences"

        const val MIN_STOP_DURATION_MINUTES = 60
        const val MAX_STOP_DURATION_MINUTES = 12 * 60
        const val STOP_DURATION_STEP_MINUTES = 30

        /** Monday first, the order shown in Settings. */
        val WEEK_DAYS = listOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY
        )
        val ALL_DAYS: Set<Int> = WEEK_DAYS.toSet()

        private const val KEY_START_ENABLED = "scheduleStartReminderEnabled"
        private const val KEY_START_MINUTE = "scheduleStartMinuteOfDay"
        private const val KEY_START_DAYS = "scheduleStartDays"
        private const val KEY_STOP_ENABLED = "scheduleAutoStopEnabled"
        private const val KEY_STOP_MODE = "scheduleStopMode"
        private const val KEY_STOP_MINUTE = "scheduleStopMinuteOfDay"
        private const val KEY_STOP_DURATION = "scheduleStopDurationMinutes"

        fun load(context: Context): ScheduleSettings =
            load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        fun load(prefs: SharedPreferences): ScheduleSettings {
            val defaults = ScheduleSettings()
            return ScheduleSettings(
                startReminderEnabled = prefs.getBoolean(KEY_START_ENABLED, defaults.startReminderEnabled),
                startMinuteOfDay = prefs.getInt(KEY_START_MINUTE, defaults.startMinuteOfDay),
                startDays = prefs.getStringSet(KEY_START_DAYS, null)
                    ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: defaults.startDays,
                autoStopEnabled = prefs.getBoolean(KEY_STOP_ENABLED, defaults.autoStopEnabled),
                stopMode = AutoStopMode.entries.firstOrNull { it.name == prefs.getString(KEY_STOP_MODE, null) }
                    ?: defaults.stopMode,
                stopMinuteOfDay = prefs.getInt(KEY_STOP_MINUTE, defaults.stopMinuteOfDay),
                stopDurationMinutes = prefs.getInt(KEY_STOP_DURATION, defaults.stopDurationMinutes)
            )
        }
    }
}
