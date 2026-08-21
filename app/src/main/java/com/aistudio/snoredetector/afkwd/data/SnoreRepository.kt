package com.aistudio.snoredetector.afkwd.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Repository Pattern separating data layers from VM & Service logic.
 */
class SnoreRepository(
    private val snoreDao: SnoreDao,
    private val errorLogDao: ErrorLogDao? = null
) {

    val allEvents: Flow<List<SnoreEvent>> = snoreDao.getAllEvents()
    val allErrorLogs: Flow<List<ErrorLog>> = errorLogDao?.getAllLogs() ?: emptyFlow()

    suspend fun insertEvent(event: SnoreEvent): Long {
        return snoreDao.insertEvent(event)
    }

    suspend fun deleteEventById(id: Int) {
        snoreDao.deleteEventById(id)
    }

    suspend fun clearHistory() {
        snoreDao.deleteAllEvents()
    }

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

