package com.perch.cam.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.perch.cam.data.PerchPreferences
import com.perch.cam.data.PerchSettings
import com.perch.cam.data.RecordingDao
import com.perch.cam.data.RecordingItem
import com.perch.cam.data.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

private class InMemoryRecordingDao : RecordingDao {
    val rows = MutableStateFlow<List<RecordingItem>>(emptyList())
    private var nextId = 1L

    fun add(item: RecordingItem): RecordingItem {
        val saved = item.copy(id = nextId++)
        rows.value = rows.value + saved
        return saved
    }

    fun get(id: Long) = rows.value.first { it.id == id }
    private fun change(id: Long, f: (RecordingItem) -> RecordingItem) {
        rows.value = rows.value.map { if (it.id == id) f(it) else it }
    }

    override fun getAllRecordings(): Flow<List<RecordingItem>> = rows
    override suspend fun getRecordingsByStatus(status: UploadStatus) = rows.value.filter { it.uploadStatus == status }
    override suspend fun getLocalRecordings() = rows.value.filter { it.localAvailable }.sortedBy { it.createdAtTimestamp }
    override suspend fun countByStatus(status: UploadStatus) = rows.value.count { it.uploadStatus == status }
    override suspend fun getRecordingById(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun insertRecording(item: RecordingItem) = add(item).id
    override suspend fun updateRecording(item: RecordingItem) = change(item.id) { item }
    override suspend fun updateUploadStatus(id: Long, status: UploadStatus, driveId: String?) =
        change(id) { it.copy(uploadStatus = status, driveFileId = driveId) }
    override suspend fun resetStuckUploads() {
        rows.value = rows.value.map { if (it.uploadStatus == UploadStatus.UPLOADING) it.copy(uploadStatus = UploadStatus.PENDING) else it }
    }
    override suspend fun markLocalDeleted(id: Long) = change(id) { it.copy(localAvailable = false) }
    override suspend fun deleteHistoryOlderThan(olderThanTimestamp: Long) {
        rows.value = rows.value.filterNot { !it.localAvailable && it.createdAtTimestamp < olderThanTimestamp }
    }
    override suspend fun deleteRecording(item: RecordingItem) {
        rows.value = rows.value.filterNot { it.id == item.id }
    }
    override fun getTotalStorageUsed(): Flow<Long?> = rows.map { l -> l.filter { it.localAvailable }.sumOf { it.fileSizeBytes } }
}

/**
 * Real files in a scratch directory, real PerchPreferences (restored afterwards), fake database.
 * Row sizes are declared in the database, so multi-hundred-MB "recordings" are just a number.
 */
@RunWith(AndroidJUnit4::class)
class RetentionManagerInstrumentedTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb
    private val now = System.currentTimeMillis()

