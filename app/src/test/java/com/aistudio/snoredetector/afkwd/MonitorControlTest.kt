package com.aistudio.snoredetector.afkwd

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.service.MonitorControl
import com.aistudio.snoredetector.afkwd.service.MonitorControl.Decision
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorControlTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() = app.getSharedPreferences(MonitorControl.PREFS_NAME, Context.MODE_PRIVATE)

    private fun decide(
        action: String? = MonitorControl.ACTION_START_MONITORING,
        automationEnabled: Boolean = true,
        hasMicrophonePermission: Boolean = true,
        isRunning: Boolean = false
    ) = MonitorControl.decide(action, automationEnabled, hasMicrophonePermission, isRunning)

    @Test
    fun startsOnlyWhenOptedInWithMicrophoneAccess() {
        assertEquals(Decision.START, decide())
        assertEquals(Decision.REJECT_DISABLED, decide(automationEnabled = false))
        assertEquals(Decision.REJECT_NO_MICROPHONE_PERMISSION, decide(hasMicrophonePermission = false))
    }

    @Test
    fun startingARunningMonitorIsANoOp() {
        assertEquals(Decision.IGNORE, decide(isRunning = true))
    }

    @Test
    fun stopNeedsTheOptInButNotTheMicrophone() {
        val stop = MonitorControl.ACTION_STOP_MONITORING

        assertEquals(Decision.STOP, decide(action = stop, hasMicrophonePermission = false))
        assertEquals(Decision.STOP, decide(action = stop, isRunning = true))
        assertEquals(Decision.REJECT_DISABLED, decide(action = stop, automationEnabled = false))
    }

    @Test
    fun unknownActionsAreIgnoredWithoutDisclosingTheOptInState() {
        assertEquals(Decision.IGNORE, decide(action = null))
        assertEquals(Decision.IGNORE, decide(action = "android.intent.action.VIEW", automationEnabled = false))
    }

    @Test
    fun automationIsOffByDefaultAndPersists() {
        assertFalse(MonitorControl.isAutomationEnabled(app))

        val viewModel = SnoreViewModel(app)
        assertFalse(viewModel.automationControlEnabled.value)

        viewModel.updateAutomationControlEnabled(true)
        assertTrue(MonitorControl.isAutomationEnabled(app))
        assertTrue(SnoreViewModel(app).automationControlEnabled.value)
    }

    @Test
    fun restoringDefaultsTurnsAutomationOff() {
        val viewModel = SnoreViewModel(app)
        viewModel.updateAutomationControlEnabled(true)

        viewModel.resetAllSettingsToDefaults()

        assertFalse(viewModel.automationControlEnabled.value)
        assertFalse(MonitorControl.isAutomationEnabled(app))
    }

    @Test
    fun startIntentDefaultsMatchTheDetectionDefaults() {
        val defaults = DetectionConfig()

        val intent = MonitorControl.buildStartIntent(app)

        assertEquals(SnoreDetectionService::class.java.name, intent.component?.className)
        assertEquals(defaults.rmsDbThreshold, intent.getFloatExtra("rmsDbThreshold", -1f), 0f)
        assertEquals(defaults.zcrThreshold, intent.getFloatExtra("zcrThreshold", -1f), 0f)
        assertEquals(defaults.bandEnergyThreshold, intent.getFloatExtra("bandEnergyThreshold", -1f), 0f)
        assertEquals(defaults.lowFreqRatioThreshold, intent.getFloatExtra("lowFreqRatioThreshold", -1f), 0f)
        assertEquals(defaults.minDurationSeconds, intent.getFloatExtra("minDurationSeconds", -1f), 0f)
        assertEquals(defaults.useZcr, intent.getBooleanExtra("useZcr", !defaults.useZcr))
        assertEquals(defaults.useBandEnergy, intent.getBooleanExtra("useBandEnergy", !defaults.useBandEnergy))
        assertEquals(defaults.useLowFreqRatio, intent.getBooleanExtra("useLowFreqRatio", !defaults.useLowFreqRatio))
        assertEquals(
            defaults.ignoreDuringMediaPlayback,
            intent.getBooleanExtra("ignoreDuringMediaPlayback", !defaults.ignoreDuringMediaPlayback)
        )
        assertEquals(AudioInputDevice.PHONE_MIC.name, intent.getStringExtra("audioInputName"))
    }

    @Test
    fun startButtonSendsEverySettingChangedInTheApp() {
        val viewModel = SnoreViewModel(app)
        viewModel.updateRmsDbThreshold(61.0f)
        viewModel.updateUseZcr(false)
        viewModel.updateZcrThreshold(0.3f)
        viewModel.updateUseBandEnergy(false)
        viewModel.updateBandEnergyThreshold(0.02f)
        viewModel.updateUseLowFreqRatio(false)
        viewModel.updateLowFreqRatioThreshold(0.7f)
        viewModel.updateMinDurationSeconds(2.5f)
        viewModel.updateSaveAudioClips(false)
        viewModel.updateNotifyOnSnore(true)
        viewModel.updateIgnoreDuringMediaPlayback(false)
        viewModel.updateSelectedAudioInput(AudioInputDevice(id = 7, name = "USB Mic", typeDescription = "USB", type = 0))

        viewModel.startServiceDetection()

        val started = shadowOf(app).nextStartedService
        assertEquals(61.0f, started.getFloatExtra("rmsDbThreshold", -1f), 0f)
        assertFalse(started.getBooleanExtra("useZcr", true))
        assertEquals(0.3f, started.getFloatExtra("zcrThreshold", -1f), 0f)
        assertFalse(started.getBooleanExtra("useBandEnergy", true))
        assertEquals(0.02f, started.getFloatExtra("bandEnergyThreshold", -1f), 0f)
        assertFalse(started.getBooleanExtra("useLowFreqRatio", true))
        assertEquals(0.7f, started.getFloatExtra("lowFreqRatioThreshold", -1f), 0f)
        assertEquals(2.5f, started.getFloatExtra("minDurationSeconds", -1f), 0f)
        assertFalse(started.getBooleanExtra("saveAudioClips", true))
        assertTrue(started.getBooleanExtra("notifyOnSnore", false))
        assertFalse(started.getBooleanExtra("ignoreDuringMediaPlayback", true))
        assertEquals(7, started.getIntExtra("audioInputId", -1))
        assertEquals("USB Mic", started.getStringExtra("audioInputName"))
    }
}
