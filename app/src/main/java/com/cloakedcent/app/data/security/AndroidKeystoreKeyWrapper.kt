package com.cloakedcent.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps secrets with an AES-256-GCM key held in Android Keystore.
 *
 * The Keystore key is generated on-device and is non-exportable: its material
 * never enters app memory. Blob layout: `[ivLength:1][iv][ciphertext+tag]`.
 *
 * Because the key is bound to this device and this installation, anything it
 * wraps cannot be restored elsewhere. Portable backups must therefore use a
 * separate, user-supplied password (see README, "Backup design").
 */
class AndroidKeystoreKeyWrapper(
    private val alias: String = DEFAULT_ALIAS,
) : KeyWrapper {

    override fun wrap(plaintext: ByteArray): ByteArray = keystoreCall {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = keystoreCall {
        val key = existingKey()
            ?: throw DatabaseKeyException.KeyMissing("Keystore wrapping key is missing")

        val ivLength = wrapped.firstOrNull()?.toInt()
        if (ivLength == null || ivLength <= 0 || wrapped.size <= 1 + ivLength) {
            throw DatabaseKeyException.KeyCorrupted("Wrapped key blob is malformed")
        }
        val iv = wrapped.copyOfRange(1, 1 + ivLength)
        val ciphertext = wrapped.copyOfRange(1 + ivLength, wrapped.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw DatabaseKeyException.KeyCorrupted("Wrapped key failed authentication", e)
        }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private inline fun <T> keystoreCall(block: () -> T): T =
        try {
            block()
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyException.KeystoreUnavailable(e)
        } catch (e: java.io.IOException) {
            throw DatabaseKeyException.KeystoreUnavailable(e)
        } catch (e: java.security.ProviderException) {
            throw DatabaseKeyException.KeystoreUnavailable(e)
        }

    companion object {
        const val DEFAULT_ALIAS = "cloaked_cent_db_key_wrapper"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
    }
}
