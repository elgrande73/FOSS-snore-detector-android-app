package com.aistudio.snoredetector.afkwd

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.schedule.ScheduleAlarms
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleReminderTapTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ActivityController<MainActivity>? = null

    /** A live MainActivity keeps Compose busy and would hang every later Compose test in this JVM. */
    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun launch(intent: Intent): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().also { controller = it }

    private fun reminderTapIntent() =
        Intent(app, MainActivity::class.java).setAction(ScheduleAlarms.ACTION_START_MONITORING)

    @Test
    fun tapOnReminder_startsMonitoring() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        launch(reminderTapIntent())

        val started = shadowOf(app).nextStartedService
        assertEquals(SnoreDetectionService::class.java.name, started?.component?.className)
    }

    @Test
    fun normalLaunch_startsNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        launch(Intent(app, MainActivity::class.java))

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun tapOnReminder_whileTheAppIsOpenStartsMonitoring() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val running = launch(Intent(app, MainActivity::class.java))

        running.pause().newIntent(reminderTapIntent()).resume()

        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun tapOnReminder_withoutMicrophonePermissionStartsNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        launch(reminderTapIntent())

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun rotationAfterTheTap_doesNotStartASecondTime() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val running = launch(reminderTapIntent())
        assertNotNull(shadowOf(app).nextStartedService)

        running.recreate()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun reopeningFromRecents_doesNotStartMonitoring() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        launch(reminderTapIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))

        assertNull(shadowOf(app).nextStartedService)
    }
}
