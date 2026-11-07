package com.aistudio.snoredetector.afkwd

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.schedule.AutoStopMode
import com.aistudio.snoredetector.afkwd.schedule.ScheduleAlarms
import com.aistudio.snoredetector.afkwd.schedule.ScheduleReceiver
import com.aistudio.snoredetector.afkwd.schedule.ScheduleSettings
import com.aistudio.snoredetector.afkwd.ui.ScheduleSettingsCard
import com.aistudio.snoredetector.afkwd.ui.formatDuration
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences(ScheduleSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notifications = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private val reminderOn = ScheduleSettings(startReminderEnabled = true)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun nextAlarm() = shadowOf(alarmManager).nextScheduledAlarm

    @Test
    fun settings_defaultToOffAndSurviveAReload() {
        val defaults = ScheduleSettings.load(prefs)
        assertFalse(defaults.startReminderEnabled)
        assertFalse(defaults.autoStopEnabled)
        assertEquals(ScheduleSettings.ALL_DAYS, defaults.startDays)
        assertEquals(8 * 60, defaults.stopDurationMinutes)

        val changed = ScheduleSettings(
            startReminderEnabled = true,
            startMinuteOfDay = 21 * 60,
            startDays = emptySet(),
            autoStopEnabled = true,
            stopMode = AutoStopMode.AFTER_DURATION,
            stopMinuteOfDay = 6 * 60,
            stopDurationMinutes = 90
        )
        changed.saveTo(prefs)
        assertEquals(changed, ScheduleSettings.load(prefs))
    }

    @Test
    fun viewModel_schedulesTheAlarmAndResetClearsIt() {
        val viewModel = SnoreViewModel(app)
        assertNull("Nothing may be scheduled by default", nextAlarm())

        viewModel.updateScheduleSettings(reminderOn)
        assertNotNull(nextAlarm())

        viewModel.resetAllSettingsToDefaults()
        assertNull(nextAlarm())
        assertEquals(ScheduleSettings(), viewModel.scheduleSettings.value)
        assertEquals(ScheduleSettings(), ScheduleSettings.load(prefs))
    }

    @Test
    fun deniedExactAlarmPermissionStillSchedulesAReminder() {
        reminderOn.saveTo(prefs)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        ScheduleAlarms.reschedule(app)

        assertTrue(nextAlarm()!!.isAllowWhileIdle)
    }

    @Test
    fun receiver_restoresTheAlarmAfterReboot() {
        reminderOn.saveTo(prefs)
        assertNull(nextAlarm())

        ScheduleReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNotNull(nextAlarm())
    }

    @Test
    fun receiver_showsTheReminderSchedulesTheNextOneAndNeverStartsTheService() {
        reminderOn.saveTo(prefs)

        ScheduleReceiver().onReceive(app, Intent(ScheduleAlarms.ACTION_START_REMINDER))

        val notification = notifications.getNotification(ScheduleAlarms.REMINDER_NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals("Tap to start snore monitoring", shadowOf(notification).contentText)
        assertEquals(
            ScheduleAlarms.ACTION_START_MONITORING,
            shadowOf(notification.contentIntent).savedIntent.action
        )
        assertNotNull(nextAlarm())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun settingsSection_isCollapsedByDefaultAndExpands() {
        val viewModel = SnoreViewModel(app)
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsTab(viewModel)
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("schedule_section_header"))
        composeTestRule.onNodeWithTag("schedule_section_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("schedule_settings_card").assertDoesNotExist()

        composeTestRule.onNodeWithTag("schedule_section_header").performClick()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("schedule_settings_card"))
        composeTestRule.onNodeWithTag("schedule_start_switch").performClick()
        composeTestRule.waitForIdle()

        assertTrue(viewModel.scheduleSettings.value.startReminderEnabled)
        assertNotNull(nextAlarm())
    }

    @Test
    fun card_showsTheSafetyNoteAndTheNextReminder() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ScheduleSettingsCard(reminderOn, {})
            }
        }

        composeTestRule.onNodeWithText(
            "Monitoring always stops after 12 hours at the latest, regardless of the settings above. " +
                "This prevents unnecessary battery drain if you forget to stop it, for example after waking up."
        ).assertExists()
        composeTestRule.onNodeWithTag("schedule_next_reminder").assertExists()
    }

    @Test
    fun durationLabels() {
        assertEquals("8 hours", formatDuration(480))
        assertEquals("1 hour 30 min", formatDuration(90))
    }
}
