package com.perch.cam.storage

import com.perch.cam.data.RecordingItem
import com.perch.cam.data.UploadStatus

data class RetentionPolicy(
    /** Maximum bytes of recordings kept on the phone. */
    val limitBytes: Long,
    /** Free device space that must remain available for the OS and the next segment. */
    val minFreeBytes: Long,
    /** Remove local copies as soon as they are safely in Google Drive. */
    val deleteAfterUpload: Boolean
)

/**
 * Pure decision logic for which local recordings to delete.
 *
 * Order of preference: already-uploaded copies first (they are safe in the cloud), then the
 * oldest recordings that never made it to the cloud. A recording that is being uploaded right
 * now is never touched. Deleting un-backed-up footage is the last resort, because the
 * alternative is a full disk and no recording at all.
 */
object RetentionPlanner {

    fun plan(local: List<RecordingItem>, freeBytes: Long, policy: RetentionPolicy): List<RecordingItem> {
        val candidates = local.filter { it.localAvailable }.sortedBy { it.createdAtTimestamp }
        val toDelete = LinkedHashSet<RecordingItem>()

        if (policy.deleteAfterUpload) {
            candidates.filterTo(toDelete) { it.uploadStatus == UploadStatus.UPLOADED }
        }

        fun bytesToFree(): Long {
            val used = candidates.filter { it !in toDelete }.sumOf { it.fileSizeBytes }
            val freedSoFar = toDelete.sumOf { it.fileSizeBytes }
            val overLimit = used - policy.limitBytes
            val underFree = policy.minFreeBytes - (freeBytes + freedSoFar)
            return maxOf(overLimit, underFree, 0L)
        }

        val evictionOrder = candidates.filter { it.uploadStatus == UploadStatus.UPLOADED } +
            candidates.filter { it.uploadStatus == UploadStatus.FAILED || it.uploadStatus == UploadStatus.PENDING }

        for (item in evictionOrder) {
            if (bytesToFree() <= 0L) break
            toDelete.add(item)
        }
        return toDelete.toList()
    }
}
