package com.perch.cam.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

abstract class PerchDatabase {
    abstract fun recordingDao(): RecordingDao
    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: PerchDatabase? = null

        fun getDatabase(context: Context): PerchDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NativePerchDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

private class NativePerchDatabase(context: Context) : PerchDatabase() {

    private val dbHelper = DatabaseHelper(context)
    private val recordingDaoImpl = NativeRecordingDao(dbHelper)
    private val alertDaoImpl = NativeAlertDao(dbHelper)

    override fun recordingDao(): RecordingDao = recordingDaoImpl
    override fun alertDao(): AlertDao = alertDaoImpl

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, "perch_security.db", null, PerchMigrations.VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            PerchMigrations.CREATE_STATEMENTS.forEach(db::execSQL)
        }

        // SQLiteOpenHelper runs this inside a transaction, so a failed step rolls back cleanly.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            PerchMigrations.statementsFor(oldVersion, newVersion).forEach(db::execSQL)
        }
    }
}

private const val LIST_LIMIT = 1000

private fun Cursor.toRecording() = RecordingItem(
    id = getLong(getColumnIndexOrThrow("id")),
    filePath = getString(getColumnIndexOrThrow("filePath")),
    fileName = getString(getColumnIndexOrThrow("fileName")),
    durationSeconds = getLong(getColumnIndexOrThrow("durationSeconds")),
    fileSizeBytes = getLong(getColumnIndexOrThrow("fileSizeBytes")),
    createdAtTimestamp = getLong(getColumnIndexOrThrow("createdAtTimestamp")),
    uploadStatus = UploadStatus.valueOf(getString(getColumnIndexOrThrow("uploadStatus"))),
    driveFileId = getString(getColumnIndexOrThrow("driveFileId")),
    thumbnailPath = getString(getColumnIndexOrThrow("thumbnailPath")),
    hasMotion = getInt(getColumnIndexOrThrow("hasMotion")) == 1,
    hasFace = getInt(getColumnIndexOrThrow("hasFace")) == 1,
    localAvailable = getInt(getColumnIndexOrThrow("localAvailable")) == 1
)

private fun RecordingItem.toValues() = ContentValues().apply {
    put("filePath", filePath)
    put("fileName", fileName)
    put("durationSeconds", durationSeconds)
    put("fileSizeBytes", fileSizeBytes)
    put("createdAtTimestamp", createdAtTimestamp)
    put("uploadStatus", uploadStatus.name)
    put("driveFileId", driveFileId)
    put("thumbnailPath", thumbnailPath)
    put("hasMotion", if (hasMotion) 1 else 0)
    put("hasFace", if (hasFace) 1 else 0)
    put("localAvailable", if (localAvailable) 1 else 0)
}

private class NativeRecordingDao(private val dbHelper: SQLiteOpenHelper) : RecordingDao {

    private val _recordingsFlow = MutableStateFlow<List<RecordingItem>>(emptyList())

    init {
        refreshFlow()
    }

    private fun refreshFlow() {
        // The UI only ever shows the newest rows; reloading the whole history on every write would
        // grow without bound (a segment every 5 minutes is ~100k rows a year).
        _recordingsFlow.value = query("SELECT * FROM recordings ORDER BY createdAtTimestamp DESC LIMIT $LIST_LIMIT")
    }

