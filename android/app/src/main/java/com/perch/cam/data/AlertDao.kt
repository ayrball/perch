package com.perch.cam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<AlertEvent>>

    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentAlerts(limit: Int = 10): Flow<List<AlertEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(event: AlertEvent): Long

    @Delete
    suspend fun deleteAlert(event: AlertEvent)

    @Query("DELETE FROM alert_events WHERE timestamp < :olderThanTimestamp")
    suspend fun pruneOldAlerts(olderThanTimestamp: Long)
}
