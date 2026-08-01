package com.aistudio.snoredetector.afkwd.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for handling Database operations on snoring events.
 */
@Dao
interface SnoreDao {

    @Query("SELECT * FROM snore_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<SnoreEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SnoreEvent): Long

    @Query("DELETE FROM snore_events WHERE id = :id")
    suspend fun deleteEventById(id: Int)

    @Query("DELETE FROM snore_events")
    suspend fun deleteAllEvents()
}
