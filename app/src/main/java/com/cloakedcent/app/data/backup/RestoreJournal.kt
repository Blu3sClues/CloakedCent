package com.cloakedcent.app.data.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Where live data, a staged restore and the previous data live. All on one filesystem, so renames are atomic. */
class StorageLocations(
    val databaseFile: File,
    val keyFile: File,
    val receiptsDir: File,
    /** Restore staging, with the same layout as live data. */
    val stagingDir: File,
    /** The data being replaced, kept until the replacement is complete. */
    val previousDir: File,
    val journalFile: File,
) {
    val stagedDatabase get() = File(stagingDir, "database/${databaseFile.name}")
    val stagedKey get() = File(stagingDir, keyFile.name)
    val stagedReceipts get() = File(stagingDir, "receipts")

    /** Written last when staging is complete and validated. */
    val readyMarker get() = File(stagingDir, "ready")

    /** Present from the moment a secure delete is confirmed until it has finished. */
    val wipeMarker get() = File(journalFile.parentFile, "secure-wipe.pending")

    internal class Item(val live: File, val staged: File, val previous: File, val required: Boolean)

    /** Every live path a restore replaces: the database and its WAL/SHM/journal, the key, the receipt images. */
    internal fun items(): List<Item> {
        fun item(live: File, staged: File, required: Boolean) = Item(live, staged, File(previousDir, live.name), required)
        return listOf(item(databaseFile, stagedDatabase, true)) +
            listOf("-wal", "-shm", "-journal").map { item(File(databaseFile.path + it), File(stagedDatabase.path + it), false) } +
            listOf(item(keyFile, stagedKey, true), item(receiptsDir, stagedReceipts, true))
    }
}

/**
 * Applies a validated restore with renames that can be repeated after a crash.
 *
 * The journal records the phase:
 * - `PENDING` (plus which live items existed): staging is complete and validated. For each
 *   item the live file, if any, is moved aside to the previous-data folder, then the staged
 *   file is moved into place. Every step checks what is already done, so running again after
 *   an interruption finishes the job.
 * - `DONE`: everything is in place; only the previous data remains to be deleted.
 *
 * It runs only at startup, before the database is opened, so no connection is open while
 * files move. If a replacement is missing for an item that has not yet been moved, nothing
 * is moved and the live data stays exactly as it is.
 */
object RestoreJournal {
    private const val PENDING = "PENDING"
    private const val DONE = "DONE"

    /** Records that the staged restore should replace live data at the next start. */
    fun schedule(locations: StorageLocations) {
        if (!locations.readyMarker.isFile) throw IOException("No validated restore is staged")
        val existing = locations.items().filter { it.live.exists() }.joinToString(",") { it.live.name }
        writeAtomically(locations.journalFile, "$PENDING\n$existing")
    }

    fun isScheduled(locations: StorageLocations) = locations.journalFile.isFile

    /**
     * Finishes or cleans up any restore; returns true when one was applied. [beforeStep]
     * lets tests simulate a crash between renames.
     */
    fun recover(locations: StorageLocations, beforeStep: (Int) -> Unit = {}): Boolean {
        val lines = locations.journalFile.takeIf { it.isFile }?.readLines()
        val state = lines?.firstOrNull()?.trim()
        if (state != PENDING && state != DONE) {
            // Never confirmed, abandoned, or an unreadable journal: discard staging, keep live data.
            locations.journalFile.delete()
            locations.stagingDir.deleteRecursively()
            locations.previousDir.deleteRecursively()
            return false
        }
        if (state == PENDING) {
            val existedBefore = lines.getOrNull(1).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
            val items = locations.items()
            // A required item is accounted for if it is still staged or was already moved in.
            val complete = items.filter { it.required }.all { item ->
                item.staged.exists() || (item.live.exists() && (item.live.name !in existedBefore || item.previous.exists()))
            }
            if (!complete) {
                locations.journalFile.delete()
                locations.stagingDir.deleteRecursively()
                return false
            }
            locations.previousDir.mkdirs()
            var step = 0
            for (item in items) {
                // Move the old item aside (including a stale WAL/SHM, which must never meet the new database).
                if (item.live.exists() && !item.previous.exists() && item.live.name in existedBefore) {
                    beforeStep(step++)
                    move(item.live, item.previous)
                }
                if (item.staged.exists()) {
                    beforeStep(step++)
                    move(item.staged, item.live)
                }
            }
            writeAtomically(locations.journalFile, DONE)
        }
        locations.previousDir.deleteRecursively()
        locations.stagingDir.deleteRecursively()
        locations.journalFile.delete()
        return true
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw IOException("Could not move restore data")
    }

    internal fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("Could not record restore state")
    }
}
