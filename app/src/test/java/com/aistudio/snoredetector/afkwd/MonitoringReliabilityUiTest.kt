package com.aistudio.snoredetector.afkwd

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.service.MonitoringSessionStore
import com.aistudio.snoredetector.afkwd.ui.MonitoringReliabilityBanner
import com.aistudio.snoredetector.afkwd.ui.MonitoringReliabilityCard
import com.aistudio.snoredetector.afkwd.ui.rememberMonitoringReliabilityState
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import com.aistudio.snoredetector.afkwd.ui.vendorGuideUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringReliabilityUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var store: MonitoringSessionStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = MonitoringSessionStore(app)
        store.pendingNotice = null
        store.isBatteryHintDismissed = false
    }

    private fun setBatteryOptimizationDisabled(disabled: Boolean) {
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(app.packageName, disabled)
    }

    private fun setBanner() {
        composeTestRule.setContent {
            MyApplicationTheme {
                val state = rememberMonitoringReliabilityState()
                if (state.hasBanner) MonitoringReliabilityBanner(state)
            }
        }
    }

    @Test
    fun hintIsShownWhileBatteryOptimizationIsOn() {
        setBatteryOptimizationDisabled(false)
        setBanner()
        composeTestRule.onNodeWithText("Keep monitoring running all night").assertIsDisplayed()
    }

    @Test
    fun nothingIsShownOnceTheAppIsExempt() {
        setBatteryOptimizationDisabled(true)
        setBanner()
        composeTestRule.onNodeWithTag("monitoring_reliability_banner").assertDoesNotExist()
    }

    @Test
    fun dismissingTheHintIsRemembered() {
        setBatteryOptimizationDisabled(false)
        setBanner()

        composeTestRule.onNodeWithText("Not now").performClick()

        composeTestRule.onNodeWithTag("monitoring_reliability_banner").assertDoesNotExist()
        assertTrue(store.isBatteryHintDismissed)
    }

    @Test
    fun batterySettingsButtonOpensTheSystemList() {
        setBatteryOptimizationDisabled(false)
        setBanner()

        composeTestRule.onNodeWithText("Battery settings").performClick()

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, started.action)
    }

    @Test
    fun tipsButtonOpensTheVendorGuide() {
        setBatteryOptimizationDisabled(false)
        setBanner()

        composeTestRule.onNodeWithText("Tips for this phone").performClick()

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertTrue(started.data.toString().startsWith("https://dontkillmyapp.com/"))
    }

    @Test
    fun interruptionStaysUntilDismissedEvenWhenExempt() {
        setBatteryOptimizationDisabled(true)
        store.pendingNotice = "Monitoring interrupted 02:14–07:00."
        setBanner()

        composeTestRule
            .onNodeWithText("Monitoring interrupted 02:14–07:00.", substring = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Dismiss").performClick()

        composeTestRule.onNodeWithTag("monitoring_reliability_banner").assertDoesNotExist()
        assertEquals(null, store.pendingNotice)
    }

    @Test
    fun settingsCardStaysAvailableAfterTheHintWasDismissed() {
        setBatteryOptimizationDisabled(false)
        store.isBatteryHintDismissed = true
        composeTestRule.setContent { MyApplicationTheme { MonitoringReliabilityCard() } }

        composeTestRule.onNodeWithText("Battery optimization is on", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery settings").assertIsDisplayed()
    }

    @Test
    fun settingsCardReportsTheExemption() {
        setBatteryOptimizationDisabled(true)
        composeTestRule.setContent { MyApplicationTheme { MonitoringReliabilityCard() } }

        composeTestRule.onNodeWithText("Battery optimization is off", substring = true).assertIsDisplayed()
    }

    @Test
    fun vendorGuideGoesToTheMakersPageWhenThereIsOne() {
        assertEquals("https://dontkillmyapp.com/realme", vendorGuideUrl("realme"))
        assertEquals("https://dontkillmyapp.com/samsung", vendorGuideUrl(" Samsung "))
        assertEquals("https://dontkillmyapp.com/", vendorGuideUrl("Fairphone"))
    }
}
