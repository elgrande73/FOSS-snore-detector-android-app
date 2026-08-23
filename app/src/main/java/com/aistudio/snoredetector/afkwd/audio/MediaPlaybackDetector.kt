package com.aistudio.snoredetector.afkwd.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result evaluation of media playback status on the system.
 */
data class MediaPlaybackStatus(
    val isMediaPlaying: Boolean,
    val description: String,
    val activeMediaCount: Int = 0
)

/**
 * Dedicated detector for real-time monitoring of active media playback across the Android OS.
 *
 * Distinguishes between:
 * 1. Active media playback (Podcasts, Music, Audiobooks, Video, Games) which produces sustained audio
 *    and can falsely trigger snoring detection.
 * 2. Paused / Stopped media players and sleep timers that have expired.
 * 3. Transient system & alert audio (Notifications, Ringtones, Alarms, Navigation prompts, System clicks)
 *    which MUST NOT suppress snoring detection.
 *
 * Utilizes:
 * - AudioManager.registerAudioPlaybackCallback (Android 8.0+ / API 26+) for zero-latency, zero-permission
 *   system-wide playback lifecycle events.
 * - AudioManager.isMusicActive() for real-time PCM stream validation and backward compatibility.
 */
class MediaPlaybackDetector(
    private val context: Context,
    private val onPlaybackStateChanged: ((Boolean) -> Unit)? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _isMediaPlaying = MutableStateFlow(false)
    val isMediaPlaying: StateFlow<Boolean> = _isMediaPlaying.asStateFlow()

    private val _playbackStatus = MutableStateFlow(
        MediaPlaybackStatus(isMediaPlaying = false, description = "No media playing")
    )
    val playbackStatus: StateFlow<MediaPlaybackStatus> = _playbackStatus.asStateFlow()

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var isMonitoring = false

    companion object {
        private const val TAG = "MediaPlaybackDetector"

        /**
         * Pure functional evaluator to assess whether an AudioPlaybackConfiguration represents
         * active media playback (music, podcasts, audiobooks, games) vs transient notifications/alarms/calls.
         */
        fun isRelevantMediaUsage(usage: Int): Boolean {
            return when (usage) {
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN -> true
                else -> false
            }
        }

        /**
         * Evaluate a list of AudioPlaybackConfigurations combined with isMusicActive.
         */
        fun evaluatePlaybackState(
            configs: List<AudioPlaybackConfiguration>?,
            isMusicActive: Boolean
        ): MediaPlaybackStatus {
            // When isMusicActive is false, no media player is actively rendering audio on STREAM_MUSIC.
            // Even if a paused media player's AudioPlaybackConfiguration is still retained in memory,
            // the system is not actively playing audio. Thus snoring detection must be ACTIVE.
            if (!isMusicActive) {
                return MediaPlaybackStatus(
                    isMediaPlaying = false,
                    description = "No media playing",
                    activeMediaCount = 0
                )
            }

            // When isMusicActive is true, verify whether active playback configurations exist.
            if (configs.isNullOrEmpty()) {
                // When configs list is empty or unavailable (API < 26), isMusicActive=true indicates media stream playback.
                return MediaPlaybackStatus(
                    isMediaPlaying = true,
                    description = "Media playback active (music stream)",
                    activeMediaCount = 1
                )
            }

            // Filter for media/entertainment streams (excluding transient system alerts, alarms, ringtones)
            val mediaConfigs = configs.filter {
                val usage = it.audioAttributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
                isRelevantMediaUsage(usage)
            }

            // If there are media configurations present while music is active, playback is active.
            // If all present configs are explicitly non-media (e.g. notifications/alarms), do not suppress snoring detection.
            val isPlaying = mediaConfigs.isNotEmpty()

            val desc = if (isPlaying) {
                "Media playback active (${mediaConfigs.size} stream${if (mediaConfigs.size > 1) "s" else ""})"
            } else {
                "No media playing"
            }

            return MediaPlaybackStatus(
                isMediaPlaying = isPlaying,
                description = desc,
                activeMediaCount = mediaConfigs.size
            )
        }
    }

    /**
     * Start observing system audio playback state changes.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        val initialIsMusicActive = audioManager?.isMusicActive ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
            val initialConfigs = try {
                audioManager.activePlaybackConfigurations
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get initial active playback configs: ${e.message}")
                emptyList()
            }

            val initialStatus = evaluatePlaybackState(initialConfigs, initialIsMusicActive)
            updateState(initialStatus)

            playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    val currentMusicActive = audioManager.isMusicActive
                    val status = evaluatePlaybackState(configs, currentMusicActive)
                    Log.d(TAG, "AudioPlaybackCallback triggered: isMediaPlaying=${status.isMediaPlaying} (${status.description})")
                    updateState(status)
                }
            }

            try {
                audioManager.registerAudioPlaybackCallback(
                    playbackCallback!!,
                    Handler(Looper.getMainLooper())
                )
                Log.i(TAG, "Registered AudioPlaybackCallback successfully (initial isMediaPlaying=${initialStatus.isMediaPlaying})")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering AudioPlaybackCallback", e)
            }
        } else {
            // Fallback for API < 26
            val initialStatus = evaluatePlaybackState(null, initialIsMusicActive)
            updateState(initialStatus)
        }
    }

    /**
     * Poll/refresh current playback state on demand.
     */
    fun checkCurrentPlaybackState(): MediaPlaybackStatus {
        val musicActive = audioManager?.isMusicActive ?: false
        val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
            try {
                audioManager.activePlaybackConfigurations
            } catch (e: Exception) {
                null
            }
        } else null

        val status = evaluatePlaybackState(configs, musicActive)
        updateState(status)
        return status
    }

    private fun updateState(status: MediaPlaybackStatus) {
        val previous = _isMediaPlaying.value
        _isMediaPlaying.value = status.isMediaPlaying
        _playbackStatus.value = status
        if (previous != status.isMediaPlaying) {
            onPlaybackStateChanged?.invoke(status.isMediaPlaying)
        }
    }

    /**
     * Stop observing playback state changes.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            try {
                audioManager?.unregisterAudioPlaybackCallback(playbackCallback!!)
                Log.i(TAG, "Unregistered AudioPlaybackCallback")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering AudioPlaybackCallback: ${e.message}")
            }
            playbackCallback = null
        }

        _isMediaPlaying.value = false
        _playbackStatus.value = MediaPlaybackStatus(isMediaPlaying = false, description = "Monitoring stopped")
    }
}

