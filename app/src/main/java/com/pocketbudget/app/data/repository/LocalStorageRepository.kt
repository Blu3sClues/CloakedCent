package com.pocketbudget.app.data.repository

import android.util.Log
import com.pocketbudget.app.data.db.AppMetadataKeys
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.security.DatabaseKeyException
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.repository.StorageRepository
import com.pocketbudget.app.domain.repository.StorageUnavailableException
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * @param database provides the database lazily; the first call derives the key
 *   and opens SQLCipher, so it must run off the main thread.
 */
class LocalStorageRepository(
    private val database: () -> PocketBudgetDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StorageRepository {

    override suspend fun openStorage(): StorageStatus = withContext(ioDispatcher) {
        try {
            val db = database()
            val createdAt = db.appMetadataDao().getValue(AppMetadataKeys.CREATED_AT)
                ?.toLongOrNull()
                ?.let(Instant::ofEpochMilli)
            StorageStatus(createdAt = createdAt, cipherVersion = cipherVersion(db))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when (e) {
                is DatabaseKeyException.KeyMissing -> Reason.KEY_MISSING
                is DatabaseKeyException.KeyCorrupted -> Reason.KEY_CORRUPTED
                is DatabaseKeyException.KeystoreUnavailable -> Reason.KEYSTORE_UNAVAILABLE
                else -> Reason.UNKNOWN
            }
            // Exception messages in this path never contain key material.
            Log.e(TAG, "Encrypted storage could not be opened: $reason", e)
            throw StorageUnavailableException(reason, e)
        }
    }

    /** Confirms SQLCipher (not plain SQLite) is serving this database. */
    private fun cipherVersion(db: PocketBudgetDatabase): String =
        db.openHelper.readableDatabase.query("PRAGMA cipher_version").use { cursor ->
            check(cursor.moveToFirst()) { "SQLCipher is not active on this database" }
            cursor.getString(0)
        }

    private companion object {
        const val TAG = "LocalStorageRepository"
    }
}
