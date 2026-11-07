package com.aistudio.snoredetector.afkwd.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Repository Pattern separating data layers from VM & Service logic.
 */
class SnoreRepository(
    private val snoreDao: SnoreDao,
    private val errorLogDao: ErrorLogDao? = null,
    private val sessionDao: SessionDao? = null
) {

    val allEvents: Flow<List<SnoreEvent>> = snoreDao.getAllEvents()
    val allErrorLogs: Flow<List<ErrorLog>> = errorLogDao?.getAllLogs() ?: emptyFlow()
    val allSessions: Flow<List<MonitoringSession>> = sessionDao?.getAllSessions() ?: emptyFlow()

    suspend fun insertEvent(event: SnoreEvent): Long {
        return snoreDao.insertEvent(event)
    }

    /** Pass the event's [sessionId] so the session's summary and score stop counting it. */
    suspend fun deleteEventById(id: Int, sessionId: Long? = null) {
        snoreDao.deleteEventById(id)
        if (sessionId != null) sessionDao?.refreshSession(sessionId, null)
    }

    /** Clears events and sessions; [activeSessionId] survives so the running night still saves. */
    suspend fun clearHistory(activeSessionId: Long? = null) {
        snoreDao.deleteAllEvents()
        sessionDao?.deleteSessionsExcept(activeSessionId)
        if (activeSessionId != null) sessionDao?.refreshSession(activeSessionId, null)
    }

    suspend fun startSession(session: MonitoringSession): Long? = sessionDao?.insertSession(session)

    /** Stores the running totals; also used for the final update when the session ends. */
    suspend fun updateSession(id: Long, progress: SessionProgress) {
        sessionDao?.refreshSession(id, progress)
    }

    suspend fun getAllSessions(): List<MonitoringSession> = sessionDao?.getAllSessionsOnce() ?: emptyList()

    suspend fun insertErrorLog(log: ErrorLog): Long {
        return try {
            errorLogDao?.insertLog(log) ?: -1L
        } catch (e: Exception) {
            Log.e("SnoreRepository", "Failed to insert error log", e)
            -1L
        }
    }

    suspend fun deleteErrorLogById(id: Int) {
        try {
            errorLogDao?.deleteLogById(id)
        } catch (e: Exception) {
            Log.e("SnoreRepository", "Failed to delete error log", e)
        }
    }

    suspend fun clearErrorLogs() {
        try {
            errorLogDao?.deleteAllLogs()
        } catch (e: Exception) {
            Log.e("SnoreRepository", "Failed to clear error logs", e)
        }
    }
}

