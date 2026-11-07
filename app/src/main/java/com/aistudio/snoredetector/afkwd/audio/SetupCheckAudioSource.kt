package com.aistudio.snoredetector.afkwd.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.aistudio.snoredetector.afkwd.data.ErrorLogger
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService

/** Audio feed for one setup check. It holds the microphone until [close]. */
interface SetupCheckAudioSource {
    /** Blocking read, same contract as [AudioRecord.read]. */
    fun read(buffer: ShortArray): Int
    fun isMediaPlaying(): Boolean
    fun close()
}

/**
 * Captures through the same path as SnoreDetectionService (input device, audio source, Bluetooth
 * routing, echo canceler, media detection), so that the check measures what monitoring would hear.
 */
class AudioRecordSetupCheckSource private constructor(
    private val context: Context,
    internal val record: AudioRecord,
    private val isBluetoothTarget: Boolean
) : SetupCheckAudioSource {

    private val mediaDetector = MediaPlaybackDetector(context)
    private var echoCanceler: AcousticEchoCanceler? = null

    private fun start(target: AudioDeviceInfo?) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize AcousticEchoCanceler: ${e.message}")
        }
        target?.let { record.setPreferredDevice(it) }
        mediaDetector.startMonitoring()
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start recording" }
    }

    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

    // Polling only while flagged mirrors the service: it catches a player's sleep timer running out.
    override fun isMediaPlaying(): Boolean =
        mediaDetector.isMediaPlaying.value && mediaDetector.checkCurrentPlaybackState().isMediaPlaying

    override fun close() {
        mediaDetector.stopMonitoring()
        try {
            echoCanceler?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AcousticEchoCanceler: ${e.message}")
        }
        echoCanceler = null
        try {
            record.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        try {
            record.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        releaseBluetoothRouting(context, isBluetoothTarget)
    }

    companion object {
        private const val TAG = "SetupCheckSource"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Opens and starts capture.
         *
         * @return the running source, or null if the microphone can't be used.
         * @throws SecurityException if microphone access was refused.
         */
        fun open(context: Context, inputId: Int, inputName: String): SetupCheckAudioSource? {
            val appContext = context.applicationContext
            val target = AudioInputManager.findMatchingDeviceInfo(appContext, inputId, inputName)
                ?: AudioInputManager.getBuiltInMicrophoneDeviceInfo(appContext)
            val isBluetoothTarget = target != null && AudioInputManager.isBluetoothType(target.type)

            if (isBluetoothTarget) {
                AudioInputManager.enableBluetoothCommunicationRouting(appContext, target)
            } else {
                AudioInputManager.disableBluetoothCommunicationRouting(appContext)
            }

            var source: AudioRecordSetupCheckSource? = null
            try {
                source = AudioRecordSetupCheckSource(appContext, createRecord(isBluetoothTarget), isBluetoothTarget)
                source.start(target)
                return source
            } catch (e: Exception) {
                if (source != null) source.close() else releaseBluetoothRouting(appContext, isBluetoothTarget)
                if (e is SecurityException) throw e
                ErrorLogger.log(
                    context = appContext,
                    errorType = "SETUP_CHECK_MIC_ERROR",
                    message = "Setup check could not start the microphone: ${e.message}",
                    throwable = e,
                    component = "AudioRecord"
                )
                return null
            }
        }

        private fun createRecord(isBluetoothTarget: Boolean): AudioRecord {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            check(minBufferSize > 0) { "Unsupported recording format ($minBufferSize)" }
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            val audioSource = SnoreDetectionService.selectAudioSource(isBluetoothTarget)
            val record = try {
                AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                if (audioSource == MediaRecorder.AudioSource.MIC) throw e
                Log.w(TAG, "Falling back to MIC after source $audioSource failed: ${e.message}")
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("AudioRecord failed to initialize")
            }
            return record
        }

        /** Monitoring owns the route once it runs, so clearing it then would cut off its microphone. */
        private fun releaseBluetoothRouting(context: Context, isBluetoothTarget: Boolean) {
            if (isBluetoothTarget && !SnoreDetectionService.isServiceRunning.value) {
                AudioInputManager.disableBluetoothCommunicationRouting(context)
            }
        }
    }
}
