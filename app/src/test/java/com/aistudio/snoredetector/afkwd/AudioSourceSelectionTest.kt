package com.aistudio.snoredetector.afkwd

import android.media.MediaRecorder
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourceSelectionTest {

    @Test
    fun builtInUsbAndWiredInputsUseRawMicSource() {
        assertEquals(MediaRecorder.AudioSource.MIC, SnoreDetectionService.selectAudioSource(isBluetoothTarget = false))
    }

    @Test
    fun bluetoothInputUsesVoiceRecognitionSource() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SnoreDetectionService.selectAudioSource(isBluetoothTarget = true)
        )
    }
}
