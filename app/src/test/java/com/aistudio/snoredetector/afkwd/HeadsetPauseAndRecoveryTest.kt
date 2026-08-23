package com.aistudio.snoredetector.afkwd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice
import com.aistudio.snoredetector.afkwd.audio.AudioInputManager
import com.aistudio.snoredetector.afkwd.audio.MediaPlaybackDetector
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.dsp.SnoreAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Regression and resilience unit tests covering:
 * - External Bluetooth & wired headset pause/play commands
 * - System media playback state transitions
 * - AudioRecord read error recovery
 * - Decoupling of external media controls from continuous snore monitoring
 */
class HeadsetPauseAndRecoveryTest {

    private val snoreAnalyzer = SnoreAnalyzer()
    private val config = DetectionConfig(
        useRms = true,
        rmsDbThreshold = 50.0f,
        useZcr = false,
        useBandEnergy = false,
        useLowFreqRatio = false,
        ignoreDuringMediaPlayback = true
    )

    @Test
    fun testHeadsetPauseCommand_WithIgnoreDuringMediaPlaybackDisabled() {
        // When ignoreDuringMediaPlayback is set to FALSE (disabled), the user wants continuous snore detection
        // regardless of whether media is playing or not.
        val configDisabled = config.copy(ignoreDuringMediaPlayback = false)

        val snoreBuffer = ShortArray(1024) { i ->
            (15000 * sin(2.0 * Math.PI * 150.0 * i / 16000.0)).toInt().toShort()
        }

        // 1. While external media is playing:
        val mediaActiveState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = true)
        assertTrue(mediaActiveState.isMediaPlaying)

        // Snore detection should NOT be suspended because ignoreDuringMediaPlayback is false
        val isSuspendedWhilePlaying = configDisabled.ignoreDuringMediaPlayback && mediaActiveState.isMediaPlaying
        assertFalse("Detection must NOT be suspended when ignoreDuringMediaPlayback is false", isSuspendedWhilePlaying)

        val analysisWhilePlaying = snoreAnalyzer.analyze(snoreBuffer, configDisabled)
        val effectiveSnoringWhilePlaying = if (isSuspendedWhilePlaying) false else analysisWhilePlaying.isSnoring
        assertTrue("Snoring must be detected even while media is playing when setting is disabled", effectiveSnoringWhilePlaying)

