package com.cloakedcent.app.domain.repository

import java.io.InputStream
import java.io.OutputStream

/** Bytes processed so far out of [total] (null when the size is not known). */
data class BackupProgress(val done: Long, val total: Long?)

/** What a validated backup contains, shown before anything is replaced. No amounts. */
data class RestorePreview(
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val accounts: Int,
    val transactions: Int,
    val receipts: Int,
    val attachments: Int,
)

/** Why a backup or restore failed. Messages never contain financial data or key material. */
sealed class BackupException(message: String) : Exception(message) {
    class PasswordTooShort : BackupException("password too short")
    class WrongPasswordOrDamaged : BackupException("wrong password or damaged backup")
    class Damaged : BackupException("damaged or invalid backup")
    class Incomplete : BackupException("incomplete backup")
    class Unsupported : BackupException("unsupported backup")
    class NewerVersion : BackupException("backup from a newer app version")
    class MissingAttachment : BackupException("receipt image missing")
    class InsufficientStorage : BackupException("not enough storage")
    class NothingPrepared : BackupException("no validated backup to restore")
}

/**
 * Manual, password-encrypted backups that never leave the device unless the user moves
 * the file. Restore is two-step: [prepareRestore] decrypts and fully validates a backup
 * into a private staging area without touching current data; [commitPreparedRestore]
 * then schedules the replacement, which completes when the app restarts.
 */
interface BackupRepository {
    /** Writes a complete encrypted backup to [output]. [password] is wiped afterwards. */
    suspend fun createBackup(output: OutputStream, password: CharArray, onProgress: (BackupProgress) -> Unit = {})

    /** Decrypts and validates [input]; current data is never touched. [password] is wiped afterwards. */
    suspend fun prepareRestore(input: InputStream, totalBytes: Long?, password: CharArray, onProgress: (BackupProgress) -> Unit = {}): RestorePreview

    /** Removes a prepared restore that the user decided not to apply. */
    suspend fun discardPreparedRestore()

    /** Marks the prepared restore for replacement; the caller must restart the app to apply it. */
    suspend fun commitPreparedRestore()
}

const val MIN_BACKUP_PASSWORD = 8
