package com.aistudio.snoredetector.afkwd.schedule

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** When a monitoring session has to end, and whether that is the safety limit or the user's setting. */
data class StopPlan(val stopAtMillis: Long, val isSafetyLimit: Boolean)

/**
 * Time calculations for the schedule. Uses [Calendar] because java.time needs API 26 and minSdk is 24.
 * Wall-clock times resolve through the time zone rules, so they stay correct across daylight saving:
 * a time skipped by the clock change moves forward by the size of the gap, and a time that occurs
 * twice resolves to a single instant.
 */
object ScheduleTime {

    /** Every session ends after this long, whatever the settings say. */
    const val SAFETY_LIMIT_MS = 12 * 60 * 60 * 1000L

    /** The first start time strictly after [afterMillis] on a selected day, or null if nothing is scheduled. */
    fun nextStartMillis(settings: ScheduleSettings, afterMillis: Long, timeZone: TimeZone): Long? {
        if (!settings.startReminderEnabled) return null
        // Offset 7 is today's weekday again, for when today is the only selected day and has passed.
        for (dayOffset in 0..7) {
            val candidate = occurrence(afterMillis, dayOffset, settings.startMinuteOfDay, timeZone)
            if (candidate.get(Calendar.DAY_OF_WEEK) in settings.startDays && candidate.timeInMillis > afterMillis) {
                return candidate.timeInMillis
            }
        }
        return null
    }

    /**
     * When the session started at [sessionStartMillis] has to stop, seen from [nowMillis].
     *
     * A stop time means its next occurrence, so a time earlier than the session start is the next
     * morning. A duration is real elapsed time from the session start, and so is the safety limit.
     */
    fun stopPlan(
        settings: ScheduleSettings,
        sessionStartMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone
    ): StopPlan {
        val safetyLimit = sessionStartMillis + SAFETY_LIMIT_MS
        val configured = when {
            !settings.autoStopEnabled -> null
            settings.stopMode == AutoStopMode.AT_TIME ->
                nextDailyOccurrence(nowMillis, settings.stopMinuteOfDay, timeZone)
            else -> sessionStartMillis + settings.stopDurationMinutes
                .coerceIn(ScheduleSettings.MIN_STOP_DURATION_MINUTES, ScheduleSettings.MAX_STOP_DURATION_MINUTES) * 60_000L
        }
        return if (configured != null && configured <= safetyLimit) {
            StopPlan(configured, isSafetyLimit = false)
        } else {
            StopPlan(safetyLimit, isSafetyLimit = true)
        }
    }

    private fun nextDailyOccurrence(afterMillis: Long, minuteOfDay: Int, timeZone: TimeZone): Long {
        val today = occurrence(afterMillis, 0, minuteOfDay, timeZone).timeInMillis
        return if (today > afterMillis) today else occurrence(afterMillis, 1, minuteOfDay, timeZone).timeInMillis
    }

    private fun occurrence(baseMillis: Long, dayOffset: Int, minuteOfDay: Int, timeZone: TimeZone): Calendar =
        GregorianCalendar(timeZone).apply {
            timeInMillis = baseMillis
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
