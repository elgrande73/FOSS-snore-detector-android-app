package com.aistudio.snoredetector.afkwd

import android.Manifest
import android.app.Application
import android.media.AudioRecord
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.audio.SetupCheckProblem
import com.aistudio.snoredetector.afkwd.audio.SetupCheckState
import com.aistudio.snoredetector.afkwd.dsp.CriterionResult
import com.aistudio.snoredetector.afkwd.dsp.CriterionStatus
import com.aistudio.snoredetector.afkwd.dsp.SetupCheckReport
import com.aistudio.snoredetector.afkwd.dsp.SetupCriterion
import com.aistudio.snoredetector.afkwd.dsp.SetupVerdict
import com.aistudio.snoredetector.afkwd.ui.SetupCheckCardContent
import com.aistudio.snoredetector.afkwd.ui.StopSetupCheckWhenLeaving
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SetupCheckCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val report = SetupCheckReport(
        noiseFloorDb = 41.26f,
        noiseFloorReachesVolumeThreshold = false,
        rmsDbThreshold = 55f,
        criteria = listOf(
            CriterionResult(SetupCriterion.VOLUME, CriterionStatus.PASS, 62.04f, 55f),
            CriterionResult(SetupCriterion.ZCR, CriterionStatus.FAIL, 0.2f, 0.15f),
            CriterionResult(SetupCriterion.BAND_ENERGY, CriterionStatus.DISABLED, null, 0.015f),
            CriterionResult(SetupCriterion.LOW_FREQ_RATIO, CriterionStatus.PASS, 0.72f, 0.65f),
            CriterionResult(SetupCriterion.DURATION, CriterionStatus.FAIL, 0.4f, 1f)
        ),
        verdict = SetupVerdict.NOT_DETECTED,
        pausedForMedia = false
    )

    private fun setCard(
        state: SetupCheckState,
        isMonitoring: Boolean = false,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onRequestPermission: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MyApplicationTheme {
                SetupCheckCardContent(state, isMonitoring, onStart, onStop, onRequestPermission)
            }
        }
    }

    @Test
    fun idleCardOffersTheCheck() {
        var started = 0
        setCard(SetupCheckState.Idle, onStart = { started++ })

        composeTestRule.onNodeWithText("Test your setup").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup_check_start").assertIsEnabled().performClick()

        assertEquals(1, started)
    }

    @Test
    fun checkCannotBeStartedWhileMonitoring() {
        var started = 0
        setCard(SetupCheckState.Idle, isMonitoring = true, onStart = { started++ })

        composeTestRule.onNodeWithTag("setup_check_start").assertIsNotEnabled().performClick()

        assertEquals(0, started)
        composeTestRule.onNodeWithText("Stop monitoring on the Dashboard to run the check.").assertIsDisplayed()
    }

    @Test
    fun runningCheckPromptsForQuietThenForTheSoundAndCanBeCancelled() {
        var state by mutableStateOf<SetupCheckState>(SetupCheckState.Running(3))
        var stopped = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                SetupCheckCardContent(state, false, {}, { stopped++ }, {})
            }
        }

        composeTestRule.onNodeWithTag("setup_check_prompt").assertTextContains("Stay quiet", substring = true)
        composeTestRule.onNodeWithText("27 s left").assertIsDisplayed()

        state = SetupCheckState.Running(10)
        composeTestRule.onNodeWithTag("setup_check_prompt").assertTextContains("Make your test sound now")

        composeTestRule.onNodeWithTag("setup_check_cancel").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun reportShowsNoiseFloorAndEveryCriterionWithItsOutcome() {
        setCard(SetupCheckState.Finished(report))

        composeTestRule.onNodeWithTag("setup_check_noise_floor").assertTextContains("Background noise: 41.3 dB")
        composeTestRule.onNodeWithText("62.0 dB, needs ≥ 55.0 dB").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.200, needs ≤ 0.150").assertIsDisplayed()
        composeTestRule.onNodeWithText("needs ≥ 0.0150").assertIsDisplayed()
        composeTestRule.onNodeWithText("72%, needs ≥ 65%").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.4 s, needs ≥ 1.0 s").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup_check_row_VOLUME", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Off").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup_check_verdict")
            .assertTextContains("would not be logged", substring = true)
        composeTestRule.onNodeWithTag("setup_check_start").assertTextContains("Run Again")
    }

    @Test
    fun mediaAndLoudBackgroundAreExplained() {
        setCard(
            SetupCheckState.Finished(report.copy(pausedForMedia = true, noiseFloorReachesVolumeThreshold = true))
        )

        composeTestRule.onNodeWithText("Media was playing", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Background noise reaches", substring = true).assertIsDisplayed()
    }

    @Test
    fun silentInputHidesMeaninglessMeasurements() {
        setCard(SetupCheckState.Finished(report.copy(verdict = SetupVerdict.NO_SIGNAL)))

        composeTestRule.onNodeWithTag("setup_check_verdict")
            .assertTextContains("only delivered silence", substring = true)
        composeTestRule.onNodeWithTag("setup_check_noise_floor").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sound Volume").assertDoesNotExist()
    }

    @Test
    fun missingPermissionOffersTheRequestInsteadOfTheCheck() {
        var requested = 0
        var started = 0
        setCard(
            SetupCheckState.Failed(SetupCheckProblem.PERMISSION_MISSING),
            onStart = { started++ },
            onRequestPermission = { requested++ }
        )

        composeTestRule.onNodeWithText("The setup check needs microphone access.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup_check_start")
            .assertTextContains("Grant Microphone Access")
            .performClick()

        assertEquals(1, requested)
        assertEquals(0, started)
    }

    @Test
    fun micFailureCanBeRetried() {
        var started = 0
        setCard(SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE), onStart = { started++ })

        composeTestRule.onNodeWithTag("setup_check_start").assertTextContains("Run Again").performClick()

        assertEquals(1, started)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun leavingTheForegroundStopsTheCheck() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var left = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                StopSetupCheckWhenLeaving(onLeave = { left++ })
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(0, left)

        composeTestRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(1, left)
    }

    @Test
    fun leavingTheScreenStopsTheCheck() {
        var left = 0
        var onScreen by mutableStateOf(true)
        composeTestRule.setContent {
            if (onScreen) StopSetupCheckWhenLeaving(onLeave = { left++ })
        }
        composeTestRule.waitForIdle()
        assertEquals(0, left)

        onScreen = false
        composeTestRule.waitForIdle()

        assertEquals(1, left)
    }

    /** Real ViewModel, SettingsTab and AudioRecord path, on Robolectric's fake recorder. */
    private inner class LiveCheck {
        val record = AtomicReference<AudioRecord?>(null)
        val viewModel: SnoreViewModel

        init {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            ShadowAudioRecord.setSourceProvider { audioRecord ->
                record.set(audioRecord)
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                        Thread.sleep(50) // stays Running for the whole test
                        return size
                    }
                }
            }
            viewModel = SnoreViewModel(app)
        }

        fun awaitRunning() = composeTestRule.waitUntil(5_000) {
            viewModel.setupCheck.state.value is SetupCheckState.Running && record.get() != null
        }
    }

    private var liveCheck: LiveCheck? = null

    @After
    fun stopLiveCheck() {
        ShadowAudioRecord.clearSource()
        liveCheck?.viewModel?.setupCheck?.stop()
    }

    @Test
    fun checkSurvivesScrollingTheCardOutOfViewAndStopsWhenTheTabLeaves() {
        val live = LiveCheck().also { liveCheck = it }
        var onSettingsTab by mutableStateOf(true)
        composeTestRule.setContent {
            MyApplicationTheme {
                if (onSettingsTab) SettingsTab(live.viewModel)
            }
        }

        composeTestRule.onNodeWithTag("setup_check_start").performClick()
        live.awaitRunning()

        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("general_section_header"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("setup_check_card").assertDoesNotExist()
        assertTrue(live.viewModel.setupCheck.state.value is SetupCheckState.Running)

        onSettingsTab = false
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(5_000) { live.viewModel.setupCheck.state.value == SetupCheckState.Idle }

        assertEquals(AudioRecord.STATE_UNINITIALIZED, live.record.get()?.state)
    }
}
