package com.aistudio.snoredetector.afkwd

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.service.MonitoringInterruptions
import com.aistudio.snoredetector.afkwd.service.MonitoringSession
import com.aistudio.snoredetector.afkwd.service.MonitoringSessionStore
import com.aistudio.snoredetector.afkwd.service.SessionStart
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.service.decideSessionStart
import com.aistudio.snoredetector.afkwd.service.interruptionEnd
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource
import org.robolectric.shadows.ShadowService
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Android refuses a restart from the background, as it does for microphone services on API 34+. */
@Implements(Service::class)
class RefusingForegroundShadow : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: android.app.Notification) {
        throw SecurityException("Starting FGS with type microphone from the background")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringSessionTest {

    private val hour = 60 * 60 * 1000L
    private val limit = SnoreDetectionService.LIMIT_DURATION_MS

    private lateinit var app: Application
    private lateinit var store: MonitoringSessionStore
    private var controller: ServiceController<SnoreDetectionService>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = MonitoringSessionStore(app)
        store.clear()
        store.pendingNotice = null
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        ShadowAudioRecord.clearSource()
    }

    /** Stands in for a microphone: fills every read with [sample]. */
    private fun microphoneReturning(sample: Short) {
        ShadowAudioRecord.setSource(object : AudioRecordSource {
            override fun readInShortArray(
                audioData: ShortArray,
                offsetInShorts: Int,
                shortsToRead: Int,
                isBlocking: Boolean
            ): Int {
                audioData.fill(sample, offsetInShorts, offsetInShorts + shortsToRead)
                return shortsToRead
            }
        })
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun start(flags: Int): SnoreDetectionService {
        val intent = Intent(app, SnoreDetectionService::class.java)
        val built = Robolectric.buildService(SnoreDetectionService::class.java, intent).create()
        controller = built
        built.get().onStartCommand(intent, flags, 1)
        return built.get()
    }

    private fun stoppedNotification() =
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications
            .firstOrNull {
                it.extras.getString(android.app.Notification.EXTRA_TITLE) == "Monitoring stopped unexpectedly"
            }

    @Test
    fun userStartIsFreshAndCarriesAnOlderUnfinishedSession() {
        val old = MonitoringSession(startedAt = 100L, lastActiveAt = 200L)
        assertEquals(SessionStart.Fresh(old), decideSessionStart(old, false, 300L, limit))
        assertEquals(SessionStart.Fresh(null), decideSessionStart(null, false, 300L, limit))
    }

    @Test
    fun systemRestartResumesOnlyInsideTheSessionLimit() {
        val session = MonitoringSession(startedAt = 0L, lastActiveAt = 2 * hour)
        assertEquals(SessionStart.Resume(session), decideSessionStart(session, true, limit - 1, limit))
        assertEquals(SessionStart.Refuse(session), decideSessionStart(session, true, limit, limit))
    }

    @Test
    fun systemRestartWithoutASessionMustNotStartRecording() {
        assertEquals(SessionStart.Refuse(null), decideSessionStart(null, true, 1L, limit))
    }

    @Test
    fun gapEndsWhenNoticedButNeverAfterTheSessionWouldHaveEnded() {
        val session = MonitoringSession(startedAt = 0L, lastActiveAt = 2 * hour)
        assertEquals(7 * hour, interruptionEnd(session, 7 * hour, limit))
        assertEquals(limit, interruptionEnd(session, 40 * hour, limit))
    }

    @Test
    fun gapEndCopesWithAClockThatMovedBackwards() {
        val session = MonitoringSession(startedAt = 0L, lastActiveAt = 2 * hour)
        assertEquals(2 * hour, interruptionEnd(session, hour, limit))
    }

    @Test
    fun messageShowsTheGapInLocalTime() {
        val defaultZone = TimeZone.getDefault()
        val defaultLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.US)
            val at = { h: Int, m: Int ->
                Calendar.getInstance().apply {
                    timeInMillis = 0L
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                }.timeInMillis
            }
            assertEquals(
                "Monitoring interrupted 02:14–07:00.",
                MonitoringInterruptions.gapMessage(at(2, 14), at(7, 0))
            )
        } finally {
            TimeZone.setDefault(defaultZone)
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun appOpenReportsASessionWhoseProcessDiedAndOnlyOnce() {
        store.begin(1_000L)
        val later = 1_000L + 5 * hour

        MonitoringInterruptions.reportAbandonedSession(app, later)

        assertNull(store.activeSession())
        val notice = store.pendingNotice
        assertNotNull(notice)

        store.pendingNotice = null
        MonitoringInterruptions.reportAbandonedSession(app, later + hour)
        assertNull(store.pendingNotice)
    }

    @Test
    fun appOpenLeavesASessionTheSystemMayStillRestart() {
        store.begin(1_000L)

        MonitoringInterruptions.reportAbandonedSession(app, 1_000L + 30_000L)

        assertNotNull(store.activeSession())
        assertNull(store.pendingNotice)
    }

    @Test
    fun userStartOpensASessionAndCleanStopLeavesNoInterruption() {
        start(flags = 0)
        assertNotNull(store.activeSession())

        controller?.destroy()
        controller = null

        assertNull(store.activeSession())
        assertNull(store.pendingNotice)
    }

    @Test
    fun userStartAfterAKillReportsTheOldSessionAndStartsANewOne() {
        val oldStart = System.currentTimeMillis() - 30 * hour
        store.begin(oldStart)

        start(flags = 0)

        assertNotEquals(oldStart, store.activeSession()?.startedAt)
        assertNotNull(store.pendingNotice)
    }

    @Test
    fun systemRestartContinuesTheOriginalSession() {
        val startedAt = System.currentTimeMillis() - 3 * hour
        val lastActive = System.currentTimeMillis() - hour
        store.begin(startedAt)
        store.heartbeat(lastActive)

        val service = start(Service.START_FLAG_REDELIVERY)

        assertEquals(startedAt, SnoreDetectionService.sessionStartTime.value)
        assertEquals(startedAt, store.activeSession()?.startedAt)
        assertNotEquals(lastActive, store.activeSession()?.lastActiveAt)
        assertTrue(store.pendingNotice.orEmpty().endsWith("then resumed."))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun repeatedStartCommandsDoNotReopenTheSession() {
        val service = start(flags = 0)
        val startedAt = store.activeSession()?.startedAt

        // The view model re-sends the start intent on every settings change.
        service.onStartCommand(Intent(app, SnoreDetectionService::class.java), 0, 2)

        assertEquals(startedAt, store.activeSession()?.startedAt)
        assertNull(store.pendingNotice)
    }

    @Test
    fun systemRestartAfterTheSessionLimitStopsInsteadOfRecording() {
        store.begin(System.currentTimeMillis() - limit - hour)

        val service = start(Service.START_FLAG_REDELIVERY)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(SnoreDetectionService.isServiceRunning.value)
        assertNull(store.activeSession())
        assertNotNull(store.pendingNotice)
    }

    @Test
    fun systemRestartWithNothingToResumeStops() {
        val service = start(Service.START_FLAG_REDELIVERY)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(store.activeSession())
    }

    @Test
    fun restartThatOnlyGetsSilenceEndsTheSessionInsteadOfFakingAQuietNight() {
        microphoneReturning(0)
        store.begin(System.currentTimeMillis() - 3 * hour)

        val service = start(Service.START_FLAG_REDELIVERY)
        waitFor { shadowOf(service).isStoppedBySelf }

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(store.activeSession())
        assertTrue(store.pendingNotice.orEmpty().contains("microphone silent"))
        assertNotNull(stoppedNotification())
    }

    @Test
    fun restartWithRealAudioKeepsRunning() {
        microphoneReturning(40)
        store.begin(System.currentTimeMillis() - 3 * hour)

        val service = start(Service.START_FLAG_REDELIVERY)
        waitFor { SnoreDetectionService.liveAnalysis.value != null }

        assertNotNull(SnoreDetectionService.liveAnalysis.value)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNotNull(store.activeSession())
    }

    @Test
    fun silenceInASessionTheUserStartedIsLeftAlone() {
        microphoneReturning(0)

        val service = start(flags = 0)
        waitFor { (SnoreDetectionService.liveAnalysis.value != null) }
        Thread.sleep(300)

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNotNull(store.activeSession())
    }

    @Test
    @Config(shadows = [RefusingForegroundShadow::class])
    fun refusedRestartDoesNotCrashAndLeavesAMorningNotification() {
        val lastActive = System.currentTimeMillis() - hour
        store.begin(System.currentTimeMillis() - 3 * hour)
        store.heartbeat(lastActive)

        val service = start(Service.START_FLAG_REDELIVERY)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(store.activeSession())
        assertFalse(SnoreDetectionService.isServiceRunning.value)
        assertTrue(store.pendingNotice.orEmpty().contains("did not allow it to restart"))
        assertNotNull(stoppedNotification())
    }

    @Test
    @Config(shadows = [RefusingForegroundShadow::class])
    fun refusedUserStartReportsAnErrorInsteadOfCrashing() {
        val service = start(flags = 0)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(store.activeSession())
        assertNotNull(SnoreDetectionService.serviceError.value)
        assertNull(stoppedNotification())
    }

    @Test
    fun interruptionReachesTheHistoryErrorLogWithItsBatteryState() = runBlocking {
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(app.packageName, true)
        store.begin(1_000L)

        MonitoringInterruptions.reportAbandonedSession(app, 1_000L + hour)

        val log = withTimeout(5_000L) {
            AppDatabase.getDatabase(app).errorLogDao().getAllLogs()
                .map { logs -> logs.firstOrNull { it.message == store.pendingNotice } }
                .filterNotNull()
                .first()
        }
        assertEquals(MonitoringInterruptions.ERROR_TYPE, log.errorType)
        assertTrue(log.diagnosticDetails.contains("BatteryOptimizationDisabled: true"))
    }
}
