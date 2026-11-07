package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.data.SnoreScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SnoreScoreTest {

    @Test
    fun quietNightScoresZero() {
        val result = SnoreScore.calculate(480.0, 0.0, 0, 0.0, null)
        assertEquals(0, result?.score)
    }

    @Test
    fun snoringThroughoutScoresOneHundred() {
        val result = SnoreScore.calculate(480.0, 480.0, 960, 3600.0, 40.0)
        assertEquals(100, result?.score)
    }

    @Test
    fun scoreFollowsTheDocumentedFormula() {
        // 8 h monitored: 60 snoring minutes = 7.5 min/h, 120 episodes = 15/h, 150 s longest, 15 dB loud.
        val result = SnoreScore.calculate(480.0, 60.0, 120, 150.0, 15.0)
        assertNotNull(result)
        result!!
        assertEquals(7.5, result.snoreIndex, 0.0001)
        assertEquals(15.0, result.episodesPerHour, 0.0001)
        assertEquals(15.0, result.snoreIndexPoints, 0.0001)   // 60 × 7.5/30
        assertEquals(5.0, result.episodePoints, 0.0001)       // 20 × 15/60
        assertEquals(2.5, result.longestEpisodePoints, 0.0001) // 10 × 150/600
        assertEquals(5.0, result.loudnessPoints, 0.0001)      // 10 × 15/30
        assertEquals(28, result.score)                        // 27.5 rounded
    }

    @Test
    fun sameSnoringOverLongerMonitoringScoresLower() {
        val short = SnoreScore.calculate(120.0, 30.0, 30, 60.0, 10.0)!!
        val long = SnoreScore.calculate(480.0, 30.0, 30, 60.0, 10.0)!!
        assertEquals(4.0 * long.snoreIndex, short.snoreIndex, 0.0001)
        assert(long.score < short.score)
    }

    @Test
    fun missingLoudnessAddsNoPoints() {
        val result = SnoreScore.calculate(480.0, 60.0, 120, 150.0, null)!!
        assertEquals(0.0, result.loudnessPoints, 0.0001)
        assertEquals(23, result.score) // 22.5 rounded
    }

    @Test
    fun negativeLoudnessIsClamped() {
        val result = SnoreScore.calculate(480.0, 0.0, 0, 0.0, -10.0)!!
        assertEquals(0, result.score)
    }

    @Test
    fun noMonitoredTimeGivesNoScore() {
        assertNull(SnoreScore.calculate(0.0, 5.0, 3, 10.0, 10.0))
    }
}
