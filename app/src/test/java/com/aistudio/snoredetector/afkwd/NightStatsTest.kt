package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.data.ChangeKind
import com.aistudio.snoredetector.afkwd.data.ChangeMarker
import com.aistudio.snoredetector.afkwd.data.MonitoringSession
import com.aistudio.snoredetector.afkwd.data.NightStats
import com.aistudio.snoredetector.afkwd.data.NightStatus
import com.aistudio.snoredetector.afkwd.data.NightSummary
import com.aistudio.snoredetector.afkwd.data.PeriodComparison
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class NightStatsTest {

    private val lisbon = TimeZone.getTimeZone("Europe/Lisbon")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = lisbon): Long =
        GregorianCalendar(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun session(
        start: Long,
        monitoredMinutes: Double = 480.0,
        events: Int = 0,
        snoreMinutes: Double = 0.0,
        longest: Double = 0.0,
        avgPeak: Float? = null,
        background: Float? = null,
        version: String? = "1.2.0",
        settings: String? = "a",
        backfilled: Boolean = false
    ) = MonitoringSession(
        startTime = start,
        endTime = start + (monitoredMinutes * 60_000).toLong(),
        monitoredMinutes = monitoredMinutes,
        snoreEventCount = events,
        totalSnoreMinutes = snoreMinutes,
        longestEpisodeSeconds = longest,
        avgPeakDb = avgPeak,
        maxPeakDb = avgPeak,
        backgroundDb = background,
        appVersion = version,
        detectionSettings = settings,
        isBackfilled = backfilled
    )

    /** A night on [day] whose score is exactly [score], from the snore index alone. */
    private fun scoredNight(day: Long, score: Int, status: NightStatus = NightStatus.OK): NightSummary {
        val snoreMinutes = score * 8 / 2.0 // 60 points at 30 min/h over 8 h
        val start = NightStats.epochDayToUtcMillis(day) + 22 * 3_600_000L
        val utc = TimeZone.getTimeZone("UTC")
        val night = NightStats.summarize(listOf(session(start, snoreMinutes = snoreMinutes)), utc).single()
        return night.copy(status = status, breakdown = night.breakdown?.copy(score = score))
    }

    @Test
    fun beforeAndAfterMidnightAreTheSameNight() {
        val before = NightStats.nightEpochDay(time(2026, 3, 10, 23, 50), lisbon)
        val after = NightStats.nightEpochDay(time(2026, 3, 11, 0, 20), lisbon)
        val morning = NightStats.nightEpochDay(time(2026, 3, 11, 7, 30), lisbon)
        assertEquals(before, after)
        assertEquals(before, morning)
    }

    @Test
    fun noonStartsTheNextNight() {
        val lateMorning = NightStats.nightEpochDay(time(2026, 3, 11, 11, 59), lisbon)
        val noon = NightStats.nightEpochDay(time(2026, 3, 11, 12, 0), lisbon)
        assertEquals(lateMorning + 1, noon)
    }

    @Test
    fun nightIsNamedAfterTheDateItBegan() {
        val day = NightStats.nightEpochDay(time(2026, 3, 11, 2, 0), lisbon)
        val expected = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.MARCH, 10)
        }.timeInMillis
        assertEquals(expected, NightStats.epochDayToUtcMillis(day))
    }

    @Test
    fun nightFollowsTheLocalClockNotUtc() {
        // 16:00 UTC is 01:00 the next day in Tokyo, which belongs to the night that began that evening.
        val instant = time(2026, 3, 10, 16, 0, TimeZone.getTimeZone("UTC"))
        assertEquals(
            NightStats.nightEpochDay(time(2026, 3, 10, 23, 0, tokyo), tokyo),
            NightStats.nightEpochDay(instant, tokyo)
        )
    }

    @Test
    fun nightsAcrossDaylightSavingChangeStayConsecutive() {
        // Clocks go forward in Lisbon at 01:00 on 29 March 2026.
        val saturday = NightStats.nightEpochDay(time(2026, 3, 28, 23, 0), lisbon)
        val duringChange = NightStats.nightEpochDay(time(2026, 3, 29, 3, 0), lisbon)
        val sunday = NightStats.nightEpochDay(time(2026, 3, 29, 23, 0), lisbon)
        assertEquals(saturday, duringChange)
        assertEquals(saturday + 1, sunday)
    }

    @Test
    fun sessionsOfOneNightAreMergedBeforeScoring() {
        val nights = NightStats.summarize(
            listOf(
                session(time(2026, 3, 11, 2, 0), monitoredMinutes = 300.0, events = 10, snoreMinutes = 5.0, longest = 40.0, avgPeak = 60f, background = 40f),
                session(time(2026, 3, 10, 23, 0), monitoredMinutes = 100.0, events = 30, snoreMinutes = 15.0, longest = 90.0, avgPeak = 70f, background = 44f)
            ),
            lisbon
        )

        val night = nights.single()
        assertEquals(2, night.sessionCount)
        assertEquals(400.0, night.monitoredMinutes, 0.001)
        assertEquals(40, night.snoreEventCount)
        assertEquals(20.0, night.totalSnoreMinutes, 0.001)
        assertEquals(90.0, night.longestEpisodeSeconds, 0.001)
        assertEquals(67.5f, night.avgPeakDb!!, 0.001f)     // Weighted by events
        assertEquals(41.0f, night.backgroundDb!!, 0.001f)  // Weighted by monitored time
        assertEquals(26.5, night.breakdown!!.loudnessAboveBackgroundDb!!, 0.001)
        assertEquals(3.0, night.breakdown!!.snoreIndex, 0.001) // 20 min over 6.67 h
        assertEquals(time(2026, 3, 10, 23, 0), night.startTime)
    }

    @Test
    fun twoShortRunsTogetherCanMakeAReliableNight() {
        val nights = NightStats.summarize(
            listOf(
                session(time(2026, 3, 10, 23, 0), monitoredMinutes = 70.0),
                session(time(2026, 3, 11, 1, 0), monitoredMinutes = 70.0)
            ),
            lisbon
        )
        assertEquals(NightStatus.OK, nights.single().status)
    }

    @Test
    fun nightsAreSortedOldestFirst() {
        val nights = NightStats.summarize(
            listOf(session(time(2026, 3, 12, 23, 0)), session(time(2026, 3, 10, 23, 0))),
            lisbon
        )
        assertEquals(2, nights.size)
        assertEquals(nights[0].epochDay + 2, nights[1].epochDay)
    }

    @Test
    fun shortNightIsFlaggedAndLeftOutOfTrendsByDefault() {
        val nights = NightStats.summarize(
            listOf(
                session(time(2026, 3, 10, 23, 0), monitoredMinutes = 119.0),
                session(time(2026, 3, 11, 23, 0), monitoredMinutes = 120.0)
            ),
            lisbon
        )
        assertEquals(NightStatus.TOO_SHORT, nights[0].status)
        assertEquals(NightStatus.OK, nights[1].status)
        assertEquals(listOf(nights[1]), NightStats.trendNights(nights, includeShort = false))
        assertEquals(nights, NightStats.trendNights(nights, includeShort = true))
    }

    @Test
    fun backfilledNightHasNoScoreAndNeverEntersTrends() {
        val night = NightStats.summarize(
            listOf(session(time(2026, 3, 10, 23, 0), monitoredMinutes = 0.0, events = 40, snoreMinutes = 9.0, backfilled = true, version = null, settings = null)),
            lisbon
        ).single()

        assertEquals(NightStatus.NO_MONITORED_TIME, night.status)
        assertNull(night.score)
        assertEquals(40, night.snoreEventCount)
        assertEquals(emptyList<NightSummary>(), NightStats.trendNights(listOf(night), includeShort = true))
    }

    @Test
    fun backfilledEventsDoNotInflateARealSessionOfTheSameNight() {
        val real = session(time(2026, 3, 11, 1, 0), events = 4, snoreMinutes = 8.0)
        val alone = NightStats.summarize(listOf(real), lisbon).single()
        val mixed = NightStats.summarize(
            listOf(session(time(2026, 3, 10, 23, 0), monitoredMinutes = 0.0, events = 500, snoreMinutes = 120.0, backfilled = true), real),
            lisbon
        ).single()

        assertEquals(alone.score, mixed.score)
        assertEquals(NightStatus.OK, mixed.status)
    }

    @Test
    fun movingAverageUsesCalendarDaysNotRows() {
        val nights = listOf(
            scoredNight(100, 10),
            scoredNight(101, 20),
            scoredNight(107, 40), // Window 101..107 holds day 101 but no longer day 100
            scoredNight(120, 80)  // Alone in its window after the gap
        )
        val average = NightStats.movingAverage(nights)
        assertEquals(listOf(10.0, 15.0, 30.0, 80.0), average.map { it.value })
        assertEquals(listOf(100L, 101L, 107L, 120L), average.map { it.epochDay })
    }

    @Test
    fun comparisonUsesAdjacentWindowsAndReportsChange() {
        val today = 1000L
        val nights = listOf(
            scoredNight(today - 60, 90), // Outside both windows
            scoredNight(today - 59, 40), // First day of the previous window
            scoredNight(today - 30, 60), // Last day of the previous window
            scoredNight(today - 29, 30), // First day of the recent window
            scoredNight(today, 40)
        )
        val result = NightStats.comparePeriods(nights, today)

        assertEquals(50.0, result.previousAverage!!, 0.001)
        assertEquals(35.0, result.recentAverage!!, 0.001)
        assertEquals(2, result.previousNights)
        assertEquals(2, result.recentNights)
        assertEquals(-15.0, result.change!!, 0.001)
        assertEquals(-30.0, result.percentChange!!, 0.001)
    }

    @Test
    fun comparisonNeedsBothPeriods() {
        val result = NightStats.comparePeriods(listOf(scoredNight(1000, 30)), 1000)
        assertEquals(30.0, result.recentAverage!!, 0.001)
        assertNull(result.previousAverage)
        assertNull(result.change)
        assertNull(result.percentChange)
    }

    @Test
    fun percentChangeFromZeroIsUndefined() {
        val result = PeriodComparison(recentAverage = 20.0, previousAverage = 0.0, recentNights = 3, previousNights = 3)
        assertEquals(20.0, result.change!!, 0.001)
        assertNull(result.percentChange)
    }

    @Test
    fun markersShowVersionAndSettingsChanges() {
        val nights = NightStats.summarize(
            listOf(
                session(time(2026, 3, 8, 23, 0), monitoredMinutes = 0.0, version = null, settings = null, backfilled = true),
                session(time(2026, 3, 9, 23, 0), version = "1.2.0", settings = "a"),
                session(time(2026, 3, 10, 23, 0), version = "1.2.0", settings = "a"),
                session(time(2026, 3, 11, 23, 0), version = "1.3.0", settings = "a"),
                session(time(2026, 3, 12, 23, 0), version = "1.3.0", settings = "b")
            ),
            lisbon
        )
        assertEquals(
            listOf(
                ChangeMarker(nights[3].epochDay, ChangeKind.APP_UPDATE),
                ChangeMarker(nights[4].epochDay, ChangeKind.SETTINGS)
            ),
            NightStats.changeMarkers(nights)
        )
    }
}
