package com.perch.cam.storage

import com.perch.cam.data.RecordingItem
import com.perch.cam.data.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPlannerTest {

    private val mb = 1024L * 1024L

    private fun rec(id: Long, sizeMb: Long, status: UploadStatus, local: Boolean = true) = RecordingItem(
        id = id,
        filePath = "/r/$id.mp4",
        fileName = "$id.mp4",
        durationSeconds = 300,
        fileSizeBytes = sizeMb * mb,
        createdAtTimestamp = id, // id doubles as age: lower id = older
        uploadStatus = status,
        localAvailable = local
    )

    private fun policy(limitMb: Long, deleteAfterUpload: Boolean = false, minFreeMb: Long = 0) =
        RetentionPolicy(limitMb * mb, minFreeMb * mb, deleteAfterUpload)

    private fun ids(items: List<RecordingItem>) = items.map { it.id }

    @Test
    fun underLimitDeletesNothing() {
        val items = listOf(rec(1, 100, UploadStatus.PENDING), rec(2, 100, UploadStatus.UPLOADED))
        assertTrue(RetentionPlanner.plan(items, freeBytes = 10_000 * mb, policy = policy(500)).isEmpty())
    }

    @Test
    fun deleteAfterUploadRemovesEveryUploadedCopyAndNothingElse() {
        val items = listOf(
            rec(1, 100, UploadStatus.UPLOADED),
            rec(2, 100, UploadStatus.PENDING),
            rec(3, 100, UploadStatus.UPLOADED),
            rec(4, 100, UploadStatus.UPLOADING)
        )
        val plan = RetentionPlanner.plan(items, 10_000 * mb, policy(10_000, deleteAfterUpload = true))
        assertEquals(listOf(1L, 3L), ids(plan))
    }

    @Test
    fun overLimitEvictsUploadedBeforeUnbackedFootage() {
        val items = listOf(
            rec(1, 100, UploadStatus.PENDING),   // oldest, but not backed up
            rec(2, 100, UploadStatus.UPLOADED),
            rec(3, 100, UploadStatus.PENDING)
        )
        // 300 MB used, limit 250 -> only 50 MB must go; the uploaded one is the cheapest loss.
        val plan = RetentionPlanner.plan(items, 10_000 * mb, policy(250))
        assertEquals(listOf(2L), ids(plan))
    }

    @Test
    fun whenUploadedAreNotEnoughOldestPendingGoNext() {
        val items = listOf(
            rec(1, 100, UploadStatus.PENDING),
            rec(2, 100, UploadStatus.UPLOADED),
            rec(3, 100, UploadStatus.PENDING),
            rec(4, 100, UploadStatus.FAILED)
        )
        // 400 MB used, limit 150 -> 250 MB must go: uploaded(2), then oldest pending/failed (1, 3).
        val plan = RetentionPlanner.plan(items, 10_000 * mb, policy(150))
        assertEquals(listOf(2L, 1L, 3L), ids(plan))
    }

    @Test
    fun neverTouchesAnUploadInProgress() {
        val items = listOf(rec(1, 500, UploadStatus.UPLOADING), rec(2, 10, UploadStatus.PENDING))
        val plan = RetentionPlanner.plan(items, 10_000 * mb, policy(1))
        assertEquals(listOf(2L), ids(plan))
    }

    @Test
    fun lowDeviceFreeSpaceTriggersEvictionEvenUnderTheLimit() {
        val items = listOf(rec(1, 100, UploadStatus.UPLOADED), rec(2, 100, UploadStatus.PENDING))
        // Plenty of budget, but the phone has only 300 MB free and needs 1 GB.
        val plan = RetentionPlanner.plan(items, freeBytes = 300 * mb, policy = policy(10_000, minFreeMb = 1024))
        assertEquals(listOf(1L, 2L), ids(plan))
    }

    @Test
    fun ignoresRecordingsWhoseFileIsAlreadyGone() {
        val items = listOf(rec(1, 1000, UploadStatus.UPLOADED, local = false), rec(2, 10, UploadStatus.PENDING))
        assertTrue(RetentionPlanner.plan(items, 10_000 * mb, policy(100)).isEmpty())
    }
}
