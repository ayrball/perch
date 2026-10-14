package com.perch.cam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerchMigrationsTest {

    @Test
    fun upgradeFromV1AddsColumnWithoutDroppingAnything() {
        val statements = PerchMigrations.statementsFor(1, PerchMigrations.VERSION)
        assertTrue(statements.any { it.contains("ADD COLUMN localAvailable") })
        assertTrue(
            "migrations must never drop data",
            statements.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) }
        )
    }

    @Test
    fun alreadyCurrentNeedsNoStatements() {
        assertTrue(PerchMigrations.statementsFor(PerchMigrations.VERSION, PerchMigrations.VERSION).isEmpty())
    }

    @Test
    fun freshInstallSchemaMatchesTheMigratedSchema() {
        val create = PerchMigrations.CREATE_STATEMENTS.joinToString("\n")
        assertTrue(create.contains("localAvailable INTEGER NOT NULL DEFAULT 1"))
        assertEquals(2, PerchMigrations.VERSION)
    }

    @Test
    fun rejectsDowngradesAndUnknownVersions() {
        assertFalse(runCatching { PerchMigrations.statementsFor(2, 1) }.isSuccess)
        assertFalse(runCatching { PerchMigrations.statementsFor(0, 2) }.isSuccess)
        assertFalse(runCatching { PerchMigrations.statementsFor(1, 99) }.isSuccess)
    }
}
