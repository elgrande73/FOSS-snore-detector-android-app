package com.aistudio.snoredetector.afkwd.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for handling Database operations on error logs.
 */
@Dao
interface ErrorLogDao {

    @Query("SELECT * FROM error_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ErrorLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ErrorLog): Long

    @Query("DELETE FROM error_logs WHERE id = :id")
    suspend fun deleteLogById(id: Int)

    @Query("DELETE FROM error_logs")
    suspend fun deleteAllLogs()
}

