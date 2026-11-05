package com.cloakedcent.app.data.security

/**
 * Failures while obtaining the database encryption key.
 *
 * Messages describe what went wrong, never the key itself, so these
 * exceptions are safe to log.
 */
sealed class DatabaseKeyException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The key needed to open existing data is gone (key file or Keystore entry). */
    class KeyMissing(message: String) : DatabaseKeyException(message)

    /** The stored key file exists but cannot be parsed or authenticated. */
    class KeyCorrupted(message: String, cause: Throwable? = null) :
        DatabaseKeyException(message, cause)

    /** Android Keystore failed for a reason other than a missing or corrupted key. */
    class KeystoreUnavailable(cause: Throwable) :
        DatabaseKeyException("Android Keystore operation failed", cause)
}
