package com.cloakedcent.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Keeps deleted data out of the database file itself, not just out of its tables.
 *
 * - secure_delete makes SQLite overwrite deleted content with zeros instead of
 *   leaving it in free pages. It is per connection, so it is set on open and again
 *   at the start of every erasure.
 * - The write-ahead log keeps old copies of changed pages until a checkpoint; after
 *   an erasure it is checkpointed and truncated so those copies are gone.
 * - Data deleted before secure_delete was on may still sit in free pages; the v9
 *   migration requests a one-time VACUUM, which rewrites the whole file.
 *
 * PRAGMAs that return a row must go through query(); execSQL rejects them.
 */
object SecureErase {

    fun enableSecureDelete(db: SupportSQLiteDatabase) {
        db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
    }

    fun isSecureDeleteOn(db: SupportSQLiteDatabase): Boolean =
        db.query("PRAGMA secure_delete").use { it.moveToFirst() && it.getInt(0) == 1 }

    /** Best effort: a checkpoint cannot finish while another connection is reading. */
    fun truncateWal(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    /** Runs the VACUUM a migration asked for. Must be called outside any transaction. */
    fun vacuumIfRequested(db: SupportSQLiteDatabase) {
        val requested = db.query("SELECT 1 FROM app_metadata WHERE meta_key = ?", arrayOf(AppMetadataKeys.VACUUM_PENDING))
            .use { it.moveToFirst() }
        if (!requested) return
        // Cleared first: if VACUUM is interrupted, the data it would have scrubbed is
        // already deleted and zeroed by secure_delete from here on.
        db.execSQL("DELETE FROM app_metadata WHERE meta_key = ?", arrayOf(AppMetadataKeys.VACUUM_PENDING))
        db.execSQL("VACUUM")
        truncateWal(db)
    }
}
