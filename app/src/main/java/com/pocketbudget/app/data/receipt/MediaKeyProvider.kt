package com.pocketbudget.app.data.receipt

import com.pocketbudget.app.data.db.AppMetadataKeys
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.db.entity.AppMetadataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64

/**
 * The AES-256 key for receipt image files. Generated once and kept inside the
 * SQLCipher database (never in plain files, logs or preferences), so the database
 * key protects it and a future encrypted backup of the database carries it along.
 * Once written it is never replaced; replacing it would make every image unreadable.
 */
class MediaKeyProvider(private val database: () -> PocketBudgetDatabase) {

    private val lock = Mutex()
    private var cached: ByteArray? = null

    suspend fun get(): ByteArray = lock.withLock {
        cached ?: load().also { cached = it }
    }

    private suspend fun load(): ByteArray = withContext(Dispatchers.IO) {
        val dao = database().appMetadataDao()
        dao.getValue(AppMetadataKeys.MEDIA_KEY)?.let { return@withContext decode(it) }
        val fresh = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        dao.insertIfAbsent(AppMetadataEntity(AppMetadataKeys.MEDIA_KEY, Base64.getEncoder().encodeToString(fresh)))
        decode(requireNotNull(dao.getValue(AppMetadataKeys.MEDIA_KEY)))
    }

    private fun decode(value: String): ByteArray =
        Base64.getDecoder().decode(value).also { check(it.size == KEY_BYTES) { "Receipt media key is damaged" } }

    private companion object {
        const val KEY_BYTES = 32
    }
}
