package com.aistudio.snoredetector.afkwd.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.TimeZone

/**
 * Part of the v2 -> v3 migration: groups existing events into one session per night.
 *
 * The old schema kept no monitored time, so these sessions keep [MonitoringSession.monitoredMinutes]
 * at 0 and get no score. Guessing it from the first and last event would overstate snoring.
 */
internal object SessionBackfill {

    fun run(db: SupportSQLiteDatabase, timeZone: TimeZone) {
        val events = readEvents(db)
        events.groupBy { NightStats.nightEpochDay(it.timestamp, timeZone) }.values.forEach { night ->
            val session = MonitoringSession(
                startTime = night.minOf { it.timestamp },
                endTime = night.maxOf { it.timestamp + (it.durationSeconds * 1000).toLong() },
                monitoredMinutes = 0.0,
                isBackfilled = true
            ).withSummary(EventSummary.from(night))

            val sessionId = db.insert("monitoring_sessions", SQLiteDatabase.CONFLICT_ABORT, session.toContentValues())
            night.forEach { event ->
                db.execSQL("UPDATE snore_events SET sessionId = ? WHERE id = ?", arrayOf<Any>(sessionId, event.id))
            }
        }
    }

    private fun readEvents(db: SupportSQLiteDatabase): List<SnoreEvent> {
        val events = mutableListOf<SnoreEvent>()
        db.query("SELECT id, timestamp, durationSeconds, maxDb FROM snore_events ORDER BY timestamp").use { cursor ->
            while (cursor.moveToNext()) {
                events += SnoreEvent(
                    id = cursor.getInt(0),
                    timestamp = cursor.getLong(1),
                    durationSeconds = cursor.getDouble(2),
                    maxDb = cursor.getFloat(3),
                    maxRms = 0f,
                    meanZcr = 0f,
                    meanBandEnergy = 0f,
                    meanLowFreqRatio = 0f
                )
            }
        }
        return events
    }

    private fun MonitoringSession.toContentValues() = ContentValues().apply {
        put("startTime", startTime)
        put("endTime", endTime)
        put("monitoredMinutes", monitoredMinutes)
        put("snoreEventCount", snoreEventCount)
        put("totalSnoreMinutes", totalSnoreMinutes)
        put("longestEpisodeSeconds", longestEpisodeSeconds)
        put("avgPeakDb", avgPeakDb)
        put("maxPeakDb", maxPeakDb)
        put("isBackfilled", isBackfilled)
    }
}
