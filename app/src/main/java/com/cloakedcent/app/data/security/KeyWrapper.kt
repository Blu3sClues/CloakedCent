package com.cloakedcent.app.data.security

/**
 * Encrypts ("wraps") small secrets such as the database passphrase so they can
 * be stored on disk. The production implementation is [AndroidKeystoreKeyWrapper];
 * tests substitute a fake.
 */
interface KeyWrapper {

    /** Returns an opaque blob that only [unwrap] on this device can reverse. */
    fun wrap(plaintext: ByteArray): ByteArray

    /**
     * Reverses [wrap].
     *
     * @throws DatabaseKeyException.KeyMissing if the wrapping key no longer exists.
     * @throws DatabaseKeyException.KeyCorrupted if the blob fails authentication.
     * @throws DatabaseKeyException.KeystoreUnavailable for other platform failures.
     */
    fun unwrap(wrapped: ByteArray): ByteArray

    /** Permanently destroys the key, so nothing wrapped by it can ever be unwrapped. */
    fun destroy()
}
