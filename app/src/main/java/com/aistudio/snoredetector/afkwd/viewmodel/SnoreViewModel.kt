package com.aistudio.snoredetector.afkwd.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.data.SnoreRepository
import com.aistudio.snoredetector.afkwd.dsp.AmplitudePoint
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Architecture ViewModel managing user interactions, configurations, data exports, and audio playbacks.
 */
class SnoreViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("snore_detector_preferences", Context.MODE_PRIVATE)
    private val repository: SnoreRepository

    // Central state synchronization of DB SnoreEvents
    val hLogs: StateFlow<List<SnoreEvent>>

    // Media Player state
    private var mediaPlayer: MediaPlayer? = null
    private val _playingEventId = MutableStateFlow<Int?>(null)
    val playingEventId = _playingEventId.asStateFlow()

    // Threshold Config State flows
    private val _useRms = MutableStateFlow(prefs.getBoolean("useRms", true))
    val useRms = _useRms.asStateFlow()

    private val _rmsDbThreshold = MutableStateFlow(prefs.getFloat("rmsDbThreshold", 55.0f))
    val rmsDbThreshold = _rmsDbThreshold.asStateFlow()

    private val _useZcr = MutableStateFlow(prefs.getBoolean("useZcr", true))
    val useZcr = _useZcr.asStateFlow()

    private val _zcrThreshold = MutableStateFlow(prefs.getFloat("zcrThreshold", 0.15f))
    val zcrThreshold = _zcrThreshold.asStateFlow()

    private val _useBandEnergy = MutableStateFlow(prefs.getBoolean("useBandEnergy", true))
    val useBandEnergy = _useBandEnergy.asStateFlow()

    private val _bandEnergyThreshold = MutableStateFlow(prefs.getFloat("bandEnergyThreshold", 0.015f))
    val bandEnergyThreshold = _bandEnergyThreshold.asStateFlow()

    private val _useLowFreqRatio = MutableStateFlow(prefs.getBoolean("useLowFreqRatio", true))
    val useLowFreqRatio = _useLowFreqRatio.asStateFlow()

    private val _lowFreqRatioThreshold = MutableStateFlow(prefs.getFloat("lowFreqRatioThreshold", 0.65f))
    val lowFreqRatioThreshold = _lowFreqRatioThreshold.asStateFlow()

    private val _saveAudioClips = MutableStateFlow(prefs.getBoolean("saveAudioClips", true))
    val saveAudioClips = _saveAudioClips.asStateFlow()

    // Last measurement timeline persistent cache
    private val _lastSavedTimeline = MutableStateFlow<List<AmplitudePoint>>(emptyList())
    val lastSavedTimeline = _lastSavedTimeline.asStateFlow()

    // Combined Live timeline state
    val timelineDisplayState: StateFlow<List<AmplitudePoint>>

    init {
        val database = AppDatabase.getDatabase(context)
        repository = SnoreRepository(database.snoreDao())
        
        // Expose db stream directly to view
        hLogs = repository.allEvents.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Load cached last-measurement points from disk if any
        loadLastSavedTimelineOffline()

        // Bind timeline state combining either background service data (in-progress) or our saved historical timeline
        timelineDisplayState = combine(
            SnoreDetectionService.isServiceRunning,
            SnoreDetectionService.currentSessionData,
            _lastSavedTimeline
        ) { isRunning, liveSession, lastSaved ->
            if (isRunning) liveSession else lastSaved
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Monitor background service state transitions to cache completed sessions
        viewModelScope.launch {
            SnoreDetectionService.isServiceRunning.collect { isRunning ->
                if (!isRunning) {
                    val completedSession = SnoreDetectionService.currentSessionData.value
                    if (completedSession.isNotEmpty()) {
                        saveTimelineLocally(completedSession)
                    }
                }
            }
        }
    }

    // --- SERVICE CONTROLS ---

    fun startServiceDetection() {
        val intent = Intent(context, SnoreDetectionService::class.java).apply {
            putExtra("saveAudioClips", _saveAudioClips.value)
            putExtra("useRms", _useRms.value)
            putExtra("rmsDbThreshold", _rmsDbThreshold.value)
            putExtra("useZcr", _useZcr.value)
            putExtra("zcrThreshold", _zcrThreshold.value)
            putExtra("useBandEnergy", _useBandEnergy.value)
            putExtra("bandEnergyThreshold", _bandEnergyThreshold.value)
            putExtra("useLowFreqRatio", _useLowFreqRatio.value)
            putExtra("lowFreqRatioThreshold", _lowFreqRatioThreshold.value)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopServiceDetection() {
        val intent = Intent(context, SnoreDetectionService::class.java)
        context.stopService(intent)
    }

    // --- CONFIG UPDATE METHODS ---

    fun updateUseRms(value: Boolean) {
        _useRms.value = value
        prefs.edit().putBoolean("useRms", value).apply()
    }

    fun updateRmsDbThreshold(value: Float) {
        _rmsDbThreshold.value = value
        prefs.edit().putFloat("rmsDbThreshold", value).apply()
    }

    fun updateUseZcr(value: Boolean) {
        _useZcr.value = value
        prefs.edit().putBoolean("useZcr", value).apply()
    }

    fun updateZcrThreshold(value: Float) {
        _zcrThreshold.value = value
        prefs.edit().putFloat("zcrThreshold", value).apply()
    }

    fun updateUseBandEnergy(value: Boolean) {
        _useBandEnergy.value = value
        prefs.edit().putBoolean("useBandEnergy", value).apply()
    }

    fun updateBandEnergyThreshold(value: Float) {
        _bandEnergyThreshold.value = value
        prefs.edit().putFloat("bandEnergyThreshold", value).apply()
    }

    fun updateUseLowFreqRatio(value: Boolean) {
        _useLowFreqRatio.value = value
        prefs.edit().putBoolean("useLowFreqRatio", value).apply()
    }

    fun updateLowFreqRatioThreshold(value: Float) {
        _lowFreqRatioThreshold.value = value
        prefs.edit().putFloat("lowFreqRatioThreshold", value).apply()
    }

    fun updateSaveAudioClips(value: Boolean) {
        _saveAudioClips.value = value
        prefs.edit().putBoolean("saveAudioClips", value).apply()
    }

    // --- PLAYBACK CONTROLS ---

    fun togglePlayback(event: SnoreEvent) {
        val path = event.audioFilePath
        if (path.isNullOrEmpty()) return

        if (_playingEventId.value == event.id) {
            stopAudio()
        } else {
            stopAudio()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener {
                        _playingEventId.value = null
                        stopAudio()
                    }
                    start()
                }
                _playingEventId.value = event.id
            } catch (e: Exception) {
                Log.e("SnoreVM", "Playback failed", e)
                _playingEventId.value = null
            }
        }
    }

    private fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        _playingEventId.value = null
    }

    // --- DELETE LOGS ---

    fun deleteEvent(event: SnoreEvent) {
        viewModelScope.launch {
            // Delete associated WAV file if exists on disk
            event.audioFilePath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.e("SnoreVM", "Failed to delete file $path", e)
                }
            }
            repository.deleteEventById(event.id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            // Clear directory files first
            try {
                val dir = File(context.filesDir, "snore_clips")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            } catch (e: Exception) {
                Log.e("SnoreVM", "Failed to clear audio files", e)
            }
            repository.clearHistory()
        }
    }

    // --- TEXT/CSV DATA EXPORT ENGINE ---

    /**
     * Generate standard CSV file and retrieve share Intent.
     */
    fun getCsvShareIntent(events: List<SnoreEvent>): Intent? {
        if (events.isEmpty()) return null
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val csvBuilder = StringBuilder()
        // CSV Headers containing timestamp, dB levels, maxRMS, mean zero crossing, band energy and low frequency energy
        csvBuilder.append("Timestamp,Datetime,Duration_Seconds,dB_Level,Max_RMS,Mean_ZCR,Mean_BandEnergy,Mean_LowFreqRatio,AudioClip\n")
        
        for (e in events) {
            val formattedDate = sdf.format(Date(e.timestamp))
            val clipName = e.audioFilePath?.let { File(it).name } ?: "None"
            csvBuilder.append("${e.timestamp},\"$formattedDate\",${e.durationSeconds},${e.maxDb},${e.maxRms},${e.meanZcr},${e.meanBandEnergy},${e.meanLowFreqRatio},\"$clipName\"\n")
        }

        return try {
            val cacheDir = File(context.cacheDir, "exports")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val exportFile = File(cacheDir, "snore_analytics_redefined.csv")
            exportFile.writeText(csvBuilder.toString())

            // Utilizing standard local file sharing.
            // Since we use strict FOSS, we can expose the absolute file sharing via standard Intent.
            // Under modern strict sharing, we can use FileProvider. But wait, we can also write to standard external cache or simple email text transfer, which is 100% compliant.
            // Let's build a clean Text Send intent containing the data directly, or refer to FileProvider if configured.
            // Sharing as content text is highly compatible, lightweight, and requires 0 configuration!
            // Let's create an Intent that can send the CSV text directly as an email/document, or share the CSV file text safely!
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Snore Detector Session Log")
                putExtra(Intent.EXTRA_TEXT, csvBuilder.toString())
            }
            Intent.createChooser(shareIntent, "Share Snoring Log CSV")
        } catch (e: Exception) {
            Log.e("SnoreVM", "Error writing CSV", e)
            null
        }
    }

    // --- TIMELINE OFFLINE PERSISTENCE ---

    private fun saveTimelineLocally(points: List<AmplitudePoint>) {
        _lastSavedTimeline.value = points
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "last_measurement.txt")
                val serialized = points.joinToString("\n") { p ->
                    "${p.dbValue},${p.isSnore},${p.timestamp}"
                }
                file.writeText(serialized)
            } catch (e: Exception) {
                Log.e("SnoreVM", "Failed to cache timeline", e)
            }
        }
    }

    private fun loadLastSavedTimelineOffline() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "last_measurement.txt")
                if (file.exists()) {
                    val points = file.readLines().mapNotNull { line ->
                        val parts = line.split(",")
                        if (parts.size == 3) {
                            AmplitudePoint(
                                dbValue = parts[0].toFloatOrNull() ?: 0.0f,
                                isSnore = parts[1].toBooleanStrictOrNull() ?: false,
                                timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                            )
                        } else null
                    }
                    withContext(Dispatchers.Main) {
                        _lastSavedTimeline.value = points
                    }
                }
            } catch (e: Exception) {
                Log.e("SnoreVM", "Failed to read cached timeline", e)
            }
        }
    }

    override fun onCleared() {
        stopAudio()
        super.onCleared()
    }
}
