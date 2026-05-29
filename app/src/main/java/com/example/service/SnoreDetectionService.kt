package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.SnoreEvent
import com.example.data.SnoreRepository
import com.example.dsp.AmplitudePoint
import com.example.dsp.AnalysisResult
import com.example.dsp.DetectionConfig
import com.example.dsp.SnoreAnalyzer
import com.example.dsp.WavWriter
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service that continuously tracks breathing and snoring audio patterns in the background.
 * Optimized for up to 12 hours of offline operation.
 */
class SnoreDetectionService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var repository: SnoreRepository
    private val snoreAnalyzer = SnoreAnalyzer()

    // Service parameters
    private var isSaveClipsEnabled: Boolean = true
    private var currentConfig = DetectionConfig()
    private var startTimeMillis: Long = 0L

    companion object {
        private const val TAG = "SnoreService"
        private const val NOTIFICATION_ID = 54321
        private const val CHANNEL_ID = "snore_detector_service_channel"

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
        repository = SnoreRepository(database.snoreDao())

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
            
            currentConfig = DetectionConfig(
                useRms = it.getBooleanExtra("useRms", true),
                useZcr = it.getBooleanExtra("useZcr", true),
                useBandEnergy = it.getBooleanExtra("useBandEnergy", true),
                useLowFreqRatio = it.getBooleanExtra("useLowFreqRatio", true),
                rmsDbThreshold = it.getFloatExtra("rmsDbThreshold", 55.0f),
                zcrThreshold = it.getFloatExtra("zcrThreshold", 0.15f),
                bandEnergyThreshold = it.getFloatExtra("bandEnergyThreshold", 0.015f),
                lowFreqRatioThreshold = it.getFloatExtra("lowFreqRatioThreshold", 0.65f)
            )
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
            
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                _serviceError.value = "Hardware microphonic recording not supported"
                stopSelf()
                return@launch
            }

            // Ensure buffer size is larger than processing window (1024 shorts is 2048 bytes)
            val finalBufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    finalBufferSize
                )
            } catch (e: SecurityException) {
                _serviceError.value = "Microphone record audio permissions not granted"
                stopSelf()
                return@launch
            }

            val record = audioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                _serviceError.value = "Failed to initialize microphone hardware. Device might be busy."
                stopSelf()
                return@launch
            }

            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                _serviceError.value = "Microphone is being locked by another application."
                stopSelf()
                return@launch
            }

            Log.d(TAG, "AudioRecord started successfully")
            
            // Temporary timeline accumulation trackers
            var timelineTimer = 0L
            val accumulatedTimelinePoints = mutableListOf<AmplitudePoint>()
            _currentSessionData.value = emptyList() // clear previous session data

            // Snore state machine variables
            var isSnoringActive = false
            var snoreStartTime = 0L
            var snoreLastDetectedTime = 0L
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

            while (isActive && isRecording.get()) {
                val readResult = record.read(audioBuffer, 0, audioBuffer.size)
                if (readResult > 0) {
                    val frameSamples = audioBuffer.copyOf(readResult)
                    
                    // Run signal processing algorithms
                    val result = snoreAnalyzer.analyze(frameSamples, currentConfig)

                    // Post real-time analytics to dashboard flow
                    _liveAnalysis.value = result

                    // Aggregate timelines mapping sample dB (e.g., save 1 sample point every 500ms to save memory)
                    val currentMillis = System.currentTimeMillis()
                    if (currentMillis - timelineTimer >= 500L) {
                        timelineTimer = currentMillis
                        val point = AmplitudePoint(
                            dbValue = result.db,
                            isSnore = result.isSnoring,
                            timestamp = currentMillis
                        )
                        accumulatedTimelinePoints.add(point)
                        // Keep only first 25000 elements over many hours
                        if (accumulatedTimelinePoints.size > 25000) {
                            accumulatedTimelinePoints.removeAt(0)
                        }
                        _currentSessionData.value = ArrayList(accumulatedTimelinePoints)
                    }

                    // --- SNORE DETECTOR DEBOUNCE STATE MACHINE ---
                    if (result.isSnoring) {
                        if (!isSnoringActive) {
                            // Initiating a new continuous snoring segment
                            isSnoringActive = true
                            snoreStartTime = currentMillis
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

                        // Accumulate audio clips (up to max 12 seconds per file to avoid RAM issues)
                        if (snoreAudioBuffer.size < 187) { // 187 * 1024 samples ≈ 12 seconds
                            snoreAudioBuffer.add(frameSamples)
                        }

                        // Accumulate values
                        snoreSumDb += result.db
                        snoreSumZcr += result.zcr
                        snoreSumBandEnergy += result.bandEnergy
                        snoreSumLowFreqRatio += result.lowFreqEnergyRatio
                        snoreMaxRms = snoreMaxRms.coerceAtLeast(result.rms)
                        snoreBlocksCount++

                        snoreLastDetectedTime = currentMillis

                    } else {
                        // Current frame is quiet, check if we are in an active snore event
                        if (isSnoringActive) {
                            // Debounce: we allow up to 2.5 seconds of silence before finalizing the snore event.
                            val elapsedSilenceMs = currentMillis - snoreLastDetectedTime
                            
                            if (elapsedSilenceMs >= 2500L) {
                                // FINALISE continuous snoring incident
                                val durationSec = (snoreLastDetectedTime - snoreStartTime) / 1000.0
                                
                                // Only save if the event lasted longer than 1.0 seconds (filters out small knocks/coughs)
                                if (durationSec >= 1.0 && snoreBlocksCount > 0) {
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
                                        }
                                    }

                                    // Save to database
                                    val event = SnoreEvent(
                                        timestamp = snoreStartTime,
                                        durationSeconds = durationSec,
                                        maxDb = avgDb, // use average dB of high states
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

                                // Reset states
                                isSnoringActive = false
                                snoreAudioBuffer.clear()
                                _isCurrentlySnoring.value = false
                                updateNotification("Monitoring bedroom acoustics dynamically...")
                            } else {
                                // Quiet frame, but within debounce window. Accumulate quiet samples to record natural pauses.
                                if (snoreAudioBuffer.size < 187) {
                                    snoreAudioBuffer.add(frameSamples)
                                }
                            }
                        }
                    }
                } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION || readResult == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord reading error flag: $readResult")
                }
            }
            
            // Clean up if service is stopped while snoring is active
            if (isSnoringActive && snoreBlocksCount > 0) {
                val durationSec = (snoreLastDetectedTime - snoreStartTime) / 1000.0
                if (durationSec >= 1.0) {
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
            
            Log.d(TAG, "Record thread loop finished")
        }
    }

    private fun stopAudioCapture() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Acoustics Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time snoring sound detection, FFT analysis, and offline persistence"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")
        stopAudioCapture()
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
        super.onDestroy()
    }
}
