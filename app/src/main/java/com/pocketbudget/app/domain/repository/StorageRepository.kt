package com.pocketbudget.app.domain.repository

import com.pocketbudget.app.domain.model.StorageStatus

interface StorageRepository {

    /**
     * Opens (creating on first launch) the encrypted store and reports its status.
     *
     * @throws StorageUnavailableException if the store cannot be opened. Data is
     *   never deleted as a way of recovering.
     */
    suspend fun openStorage(): StorageStatus
}

class StorageUnavailableException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception("Storage unavailable: $reason", cause) {

    enum class Reason {
        KEY_MISSING,
        KEY_CORRUPTED,
        KEYSTORE_UNAVAILABLE,
        UNKNOWN,
    }
}
