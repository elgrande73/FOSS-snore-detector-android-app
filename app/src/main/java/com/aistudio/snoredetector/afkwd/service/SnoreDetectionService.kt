package com.aistudio.snoredetector.afkwd.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.snoredetector.afkwd.MainActivity
import com.aistudio.snoredetector.afkwd.audio.AudioInputDevice
import com.aistudio.snoredetector.afkwd.audio.AudioInputManager
import com.aistudio.snoredetector.afkwd.audio.MediaPlaybackDetector
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.data.ErrorLogger
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.data.SnoreRepository
import com.aistudio.snoredetector.afkwd.dsp.AmplitudePoint
import com.aistudio.snoredetector.afkwd.dsp.AnalysisResult
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.dsp.SnoreAnalyzer
import com.aistudio.snoredetector.afkwd.dsp.WavWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service that continuously tracks breathing and snoring audio patterns in the background.
 * Optimized for up to 12 hours of offline operation.
 */
class SnoreDetectionService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var audioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var mediaPlaybackDetector: MediaPlaybackDetector? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var repository: SnoreRepository
    private val snoreAnalyzer = SnoreAnalyzer()
    private var headsetBroadcastReceiver: BroadcastReceiver? = null

    // Service parameters
    private var isSaveClipsEnabled: Boolean = true
    private var isNotifyOnSnoreEnabled: Boolean = false
    private var selectedAudioInputId: Int = -1
    private var selectedAudioInputName: String = ""
    private var currentConfig = DetectionConfig()
    private var startTimeMillis: Long = 0L

    companion object {
        private const val TAG = "SnoreService"
        const val NOTIFICATION_ID = 54321
        const val EVENT_NOTIFICATION_ID = 54322
        const val CHANNEL_ID = "snore_detector_service_channel"
        const val CHANNEL_EVENT_ID = "snore_detector_events_channel"

        // Duration of background operation limit: 12 Hours
        private const val LIMIT_DURATION_MS = 12 * 60 * 60 * 1000L

        // Singleton Flows for real-time Dashboard UI consumption
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _sessionStartTime = MutableStateFlow(0L)
        val sessionStartTime = _sessionStartTime.asStateFlow()

        private val _sessionEventCount = MutableStateFlow(0)
        val sessionEventCount = _sessionEventCount.asStateFlow()

        private val _liveAnalysis = MutableStateFlow<AnalysisResult?>(null)
        val liveAnalysis = _liveAnalysis.asStateFlow()

        private val _isCurrentlySnoring = MutableStateFlow(false)
        val isCurrentlySnoring = _isCurrentlySnoring.asStateFlow()

        private val _currentSessionData = MutableStateFlow<List<AmplitudePoint>>(emptyList())
        val currentSessionData = _currentSessionData.asStateFlow()

        private val _configuredInputDeviceName = MutableStateFlow(AudioInputDevice.PHONE_MIC.name)
        val configuredInputDeviceName = _configuredInputDeviceName.asStateFlow()

        private val _activeInputDeviceName = MutableStateFlow(AudioInputDevice.PHONE_MIC.name)
        val activeInputDeviceName = _activeInputDeviceName.asStateFlow()

        private val _isFallbackActive = MutableStateFlow(false)
        val isFallbackActive = _isFallbackActive.asStateFlow()

        private val _isMediaPlaying = MutableStateFlow(false)
        val isMediaPlaying = _isMediaPlaying.asStateFlow()

        private val _isDetectionSuspendedForMedia = MutableStateFlow(false)
        val isDetectionSuspendedForMedia = _isDetectionSuspendedForMedia.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError = _serviceError.asStateFlow()

        /**
         * Helper command to easily stop the service externally.
         */
        const val ACTION_STOP_SERVICE = "com.example.service.ACTION_STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Initialize local Room repository
        val database = AppDatabase.getDatabase(applicationContext)
        repository = SnoreRepository(database.snoreDao(), database.errorLogDao())

        // Acquire WakeLock to hold CPU running continuously overnight
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnoreDetector::WakeLock").apply {
            setReferenceCounted(false)
            acquire(LIMIT_DURATION_MS)
        }
        
        _isServiceRunning.value = true
        _serviceError.value = null
        startTimeMillis = System.currentTimeMillis()
        _sessionStartTime.value = startTimeMillis
        _sessionEventCount.value = 0

        // Initialize and start monitoring system media playback
        mediaPlaybackDetector = MediaPlaybackDetector(applicationContext) { isPlaying ->
            Log.d(TAG, "System media playback change observed: isPlaying=$isPlaying")
        }.also { detector ->
            detector.startMonitoring()
            serviceScope.launch {
                detector.isMediaPlaying.collect { isPlaying ->
                    _isMediaPlaying.value = isPlaying
                    val isSuspended = isPlaying && currentConfig.ignoreDuringMediaPlayback
                    _isDetectionSuspendedForMedia.value = isSuspended
                    if (isRecording.get()) {
                        if (isSuspended) {
                            updateNotification("Media playback active — Detection on standby")
                        } else if (!_isCurrentlySnoring.value) {
                            updateNotification("Monitoring bedroom acoustics dynamically...")
                        }
                    }
                }
            }
        }

        // Register broadcast receiver for hardware headset / Bluetooth connection transitions
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            @Suppress("DEPRECATION")
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            @Suppress("DEPRECATION")
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        headsetBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.i(TAG, "Audio hardware / headset state broadcast received: $action")
                if (isRecording.get()) {
                    audioRecord?.let { currentRecord ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val targetDev = AudioInputManager.findMatchingDeviceInfo(
                                this@SnoreDetectionService,
                                selectedAudioInputId,
                                selectedAudioInputName
                            ) ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(this@SnoreDetectionService)
                            applyPreferredAudioDevice(currentRecord, selectedAudioInputId, selectedAudioInputName, targetDev)
                        }
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(headsetBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(headsetBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register headsetBroadcastReceiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "Stop command received via action")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Service starting with onStartCommand")
        
        // Parse configurations passed from VM/UI
        intent?.let {
            isSaveClipsEnabled = it.getBooleanExtra("saveAudioClips", true)
            isNotifyOnSnoreEnabled = it.getBooleanExtra("notifyOnSnore", false)
            selectedAudioInputId = it.getIntExtra("audioInputId", -1)
            selectedAudioInputName = it.getStringExtra("audioInputName") ?: ""
            
            currentConfig = DetectionConfig(
                useRms = true, // Amplitude dB criterion is mandatory and always enabled
                useZcr = it.getBooleanExtra("useZcr", true),
                useBandEnergy = it.getBooleanExtra("useBandEnergy", true),
                useLowFreqRatio = it.getBooleanExtra("useLowFreqRatio", true),
                rmsDbThreshold = it.getFloatExtra("rmsDbThreshold", 55.0f),
                zcrThreshold = it.getFloatExtra("zcrThreshold", 0.15f),
                bandEnergyThreshold = it.getFloatExtra("bandEnergyThreshold", 0.015f),
                lowFreqRatioThreshold = it.getFloatExtra("lowFreqRatioThreshold", 0.65f),
                minDurationSeconds = it.getFloatExtra("minDurationSeconds", 1.0f),
                ignoreDuringMediaPlayback = it.getBooleanExtra("ignoreDuringMediaPlayback", true)
            )

            // If audio capture is already running and the selected device configuration changed, restart capture with new hardware routing
            val newConfiguredName = if (selectedAudioInputName.isNotBlank()) selectedAudioInputName else AudioInputDevice.PHONE_MIC.name
            if (isRecording.get() && _configuredInputDeviceName.value != newConfiguredName) {
                Log.i(TAG, "Input device selection changed while recording (from \"${_configuredInputDeviceName.value}\" to \"$newConfiguredName\"). Re-routing audio capture...")
                stopAudioCapture()
            }
        }

        // Setup Foreground Notification Channel and start foreground
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready to analyze..."))

        // Start real-time audio capturing & analysis
        startAudioCapture()

        // Setup automatic 12-hour timeout monitor
        serviceScope.launch {
            while (isActive) {
                delay(30000) // check every 30 seconds
                val elapsed = System.currentTimeMillis() - startTimeMillis
                if (elapsed >= LIMIT_DURATION_MS) {
                    Log.d(TAG, "12-hour recording limit reached. Stopping automatically.")
                    stopSelf()
                    break
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createAndStartAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int,
        audioSource: Int,
        targetDeviceInfo: AudioDeviceInfo?
    ): AudioRecord? {
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init with source $audioSource failed: ${e.message}")
            if (audioSource != MediaRecorder.AudioSource.MIC) {
                try {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback AudioRecord init failed", e2)
                }
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            try { record?.release() } catch (_: Exception) {}
            return null
        }

        audioRecord = record

        // Note: We deliberately do NOT attach hardware AcousticEchoCanceler / NoiseSuppressor effects here
        // because AEC couples the recording session to system playback streams, causing HAL mutes when media pauses.

        // Apply user's selected input device routing
        applyPreferredAudioDevice(record, selectedAudioInputId, selectedAudioInputName, targetDeviceInfo)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                record.addOnRoutingChangedListener({ routedRecord ->
                    val currentRecord = routedRecord as? AudioRecord
                    val routed = currentRecord?.routedDevice
                    val eval = AudioInputManager.evaluateAudioRouting(
                        configuredId = selectedAudioInputId,
                        configuredName = selectedAudioInputName,
                        routedDevice = routed
                    )
                    val addressStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && routed != null) routed.address ?: "N/A" else "N/A"
                    Log.i(
                        TAG,
                        """
                        |=== AudioRecord OnRoutingChanged ===
                        | Configured input: ${eval.configuredDisplayName}
                        | Actual routed input: ${routed?.productName ?: "N/A"} (id=${routed?.id ?: "N/A"}, type=${routed?.type ?: "N/A"}, address=$addressStr)
                        | Active UI input: ${eval.activeDisplayName} (Fallback: ${eval.isFallback})
                        | AudioRecord State: ${currentRecord?.state}, RecordingState: ${currentRecord?.recordingState}
                        |===================================
                        """.trimMargin()
                    )
                    _configuredInputDeviceName.value = eval.configuredDisplayName
                    _activeInputDeviceName.value = eval.activeDisplayName
                    _isFallbackActive.value = eval.isFallback
                }, null)
            } catch (e: Exception) {
                Log.w(TAG, "RoutingChangedListener not supported: ${e.message}")
            }
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord startRecording called but recordingState is ${record.recordingState}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording", e)
            try { record.release() } catch (_: Exception) {}
            audioRecord = null
            return null
        }

        // Log verified routing diagnostics immediately after startRecording
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val preferred = record.preferredDevice
            val routed = record.routedDevice
            val eval = AudioInputManager.evaluateAudioRouting(
                configuredId = selectedAudioInputId,
                configuredName = selectedAudioInputName,
                routedDevice = routed ?: preferred ?: targetDeviceInfo
            )

            Log.i(
                TAG,
                """
                |======================================================
                | Recording Started - Audio Routing Diagnostics
                | Configured input: ${eval.configuredDisplayName}
                | Preferred device: ${preferred?.productName} (id=${preferred?.id ?: "null"})
                | Actual routed device: ${routed?.productName} (id=${routed?.id ?: "pending"})
                | Active UI Device Name: ${eval.activeDisplayName} (Fallback: ${eval.isFallback})
                | AudioRecord State: ${record.state}, RecordingState: ${record.recordingState}
                |======================================================
                """.trimMargin()
            )
            _configuredInputDeviceName.value = eval.configuredDisplayName
            _activeInputDeviceName.value = eval.activeDisplayName
            _isFallbackActive.value = eval.isFallback
        }

        return record
    }

    /**
     * Set up and start the AudioRecord mic-capturing loop on dispatch thread.
     */
    private fun startAudioCapture() {
        if (isRecording.getAndSet(true)) {
            Log.d(TAG, "Capture already active")
            return
        }

        recordingJob = serviceScope.launch(Dispatchers.Default) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val configuredName = if (selectedAudioInputName.isNotBlank()) selectedAudioInputName else AudioInputDevice.PHONE_MIC.name
            _configuredInputDeviceName.value = configuredName

            // Resolve target device to determine if Bluetooth communication routing is needed
            val targetDeviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioInputManager.findMatchingDeviceInfo(this@SnoreDetectionService, selectedAudioInputId, selectedAudioInputName)
                    ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(this@SnoreDetectionService)
            } else null

            val isBluetoothTarget = targetDeviceInfo != null && AudioInputManager.isBluetoothType(targetDeviceInfo.type)

            // Setup or clear Bluetooth communication routing (SCO / communication device)
            if (isBluetoothTarget) {
                Log.i(TAG, "Target is Bluetooth input (\"${targetDeviceInfo?.productName}\"). Activating Bluetooth communication routing...")
                AudioInputManager.enableBluetoothCommunicationRouting(applicationContext, targetDeviceInfo)
            } else {
                Log.i(TAG, "Target is Non-Bluetooth input ($configuredName). Ensuring Bluetooth communication routing is cleared...")
                AudioInputManager.disableBluetoothCommunicationRouting(applicationContext)
            }

            // Select AudioSource: MediaRecorder.AudioSource.MIC provides clean, independent acoustic capture
            // that never intercepts headset hook or media button events in AudioPolicyManager/MediaSession.
            val audioSource = MediaRecorder.AudioSource.MIC

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                val errorStr = "Hardware microphonic recording not supported"
                _serviceError.value = errorStr
                ErrorLogger.log(
                    context = applicationContext,
                    errorType = "AUDIO_BUFFER_ERROR",
                    message = errorStr,
                    component = "AudioRecord",
                    additionalDiagnostics = mapOf(
                        "SampleRate" to sampleRate.toString(),
                        "MinBufferSize" to minBufferSize.toString()
                    )
                )
                _isServiceRunning.value = false
                AudioInputManager.disableBluetoothCommunicationRouting(applicationContext)
                stopSelf()
                return@launch
            }

            // Ensure buffer size is larger than processing window
            val finalBufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            var record: AudioRecord? = createAndStartAudioRecord(
                sampleRate,
                channelConfig,
                audioFormat,
                finalBufferSize,
                audioSource,
                targetDeviceInfo
            )

            if (record == null) {
                val errorStr = "Failed to initialize microphone hardware. Device might be busy."
                _serviceError.value = errorStr
                ErrorLogger.log(
                    context = applicationContext,
                    errorType = "AUDIO_HARDWARE_BUSY",
                    message = errorStr,
                    component = "AudioRecord",
                    additionalDiagnostics = mapOf(
                        "ConfiguredInput" to _configuredInputDeviceName.value
                    )
                )
                _isServiceRunning.value = false
                AudioInputManager.disableBluetoothCommunicationRouting(applicationContext)
                stopSelf()
                return@launch
            }

            Log.d(TAG, "AudioRecord started successfully with audioSource=$audioSource")
            
            // Temporary timeline accumulation trackers
            var timelineTimer = 0L
            val accumulatedTimelinePoints = mutableListOf<AmplitudePoint>()
            _currentSessionData.value = emptyList() // clear previous session data

            // Snore state machine variables
            var isSnoringActive = false
            var snoreStartTime = 0L
            var snoreLastDetectedTime = 0L
            var hasNotifiedForCurrentEvent = false
            val snoreAudioBuffer = mutableListOf<ShortArray>()
            
            // To compute average values of parameters over a single unified snoring incident:
            var snoreSumDb = 0.0f
            var snoreSumZcr = 0.0f
            var snoreSumBandEnergy = 0.0f
            var snoreSumLowFreqRatio = 0.0f
            var snoreMaxRms = 0.0f
            var snoreBlocksCount = 0

            // Pre-allocate reading buffer (N=1024 samples)
            val audioBuffer = ShortArray(1024)

            // Diagnostic logging counters for non-zero audio verification and self-healing recovery
            var diagnosticFrameCount = 0
            var consecutiveZeroFrames = 0
            var consecutiveErrorReads = 0
            var lastPlaybackPollMillis = 0L

            try {
                while (isActive && isRecording.get()) {
                    val readResult = try {
                        record?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading audio buffer", e)
                        -1
                    }

                    if (!isRecording.get() || !isActive) break

                    // Inspect readResult for error codes and dead HAL stream
                    if (readResult < 0 || record?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        consecutiveErrorReads++
                        val errName = when (readResult) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                            else -> "ERROR ($readResult)"
                        }
                        Log.w(TAG, "AudioRecord read returned $errName (consecutiveErrors=$consecutiveErrorReads, state=${record?.recordingState})")

                        if (consecutiveErrorReads >= 5 || readResult == AudioRecord.ERROR_DEAD_OBJECT || record?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            Log.w(TAG, "Initiating AudioRecord self-healing recovery after read error or route disruption...")
                            ErrorLogger.log(
                                context = applicationContext,
                                errorType = "AUDIO_STREAM_RECOVERED",
                                message = "AudioRecord recovered after hardware routing change or media pause (error: $errName)",
                                component = "SnoreDetectionService"
                            )
                            try { record?.stop() } catch (_: Exception) {}
                            try { record?.release() } catch (_: Exception) {}
                            releaseAudioEffects()
                            delay(250) // Allow HAL audio route to settle
                            val currentTarget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                AudioInputManager.findMatchingDeviceInfo(this@SnoreDetectionService, selectedAudioInputId, selectedAudioInputName)
                                    ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(this@SnoreDetectionService)
                            } else null
                            record = createAndStartAudioRecord(sampleRate, channelConfig, audioFormat, finalBufferSize, audioSource, currentTarget)
                            consecutiveErrorReads = 0
                            consecutiveZeroFrames = 0
                            if (record == null) {
                                Log.e(TAG, "AudioRecord recovery attempt failed; waiting before retry")
                                delay(1000)
                            }
                        } else {
                            delay(20)
                        }
                        continue
                    }

                    if (readResult == 0) {
                        delay(10)
                        continue
                    }

                    // Successful read: reset consecutive error reads
                    consecutiveErrorReads = 0

                    val currentMillis = System.currentTimeMillis()

                    // If media is currently marked as playing, periodically poll system playback state (every ~350ms)
                    // This provides a fallback to ensure immediate resumption if a player sleep timer or background stream finished
                    if (currentConfig.ignoreDuringMediaPlayback && _isMediaPlaying.value) {
                        if (currentMillis - lastPlaybackPollMillis >= 350L) {
                            lastPlaybackPollMillis = currentMillis
                            mediaPlaybackDetector?.checkCurrentPlaybackState()
                        }
                    }

                    // Inspect PCM samples for non-zero audio content
                    var maxAbsSample = 0
                    var hasNonZeroAudio = false
                    for (i in 0 until readResult) {
                        val abs = kotlin.math.abs(audioBuffer[i].toInt())
                        if (abs > maxAbsSample) maxAbsSample = abs
                        if (audioBuffer[i].toInt() != 0) hasNonZeroAudio = true
                    }

                    if (hasNonZeroAudio) {
                        consecutiveZeroFrames = 0
                    } else {
                        consecutiveZeroFrames++
                    }

                    // Anomaly recovery: if 50 consecutive frames (~3.2s) are pure digital zero, re-apply preferred device
                    if (consecutiveZeroFrames == 50) {
                        Log.w(TAG, "Detected 50 consecutive zero-sample frames (~3.2s silence). Re-evaluating audio device binding...")
                        val targetDev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            AudioInputManager.findMatchingDeviceInfo(this@SnoreDetectionService, selectedAudioInputId, selectedAudioInputName)
                                ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(this@SnoreDetectionService)
                        } else null
                        record?.let { applyPreferredAudioDevice(it, selectedAudioInputId, selectedAudioInputName, targetDev) }
                    } else if (consecutiveZeroFrames >= 100 && consecutiveZeroFrames % 50 == 0) {
                        Log.w(TAG, "Persistent zero-sample anomaly ($consecutiveZeroFrames frames). Full AudioRecord self-healing restart...")
                        try { record?.stop() } catch (_: Exception) {}
                        try { record?.release() } catch (_: Exception) {}
                        releaseAudioEffects()
                        delay(150)
                        val currentTarget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            AudioInputManager.findMatchingDeviceInfo(this@SnoreDetectionService, selectedAudioInputId, selectedAudioInputName)
                                ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(this@SnoreDetectionService)
                        } else null
                        record = createAndStartAudioRecord(sampleRate, channelConfig, audioFormat, finalBufferSize, audioSource, currentTarget)
                        consecutiveZeroFrames = 0
                    }

                    val frameSamples = audioBuffer.copyOf(readResult)
                    
                    // Run signal processing algorithms
                    val result = snoreAnalyzer.analyze(frameSamples, currentConfig)

                    // Evaluate active media playback coexistence
                    val isMediaActive = currentConfig.ignoreDuringMediaPlayback && _isMediaPlaying.value
                    _isDetectionSuspendedForMedia.value = isMediaActive

                    // If media playback is active, prevent snoring triggers while keeping live decibel metering active
                    val effectiveIsSnoring = if (isMediaActive) false else result.isSnoring
                    val effectiveResult = if (isMediaActive) result.copy(isSnoring = false) else result

                    // Post real-time analytics to dashboard flow
                    _liveAnalysis.value = effectiveResult

                    // If media suddenly became active while in the middle of accumulating a snore, safely reset state
                    if (isMediaActive && isSnoringActive) {
                        isSnoringActive = false
                        hasNotifiedForCurrentEvent = false
                        snoreAudioBuffer.clear()
                        _isCurrentlySnoring.value = false
                        updateNotification("Media playback active — Detection on standby")
                    }

                    // Diagnostic logging every ~5 seconds (~75 frames)
                    diagnosticFrameCount++
                    if (diagnosticFrameCount % 78 == 0) {
                        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) record?.routedDevice else null
                        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) record?.preferredDevice else null
                        val routedName = routed?.productName?.takeIf { it.isNotBlank() }?.toString()
                            ?: routed?.let { AudioInputManager.getDeviceTypeName(it.type) }
                            ?: _activeInputDeviceName.value
                        Log.i(
                            TAG,
                            """
                            |--- [Audio Stream Health Diagnostics] ---
                            | Thread: ${Thread.currentThread().name}
                            | AudioRecord State: ${record?.state}, RecordingState: ${record?.recordingState}
                            | Audio Source: MediaRecorder.AudioSource.MIC ($audioSource), SampleRate: $sampleRate
                            | Configured Input: ${_configuredInputDeviceName.value}
                            | Preferred Device: ${preferred?.productName ?: "None"} (id=${preferred?.id ?: "N/A"})
                            | Active Routed Device: $routedName (id=${routed?.id ?: "N/A"}, type=${routed?.type ?: "N/A"})
                            | Samples Read: $readResult, Max Amplitude: $maxAbsSample, RMS dB: ${String.format(Locale.US, "%.1f", result.db)}
                            | Non-Zero Audio: $hasNonZeroAudio (Zero streak: $consecutiveZeroFrames frames)
                            | System Media Playing: ${_isMediaPlaying.value} (ignoreDuringMediaPlayback=${currentConfig.ignoreDuringMediaPlayback})
                            | Detection Suspended: $isMediaActive, Snoring Trigger: effectiveIsSnoring=$effectiveIsSnoring (raw=${result.isSnoring})
                            |------------------------------------------
                            """.trimMargin()
                        )
                    }

                    // Aggregate timelines mapping sample dB
                    if (currentMillis - timelineTimer >= 500L) {
                        timelineTimer = currentMillis
                        val point = AmplitudePoint(
                            dbValue = result.db,
                            isSnore = effectiveIsSnoring,
                            timestamp = currentMillis
                        )
                        accumulatedTimelinePoints.add(point)
                        if (accumulatedTimelinePoints.size > 25000) {
                            accumulatedTimelinePoints.removeAt(0)
                        }
                        _currentSessionData.value = ArrayList(accumulatedTimelinePoints)
                    }

                    // --- SNORE DETECTOR DEBOUNCE STATE MACHINE ---
                    if (effectiveIsSnoring) {
                            if (!isSnoringActive) {
                                isSnoringActive = true
                                snoreStartTime = currentMillis
                                hasNotifiedForCurrentEvent = false
                                snoreAudioBuffer.clear()
                                
                                snoreSumDb = 0.0f
                                snoreSumZcr = 0.0f
                                snoreSumBandEnergy = 0.0f
                                snoreSumLowFreqRatio = 0.0f
                                snoreMaxRms = 0.0f
                                snoreBlocksCount = 0
                                
                                _isCurrentlySnoring.value = true
                                updateNotification("Snoring audio detected in progress...")
                            }

                            // Trigger real-time notification immediately when continuous duration meets minDurationSeconds
                            if (!hasNotifiedForCurrentEvent) {
                                val activeDurationMs = currentMillis - snoreStartTime
                                val targetDurationMs = (currentConfig.minDurationSeconds * 1000L).toLong()
                                if (activeDurationMs >= targetDurationMs) {
                                    hasNotifiedForCurrentEvent = true
                                    if (isNotifyOnSnoreEnabled) {
                                        sendSnoreEventNotification()
                                    }
                                }
                            }

                            if (snoreAudioBuffer.size < 187) {
                                snoreAudioBuffer.add(frameSamples)
                            }

                            snoreSumDb += result.db
                            snoreSumZcr += result.zcr
                            snoreSumBandEnergy += result.bandEnergy
                            snoreSumLowFreqRatio += result.lowFreqEnergyRatio
                            snoreMaxRms = snoreMaxRms.coerceAtLeast(result.rms)
                            snoreBlocksCount++

                            snoreLastDetectedTime = currentMillis

                        } else {
                            if (isSnoringActive) {
                                val elapsedSilenceMs = currentMillis - snoreLastDetectedTime
                                
                                if (elapsedSilenceMs >= 2500L) {
                                    val durationSec = (snoreLastDetectedTime - snoreStartTime) / 1000.0
                                    
                                    if (durationSec >= currentConfig.minDurationSeconds && snoreBlocksCount > 0) {
                                        val avgDb = snoreSumDb / snoreBlocksCount
                                        val avgZcr = snoreSumZcr / snoreBlocksCount
                                        val avgBand = snoreSumBandEnergy / snoreBlocksCount
                                        val avgLowFreq = snoreSumLowFreqRatio / snoreBlocksCount

                                        var savedAudioPath: String? = null
                                        
                                        if (isSaveClipsEnabled && snoreAudioBuffer.isNotEmpty()) {
                                            try {
                                                val dir = File(applicationContext.filesDir, "snore_clips")
                                                if (!dir.exists()) dir.mkdirs()
                                                
                                                val clipFile = File(dir, "snore_${snoreStartTime}.wav")
                                                WavWriter.saveWavFile(clipFile, sampleRate, snoreAudioBuffer)
                                                savedAudioPath = clipFile.absolutePath
                                            } catch (e: Exception) {
                                                Log.e(TAG, "WAV write failed", e)
                                                ErrorLogger.log(
                                                    context = applicationContext,
                                                    errorType = "AUDIO_CLIP_SAVE_ERROR",
                                                    message = "Failed to save snore audio clip: ${e.message}",
                                                    throwable = e,
                                                    component = "WavWriter",
                                                    additionalDiagnostics = mapOf("ClipStartTime" to snoreStartTime.toString())
                                                )
                                            }
                                        }

                                        val event = SnoreEvent(
                                            timestamp = snoreStartTime,
                                            durationSeconds = durationSec,
                                            maxDb = avgDb,
                                            maxRms = snoreMaxRms,
                                            meanZcr = avgZcr,
                                            meanBandEnergy = avgBand,
                                            meanLowFreqRatio = avgLowFreq,
                                            audioFilePath = savedAudioPath
                                        )
                                        
                                        launch {
                                            val rowId = repository.insertEvent(event)
                                            Log.d(TAG, "SnoreEvent saved. DB Row ID: $rowId")
                                            _sessionEventCount.value = _sessionEventCount.value + 1
                                        }
                                    }

                                    isSnoringActive = false
                                    hasNotifiedForCurrentEvent = false
                                    snoreAudioBuffer.clear()
                                    _isCurrentlySnoring.value = false
                                    updateNotification("Monitoring bedroom acoustics dynamically...")
                                } else {
                                    if (snoreAudioBuffer.size < 187) {
                                        snoreAudioBuffer.add(frameSamples)
                                    }
                                }
                            }
                        }
                }
            } finally {
                if (isSnoringActive && snoreBlocksCount > 0) {
                    val durationSec = (snoreLastDetectedTime - snoreStartTime) / 1000.0
                    if (durationSec >= currentConfig.minDurationSeconds) {
                        val avgDb = snoreSumDb / snoreBlocksCount
                        val avgZcr = snoreSumZcr / snoreBlocksCount
                        val avgBand = snoreSumBandEnergy / snoreBlocksCount
                        val avgLowFreq = snoreSumLowFreqRatio / snoreBlocksCount

                        var savedAudioPath: String? = null
                        if (isSaveClipsEnabled && snoreAudioBuffer.isNotEmpty()) {
                            try {
                                val dir = File(applicationContext.filesDir, "snore_clips")
                                if (!dir.exists()) dir.mkdirs()
                                val clipFile = File(dir, "snore_${snoreStartTime}.wav")
                                WavWriter.saveWavFile(clipFile, sampleRate, snoreAudioBuffer)
                                savedAudioPath = clipFile.absolutePath
                            } catch (e: Exception) {
                                Log.e(TAG, "WAV write failed", e)
                                ErrorLogger.log(
                                    context = applicationContext,
                                    errorType = "AUDIO_CLIP_SAVE_ERROR",
                                    message = "Failed to save final snore audio clip: ${e.message}",
                                    throwable = e,
                                    component = "WavWriter",
                                    additionalDiagnostics = mapOf("ClipStartTime" to snoreStartTime.toString())
                                )
                            }
                        }

                        val event = SnoreEvent(
                            timestamp = snoreStartTime,
                            durationSeconds = durationSec,
                            maxDb = avgDb,
                            maxRms = snoreMaxRms,
                            meanZcr = avgZcr,
                            meanBandEnergy = avgBand,
                            meanLowFreqRatio = avgLowFreq,
                            audioFilePath = savedAudioPath
                        )
                        repository.insertEvent(event)
                        _sessionEventCount.value = _sessionEventCount.value + 1
                    }
                }

                releaseAudioEffects()
                try {
                    if (record?.state == AudioRecord.STATE_INITIALIZED) {
                        record?.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord in finally", e)
                }
                try {
                    record?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing AudioRecord in finally", e)
                }
                if (audioRecord == record) {
                    audioRecord = null
                }
                Log.d(TAG, "Record thread loop finished & hardware released safely")
            }
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        releaseAudioEffects()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.i(TAG, "AcousticEchoCanceler enabled on audioSessionId $audioSessionId (success=${acousticEchoCanceler?.enabled})")
            } else {
                Log.d(TAG, "AcousticEchoCanceler is not supported on this device/HAL")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize AcousticEchoCanceler on audioSessionId $audioSessionId: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.i(TAG, "NoiseSuppressor enabled on audioSessionId $audioSessionId (success=${noiseSuppressor?.enabled})")
            } else {
                Log.d(TAG, "NoiseSuppressor is not supported on this device/HAL")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize NoiseSuppressor on audioSessionId $audioSessionId: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try {
            acousticEchoCanceler?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AcousticEchoCanceler: ${e.message}")
        }
        acousticEchoCanceler = null

        try {
            noiseSuppressor?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing NoiseSuppressor: ${e.message}")
        }
        noiseSuppressor = null
    }

    private fun stopAudioCaptureInternal() {
        releaseAudioEffects()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord in internal stop", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord in internal stop", e)
        }
        audioRecord = null
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun stopAudioCapture() {
        if (!isRecording.getAndSet(false)) return
        stopAudioCaptureInternal()
        AudioInputManager.disableBluetoothCommunicationRouting(applicationContext)
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * Send an immediate Android notification when a snoring incident is confirmed.
     * Delivered on a dedicated high-priority channel suitable for notification-forwarding tools (e.g. Gadgetbridge).
     */
    private fun sendSnoreEventNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted; skipping event notification")
                return
            }
        }

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_EVENT_ID)
            .setContentTitle("Snoring Detected")
            .setContentText("A snoring incident was detected.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(EVENT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, SnoreDetectionService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Snore Detector Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Monitoring", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Foreground Service Channel (Low importance, persistent)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Acoustics Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time snoring sound detection, FFT analysis, and offline persistence"
            }
            manager.createNotificationChannel(serviceChannel)

            // Real-Time Snoring Event Alerts Channel (High importance, forwardable to companion smartwatches)
            val eventChannel = NotificationChannel(
                CHANNEL_EVENT_ID,
                "Snoring Event Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time notifications sent immediately when a snoring episode is confirmed"
                enableVibration(true)
            }
            manager.createNotificationChannel(eventChannel)
        }
    }

    /**
     * Apply preferred audio input device to AudioRecord without affecting media audio routing or audio focus.
     */
    private fun applyPreferredAudioDevice(
        record: AudioRecord,
        deviceId: Int,
        deviceName: String,
        resolvedTarget: AudioDeviceInfo? = null
    ): Boolean {
        val configuredName = if (deviceName.isNotBlank()) deviceName else AudioInputDevice.PHONE_MIC.name
        _configuredInputDeviceName.value = configuredName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val targetDevice = resolvedTarget
                ?: AudioInputManager.findMatchingDeviceInfo(this, deviceId, deviceName)
                ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(this)

            if (targetDevice != null) {
                try {
                    val addressStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) targetDevice.address ?: "N/A" else "N/A"
                    val setOk = record.setPreferredDevice(targetDevice)
                    val activePreferred = record.preferredDevice
                    val currentRouted = record.routedDevice

                    val eval = AudioInputManager.evaluateAudioRouting(
                        configuredId = deviceId,
                        configuredName = deviceName,
                        routedDevice = currentRouted ?: activePreferred ?: targetDevice
                    )

                    val channelCountsStr = targetDevice.channelCounts.let { if (it.isEmpty()) "Standard/All" else it.joinToString(", ") }
                    val sampleRatesStr = targetDevice.sampleRates.let { if (it.isEmpty()) "Standard/Resampled" else it.joinToString(", ") { r -> "${r}Hz" } }
                    val encodingsStr = targetDevice.encodings.let { if (it.isEmpty()) "Standard/PCM16" else it.joinToString(", ") }

                    Log.i(
                        TAG,
                        """
                        |=== [Audio Input Device Inspection & Configuration] ===
                        |  * Configured input: ${eval.configuredDisplayName}
                        |  * Target Device ID: ${targetDevice.id}
                        |  * Target Type: ${targetDevice.type} (${AudioInputManager.getDeviceTypeName(targetDevice.type)})
                        |  * Target Product Name: "${targetDevice.productName}"
                        |  * Target Address: $addressStr
                        |  * Supported Channel Counts: [$channelCountsStr]
                        |  * Supported Sample Rates: [$sampleRatesStr]
                        |  * Supported Encodings: [$encodingsStr]
                        |  * Preferred Device Set Result: $setOk
                        |  * AudioRecord.getPreferredDevice(): "${activePreferred?.productName}" (id=${activePreferred?.id ?: "null"})
                        |  * AudioRecord.getRoutedDevice(): "${currentRouted?.productName}" (id=${currentRouted?.id ?: "pending"})
                        |  * AudioRecord.getState(): ${record.state} (STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED})
                        |  * AudioRecord.getRecordingState(): ${record.recordingState} (RECORDSTATE_RECORDING=${AudioRecord.RECORDSTATE_RECORDING})
                        |  * Active UI Device Name: ${eval.activeDisplayName} (Fallback: ${eval.isFallback})
                        |======================================================
                        """.trimMargin()
                    )

                    _configuredInputDeviceName.value = eval.configuredDisplayName
                    _activeInputDeviceName.value = eval.activeDisplayName
                    _isFallbackActive.value = eval.isFallback
                    return setOk
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting preferred audio device to target", e)
                    val eval = AudioInputManager.evaluateAudioRouting(deviceId, deviceName, null)
                    _configuredInputDeviceName.value = eval.configuredDisplayName
                    _activeInputDeviceName.value = eval.activeDisplayName
                    _isFallbackActive.value = eval.isFallback
                    return false
                }
            } else {
                Log.w(TAG, "No valid AudioDeviceInfo found; keeping Phone Microphone")
                val eval = AudioInputManager.evaluateAudioRouting(deviceId, deviceName, null)
                _configuredInputDeviceName.value = eval.configuredDisplayName
                _activeInputDeviceName.value = eval.activeDisplayName
                _isFallbackActive.value = eval.isFallback
                return false
            }
        } else {
            val eval = AudioInputManager.evaluateAudioRouting(deviceId, deviceName, null)
            _configuredInputDeviceName.value = eval.configuredDisplayName
            _activeInputDeviceName.value = eval.activeDisplayName
            _isFallbackActive.value = eval.isFallback
            return false
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")
        if (headsetBroadcastReceiver != null) {
            try {
                unregisterReceiver(headsetBroadcastReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister headsetBroadcastReceiver: ${e.message}")
            }
            headsetBroadcastReceiver = null
        }
        mediaPlaybackDetector?.stopMonitoring()
        mediaPlaybackDetector = null
        stopAudioCapture()
        releaseAudioEffects()
        AudioInputManager.disableBluetoothCommunicationRouting(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        serviceJob.cancel()
        
        // Release WakeLock safely
        try {
            wakeLock?.apply {
                if (isHeld) {
                    release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null

        _isServiceRunning.value = false
        _isCurrentlySnoring.value = false
        _liveAnalysis.value = null
        _sessionStartTime.value = 0L
        _sessionEventCount.value = 0
        _isMediaPlaying.value = false
        _isDetectionSuspendedForMedia.value = false
        _configuredInputDeviceName.value = AudioInputDevice.DEFAULT_DEVICE.name
        _activeInputDeviceName.value = AudioInputDevice.DEFAULT_DEVICE.name
        _isFallbackActive.value = false
        super.onDestroy()
    }
}

