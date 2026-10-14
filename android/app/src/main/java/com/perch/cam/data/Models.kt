package com.perch.cam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class UploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED
}

enum class AlertType {
    MOTION,
    FACE_STRANGER,
    FACE_RECOGNIZED,
    TAMPER,
    PET
}

@Entity(tableName = "recordings")
data class RecordingItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val durationSeconds: Long,
    val fileSizeBytes: Long,
    val createdAtTimestamp: Long = System.currentTimeMillis(),
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val driveFileId: String? = null,
    val thumbnailPath: String? = null,
    val hasMotion: Boolean = false,
    val hasFace: Boolean = false,
    /** False once the local mp4 was removed by the retention policy (the history row is kept). */
    val localAvailable: Boolean = true
)

@Entity(tableName = "alert_events")
data class AlertEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: AlertType,
    val title: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val snapshotPath: String? = null,
    val faceCount: Int = 0,
    val confidence: Float = 0.0f
)
