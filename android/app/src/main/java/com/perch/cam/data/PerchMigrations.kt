package com.perch.cam.data

/**
 * Incremental schema migrations for `perch_security.db`.
 *
 * The recordings table is the only record of what was backed up and where, so schema changes
 * must never drop tables. Add a new list at the end of [STEPS] and bump [VERSION] together.
 */
object PerchMigrations {

    /** Statements that create the current schema from scratch. */
    val CREATE_STATEMENTS: List<String> = listOf(
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
            hasFace INTEGER NOT NULL,
            localAvailable INTEGER NOT NULL DEFAULT 1
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
        """.trimIndent(),
        "CREATE INDEX idx_recordings_status ON recordings(uploadStatus, createdAtTimestamp)",
        "CREATE INDEX idx_alert_events_timestamp ON alert_events(timestamp)"
    )

    /** STEPS[n] upgrades schema version n+1 to n+2. */
    private val STEPS: List<List<String>> = listOf(
        // 1 -> 2: track local file availability for the retention policy, add lookup indexes.
        listOf(
            "ALTER TABLE recordings ADD COLUMN localAvailable INTEGER NOT NULL DEFAULT 1",
            "CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(uploadStatus, createdAtTimestamp)",
            "CREATE INDEX IF NOT EXISTS idx_alert_events_timestamp ON alert_events(timestamp)"
        )
    )

    val VERSION: Int = STEPS.size + 1

    fun statementsFor(oldVersion: Int, newVersion: Int): List<String> {
        require(oldVersion >= 1 && newVersion <= VERSION && oldVersion <= newVersion) {
            "Unsupported migration $oldVersion -> $newVersion (current schema is $VERSION)"
        }
        return (oldVersion until newVersion).flatMap { STEPS[it - 1] }
    }
}
