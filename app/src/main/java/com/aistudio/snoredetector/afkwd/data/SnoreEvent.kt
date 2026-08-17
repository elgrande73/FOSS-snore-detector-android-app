package com.aistudio.snoredetector.afkwd.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Database Model representing a single detected snoring event.
 */
@Immutable
@Entity(tableName = "snore_events")
data class SnoreEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,                       // Start time of the snore event (Epoch Millis)
    val durationSeconds: Double,               // Length of the snoring incident (seconds)
    val maxDb: Float,                          // Peak audio level in relative dB
    val maxRms: Float,                         // Maximum RMS amplitude reached (0..1)
    val meanZcr: Float,                        // Mean zero-crossing rate over the event (0..1)
    val meanBandEnergy: Float,                 // Mean core band energy in the 100-1000Hz band
    val meanLowFreqRatio: Float,               // Mean ratio of energy below 500Hz
    val audioFilePath: String? = null          // Relative or absolute path to the WAV clip
)
