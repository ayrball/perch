package com.perch.cam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAtTimestamp DESC")
    fun getAllRecordings(): Flow<List<RecordingItem>>

    @Query("SELECT * FROM recordings WHERE uploadStatus = :status ORDER BY createdAtTimestamp ASC")
    suspend fun getRecordingsByStatus(status: UploadStatus): List<RecordingItem>

    @Query("SELECT * FROM recordings WHERE localAvailable = 1 ORDER BY createdAtTimestamp ASC")
    suspend fun getLocalRecordings(): List<RecordingItem>

    @Query("SELECT COUNT(*) FROM recordings WHERE uploadStatus = :status")
    suspend fun countByStatus(status: UploadStatus): Int

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getRecordingById(id: Long): RecordingItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(item: RecordingItem): Long

    @Update
    suspend fun updateRecording(item: RecordingItem)

    @Query("UPDATE recordings SET uploadStatus = :status, driveFileId = :driveId WHERE id = :id")
    suspend fun updateUploadStatus(id: Long, status: UploadStatus, driveId: String?)

    /** Puts every in-flight upload back in the queue (used after a crash/kill mid-upload). */
    @Query("UPDATE recordings SET uploadStatus = 'PENDING' WHERE uploadStatus = 'UPLOADING'")
    suspend fun resetStuckUploads()

    @Query("UPDATE recordings SET localAvailable = 0 WHERE id = :id")
    suspend fun markLocalDeleted(id: Long)

    /** Drops history rows whose local file is gone and that are older than [olderThanTimestamp]. */
    @Query("DELETE FROM recordings WHERE localAvailable = 0 AND createdAtTimestamp < :olderThanTimestamp")
    suspend fun deleteHistoryOlderThan(olderThanTimestamp: Long)

    @Delete
    suspend fun deleteRecording(item: RecordingItem)

    /** Total bytes of recordings that still exist on the phone. */
    @Query("SELECT SUM(fileSizeBytes) FROM recordings WHERE localAvailable = 1")
    fun getTotalStorageUsed(): Flow<Long?>
}
