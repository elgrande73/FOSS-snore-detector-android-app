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
}
