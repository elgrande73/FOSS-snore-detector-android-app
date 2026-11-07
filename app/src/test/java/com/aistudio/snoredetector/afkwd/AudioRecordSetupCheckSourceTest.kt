package com.aistudio.snoredetector.afkwd

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.audio.AudioRecordSetupCheckSource
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRecordSetupCheckSourceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private fun inputDevice(type: Int): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        // The builder leaves the port empty: no role makes it an output, no arrays fail the device logging.
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        val audioPort = Class.forName("android.media.AudioPort")
        ReflectionHelpers.setField(audioPort, port, "mRole", 1 /* ROLE_SOURCE */)
        listOf("mSamplingRates", "mChannelMasks", "mChannelIndexMasks", "mFormats").forEach {
            ReflectionHelpers.setField(audioPort, port, it, IntArray(0))
        }
        return device
    }

    @Before
    fun setUp() {
        shadowOf(audioManager).setInputDevices(listOf(inputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)))
    }

    private fun open(inputId: Int, inputName: String) =
        AudioRecordSetupCheckSource.open(context, inputId, inputName) as AudioRecordSetupCheckSource

    @Test
    fun phoneMicUsesTheSameRawSourceAsDetection() {
        val source = open(-1, "")

        assertEquals(MediaRecorder.AudioSource.MIC, source.record.audioSource)
        assertEquals(16000, source.record.sampleRate)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, source.record.recordingState)
        source.close()
    }

    @Test
    fun bluetoothMicUsesTheSameVoiceRecognitionSourceAsDetection() {
        shadowOf(audioManager).setInputDevices(listOf(inputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))

        val source = open(7, "Bluetooth headset")

        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, source.record.audioSource)
        source.close()
    }

    @Test
    fun closeReleasesTheMicrophone() {
        val source = open(-1, "")

        source.close()

        assertEquals(AudioRecord.STATE_UNINITIALIZED, source.record.state)
        assertEquals(AudioRecord.RECORDSTATE_STOPPED, source.record.recordingState)
    }
}
