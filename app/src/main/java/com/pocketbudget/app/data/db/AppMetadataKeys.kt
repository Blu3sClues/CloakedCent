package com.pocketbudget.app.data.db

/** Well-known keys in the `app_metadata` table. */
object AppMetadataKeys {
    /** Epoch milliseconds when the database was first created. */
    const val CREATED_AT = "database_created_at"

    /**
     * Base64 AES-256 key that encrypts receipt image files. It lives only inside this
     * SQLCipher database, so an encrypted backup of the database carries it with the
     * already-encrypted image files.
     */
    const val MEDIA_KEY = "receipt_media_key_v1"
}
