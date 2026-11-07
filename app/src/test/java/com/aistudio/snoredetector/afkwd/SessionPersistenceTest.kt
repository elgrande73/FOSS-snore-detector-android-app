package com.aistudio.snoredetector.afkwd

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.data.AudioExportManager
import com.aistudio.snoredetector.afkwd.data.MonitoringSession
import com.aistudio.snoredetector.afkwd.data.NightStats
import com.aistudio.snoredetector.afkwd.data.SessionProgress
import com.aistudio.snoredetector.afkwd.data.SessionTracker
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.data.SnoreRepository
import com.aistudio.snoredetector.afkwd.data.toSnapshot
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** Exercises sessions against a real Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SnoreRepository

    private val start = 1_771_372_800_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = SnoreRepository(db.snoreDao(), db.errorLogDao(), db.sessionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(sessionId: Long?, offsetMinutes: Int, seconds: Double, level: Float) = SnoreEvent(
        timestamp = start + offsetMinutes * 60_000L,
        durationSeconds = seconds,
        maxDb = level,
        maxRms = 0.04f,
        meanZcr = 0.08f,
        meanBandEnergy = 0.03f,
        meanLowFreqRatio = 0.78f,
        sessionId = sessionId
    )

    private fun progress(minutes: Double) = SessionProgress(
        endTime = start + (minutes * 60_000).toLong(),
        monitoredMinutes = minutes,
        backgroundDb = 40f,
        detectionSettings = "b"
    )

    private suspend fun newSession(): Long = repository.startSession(
        MonitoringSession(startTime = start, endTime = start, monitoredMinutes = 0.0, appVersion = "1.2.0", detectionSettings = "a")
    )!!

    @Test
    fun sessionSummaryIsWorkedOutFromItsOwnEvents(): Unit = runBlocking {
        val id = newSession()
        repository.insertEvent(event(id, 1, 30.0, 60f))
        repository.insertEvent(event(id, 2, 90.0, 70f))
        repository.insertEvent(event(null, 3, 999.0, 99f)) // Another session's event must not count

        repository.updateSession(id, progress(240.0))

        val session = repository.getAllSessions().single()
        assertEquals(2, session.snoreEventCount)
        assertEquals(2.0, session.totalSnoreMinutes, 0.0001)
        assertEquals(90.0, session.longestEpisodeSeconds, 0.0001)
        assertEquals(65f, session.avgPeakDb!!, 0.0001f)
        assertEquals(70f, session.maxPeakDb!!, 0.0001f)
        assertEquals(240.0, session.monitoredMinutes, 0.0001)
        assertEquals(40f, session.backgroundDb!!, 0.0001f)
        assertEquals("b", session.detectionSettings)
        assertEquals("1.2.0", session.appVersion)
    }

    @Test
    fun sessionWithoutEventsIsStillSavedAsAQuietNight(): Unit = runBlocking {
        val id = newSession()
        repository.updateSession(id, progress(480.0))

        val session = repository.getAllSessions().single()
        assertEquals(0, session.snoreEventCount)
        assertNull(session.avgPeakDb)
        assertEquals(0, NightStats.summarize(listOf(session)).single().score)
    }

    @Test
    fun deletingAnEventRemovesItFromTheSummary(): Unit = runBlocking {
        val id = newSession()
        val keep = repository.insertEvent(event(id, 1, 30.0, 60f))
        val falseAlarm = repository.insertEvent(event(id, 2, 300.0, 80f))
        repository.updateSession(id, progress(240.0))

        repository.deleteEventById(falseAlarm.toInt(), id)

        val session = repository.getAllSessions().single()
        assertEquals(1, session.snoreEventCount)
        assertEquals(30.0, session.longestEpisodeSeconds, 0.0001)
        assertEquals(60f, session.maxPeakDb!!, 0.0001f)
        assertEquals(240.0, session.monitoredMinutes, 0.0001) // Monitored time is untouched
        assertEquals(keep.toInt(), db.snoreDao().getAllEvents().first().single().id)
    }

    @Test
    fun deletingTheLastEventKeepsAMeasuredSessionButDropsABackfilledOne(): Unit = runBlocking {
        val measured = newSession()
        val measuredEvent = repository.insertEvent(event(measured, 1, 30.0, 60f))
        val backfilled = repository.startSession(
            MonitoringSession(startTime = start - 86_400_000L, endTime = start, monitoredMinutes = 0.0, isBackfilled = true)
        )!!
        val backfilledEvent = repository.insertEvent(event(backfilled, -1000, 30.0, 60f))

        repository.deleteEventById(measuredEvent.toInt(), measured)
        repository.deleteEventById(backfilledEvent.toInt(), backfilled)

        assertEquals(listOf(measured), repository.getAllSessions().map { it.id })
    }

    @Test
    fun clearingHistoryRemovesSessions(): Unit = runBlocking {
        val id = newSession()
        repository.insertEvent(event(id, 1, 30.0, 60f))

        repository.clearHistory()

        assertTrue(repository.getAllSessions().isEmpty())
        assertTrue(db.snoreDao().getAllEvents().first().isEmpty())
    }

    @Test
    fun clearingHistoryDuringMonitoringKeepsTheRunningSession(): Unit = runBlocking {
        val finished = newSession()
        val running = newSession()
        repository.insertEvent(event(running, 1, 30.0, 60f))
        repository.updateSession(running, progress(60.0))

        repository.clearHistory(activeSessionId = running)

        val session = repository.getAllSessions().single()
        assertEquals(running, session.id)
        assertNotEquals(finished, session.id)
        assertEquals(0, session.snoreEventCount)
        assertEquals(60.0, session.monitoredMinutes, 0.0001)
    }

    @Test
    fun trackerExcludesPausedAudioFromMonitoredTime() {
        val tracker = SessionTracker()
        repeat(100) { tracker.onFrame(600.0, 40f, isSnoring = false, isPaused = false) }
        repeat(100) { tracker.onFrame(600.0, 40f, isSnoring = false, isPaused = true) }
        assertEquals(1.0, tracker.monitoredMinutes, 0.0001) // 100 frames of 600 ms
    }

    @Test
    fun trackerBackgroundIsMedianOfQuietFramesOnly() {
        val tracker = SessionTracker()
        repeat(60) { tracker.onFrame(64.0, 38f, isSnoring = false, isPaused = false) }
        repeat(40) { tracker.onFrame(64.0, 95f, isSnoring = false, isPaused = false) } // Door slams
        repeat(500) { tracker.onFrame(64.0, 70f, isSnoring = true, isPaused = false) }
        repeat(500) { tracker.onFrame(64.0, 80f, isSnoring = false, isPaused = true) } // Media playing
        assertEquals(38f, tracker.backgroundDb!!, 0.0001f)
    }

    @Test
    fun trackerHasNoBackgroundWithoutQuietFrames() {
        val tracker = SessionTracker()
        tracker.onFrame(64.0, 70f, isSnoring = true, isPaused = false)
        assertNull(tracker.backgroundDb)
    }

    @Test
    fun settingsSnapshotChangesOnlyWithTheSettings() {
        assertEquals(DetectionConfig().toSnapshot(), DetectionConfig().toSnapshot())
        assertNotEquals(DetectionConfig().toSnapshot(), DetectionConfig(rmsDbThreshold = 60f).toSnapshot())
        assertNotEquals(DetectionConfig().toSnapshot(), DetectionConfig(useZcr = false).toSnapshot())
    }

    @Test
    fun nightCsvHasOneRowPerNightWithScoreAndStatus(): Unit = runBlocking {
        val utc = TimeZone.getTimeZone("UTC")
        val nights = NightStats.summarize(
            listOf(
                MonitoringSession(startTime = start, endTime = start + 1, monitoredMinutes = 480.0, totalSnoreMinutes = 60.0, snoreEventCount = 120, longestEpisodeSeconds = 150.0, detectionSettings = "minDb=55.0;ignoreMedia=true", appVersion = "1.2.0"),
                MonitoringSession(startTime = start + 3_600_000L, endTime = start + 2, monitoredMinutes = 10.0),
                MonitoringSession(startTime = start + 2 * 86_400_000L, endTime = start + 2 * 86_400_000L, monitoredMinutes = 30.0),
                MonitoringSession(startTime = start + 3 * 86_400_000L, endTime = start + 3 * 86_400_000L, monitoredMinutes = 0.0, snoreEventCount = 5, isBackfilled = true)
            ),
            utc
        )

        val lines = AudioExportManager.generateNightCsv(nights).trim().lines()

        assertEquals(4, lines.size) // Header + 3 nights: the first two sessions share a night
        assertTrue(lines[0].startsWith("Night,Start,End,Sessions,Monitored_Minutes"))
        assertEquals(lines[0].split(",").size, lines[1].replace(";", "").split(",").size)
        assertTrue(lines[1].contains(",2,490.00,120,60.00,"))
        assertTrue(lines[1].contains(",OK,"))
        assertTrue(lines[2].contains(",TOO_SHORT,"))
        assertTrue(lines[3].contains(",,NO_MONITORED_TIME,")) // Empty score, never a made-up one
    }
}
