package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.data.AudioExportManager
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioExportManagerTest {

    @Test
    fun testAudioFileNameGeneration() {
        val timestamp = 1771372800000L // Specific epoch millis
        val fileName = AudioExportManager.formatAudioFileName(timestamp, eventId = 42)
        assertTrue(fileName.startsWith("SnoreDetector_"))
        assertTrue(fileName.endsWith(".wav"))
    }

    @Test
    fun testCsvGenerationWithEvents() {
        val testEvents = listOf(
            SnoreEvent(
                id = 1,
                timestamp = 1771372800000L,
                durationSeconds = 2.4f,
                maxDb = 68.5f,
                maxRms = 0.045f,
                meanZcr = 0.08f,
                meanBandEnergy = 0.035f,
                meanLowFreqRatio = 0.78f,
                audioFilePath = "/dummy/path/audio.wav"
            ),
            SnoreEvent(
                id = 2,
                timestamp = 1771372860000L,
                durationSeconds = 1.8f,
                maxDb = 62.0f,
                maxRms = 0.032f,
                meanZcr = 0.09f,
                meanBandEnergy = 0.028f,
                meanLowFreqRatio = 0.72f,
                audioFilePath = null
            )
        )

        val csv = AudioExportManager.generateCsvContent(testEvents)
        assertNotNull(csv)
        assertTrue(csv.contains("Timestamp,Datetime,Duration_Seconds,dB_Level,Max_RMS,Mean_ZCR,Mean_BandEnergy,Mean_LowFreqRatio,AudioClip"))
        assertTrue(csv.contains("2.4,68.5"))
        assertTrue(csv.contains("1.8,62.0"))
        assertTrue(csv.contains("None"))
    }
}
