package com.aistudio.snoredetector.afkwd

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.service.MonitorControl
import com.aistudio.snoredetector.afkwd.service.MonitorControlActivity
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorControlActivityTest {

    /** Behaves like a phone where Android refuses the foreground service start. */
    class RefusedStartActivity : MonitorControlActivity() {
        override fun startForegroundService(service: Intent): ComponentName? {
            throw IllegalStateException("startForegroundService() not allowed")
        }
    }

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun prefs() = app.getSharedPreferences(MonitorControl.PREFS_NAME, Context.MODE_PRIVATE)

    private fun enableAutomation() {
        prefs().edit().putBoolean(MonitorControl.PREF_AUTOMATION_ENABLED, true).commit()
    }

    private fun intentFor(action: String?) = Intent(app, MonitorControlActivity::class.java).setAction(action)

    private fun launch(action: String?) =
        Robolectric.buildActivity(MonitorControlActivity::class.java, intentFor(action)).setup().get()

    @Test
    fun startDoesNothingUntilTheUserOptsIn() {
        val activity = launch(MonitorControl.ACTION_START_MONITORING)

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(activity.isFinishing)
        assertFalse(shadowOf(activity).turnScreenOn)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Automation Control is off"))
    }

    @Test
    fun stopDoesNothingUntilTheUserOptsIn() {
        val activity = launch(MonitorControl.ACTION_STOP_MONITORING)

        assertNull(shadowOf(app).nextStoppedService)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun startWaitsForTheForegroundThenFinishes() {
        enableAutomation()
        val controller = Robolectric.buildActivity(
            MonitorControlActivity::class.java,
            intentFor(MonitorControl.ACTION_START_MONITORING)
        )

        controller.create().start()
        assertNull("Android refuses this start before the activity is resumed", shadowOf(app).nextStartedService)
        assertFalse(controller.get().isFinishing)
        assertTrue(shadowOf(controller.get()).showWhenLocked)
        assertTrue(shadowOf(controller.get()).turnScreenOn)

        controller.resume()
        val started = shadowOf(app).nextStartedService
        assertEquals(SnoreDetectionService::class.java.name, started.component?.className)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun startUsesSavedSettingsAndIgnoresCallerExtras() {
        enableAutomation()
        prefs().edit().putFloat("rmsDbThreshold", 72.0f).commit()
        val hostile = intentFor(MonitorControl.ACTION_START_MONITORING)
            .putExtra("rmsDbThreshold", 1.0f)
            .putExtra("saveAudioClips", false)
            .putExtra("audioInputName", "attacker")

        Robolectric.buildActivity(MonitorControlActivity::class.java, hostile).setup()

        val started = shadowOf(app).nextStartedService
        assertEquals(72.0f, started.getFloatExtra("rmsDbThreshold", 0f), 0f)
        assertTrue(started.getBooleanExtra("saveAudioClips", false))
        assertNotEquals("attacker", started.getStringExtra("audioInputName"))
    }

    @Test
    fun startWithoutMicrophonePermissionNeverReachesTheService() {
        enableAutomation()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val activity = launch(MonitorControl.ACTION_START_MONITORING)

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(activity.isFinishing)
        assertFalse(shadowOf(activity).turnScreenOn)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("microphone"))
    }

    @Test
    fun refusedStartIsReportedInsteadOfCrashing() {
        enableAutomation()

        val activity = Robolectric.buildActivity(
            RefusedStartActivity::class.java,
            intentFor(MonitorControl.ACTION_START_MONITORING)
        ).setup().get()

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(activity.isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Could not start"))
    }

    @Test
    fun startThatNeverReachedTheForegroundIsDropped() {
        enableAutomation()
        val controller = Robolectric.buildActivity(
            MonitorControlActivity::class.java,
            intentFor(MonitorControl.ACTION_START_MONITORING)
        )

        controller.create().start().stop()

        assertTrue(controller.get().isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Could not start"))

        // Waking the phone later must not start a session the user no longer expects.
        controller.resume()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun stopStopsTheServiceWithoutWakingTheScreen() {
        enableAutomation()

        val activity = launch(MonitorControl.ACTION_STOP_MONITORING)

        assertEquals(SnoreDetectionService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
        assertTrue(activity.isFinishing)
        assertFalse(shadowOf(activity).turnScreenOn)
        assertFalse(shadowOf(activity).showWhenLocked)
    }

    @Test
    fun otherActionsAreIgnored() {
        enableAutomation()

        val activity = launch("com.example.DELETE_EVERYTHING")

        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStoppedService)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun manifestExposesOnlyTheControlActivity() {
        val packageManager = app.packageManager
        val activityName = ComponentName(app, MonitorControlActivity::class.java)

        assertTrue(packageManager.getActivityInfo(activityName, 0).exported)
        assertFalse(
            "The recording service itself must stay private",
            packageManager.getServiceInfo(ComponentName(app, SnoreDetectionService::class.java), 0).exported
        )
        listOf(MonitorControl.ACTION_START_MONITORING, MonitorControl.ACTION_STOP_MONITORING).forEach { action ->
            val resolved = packageManager.resolveActivity(
                Intent(action).setPackage(app.packageName),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            assertNotNull("$action must resolve for automation apps", resolved)
            assertEquals(activityName.className, resolved?.activityInfo?.name)
        }
    }
}
