package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.data.AudioExportManager
import com.aistudio.snoredetector.afkwd.data.SESSION_GAP_MILLIS
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.data.SnoreSession
import com.aistudio.snoredetector.afkwd.data.formatSessionRange
import com.aistudio.snoredetector.afkwd.data.groupEventsIntoSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SnoreSessionsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 60L * 60L * 1000L

    // 2026-11-05 23:00:00 UTC, a Thursday
    private val night = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = utc }
        .parse("2026-11-05 23:00")!!
        .time

    private fun event(id: Int, timestamp: Long) = SnoreEvent(
        id = id,
        timestamp = timestamp,
        durationSeconds = 2.0,
        maxDb = 60f,
        maxRms = 0.04f,
        meanZcr = 0.08f,
        meanBandEnergy = 0.03f,
        meanLowFreqRatio = 0.7f
    )

    @Test
    fun noEventsGiveNoSessions() {
        assertTrue(groupEventsIntoSessions(emptyList()).isEmpty())
    }

    @Test
    fun eventsAtExactlyTheGapStayTogether() {
        val events = listOf(event(2, night + SESSION_GAP_MILLIS), event(1, night))

        assertEquals(1, groupEventsIntoSessions(events).size)
    }

    @Test
    fun eventsJustOverTheGapAreSplit() {
        val events = listOf(event(2, night + SESSION_GAP_MILLIS + 1), event(1, night))

        assertEquals(2, groupEventsIntoSessions(events).size)
    }

    @Test
    fun newestFirstHistoryKeepsItsOrderAcrossTwoNights() {
        val nextNight = night + 24 * hour
        val events = listOf(
            event(4, nextNight + 2 * hour),
            event(3, nextNight),
            event(2, night + 3 * hour),
            event(1, night)
        )

        val sessions = groupEventsIntoSessions(events)

        assertEquals(listOf(listOf(4, 3), listOf(2, 1)), sessions.map { s -> s.events.map { it.id } })
        assertEquals(nextNight, sessions[0].startMillis)
        assertEquals(nextNight + 2 * hour, sessions[0].endMillis)
        assertEquals(night, sessions[1].startMillis)
    }

    @Test
    fun oldestFirstEventsAreSplitToo() {
        val events = listOf(event(1, night), event(2, night + 24 * hour))

        assertEquals(2, groupEventsIntoSessions(events).size)
    }

    @Test
    fun rangeAcrossMidnightShowsBothDates() {
        val session = SnoreSession(listOf(event(2, night + 7 * hour), event(1, night)))

        assertEquals(
            "Thu, Nov 05, 11:00 PM – Fri, Nov 06, 06:00 AM",
            formatSessionRange(session, Locale.US, utc)
        )
    }

    @Test
    fun rangeWithinOneDayShowsTheDateOnce() {
        val session = SnoreSession(listOf(event(2, night + 2 * hour), event(1, night + hour)))

        assertEquals(
            "Fri, Nov 06, 12:00 AM – 01:00 AM",
            formatSessionRange(session, Locale.US, utc)
        )
    }

    @Test
    fun singleEventShowsOneTime() {
        val session = SnoreSession(listOf(event(1, night)))

        assertEquals("Thu, Nov 05, 11:00 PM", formatSessionRange(session, Locale.US, utc))
    }

    @Test
    fun csvRowsCarryTheStartOfTheirSessionAndKeepTheirOrder() {
        val nextNight = night + 24 * hour
        val events = listOf(
            event(3, nextNight + hour),
            event(2, nextNight),
            event(1, night)
        )
        // generateCsvContent formats in the default time zone
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val firstNight = "\"${sdf.format(Date(night))}\""
        val secondNight = "\"${sdf.format(Date(nextNight))}\""

        val lines = AudioExportManager.generateCsvContent(events).trim().lines()

        assertTrue(lines[0].endsWith(",AudioClip,Session_Start"))
        assertEquals(4, lines.size)
        assertEquals(events.map { it.timestamp.toString() }, lines.drop(1).map { it.substringBefore(",") })
        assertEquals(
            listOf(secondNight, secondNight, firstNight),
            lines.drop(1).map { it.substringAfterLast(",") }
        )
        val columns = lines[0].split(",").size
        assertTrue(lines.drop(1).all { line -> line.split(",").size == columns })
    }
}
