package com.aistudio.snoredetector.afkwd.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Database Model representing a captured system or audio error log.
 * Privacy-preserving and strictly stored locally on device.
 */
@Immutable
@Entity(tableName = "error_logs")
data class ErrorLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(), // Epoch Millis when error occurred
    val errorType: String,                           // E.g. AUDIO_RECORD_ERROR, PLAYBACK_ERROR, EXPORT_ERROR
    val message: String,                             // Human-readable error description
    val diagnosticDetails: String,                   // Safe diagnostic data (OS version, device, stacktrace)
    val component: String = "App"                    // Subsystem: Service, AudioRecord, Player, Export, UI
)

