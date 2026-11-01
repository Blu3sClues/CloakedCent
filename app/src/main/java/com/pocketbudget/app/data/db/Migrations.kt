package com.pocketbudget.app.data.db

import androidx.room.migration.Migration

/**
 * Every schema migration, in order.
 *
 * Rules:
 * - Bump [PocketBudgetDatabase] `version`, build once to export the new schema
 *   JSON into app/schemas, and commit that JSON alongside the migration.
 * - Never edit an exported schema or a released migration.
 * - Every migration gets a test in MigrationTest.
 * - Destructive fallback is never enabled: a missing migration must crash in
 *   development rather than wipe a user's financial records.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
