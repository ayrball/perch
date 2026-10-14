package com.perch.cam.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real migration SQL on Android's real SQLite engine. */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    // The exact schema shipped in version 1 of the app.
    private val v1Schema = listOf(
        """
        CREATE TABLE recordings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            filePath TEXT NOT NULL,
            fileName TEXT NOT NULL,
            durationSeconds INTEGER NOT NULL,
            fileSizeBytes INTEGER NOT NULL,
            createdAtTimestamp INTEGER NOT NULL,
            uploadStatus TEXT NOT NULL,
            driveFileId TEXT,
            thumbnailPath TEXT,
            hasMotion INTEGER NOT NULL,
            hasFace INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE alert_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            title TEXT NOT NULL,
            description TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            snapshotPath TEXT,
            faceCount INTEGER NOT NULL,
            confidence REAL NOT NULL
        )
        """.trimIndent()
    )

    private lateinit var db: SQLiteDatabase

    @Before
    fun open() {
        db = SQLiteDatabase.create(null) // in-memory
    }

    @After
    fun close() = db.close()

    private fun insertV1Recording(id: Int, status: String) = db.execSQL(
        "INSERT INTO recordings (filePath, fileName, durationSeconds, fileSizeBytes, createdAtTimestamp, " +
            "uploadStatus, driveFileId, thumbnailPath, hasMotion, hasFace) " +
            "VALUES ('/r/$id.mp4', '$id.mp4', 300, ${id * 1000L}, $id, '$status', NULL, NULL, 1, 0)"
    )

    private fun columns(table: String): List<List<String?>> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    // name, type, notnull, default
                    add(listOf(c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
                }
            }
        }

    private fun indexNames(): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test
    fun upgradeFromV1KeepsEveryRowAndDefaultsLocalAvailableToTrue() {
        v1Schema.forEach(db::execSQL)
        insertV1Recording(1, "UPLOADED")
        insertV1Recording(2, "PENDING")
        db.execSQL(
            "INSERT INTO alert_events (type, title, description, timestamp, faceCount, confidence) " +
                "VALUES ('MOTION', 't', 'd', 5, 0, 0.5)"
        )

        db.beginTransaction()
        try {
            PerchMigrations.statementsFor(1, PerchMigrations.VERSION).forEach(db::execSQL)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        db.rawQuery("SELECT id, uploadStatus, localAvailable, fileSizeBytes FROM recordings ORDER BY id", null).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("UPLOADED", c.getString(1))
            assertEquals("old rows must count as still on the phone", 1, c.getInt(2))
            assertEquals(1000L, c.getLong(3))
            c.moveToNext()
            assertEquals("PENDING", c.getString(1))
        }
        db.rawQuery("SELECT COUNT(*) FROM alert_events", null).use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun migratedSchemaIsIdenticalToAFreshInstall() {
        v1Schema.forEach(db::execSQL)
        PerchMigrations.statementsFor(1, PerchMigrations.VERSION).forEach(db::execSQL)
        val migratedRecordings = columns("recordings")
        val migratedAlerts = columns("alert_events")
        val migratedIndexes = indexNames()
        close()

        open()
        PerchMigrations.CREATE_STATEMENTS.forEach(db::execSQL)
        assertEquals(columns("recordings"), migratedRecordings)
        assertEquals(columns("alert_events"), migratedAlerts)
        assertEquals(indexNames(), migratedIndexes)
        assertTrue(migratedIndexes.containsAll(listOf("idx_recordings_status", "idx_alert_events_timestamp")))
    }

    @Test
    fun queriesTheAppRunsAreValidAgainstTheMigratedSchema() {
        v1Schema.forEach(db::execSQL)
        insertV1Recording(1, "UPLOADING")
        PerchMigrations.statementsFor(1, PerchMigrations.VERSION).forEach(db::execSQL)

        db.rawQuery("SELECT * FROM recordings WHERE localAvailable = 1 ORDER BY createdAtTimestamp ASC", null).use {
            assertEquals(1, it.count)
        }
        db.execSQL("UPDATE recordings SET uploadStatus = 'PENDING' WHERE uploadStatus = 'UPLOADING'")
        db.execSQL("UPDATE recordings SET localAvailable = 0 WHERE id = 1")
        db.execSQL("DELETE FROM recordings WHERE localAvailable = 0 AND createdAtTimestamp < 100")
        db.rawQuery("SELECT COUNT(*) FROM recordings", null).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun aFailedMigrationStepRollsBackInsteadOfLeavingAHalfUpgradedDatabase() {
        v1Schema.forEach(db::execSQL)
        insertV1Recording(1, "PENDING")
        val before = columns("recordings")

        db.beginTransaction()
        runCatching {
            db.execSQL(PerchMigrations.statementsFor(1, 2).first())
            db.execSQL("THIS IS NOT SQL")
            db.setTransactionSuccessful()
        }
        db.endTransaction()

        assertEquals(before, columns("recordings"))
    }
}
