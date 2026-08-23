package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.dsp.SnoreAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoreDetectionLogicTest {

    private val analyzer = SnoreAnalyzer()

    @Test
    fun testDefaultDetectionConfigValues() {
        val config = DetectionConfig()
        assertEquals(55.0f, config.rmsDbThreshold, 0.001f)
        assertEquals(0.15f, config.zcrThreshold, 0.001f)
        assertEquals(0.015f, config.bandEnergyThreshold, 0.001f)
        assertEquals(0.65f, config.lowFreqRatioThreshold, 0.001f)
        assertEquals(1.0f, config.minDurationSeconds, 0.001f)
        assertTrue(config.useRms)
        assertTrue(config.useZcr)
        assertTrue(config.useBandEnergy)
        assertTrue(config.useLowFreqRatio)
    }

    @Test
    fun testSilenceProducesNoSnore() {
        val silence = ShortArray(1024) { 0 }
        val config = DetectionConfig()
        val result = analyzer.analyze(silence, config)

        assertFalse("Volume threshold should not be met on total silence", result.rmsThresholdMet)
        assertFalse("Silence should not be classified as snoring", result.isSnoring)
    }

    @Test
    fun testCustomDurationConfig() {
        val config = DetectionConfig(minDurationSeconds = 2.0f)
        assertEquals(2.0f, config.minDurationSeconds, 0.001f)
    }

    @Test
    fun testAmplitudeCriterionIsAlwaysMandatory() {
        // Generate a 200 Hz sine wave tone at low volume (below 55 dB threshold)
        val lowVolumeBuffer = ShortArray(1024) { i ->
            val sinVal = Math.sin(2.0 * Math.PI * 200.0 * i / 16000.0)
            (sinVal * 15.0).toInt().toShort() // Very low amplitude (~30 dB)
        }

        // Even with all optional methods disabled, amplitude criterion must still be evaluated
        val config = DetectionConfig(
            useZcr = false,
            useBandEnergy = false,
            useLowFreqRatio = false,
            rmsDbThreshold = 55.0f
        )
        val result = analyzer.analyze(lowVolumeBuffer, config)
        assertFalse("Low volume audio should not meet 55 dB threshold", result.rmsThresholdMet)
        assertFalse("Snoring should be false because mandatory amplitude criterion is not met", result.isSnoring)
    }

    @Test
    fun testChangingRmsDbThresholdDirectlyAffectsDetection() {
        // Generate a 200 Hz tone with amplitude ~100 (~66 dB)
        val buffer = ShortArray(1024) { i ->
            val sinVal = Math.sin(2.0 * Math.PI * 200.0 * i / 16000.0)
            (sinVal * 100.0).toInt().toShort()
        }

        // 1. With threshold at 50 dB: threshold is met (signal is ~66 dB >= 50 dB)
        val configPermissive = DetectionConfig(
            useZcr = false,
            useBandEnergy = false,
            useLowFreqRatio = false,
            rmsDbThreshold = 50.0f
        )
        val resultPermissive = analyzer.analyze(buffer, configPermissive)
        assertTrue("Signal should exceed 50 dB threshold", resultPermissive.rmsThresholdMet)
        assertTrue("Snoring should be detected when threshold is met", resultPermissive.isSnoring)

        // 2. With threshold increased to 80 dB: threshold is NOT met (signal is ~66 dB < 80 dB)
        val configStrict = DetectionConfig(
            useZcr = false,
            useBandEnergy = false,
            useLowFreqRatio = false,
            rmsDbThreshold = 80.0f
        )
        val resultStrict = analyzer.analyze(buffer, configStrict)
        assertFalse("Signal should not exceed strict 80 dB threshold", resultStrict.rmsThresholdMet)
        assertFalse("Snoring must not be detected when amplitude threshold is not met", resultStrict.isSnoring)
    }
}

