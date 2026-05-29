package com.example.data

import kotlinx.coroutines.flow.Flow

/**
 * Repository Pattern separating data layers from VM & Service logic.
 */
class SnoreRepository(private val snoreDao: SnoreDao) {

    val allEvents: Flow<List<SnoreEvent>> = snoreDao.getAllEvents()

    suspend fun insertEvent(event: SnoreEvent): Long {
        return snoreDao.insertEvent(event)
    }

    suspend fun deleteEventById(id: Int) {
        snoreDao.deleteEventById(id)
    }

    suspend fun clearHistory() {
        snoreDao.deleteAllEvents()
    }
}
