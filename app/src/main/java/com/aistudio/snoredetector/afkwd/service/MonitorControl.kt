package com.aistudio.snoredetector.afkwd.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice

/**
 * Starts and stops the monitor from the user's saved settings, so the in-app button and
 * [MonitorControlActivity] always launch the same session configuration.
 */
object MonitorControl {
    const val PREFS_NAME = "snore_detector_preferences"
    const val PREF_AUTOMATION_ENABLED = "automationControlEnabled"

    const val ACTION_START_MONITORING = "com.aistudio.snoredetector.afkwd.action.START_MONITORING"
    const val ACTION_STOP_MONITORING = "com.aistudio.snoredetector.afkwd.action.STOP_MONITORING"

    /** What an automation request should do. */
    enum class Decision {
        START,
        STOP,
        REJECT_DISABLED,
        REJECT_NO_MICROPHONE_PERMISSION,
        IGNORE
    }

    /**
     * Decides how to answer an automation request. Nothing happens unless the user opted in, and
     * starting twice is a no-op because a second start would add another 12-hour limit timer.
     */
    fun decide(
        action: String?,
        automationEnabled: Boolean,
        hasMicrophonePermission: Boolean,
        isRunning: Boolean
    ): Decision = when {
        action != ACTION_START_MONITORING && action != ACTION_STOP_MONITORING -> Decision.IGNORE
        !automationEnabled -> Decision.REJECT_DISABLED
        action == ACTION_STOP_MONITORING -> Decision.STOP
        isRunning -> Decision.IGNORE
        !hasMicrophonePermission -> Decision.REJECT_NO_MICROPHONE_PERMISSION
        else -> Decision.START
    }

    fun isAutomationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTOMATION_ENABLED, false)

    /** Builds the service start intent from saved settings only. */
    fun buildStartIntent(context: Context): Intent {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Intent(context, SnoreDetectionService::class.java).apply {
            putExtra("saveAudioClips", prefs.getBoolean("saveAudioClips", true))
            putExtra("notifyOnSnore", prefs.getBoolean("notifyOnSnore", false))
            putExtra("audioInputId", prefs.getInt("selectedAudioInputId", -1))
            putExtra(
                "audioInputName",
                prefs.getString("selectedAudioInputName", AudioInputDevice.PHONE_MIC.name) ?: AudioInputDevice.PHONE_MIC.name
            )
            putExtra("useRms", true) // Amplitude dB criterion is mandatory and always enabled
            putExtra("rmsDbThreshold", prefs.getFloat("rmsDbThreshold", 55.0f))
            putExtra("useZcr", prefs.getBoolean("useZcr", true))
            putExtra("zcrThreshold", prefs.getFloat("zcrThreshold", 0.15f))
            putExtra("useBandEnergy", prefs.getBoolean("useBandEnergy", true))
            putExtra("bandEnergyThreshold", prefs.getFloat("bandEnergyThreshold", 0.015f))
            putExtra("useLowFreqRatio", prefs.getBoolean("useLowFreqRatio", true))
            putExtra("lowFreqRatioThreshold", prefs.getFloat("lowFreqRatioThreshold", 0.65f))
            putExtra("minDurationSeconds", prefs.getFloat("minDurationSeconds", 1.0f))
            putExtra("ignoreDuringMediaPlayback", prefs.getBoolean("ignoreDuringMediaPlayback", true))
        }
    }

    /**
     * Starts or reconfigures the monitor. Must be called while the app is in the foreground: from
     * the background Android refuses the start on API 31+ and withholds the microphone on API 30+.
     */
    fun startMonitoring(context: Context) {
        ContextCompat.startForegroundService(context, buildStartIntent(context))
    }

    fun stopMonitoring(context: Context) {
        context.stopService(Intent(context, SnoreDetectionService::class.java))
    }
}
