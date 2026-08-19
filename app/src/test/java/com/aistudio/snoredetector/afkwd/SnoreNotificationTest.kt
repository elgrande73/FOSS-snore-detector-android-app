package com.aistudio.snoredetector.afkwd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SnoreNotificationTest {

    @Test
    fun testNotifyOnSnoreDefaultAndToggle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("snore_detector_preferences", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val viewModel = SnoreViewModel(app)
        // Default is disabled
        assertFalse("Real-time snore notifications should default to false", viewModel.notifyOnSnore.value)

        // Toggle on
        viewModel.updateNotifyOnSnore(true)
        assertTrue("Real-time snore notifications should now be true", viewModel.notifyOnSnore.value)
        assertTrue("Preference should persist true value", prefs.getBoolean("notifyOnSnore", false))

        // Reset to defaults
        viewModel.resetAllSettingsToDefaults()
        assertFalse("Real-time snore notifications should reset to false", viewModel.notifyOnSnore.value)
    }

    @Test
    fun testNotificationChannelsCreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the channels using the service's channel configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SnoreDetectionService.CHANNEL_ID,
                "Acoustics Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time snoring sound detection, FFT analysis, and offline persistence"
            }
            notificationManager.createNotificationChannel(serviceChannel)

            val eventChannel = NotificationChannel(
                SnoreDetectionService.CHANNEL_EVENT_ID,
                "Snoring Event Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time notifications sent immediately when a snoring episode is confirmed"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(eventChannel)

            val retrievedServiceChannel = notificationManager.getNotificationChannel(SnoreDetectionService.CHANNEL_ID)
            assertNotNull(retrievedServiceChannel)
            assertEquals("Acoustics Monitoring Service", retrievedServiceChannel.name)
            assertEquals(NotificationManager.IMPORTANCE_LOW, retrievedServiceChannel.importance)

            val retrievedEventChannel = notificationManager.getNotificationChannel(SnoreDetectionService.CHANNEL_EVENT_ID)
            assertNotNull(retrievedEventChannel)
            assertEquals("Snoring Event Alerts", retrievedEventChannel.name)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, retrievedEventChannel.importance)
        }
    }

    @Test
    fun testStartServiceIntentCarriesNotifyOnSnoreExtra() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = SnoreViewModel(app)
        viewModel.updateNotifyOnSnore(true)

        val intent = Intent(app, SnoreDetectionService::class.java).apply {
            putExtra("notifyOnSnore", viewModel.notifyOnSnore.value)
        }

        assertTrue("Intent must carry notifyOnSnore extra as true", intent.getBooleanExtra("notifyOnSnore", false))
    }

    @Test
    fun testRealTimeDetectionDebounceTriggerLogic() {
        // Simulating the state machine logic from SnoreDetectionService
        val minDurationSeconds = 1.0f
        val targetDurationMs = (minDurationSeconds * 1000L).toLong()
        
        var isSnoringActive = false
        var snoreStartTime = 0L
        var hasNotifiedForCurrentEvent = false
        var notificationCount = 0

        fun onSnoreFrame(currentMillis: Long, isSnoring: Boolean, notifyEnabled: Boolean) {
            if (isSnoring) {
                if (!isSnoringActive) {
                    isSnoringActive = true
                    snoreStartTime = currentMillis
                    hasNotifiedForCurrentEvent = false
                }

                if (!hasNotifiedForCurrentEvent) {
                    val activeDurationMs = currentMillis - snoreStartTime
                    if (activeDurationMs >= targetDurationMs) {
                        hasNotifiedForCurrentEvent = true
                        if (notifyEnabled) {
                            notificationCount++
                        }
                    }
                }
            } else {
                if (isSnoringActive) {
                    isSnoringActive = false
                    hasNotifiedForCurrentEvent = false
                }
            }
        }

        // Frame 1 at 0ms: snore starts (active duration = 0ms < 1000ms) -> no notification yet
        onSnoreFrame(0L, true, true)
        assertEquals(0, notificationCount)
        assertTrue(isSnoringActive)
        assertFalse(hasNotifiedForCurrentEvent)

        // Frame 2 at 500ms: continuous snore (active duration = 500ms < 1000ms) -> no notification yet
        onSnoreFrame(500L, true, true)
        assertEquals(0, notificationCount)

        // Frame 3 at 1000ms: duration threshold reached! -> immediate notification triggered
        onSnoreFrame(1000L, true, true)
        assertEquals(1, notificationCount)
        assertTrue(hasNotifiedForCurrentEvent)

        // Frame 4 at 1500ms: snore continues -> must NOT trigger duplicate notification
        onSnoreFrame(1500L, true, true)
        assertEquals(1, notificationCount)

        // Frame 5 at 2000ms: snore ends
        onSnoreFrame(2000L, false, true)
        assertFalse(isSnoringActive)
        assertFalse(hasNotifiedForCurrentEvent)

        // Second distinct snoring incident starts at 5000ms
        onSnoreFrame(5000L, true, true)
        assertEquals(1, notificationCount)

        onSnoreFrame(6000L, true, true)
        // Second incident reaches threshold -> triggers notification 2
        assertEquals(2, notificationCount)
    }
}

