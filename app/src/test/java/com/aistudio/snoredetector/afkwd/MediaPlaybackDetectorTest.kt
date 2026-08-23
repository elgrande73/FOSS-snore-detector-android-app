package com.aistudio.snoredetector.afkwd

import android.media.AudioAttributes
import com.aistudio.snoredetector.afkwd.audio.MediaPlaybackDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackDetectorTest {

    @Test
    fun testRelevantMediaUsageClassification() {
        // Media, game, and unknown streams should be treated as media
        assertTrue(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_MEDIA))
        assertTrue(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_GAME))
        assertTrue(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_UNKNOWN))

        // System, notification, alarm, voice call audio should NOT be treated as sustained media playback
        assertFalse(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_NOTIFICATION))
        assertFalse(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE))
        assertFalse(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_ALARM))
        assertFalse(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        assertFalse(MediaPlaybackDetector.isRelevantMediaUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
    }

    @Test
    fun testEvaluatePlaybackStateWhenNoConfigsAvailable() {
        // When configs are empty/null and music is active (e.g. legacy Android or background stream)
        val statusPlaying = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = true)
        assertTrue(statusPlaying.isMediaPlaying)
        assertEquals(1, statusPlaying.activeMediaCount)

        // When configs are empty/null and music is NOT active (e.g. app closed, playback stopped)
        val statusIdle = MediaPlaybackDetector.evaluatePlaybackState(emptyList(), isMusicActive = false)
        assertFalse(statusIdle.isMediaPlaying)
        assertEquals(0, statusIdle.activeMediaCount)
    }

    @Test
    fun testPlaybackStateTransitionsFromPlayingToPaused() {
        // 1. Initially active podcast playback (isMusicActive = true)
        val activeStatus = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = true)
        assertTrue(activeStatus.isMediaPlaying)

        // 2. Podcast paused / radio stopped / sleep timer expired (isMusicActive = false)
        val pausedStatus = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = false)
        assertFalse(pausedStatus.isMediaPlaying)
        assertEquals("No media playing", pausedStatus.description)

        // 3. Playback resumed (isMusicActive = true)
        val resumedStatus = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = true)
        assertTrue(resumedStatus.isMediaPlaying)
    }

    @Test
    fun testRapidPlaybackTransitionsDoNotGetStuck() {
        var isPlaying = false
        for (i in 0 until 10) {
            isPlaying = (i % 2 == 0)
            val status = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = isPlaying)
            assertEquals(isPlaying, status.isMediaPlaying)
        }
        // Final transition to stopped/idle
        val finalStatus = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = false)
        assertFalse(finalStatus.isMediaPlaying)
    }
}