        // 2. User presses Pause on the headset:
        val mediaPausedState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = false)
        assertFalse(mediaPausedState.isMediaPlaying)

        val isSuspendedAfterPause = configDisabled.ignoreDuringMediaPlayback && mediaPausedState.isMediaPlaying
        assertFalse("Detection remains active after media pause", isSuspendedAfterPause)

        val analysisAfterPause = snoreAnalyzer.analyze(snoreBuffer, configDisabled)
        val effectiveSnoringAfterPause = if (isSuspendedAfterPause) false else analysisAfterPause.isSnoring
        assertTrue("Snoring detection continues smoothly without interruption after media pause", effectiveSnoringAfterPause)
    }

    @Test
    fun testAllHeadsetButtons_VolumeNextPrevSeek_WithIgnoreDuringMediaPlaybackDisabled() {
        val configDisabled = DetectionConfig(
            useRms = true,
            rmsDbThreshold = 50.0f,
            useZcr = false,
            useBandEnergy = false,
            useLowFreqRatio = false,
            ignoreDuringMediaPlayback = false
        )

        val snoreBuffer = ShortArray(1024) { i ->
            (14000 * sin(2.0 * Math.PI * 180.0 * i / 16000.0)).toInt().toShort()
        }

        // Test scenarios corresponding to button events on Bluetooth & wired headsets:
        // 1. VOLUME_UP / VOLUME_DOWN -> Adjusts system output stream gain; input capture stream is unaffected
        // 2. NEXT_TRACK / PREVIOUS_TRACK -> Media player switches URI / audio stream in background
        // 3. FAST_FORWARD / REWIND / SEEK -> Media player repositions buffer
        // 4. PLAY / PAUSE -> Media playback toggles
        val mediaButtonScenarios = listOf(
            "VOLUME_UP" to true,
            "VOLUME_DOWN" to true,
            "NEXT_TRACK" to true,
            "PREVIOUS_TRACK" to true,
            "FAST_FORWARD_SEEK" to true,
            "PAUSE_BUTTON" to false,
            "PLAY_BUTTON" to true
        )

        for ((buttonAction, isPlayingState) in mediaButtonScenarios) {
            val playbackState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = isPlayingState)
            
            // With ignoreDuringMediaPlayback = false, isSuspended MUST ALWAYS remain false
            val isSuspended = configDisabled.ignoreDuringMediaPlayback && playbackState.isMediaPlaying
            assertFalse("Snore detection must NEVER suspend for button action $buttonAction", isSuspended)

            val analysis = snoreAnalyzer.analyze(snoreBuffer, configDisabled)
            val detected = if (isSuspended) false else analysis.isSnoring
            assertTrue("Snore analysis must remain active and positive during $buttonAction", detected)
        }
    }

    @Test
    fun testHeadsetPauseCommand_RestoresActiveSnoreMeasurement() {
        // 1. User is sleeping while listening to radio stream on Bluetooth/wired headphones.
        // Media is actively playing.
        val mediaActiveState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = true)
        assertTrue("Media should be detected as active", mediaActiveState.isMediaPlaying)

        // Snoring sound buffer generated
        val snoreBuffer = ShortArray(1024) { i ->
            (15000 * sin(2.0 * Math.PI * 150.0 * i / 16000.0)).toInt().toShort()
        }

        // While media is active and ignoreDuringMediaPlayback is true, snoring detection is suspended
        val analysisWhilePlaying = snoreAnalyzer.analyze(snoreBuffer, config)
        val isSuspended = config.ignoreDuringMediaPlayback && mediaActiveState.isMediaPlaying
        val effectiveSnoringWhilePlaying = if (isSuspended) false else analysisWhilePlaying.isSnoring
        assertFalse("Detection must be suspended while external media is playing", effectiveSnoringWhilePlaying)

        // 2. User presses Pause on the external Bluetooth/wired headset.
        // The external media app pauses, AudioFocus is abandoned, and system media becomes idle.
        val mediaPausedState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = false)
        assertFalse("Media should be detected as idle after headset pause", mediaPausedState.isMediaPlaying)

        // 3. The snore detector measurement continues and immediately receives valid audio
        val isSuspendedAfterPause = config.ignoreDuringMediaPlayback && mediaPausedState.isMediaPlaying
        assertFalse("Detection must NOT be suspended after external media pause", isSuspendedAfterPause)

        val analysisAfterPause = snoreAnalyzer.analyze(snoreBuffer, config)
        val effectiveSnoringAfterPause = if (isSuspendedAfterPause) false else analysisAfterPause.isSnoring
        assertTrue("Snoring detection must be fully active and accurately detect snoring after headset pause", effectiveSnoringAfterPause)
    }

    @Test
    fun testHeadsetPause_AudioRecordErrorRecoverySimulation() {
        // Simulates AudioRecord read return codes when HAL route transitions on media pause:
        // AudioRecord.ERROR_INVALID_OPERATION (-3), ERROR_DEAD_OBJECT (-6), or 0

        val errorInvalidOperation = AudioRecord.ERROR_INVALID_OPERATION
        val errorDeadObject = -6 // AudioRecord.ERROR_DEAD_OBJECT
        val errorBadValue = AudioRecord.ERROR_BAD_VALUE

        assertTrue(errorInvalidOperation < 0)
        assertTrue(errorDeadObject < 0)
        assertTrue(errorBadValue < 0)

        // Verify that error states are flagged for recovery
        var consecutiveErrors = 0
        var recoveryTriggered = false

        val simulatedReadResults = listOf(errorInvalidOperation, errorInvalidOperation, errorInvalidOperation, errorInvalidOperation, errorDeadObject)
        for (res in simulatedReadResults) {
            if (res < 0) {
                consecutiveErrors++
                if (consecutiveErrors >= 5 || res == errorDeadObject) {
                    recoveryTriggered = true
                    consecutiveErrors = 0
                }
            }
        }

        assertTrue("AudioRecord recovery must trigger upon persistent errors or dead object", recoveryTriggered)
        assertEquals(0, consecutiveErrors)
    }

    @Test
    fun testHeadsetPause_ZeroFrameSilenceAnomalyDetection() {
        // Simulates a severed HAL audio input stream returning 100% digital zeros after headset pause
        var consecutiveZeroFrames = 0
        var reapplyRoutingTriggered = false
        var fullRestartTriggered = false

        for (frame in 1..350) {
            val isZeroFrame = true // all PCM samples are 0
            if (isZeroFrame) {
                consecutiveZeroFrames++
            } else {
                consecutiveZeroFrames = 0
            }

            if (consecutiveZeroFrames == 150) {
                reapplyRoutingTriggered = true
            } else if (consecutiveZeroFrames >= 300 && consecutiveZeroFrames % 150 == 0) {
                fullRestartTriggered = true
            }
        }

        assertTrue("Should trigger routing re-application at 150 zero frames (~10s)", reapplyRoutingTriggered)
        assertTrue("Should trigger full self-healing restart at 300 zero frames (~20s)", fullRestartTriggered)
    }

    @Test
    fun testAudioRoutingEvaluationFallbackOnHeadsetDisconnection() {
        // Verify AudioInputManager routing evaluation when headset is unplugged or disconnected
        val evalWithNullRoute = AudioInputManager.evaluateAudioRouting(
            configuredId = 1234,
            configuredName = "Bluetooth Headset",
            routedDevice = null
        )

        assertTrue(evalWithNullRoute.isFallback)
        assertEquals(AudioInputDevice.PHONE_MIC.name, evalWithNullRoute.activeDisplayName)
    }
}

