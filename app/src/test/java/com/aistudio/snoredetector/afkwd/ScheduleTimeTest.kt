package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.schedule.AutoStopMode
import com.aistudio.snoredetector.afkwd.schedule.ScheduleSettings
import com.aistudio.snoredetector.afkwd.schedule.ScheduleTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ScheduleTimeTest {

    private val lisbon = TimeZone.getTimeZone("Europe/Lisbon")
    private val hour = 60 * 60 * 1000L

    private fun millis(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = lisbon }.parse(text)!!.time

    private fun start(minute: Int, days: Set<Int> = ScheduleSettings.ALL_DAYS) =
        ScheduleSettings(startReminderEnabled = true, startMinuteOfDay = minute, startDays = days)

    /** Offset included, so that the expected side of a daylight saving test can't be ambiguous. */
    private fun assertInstant(expected: String, actual: Long?) {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.ROOT).apply { timeZone = lisbon }
        assertEquals(expected, actual?.let { format.format(it) })
    }

    // 2026-11-07 is a Saturday.

    @Test
    fun nextStart_isTodayWhenTheTimeIsStillAhead() {
        assertInstant(
            "2026-11-07 22:30 +0000",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30), millis("2026-11-07 21:00"), lisbon)
        )
    }

    @Test
    fun nextStart_isTomorrowWhenTheTimeHasPassed() {
        assertInstant(
            "2026-11-08 22:30 +0000",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30), millis("2026-11-07 23:00"), lisbon)
        )
    }

    @Test
    fun nextStart_exactlyAtTheStartTimeMovesOn() {
        assertInstant(
            "2026-11-08 22:30 +0000",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30), millis("2026-11-07 22:30"), lisbon)
        )
    }

    @Test
    fun nextStart_skipsDaysThatAreNotSelected() {
        val days = setOf(Calendar.TUESDAY, Calendar.THURSDAY)
        assertInstant(
            "2026-11-10 22:30 +0000",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30, days), millis("2026-11-07 12:00"), lisbon)
        )
    }

    @Test
    fun nextStart_onlyTodaySelectedAndPassedIsNextWeek() {
        assertInstant(
            "2026-11-14 22:30 +0000",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30, setOf(Calendar.SATURDAY)), millis("2026-11-07 23:00"), lisbon)
        )
    }

    @Test
    fun nextStart_nothingWhenDisabledOrNoDays() {
        val now = millis("2026-11-07 12:00")
        assertNull(ScheduleTime.nextStartMillis(ScheduleSettings(), now, lisbon))
        assertNull(ScheduleTime.nextStartMillis(start(600, emptySet()), now, lisbon))
    }

    @Test
    fun nextStart_keepsTheWallClockTimeAcrossSpringForward() {
        // Clocks go from 01:00 +0000 to 02:00 +0100 on 29 March 2026.
        assertInstant(
            "2026-03-29 22:30 +0100",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30), millis("2026-03-29 00:30"), lisbon)
        )
    }

    @Test
    fun nextStart_keepsTheWallClockTimeAcrossFallBack() {
        // Clocks go from 02:00 +0100 back to 01:00 +0000 on 25 October 2026.
        assertInstant(
            "2026-10-25 22:30 +0000",
            ScheduleTime.nextStartMillis(start(22 * 60 + 30), millis("2026-10-25 00:30"), lisbon)
        )
    }

    @Test
    fun nextStart_timeSkippedBySpringForwardMovesForwardOnTheSameDay() {
        assertInstant(
            "2026-03-29 02:30 +0100",
            ScheduleTime.nextStartMillis(start(90), millis("2026-03-28 23:00"), lisbon)
        )
    }

    @Test
    fun nextStart_timeRepeatedByFallBackHappensOnce() {
        val settings = start(90)
        val first = ScheduleTime.nextStartMillis(settings, millis("2026-10-24 23:00"), lisbon)!!
        val second = ScheduleTime.nextStartMillis(settings, first, lisbon)
        assertInstant("2026-10-26 01:30 +0000", second)
    }

    private fun stopAt(minute: Int) =
        ScheduleSettings(autoStopEnabled = true, stopMode = AutoStopMode.AT_TIME, stopMinuteOfDay = minute)

    private fun stopAfter(minutes: Int) =
        ScheduleSettings(autoStopEnabled = true, stopMode = AutoStopMode.AFTER_DURATION, stopDurationMinutes = minutes)

    @Test
    fun stop_disabledEndsAtTheSafetyLimit() {
        val session = millis("2026-11-07 22:30")
        val plan = ScheduleTime.stopPlan(ScheduleSettings(), session, session, lisbon)
        assertEquals(session + 12 * hour, plan.stopAtMillis)
        assertTrue(plan.isSafetyLimit)
    }

    @Test
    fun stop_timeEarlierThanTheSessionStartIsTheNextMorning() {
        val session = millis("2026-11-07 22:30")
        val plan = ScheduleTime.stopPlan(stopAt(7 * 60), session, session, lisbon)
        assertInstant("2026-11-08 07:00 +0000", plan.stopAtMillis)
        assertFalse(plan.isSafetyLimit)
    }

    @Test
    fun stop_timeLaterTheSameEveningIsToday() {
        val session = millis("2026-11-07 20:00")
        val plan = ScheduleTime.stopPlan(stopAt(23 * 60), session, session, lisbon)
        assertInstant("2026-11-07 23:00 +0000", plan.stopAtMillis)
    }

    @Test
    fun stop_sessionStartedAfterMidnightStopsTheSameMorning() {
        val session = millis("2026-11-08 00:30")
        val plan = ScheduleTime.stopPlan(stopAt(7 * 60), session, session, lisbon)
        assertInstant("2026-11-08 07:00 +0000", plan.stopAtMillis)
    }

    @Test
    fun stop_timeMoreThanTwelveHoursAwayIsCappedByTheSafetyLimit() {
        val session = millis("2026-11-07 08:00")
        val plan = ScheduleTime.stopPlan(stopAt(7 * 60), session, session, lisbon)
        assertEquals(session + 12 * hour, plan.stopAtMillis)
        assertTrue(plan.isSafetyLimit)
    }

    @Test
    fun stop_wallClockTimeAcrossSpringForwardIsOneHourShorter() {
        val session = millis("2026-03-28 23:00")
        val plan = ScheduleTime.stopPlan(stopAt(7 * 60), session, session, lisbon)
        assertInstant("2026-03-29 07:00 +0100", plan.stopAtMillis)
        assertEquals(7 * hour, plan.stopAtMillis - session)
    }

    @Test
    fun stop_wallClockTimeAcrossFallBackIsOneHourLonger() {
        val session = millis("2026-10-24 23:00")
        val plan = ScheduleTime.stopPlan(stopAt(7 * 60), session, session, lisbon)
        assertInstant("2026-10-25 07:00 +0000", plan.stopAtMillis)
        assertEquals(9 * hour, plan.stopAtMillis - session)
    }

    @Test
    fun stop_durationIsRealElapsedTimeAcrossDaylightSaving() {
        val session = millis("2026-03-28 23:00")
        val plan = ScheduleTime.stopPlan(stopAfter(8 * 60), session, session, lisbon)
        assertEquals(session + 8 * hour, plan.stopAtMillis)
        assertInstant("2026-03-29 08:00 +0100", plan.stopAtMillis)
        assertFalse(plan.isSafetyLimit)
    }

    @Test
    fun stop_twelveHourDurationIsTheUsersChoiceNotTheSafetyLimit() {
        val session = millis("2026-11-07 22:00")
        val plan = ScheduleTime.stopPlan(stopAfter(12 * 60), session, session, lisbon)
        assertEquals(session + 12 * hour, plan.stopAtMillis)
        assertFalse(plan.isSafetyLimit)
    }

    @Test
    fun stop_durationOutsideTheAllowedRangeIsClamped() {
        val session = millis("2026-11-07 22:00")
        assertEquals(session + 12 * hour, ScheduleTime.stopPlan(stopAfter(20 * 60), session, session, lisbon).stopAtMillis)
        assertEquals(session + hour, ScheduleTime.stopPlan(stopAfter(0), session, session, lisbon).stopAtMillis)
    }

    @Test
    fun stop_timeChangedDuringSessionCountsFromNow() {
        // At 23:30 the user picks 23:00: that means tomorrow, not "stop right now".
        val session = millis("2026-11-07 22:30")
        val plan = ScheduleTime.stopPlan(stopAt(23 * 60), session, millis("2026-11-07 23:30"), lisbon)
        assertEquals(session + 12 * hour, plan.stopAtMillis)
        assertTrue(plan.isSafetyLimit)
    }

    @Test
    fun stop_durationChangedDuringSessionStillCountsFromTheSessionStart() {
        val session = millis("2026-11-07 22:30")
        val plan = ScheduleTime.stopPlan(stopAfter(60), session, millis("2026-11-07 23:00"), lisbon)
        assertEquals(session + hour, plan.stopAtMillis)
    }
}