    private lateinit var dir: File
    private lateinit var dao: InMemoryRecordingDao
    private lateinit var prefs: PerchPreferences
    private lateinit var originalSettings: PerchSettings
    private lateinit var manager: RetentionManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "retention-test-${System.nanoTime()}").apply { mkdirs() }
        dao = InMemoryRecordingDao()
        prefs = PerchPreferences(context)
        originalSettings = prefs.settingsFlow.value
        manager = RetentionManager(dao, prefs, dir)
    }

    @After
    fun tearDown() {
        prefs.updateSettings { originalSettings }
        dir.deleteRecursively()
    }

    private fun settings(f: (PerchSettings) -> PerchSettings) = prefs.updateSettings(f)

    private fun file(name: String, ageMs: Long = 0): File =
        File(dir, name).apply {
            writeText("video")
            if (ageMs > 0) setLastModified(now - ageMs)
        }

    private fun recording(
        name: String,
        status: UploadStatus,
        sizeMb: Long = 10,
        createdAt: Long = now,
        withFile: Boolean = true
    ): RecordingItem {
        val f = if (withFile) file(name) else File(dir, name)
        return dao.add(
            RecordingItem(
                filePath = f.absolutePath, fileName = name, durationSeconds = 300,
                fileSizeBytes = sizeMb * mb, createdAtTimestamp = createdAt, uploadStatus = status
            )
        )
    }

    @Test
    fun deletesUploadedCopiesOnlyWhenDriveIsConnectedAndTheOptionIsOn() = runBlocking {
        settings { it.copy(googleDriveConnected = true, googleDriveAccount = "a@b.c", deleteAfterUpload = true, maxStorageGb = 8) }
        val uploaded = recording("up.mp4", UploadStatus.UPLOADED)
        val pending = recording("pend.mp4", UploadStatus.PENDING)

        val result = manager.enforce()

        assertEquals(1, result.deletedFiles)
        assertFalse(File(uploaded.filePath).exists())
        assertFalse(dao.get(uploaded.id).localAvailable)
        assertTrue("not-yet-uploaded footage must survive", File(pending.filePath).exists())
        assertTrue(dao.get(pending.id).localAvailable)
    }

    @Test
    fun keepsUploadedCopiesWhenDriveIsNotConnected() = runBlocking {
        settings { it.copy(googleDriveConnected = false, googleDriveAccount = "", deleteAfterUpload = true, maxStorageGb = 8) }
        val uploaded = recording("up.mp4", UploadStatus.UPLOADED)

        assertEquals(0, manager.enforce().deletedFiles)
        assertTrue(File(uploaded.filePath).exists())
    }

    @Test
    fun overTheCapTheOldestFootageGoesFirstAndTheNewestSurvives() = runBlocking {
        settings { it.copy(googleDriveConnected = false, googleDriveAccount = "", maxStorageGb = 1) }
        val a = recording("a.mp4", UploadStatus.PENDING, sizeMb = 600, createdAt = now - 3000)
        val b = recording("b.mp4", UploadStatus.PENDING, sizeMb = 600, createdAt = now - 2000)
        val c = recording("c.mp4", UploadStatus.PENDING, sizeMb = 600, createdAt = now - 1000)

        val result = manager.enforce()

        assertEquals(2, result.deletedFiles)
        assertFalse(File(a.filePath).exists())
        assertFalse(File(b.filePath).exists())
        assertTrue(File(c.filePath).exists())
        assertEquals(listOf(c.id), dao.rows.value.filter { it.localAvailable }.map { it.id })
    }

    @Test
    fun neverDeletesARecordingThatIsUploadingRightNow() = runBlocking {
        settings { it.copy(googleDriveConnected = false, googleDriveAccount = "", maxStorageGb = 1) }
        val uploading = recording("u.mp4", UploadStatus.UPLOADING, sizeMb = 2000)

        manager.enforce()

        assertTrue(File(uploading.filePath).exists())
        assertTrue(dao.get(uploading.id).localAvailable)
    }

    @Test
    fun aRowWhoseFileDisappearedIsCorrectedButAnInFlightUploadIsLeftAlone() = runBlocking {
        settings { it.copy(maxStorageGb = 8) }
        val gone = recording("gone.mp4", UploadStatus.PENDING, withFile = false)
        val inFlight = recording("flying.mp4", UploadStatus.UPLOADING, withFile = false)

        manager.enforce()

        assertFalse(dao.get(gone.id).localAvailable)
        assertTrue(dao.get(inFlight.id).localAvailable)
    }

    @Test
    fun removesStaleUnindexedSegmentsButNotFreshOnesOrIndexedOnes() = runBlocking {
        settings { it.copy(maxStorageGb = 8) }
        val indexed = recording("indexed.mp4", UploadStatus.PENDING)
        File(indexed.filePath).setLastModified(now - TimeUnit.HOURS.toMillis(5))
        val staleOrphan = file("crashed.mp4", ageMs = TimeUnit.HOURS.toMillis(3))
        val freshOrphan = file("recording-now.mp4", ageMs = TimeUnit.MINUTES.toMillis(5))
        val notVideo = file("notes.txt", ageMs = TimeUnit.HOURS.toMillis(9))

        manager.enforce()

        assertFalse(staleOrphan.exists())
        assertTrue("the segment currently being written must not be touched", freshOrphan.exists())
        assertTrue(File(indexed.filePath).exists())
        assertTrue(notVideo.exists())
    }

    @Test
    fun prunesOnlyOldHistoryRowsWhoseFileIsGone() = runBlocking {
        settings { it.copy(maxStorageGb = 8) }
        val old = dao.add(
            RecordingItem(
                filePath = "/x/old.mp4", fileName = "old.mp4", durationSeconds = 1, fileSizeBytes = 1,
                createdAtTimestamp = now - TimeUnit.DAYS.toMillis(100), uploadStatus = UploadStatus.UPLOADED,
                localAvailable = false
            )
        )
        val recent = dao.add(old.copy(id = 0, createdAtTimestamp = now - TimeUnit.DAYS.toMillis(10)))

        manager.enforce()

        assertEquals(listOf(recent.id), dao.rows.value.map { it.id })
    }

    @Test
    fun runningTwiceIsHarmless() = runBlocking {
        settings { it.copy(googleDriveConnected = false, googleDriveAccount = "", maxStorageGb = 1) }
        recording("a.mp4", UploadStatus.PENDING, sizeMb = 900, createdAt = now - 2)
        recording("b.mp4", UploadStatus.PENDING, sizeMb = 900, createdAt = now - 1)

        val first = manager.enforce()
        val second = manager.enforce()

        assertEquals(1, first.deletedFiles)
        assertEquals(0, second.deletedFiles)
    }
}
