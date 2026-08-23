package com.aistudio.snoredetector.afkwd.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice
import com.aistudio.snoredetector.afkwd.audio.AudioInputManager
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.data.AudioExportManager
import com.aistudio.snoredetector.afkwd.data.ErrorLog
import com.aistudio.snoredetector.afkwd.data.ErrorLogger
import com.aistudio.snoredetector.afkwd.data.ExportSummary
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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // Central state synchronization of DB SnoreEvents & ErrorLogs
    val hLogs: StateFlow<List<SnoreEvent>>
    val errorLogs: StateFlow<List<ErrorLog>>

    // Media Player state
    private var mediaPlayer: MediaPlayer? = null
    private val _playingEventId = MutableStateFlow<Int?>(null)
    val playingEventId = _playingEventId.asStateFlow()

    // Multi-Selection state for selective export
    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode = _isMultiSelectMode.asStateFlow()

    // Audio Input Device selection state
    private val _availableInputDevices = MutableStateFlow<List<AudioInputDevice>>(listOf(AudioInputDevice.PHONE_MIC))
    val availableInputDevices = _availableInputDevices.asStateFlow()

    private val _selectedAudioInputId = MutableStateFlow(prefs.getInt("selectedAudioInputId", -1))
    val selectedAudioInputId = _selectedAudioInputId.asStateFlow()

    private val _selectedAudioInputName = MutableStateFlow(
        prefs.getString("selectedAudioInputName", AudioInputDevice.PHONE_MIC.name) ?: AudioInputDevice.PHONE_MIC.name
    )
    val selectedAudioInputName = _selectedAudioInputName.asStateFlow()

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshAvailableInputDevices()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshAvailableInputDevices()
            }
        }
    } else null

    private val _selectedEventIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedEventIds = _selectedEventIds.asStateFlow()

    // Export progress & status state
    private val _exportInProgress = MutableStateFlow(false)
    val exportInProgress = _exportInProgress.asStateFlow()

    private val _exportProgressText = MutableStateFlow<String?>(null)
    val exportProgressText = _exportProgressText.asStateFlow()

    private val _exportSummary = MutableStateFlow<ExportSummary?>(null)
    val exportSummary = _exportSummary.asStateFlow()

    // Threshold Config State flows
    // Amplitude dB criterion is mandatory and always active (cannot be disabled)
    private val _useRms = MutableStateFlow(true)
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

    private val _minDurationSeconds = MutableStateFlow(prefs.getFloat("minDurationSeconds", 1.0f))
    val minDurationSeconds = _minDurationSeconds.asStateFlow()

    private val _saveAudioClips = MutableStateFlow(prefs.getBoolean("saveAudioClips", true))
    val saveAudioClips = _saveAudioClips.asStateFlow()

    private val _notifyOnSnore = MutableStateFlow(prefs.getBoolean("notifyOnSnore", false))
    val notifyOnSnore = _notifyOnSnore.asStateFlow()

    // Material 3 / Material You Theme Preferences
    private val savedThemeModeStr = prefs.getString("themeMode", "SYSTEM") ?: "SYSTEM"
    private val _themeMode = MutableStateFlow(
        try {
            com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode.valueOf(savedThemeModeStr)
        } catch (e: Exception) {
            com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode.SYSTEM
        }
    )
    val themeMode = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamicColor", true))
    val dynamicColor = _dynamicColor.asStateFlow()

    // Last measurement timeline persistent cache
    private val _lastSavedTimeline = MutableStateFlow<List<AmplitudePoint>>(emptyList())
    val lastSavedTimeline = _lastSavedTimeline.asStateFlow()

    // Combined Live timeline state
    val timelineDisplayState: StateFlow<List<AmplitudePoint>>

    init {
        val database = AppDatabase.getDatabase(context)
        repository = SnoreRepository(database.snoreDao(), database.errorLogDao())
        
        // Expose db stream directly to view
        hLogs = repository.allEvents.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Expose error log stream to view (empty during normal, error-free operation)
        errorLogs = repository.allErrorLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Enumerate currently available audio input sources
        refreshAvailableInputDevices()

        // Register dynamic hardware audio device callback for connect / disconnect events
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w("SnoreVM", "Failed to register audio device callback: ${e.message}")
            }
        }

        // Load cached last-measurement points from disk if any
        loadLastSavedTimelineOffline()

        // Safely handle/migrate legacy useRms setting: amplitude dB criterion is always enabled
        if (prefs.contains("useRms")) {
            prefs.edit().putBoolean("useRms", true).apply()
        }

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
            putExtra("notifyOnSnore", _notifyOnSnore.value)
            putExtra("audioInputId", _selectedAudioInputId.value)
            putExtra("audioInputName", _selectedAudioInputName.value)
            putExtra("useRms", _useRms.value)
            putExtra("rmsDbThreshold", _rmsDbThreshold.value)
            putExtra("useZcr", _useZcr.value)
            putExtra("zcrThreshold", _zcrThreshold.value)
            putExtra("useBandEnergy", _useBandEnergy.value)
            putExtra("bandEnergyThreshold", _bandEnergyThreshold.value)
            putExtra("useLowFreqRatio", _useLowFreqRatio.value)
            putExtra("lowFreqRatioThreshold", _lowFreqRatioThreshold.value)
            putExtra("minDurationSeconds", _minDurationSeconds.value)
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

    // --- AUDIO INPUT DEVICE METHODS ---

    fun refreshAvailableInputDevices() {
        val devices = AudioInputManager.getAvailableInputDevices(context)
        _availableInputDevices.value = devices
    }

    fun updateSelectedAudioInput(device: AudioInputDevice) {
        _selectedAudioInputId.value = device.id
        _selectedAudioInputName.value = device.name
        prefs.edit()
            .putInt("selectedAudioInputId", device.id)
            .putString("selectedAudioInputName", device.name)
            .apply()

        // If background service is currently active, send updated intent to switch preferred device live
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    // --- CONFIG UPDATE METHODS ---

    fun updateNotifyOnSnore(value: Boolean) {
        _notifyOnSnore.value = value
        prefs.edit().putBoolean("notifyOnSnore", value).apply()
        // If the service is currently running, pass updated configuration intent
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateUseRms(value: Boolean) {
        // Amplitude dB criterion is always active
        _useRms.value = true
        prefs.edit().putBoolean("useRms", true).apply()
    }

    fun updateRmsDbThreshold(value: Float) {
        _rmsDbThreshold.value = value
        prefs.edit().putFloat("rmsDbThreshold", value).apply()
        // If the service is currently running, pass updated configuration intent immediately
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateUseZcr(value: Boolean) {
        _useZcr.value = value
        prefs.edit().putBoolean("useZcr", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateZcrThreshold(value: Float) {
        _zcrThreshold.value = value
        prefs.edit().putFloat("zcrThreshold", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateUseBandEnergy(value: Boolean) {
        _useBandEnergy.value = value
        prefs.edit().putBoolean("useBandEnergy", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateBandEnergyThreshold(value: Float) {
        _bandEnergyThreshold.value = value
        prefs.edit().putFloat("bandEnergyThreshold", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateUseLowFreqRatio(value: Boolean) {
        _useLowFreqRatio.value = value
        prefs.edit().putBoolean("useLowFreqRatio", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateLowFreqRatioThreshold(value: Float) {
        _lowFreqRatioThreshold.value = value
        prefs.edit().putFloat("lowFreqRatioThreshold", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateMinDurationSeconds(value: Float) {
        _minDurationSeconds.value = value
        prefs.edit().putFloat("minDurationSeconds", value).apply()
        if (SnoreDetectionService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun updateSaveAudioClips(value: Boolean) {
        _saveAudioClips.value = value
        prefs.edit().putBoolean("saveAudioClips", value).apply()
    }

    fun updateThemeMode(mode: com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("themeMode", mode.name).apply()
    }

    fun updateDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit().putBoolean("dynamicColor", enabled).apply()
    }

    /**
     * Restore all DSP detection parameters and app preferences to their source-of-truth default values.
     */
    fun resetAllSettingsToDefaults() {
        val defaultConfig = com.aistudio.snoredetector.afkwd.dsp.DetectionConfig()
        updateUseRms(defaultConfig.useRms)
        updateRmsDbThreshold(defaultConfig.rmsDbThreshold)
        updateUseZcr(defaultConfig.useZcr)
        updateZcrThreshold(defaultConfig.zcrThreshold)
        updateUseBandEnergy(defaultConfig.useBandEnergy)
        updateBandEnergyThreshold(defaultConfig.bandEnergyThreshold)
        updateUseLowFreqRatio(defaultConfig.useLowFreqRatio)
        updateLowFreqRatioThreshold(defaultConfig.lowFreqRatioThreshold)
        updateMinDurationSeconds(defaultConfig.minDurationSeconds)
        updateSaveAudioClips(true)
        updateNotifyOnSnore(false)
        updateSelectedAudioInput(AudioInputDevice.DEFAULT_DEVICE)
        updateThemeMode(com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode.SYSTEM)
        updateDynamicColor(true)
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
                ErrorLogger.log(
                    context = context,
                    errorType = "PLAYBACK_ERROR",
                    message = "Audio playback failed for event #${event.id}: ${e.message}",
                    throwable = e,
                    component = "MediaPlayer",
                    additionalDiagnostics = mapOf("FilePath" to path, "EventId" to event.id.toString())
                )
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

    // --- ERROR LOG ACTIONS ---

    fun deleteErrorLog(log: ErrorLog) {
        viewModelScope.launch {
            repository.deleteErrorLogById(log.id)
        }
    }

    fun clearAllErrorLogs() {
        viewModelScope.launch {
            repository.clearErrorLogs()
        }
    }

    fun getShareErrorLogIntent(log: ErrorLog): Intent {
        val text = ErrorLogger.formatAsPlainText(log)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Snore Detector Error Log - ${log.errorType}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(shareIntent, "Share Error Log")
    }

    fun exportErrorLogToUri(log: ErrorLog, targetUri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = ErrorLogger.exportErrorLogToUri(context, log, targetUri)
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    // --- MULTI-SELECTION FOR EXPORT ---

    fun toggleMultiSelectMode(enable: Boolean? = null) {
        val newMode = enable ?: !_isMultiSelectMode.value
        _isMultiSelectMode.value = newMode
        if (!newMode) {
            _selectedEventIds.value = emptySet()
        }
    }

    fun toggleEventSelection(eventId: Int) {
        val current = _selectedEventIds.value
        _selectedEventIds.value = if (current.contains(eventId)) {
            current - eventId
        } else {
            current + eventId
        }
        if (_selectedEventIds.value.isNotEmpty() && !_isMultiSelectMode.value) {
            _isMultiSelectMode.value = true
        }
    }

    fun selectAllEvents(events: List<SnoreEvent>) {
        _selectedEventIds.value = events.map { it.id }.toSet()
        _isMultiSelectMode.value = true
    }

    fun clearSelection() {
        _selectedEventIds.value = emptySet()
        _isMultiSelectMode.value = false
    }

    fun dismissExportSummary() {
        _exportSummary.value = null
    }

    // --- AUDIO & CSV EXPORT ENGINE ---

    /**
     * Share a single snore event audio file via standard Android ACTION_SEND.
     */
    fun getShareSingleAudioIntent(event: SnoreEvent): Intent? {
        return AudioExportManager.createShareAudioIntent(context, event)
    }

    /**
     * Export a single audio recording directly to a user-chosen Document Uri.
     */
    fun exportSingleAudio(sourcePath: String, targetUri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _exportInProgress.value = true
            _exportProgressText.value = "Exporting audio recording..."
            val result = try {
                AudioExportManager.exportSingleAudioToUri(context, sourcePath, targetUri)
            } catch (e: Exception) {
                Log.e("SnoreVM", "Single audio export failed", e)
                ErrorLogger.log(
                    context = context,
                    errorType = "EXPORT_AUDIO_ERROR",
                    message = "Failed to export audio to selected URI: ${e.message}",
                    throwable = e,
                    component = "AudioExportManager",
                    additionalDiagnostics = mapOf("SourcePath" to sourcePath)
                )
                false
            }
            if (!result) {
                ErrorLogger.log(
                    context = context,
                    errorType = "EXPORT_AUDIO_IO_FAILURE",
                    message = "Output stream could not be written for $sourcePath",
                    component = "AudioExportManager"
                )
            }
            withContext(Dispatchers.Main) {
                _exportInProgress.value = false
                _exportProgressText.value = null
                onComplete(result)
            }
        }
    }

    /**
     * Export CSV file directly to a user-chosen Document Uri.
     */
    fun exportCsv(events: List<SnoreEvent>, targetUri: Uri, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _exportInProgress.value = true
            _exportProgressText.value = "Exporting CSV log..."
            val result = try {
                AudioExportManager.exportCsvToUri(context, events, targetUri)
            } catch (e: Exception) {
                Log.e("SnoreVM", "CSV export failed", e)
                ErrorLogger.log(
                    context = context,
                    errorType = "EXPORT_CSV_ERROR",
                    message = "Failed to write CSV export: ${e.message}",
                    throwable = e,
                    component = "AudioExportManager",
                    additionalDiagnostics = mapOf("EventCount" to events.size.toString())
                )
                false
            }
            withContext(Dispatchers.Main) {
                _exportInProgress.value = false
                _exportProgressText.value = null
                onComplete(result)
            }
        }
    }

    /**
     * Export audio recordings or full bundle (CSV + audio) into a ZIP archive.
     */
    fun exportZipBundle(
        events: List<SnoreEvent>,
        targetUri: Uri,
        includeCsv: Boolean,
        includeAudio: Boolean,
        onComplete: (ExportSummary) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _exportInProgress.value = true
            _exportProgressText.value = "Preparing export package (0/${events.size})..."
            
            val summary = try {
                AudioExportManager.exportZipArchiveToUri(
                    context = context,
                    events = events,
                    destinationUri = targetUri,
                    includeCsv = includeCsv,
                    includeAudio = includeAudio,
                    onProgress = { current, total ->
                        _exportProgressText.value = "Exporting recording $current of $total..."
                    }
                )
            } catch (e: Exception) {
                Log.e("SnoreVM", "ZIP export failed", e)
                ErrorLogger.log(
                    context = context,
                    errorType = "EXPORT_ZIP_ERROR",
                    message = "Failed to create ZIP export archive: ${e.message}",
                    throwable = e,
                    component = "AudioExportManager",
                    additionalDiagnostics = mapOf("EventCount" to events.size.toString())
                )
                ExportSummary(
                    success = false,
                    exportedAudioCount = 0,
                    missingAudioCount = 0,
                    totalEventsCount = events.size,
                    errorMessage = e.message ?: "Failed to create ZIP package"
                )
            }

            withContext(Dispatchers.Main) {
                _exportInProgress.value = false
                _exportProgressText.value = null
                _exportSummary.value = summary
                onComplete(summary)
            }
        }
    }

    /**
     * Generate standard CSV file and retrieve share Intent.
     */
    fun getCsvShareIntent(events: List<SnoreEvent>): Intent? {
        if (events.isEmpty()) return null
        
        return try {
            val csvContent = AudioExportManager.generateCsvContent(events)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Snore Detector Session Log")
                putExtra(Intent.EXTRA_TEXT, csvContent)
            }
            Intent.createChooser(shareIntent, "Share Snoring Log CSV")
        } catch (e: Exception) {
            Log.e("SnoreVM", "Error creating CSV share intent", e)
            ErrorLogger.log(
                context = context,
                errorType = "SHARE_CSV_ERROR",
                message = "Failed to generate CSV share payload: ${e.message}",
                throwable = e,
                component = "AudioExportManager"
            )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (e: Exception) {
                Log.w("SnoreVM", "Failed to unregister audio device callback: ${e.message}")
            }
        }
        super.onCleared()
    }
}

