package com.aistudio.snoredetector.afkwd.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.snoredetector.afkwd.MainActivity
import com.aistudio.snoredetector.afkwd.data.AppDatabase
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
    private var isNotifyOnSnoreEnabled: Boolean = false
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

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError = _serviceError.asStateFlow()

        /**
         * Helper command to easily stop the service externally.
         */
        const val ACTION_STOP_SERVICE = "com.aistudio.snoredetector.afkwd.service.ACTION_STOP_SERVICE"
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
            isNotifyOnSnoreEnabled = it.getBooleanExtra("notifyOnSnore", false)
            
            currentConfig = DetectionConfig(
                useRms = it.getBooleanExtra("useRms", true),
                useZcr = it.getBooleanExtra("useZcr", true),
                useBandEnergy = it.getBooleanExtra("useBandEnergy", true),
                useLowFreqRatio = it.getBooleanExtra("useLowFreqRatio", true),
                rmsDbThreshold = it.getFloatExtra("rmsDbThreshold", 55.0f),
                zcrThreshold = it.getFloatExtra("zcrThreshold", 0.15f),
                bandEnergyThreshold = it.getFloatExtra("bandEnergyThreshold", 0.015f),
                lowFreqRatioThreshold = it.getFloatExtra("lowFreqRatioThreshold", 0.65f),
                minDurationSeconds = it.getFloatExtra("minDurationSeconds", 1.0f)
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
                _isServiceRunning.value = false
                stopSelf()
                return@launch
            }

            // Ensure buffer size is larger than processing window
            val finalBufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    finalBufferSize
                )
            } catch (e: SecurityException) {
                _serviceError.value = "Microphone record audio permissions not granted"
                _isServiceRunning.value = false
                stopSelf()
                return@launch
            } catch (e: Exception) {
                _serviceError.value = "Failed to initialize microphone hardware."
                _isServiceRunning.value = false
                stopSelf()
                return@launch
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                _serviceError.value = "Failed to initialize microphone hardware. Device might be busy."
                try { record.release() } catch (_: Exception) {}
                _isServiceRunning.value = false
                stopSelf()
                return@launch
            }

            audioRecord = record

            try {
                record.startRecording()
            } catch (e: Exception) {
                _serviceError.value = "Microphone is being locked by another application."
                try { record.release() } catch (_: Exception) {}
                audioRecord = null
                _isServiceRunning.value = false
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

            try {
                while (isActive && isRecording.get()) {
                    val readResult = try {
                        record.read(audioBuffer, 0, audioBuffer.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading audio buffer", e)
                        -1
                    }

                    if (!isRecording.get() || !isActive) break

                    if (readResult > 0) {
                        val frameSamples = audioBuffer.copyOf(readResult)
                        
                        // Run signal processing algorithms
                        val result = snoreAnalyzer.analyze(frameSamples, currentConfig)

                        // Post real-time analytics to dashboard flow
                        _liveAnalysis.value = result

                        // Aggregate timelines mapping sample dB
                        val currentMillis = System.currentTimeMillis()
                        if (currentMillis - timelineTimer >= 500L) {
                            timelineTimer = currentMillis
                            val point = AmplitudePoint(
                                dbValue = result.db,
                                isSnore = result.isSnoring,
                                timestamp = currentMillis
                            )
                            accumulatedTimelinePoints.add(point)
                            if (accumulatedTimelinePoints.size > 25000) {
                                accumulatedTimelinePoints.removeAt(0)
                            }
                            _currentSessionData.value = ArrayList(accumulatedTimelinePoints)
                        }

                        // --- SNORE DETECTOR DEBOUNCE STATE MACHINE ---
                        if (result.isSnoring) {
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

                try {
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord in finally", e)
                }
                try {
                    record.release()
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

    private fun stopAudioCapture() {
        if (!isRecording.getAndSet(false)) return
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        recordingJob?.cancel()
        recordingJob = null
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

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")
        stopAudioCapture()

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
        super.onDestroy()
    }
}