    private fun query(sql: String, vararg args: String): List<RecordingItem> {
        val list = mutableListOf<RecordingItem>()
        dbHelper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) list.add(c.toRecording())
        }
        return list
    }

    override fun getAllRecordings(): Flow<List<RecordingItem>> = _recordingsFlow.asStateFlow()

    override suspend fun getRecordingsByStatus(status: UploadStatus): List<RecordingItem> = withContext(Dispatchers.IO) {
        query("SELECT * FROM recordings WHERE uploadStatus = ? ORDER BY createdAtTimestamp ASC", status.name)
    }

    override suspend fun getLocalRecordings(): List<RecordingItem> = withContext(Dispatchers.IO) {
        query("SELECT * FROM recordings WHERE localAvailable = 1 ORDER BY createdAtTimestamp ASC")
    }

    override suspend fun countByStatus(status: UploadStatus): Int = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM recordings WHERE uploadStatus = ?", arrayOf(status.name))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    override suspend fun getRecordingById(id: Long): RecordingItem? = withContext(Dispatchers.IO) {
        query("SELECT * FROM recordings WHERE id = ? LIMIT 1", id.toString()).firstOrNull()
    }

    override suspend fun insertRecording(item: RecordingItem): Long = withContext(Dispatchers.IO) {
        val insertedId = dbHelper.writableDatabase
            .insertWithOnConflict("recordings", null, item.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlow()
        insertedId
    }

    override suspend fun updateRecording(item: RecordingItem): Unit = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.update("recordings", item.toValues(), "id = ?", arrayOf(item.id.toString()))
        refreshFlow()
    }

    override suspend fun updateUploadStatus(id: Long, status: UploadStatus, driveId: String?): Unit = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("uploadStatus", status.name)
            put("driveFileId", driveId)
        }
        dbHelper.writableDatabase.update("recordings", values, "id = ?", arrayOf(id.toString()))
        refreshFlow()
    }

    override suspend fun resetStuckUploads(): Unit = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("uploadStatus", UploadStatus.PENDING.name) }
        dbHelper.writableDatabase.update(
            "recordings", values, "uploadStatus = ?", arrayOf(UploadStatus.UPLOADING.name)
        )
        refreshFlow()
    }

    override suspend fun markLocalDeleted(id: Long): Unit = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("localAvailable", 0) }
        dbHelper.writableDatabase.update("recordings", values, "id = ?", arrayOf(id.toString()))
        refreshFlow()
    }

    override suspend fun deleteHistoryOlderThan(olderThanTimestamp: Long): Unit = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(
            "recordings", "localAvailable = 0 AND createdAtTimestamp < ?", arrayOf(olderThanTimestamp.toString())
        )
        refreshFlow()
    }

    override suspend fun deleteRecording(item: RecordingItem): Unit = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("recordings", "id = ?", arrayOf(item.id.toString()))
        refreshFlow()
    }

    override fun getTotalStorageUsed(): Flow<Long?> = _recordingsFlow.map { list ->
        list.filter { it.localAvailable }.sumOf { it.fileSizeBytes }
    }
}

private class NativeAlertDao(private val dbHelper: SQLiteOpenHelper) : AlertDao {

    private val _alertsFlow = MutableStateFlow<List<AlertEvent>>(emptyList())

    init {
        refreshFlow()
    }

    private fun refreshFlow() {
        _alertsFlow.value = loadAllFromDb()
    }

    private fun loadAllFromDb(): List<AlertEvent> {
        val list = mutableListOf<AlertEvent>()
        dbHelper.readableDatabase.rawQuery("SELECT * FROM alert_events ORDER BY timestamp DESC LIMIT $LIST_LIMIT", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    AlertEvent(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        type = AlertType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        description = c.getString(c.getColumnIndexOrThrow("description")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                        snapshotPath = c.getString(c.getColumnIndexOrThrow("snapshotPath")),
                        faceCount = c.getInt(c.getColumnIndexOrThrow("faceCount")),
                        confidence = c.getFloat(c.getColumnIndexOrThrow("confidence"))
                    )
                )
            }
        }
        return list
    }

    override fun getAllAlerts(): Flow<List<AlertEvent>> = _alertsFlow.asStateFlow()

    override fun getRecentAlerts(limit: Int): Flow<List<AlertEvent>> = _alertsFlow.map { list ->
        list.take(limit)
    }

    override suspend fun insertAlert(event: AlertEvent): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("type", event.type.name)
            put("title", event.title)
            put("description", event.description)
            put("timestamp", event.timestamp)
            put("snapshotPath", event.snapshotPath)
            put("faceCount", event.faceCount)
            put("confidence", event.confidence)
        }
        val insertedId = dbHelper.writableDatabase
            .insertWithOnConflict("alert_events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlow()
        insertedId
    }

    override suspend fun deleteAlert(event: AlertEvent): Unit = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("alert_events", "id = ?", arrayOf(event.id.toString()))
        refreshFlow()
    }

    override suspend fun pruneOldAlerts(olderThanTimestamp: Long): Unit = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("alert_events", "timestamp < ?", arrayOf(olderThanTimestamp.toString()))
        refreshFlow()
    }
}
