package com.perch.cam.control

import com.perch.cam.camera.FaceBox
import com.perch.cam.data.AlertEvent

data class BatteryInfo(val percent: Int, val charging: Boolean, val temperatureC: Float)

data class StorageInfo(val usedBytes: Long, val limitBytes: Long, val freeBytes: Long)

data class BackupInfo(
    /** "disconnected" | "connected" | "needs_auth" */
    val state: String,
    val account: String,
    val autoUpload: Boolean,
    val intervalMinutes: Int,
    val pending: Int,
    val uploaded: Int,
    val quotaUsedBytes: Long?,
    val quotaTotalBytes: Long?
)

/** Everything `GET /api/status` reports. See docs/CONTROL_API.md for the wire format. */
data class StatusSnapshot(
    val deviceName: String,
    val timestamp: Long,
    val recording: Boolean,
    val recordingSeconds: Long,
    val segmentMinutes: Int,
    val streaming: Boolean,
    val streamState: String,
    val motion: Boolean,
    val faceCount: Int,
    val faces: List<FaceBox>,
    val flashlight: Boolean,
    val camera: String,
    val videoQuality: String,
    val throttled: Boolean,
    val sirenActive: Boolean,
    val battery: BatteryInfo,
    val storage: StorageInfo,
    val backup: BackupInfo
) {
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "deviceName" to deviceName,
        "timestamp" to timestamp,
        "recording" to recording,
        "recordingSeconds" to recordingSeconds,
        "segmentMinutes" to segmentMinutes,
        "streaming" to streaming,
        "streamState" to streamState,
        "motion" to motion,
        "faceCount" to faceCount,
        "faces" to faces.map { mapOf("x" to it.x, "y" to it.y, "w" to it.w, "h" to it.h) },
        "flashlight" to flashlight,
        "camera" to camera,
        "videoQuality" to videoQuality,
        "throttled" to throttled,
        "sirenActive" to sirenActive,
        "battery" to mapOf(
            "percent" to battery.percent,
            "charging" to battery.charging,
            "temperatureC" to battery.temperatureC
        ),
        "storage" to mapOf(
            "usedBytes" to storage.usedBytes,
            "limitBytes" to storage.limitBytes,
            "freeBytes" to storage.freeBytes
        ),
        "backup" to mapOf(
            "state" to backup.state,
            "account" to backup.account,
            "autoUpload" to backup.autoUpload,
            "intervalMinutes" to backup.intervalMinutes,
            "pending" to backup.pending,
            "uploaded" to backup.uploaded,
            "quotaUsedBytes" to backup.quotaUsedBytes,
            "quotaTotalBytes" to backup.quotaTotalBytes
        )
    )
}

fun AlertEvent.toJsonMap(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "type" to type.name,
    "title" to title,
    "description" to description,
    "timestamp" to timestamp,
    "faceCount" to faceCount,
    "confidence" to confidence
)
