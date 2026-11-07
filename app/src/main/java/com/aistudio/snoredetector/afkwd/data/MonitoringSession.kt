package com.aistudio.snoredetector.afkwd.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig

/**
 * One monitoring run, from pressing start until the service stops.
 *
 * The row is inserted when capture starts and refreshed once a minute, so a run that ends without
 * a clean stop (process killed, phone off) keeps everything up to its last refresh.
 *
 * Rows with [isBackfilled] were rebuilt from pre-v3 events. Monitored time was never recorded for
 * those nights, so [monitoredMinutes] is 0 and they cannot have a score.
 */
@Immutable
@Entity(tableName = "monitoring_sessions")
data class MonitoringSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,                       // Epoch millis
    val endTime: Long,                         // Epoch millis
    val monitoredMinutes: Double,              // Audio analysed, excluding media standby and dead input
    val snoreEventCount: Int = 0,
    val totalSnoreMinutes: Double = 0.0,
    val longestEpisodeSeconds: Double = 0.0,
    val avgPeakDb: Float? = null,              // Mean of the events' dB levels, null without events
    val maxPeakDb: Float? = null,              // Highest event dB level, null without events
    val backgroundDb: Float? = null,           // Median level of the non-snoring audio
    val appVersion: String? = null,
    val detectionSettings: String? = null,     // See DetectionConfig.toSnapshot()
    val isBackfilled: Boolean = false
)

/** Values the service knows about a running session; event summaries come from the database. */
data class SessionProgress(
    val endTime: Long,
    val monitoredMinutes: Double,
    val backgroundDb: Float?,
    val detectionSettings: String
)

/** The summary columns of a session, worked out from its events. */
data class EventSummary(
    val count: Int,
    val totalSnoreMinutes: Double,
    val longestEpisodeSeconds: Double,
    val avgPeakDb: Float?,
    val maxPeakDb: Float?
) {
    companion object {
        fun from(events: List<SnoreEvent>) = EventSummary(
            count = events.size,
            totalSnoreMinutes = events.sumOf { it.durationSeconds } / 60.0,
            longestEpisodeSeconds = events.maxOfOrNull { it.durationSeconds } ?: 0.0,
            avgPeakDb = if (events.isEmpty()) null else events.map { it.maxDb }.average().toFloat(),
            maxPeakDb = events.maxOfOrNull { it.maxDb }
        )
    }
}

fun MonitoringSession.withSummary(summary: EventSummary) = copy(
    snoreEventCount = summary.count,
    totalSnoreMinutes = summary.totalSnoreMinutes,
    longestEpisodeSeconds = summary.longestEpisodeSeconds,
    avgPeakDb = summary.avgPeakDb,
    maxPeakDb = summary.maxPeakDb
)

/** Canonical text form of the detection settings; two sessions differ only if this differs. */
fun DetectionConfig.toSnapshot(): String = listOf(
    "minDb=$rmsDbThreshold",
    "maxZcr=${if (useZcr) zcrThreshold else "off"}",
    "minBand=${if (useBandEnergy) bandEnergyThreshold else "off"}",
    "minLowFreq=${if (useLowFreqRatio) lowFreqRatioThreshold else "off"}",
    "minDuration=$minDurationSeconds",
    "ignoreMedia=$ignoreDuringMediaPlayback"
).joinToString(";")
