package com.aistudio.snoredetector.afkwd.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for monitoring sessions.
 */
@Dao
interface SessionDao {

    @Query("SELECT * FROM monitoring_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<MonitoringSession>>

    @Query("SELECT * FROM monitoring_sessions ORDER BY startTime DESC")
    suspend fun getAllSessionsOnce(): List<MonitoringSession>

    @Query("SELECT * FROM monitoring_sessions WHERE id = :id")
    suspend fun getSession(id: Long): MonitoringSession?

    @Query("SELECT * FROM snore_events WHERE sessionId = :sessionId")
    suspend fun getEventsForSession(sessionId: Long): List<SnoreEvent>

    @Insert
    suspend fun insertSession(session: MonitoringSession): Long

    @Update
    suspend fun updateSession(session: MonitoringSession)

    @Query("DELETE FROM monitoring_sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long)

    /** Deletes every session except [keepId], which is the one still recording (if any). */
    @Query("DELETE FROM monitoring_sessions WHERE :keepId IS NULL OR id != :keepId")
    suspend fun deleteSessionsExcept(keepId: Long?)

    /**
     * Recalculates a session's summary from the events it still has, applying [progress] if given.
     * A backfilled session exists only because of its events, so it goes when the last one does.
     */
    @Transaction
    suspend fun refreshSession(id: Long, progress: SessionProgress?) {
        val session = getSession(id) ?: return
        val events = getEventsForSession(id)
        if (session.isBackfilled && events.isEmpty()) {
            deleteSessionById(id)
            return
        }
        val updated = if (progress == null) session else session.copy(
            endTime = progress.endTime,
            monitoredMinutes = progress.monitoredMinutes,
            backgroundDb = progress.backgroundDb,
            detectionSettings = progress.detectionSettings
        )
        updateSession(updated.withSummary(EventSummary.from(events)))
    }
}
