package com.cloakedcent.app.data.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Creates and loads the SQLCipher passphrase.
 *
 * The passphrase is 32 random bytes, generated once per installation. Only the
 * wrapped form (see [KeyWrapper]) is written to [keyFile], which should live in
 * `Context.noBackupFilesDir`.
 *
 * Integrity rules:
 * - An existing key file is never overwritten or regenerated.
 * - If the database exists but its key is gone, this fails loudly instead of
 *   creating a new key, so the caller can never "recover" by discarding data.
 */
class DatabaseKeyStore(
    private val keyFile: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * Returns the passphrase, creating it only when no database exists yet.
     *
     * @param databaseExists whether the encrypted database file is already on disk.
     */
    fun getOrCreatePassphrase(databaseExists: Boolean): ByteArray = when {
        keyFile.exists() -> loadPassphrase()
        databaseExists -> throw DatabaseKeyException.KeyMissing(
            "Encrypted database exists but its key file is missing",
        )
        else -> createPassphrase()
    }

    private fun loadPassphrase(): ByteArray {
        val bytes = try {
            keyFile.readBytes()
        } catch (e: IOException) {
            throw DatabaseKeyException.KeyCorrupted("Key file could not be read", e)
        }
        if (bytes.size < 2 || bytes[0] != FORMAT_VERSION) {
            throw DatabaseKeyException.KeyCorrupted("Key file has an unknown format")
        }
        val passphrase = wrapper.unwrap(bytes.copyOfRange(1, bytes.size))
        if (passphrase.size != PASSPHRASE_BYTES) {
            passphrase.fill(0)
            throw DatabaseKeyException.KeyCorrupted("Unwrapped key has an unexpected length")
        }
        return passphrase
    }

    private fun createPassphrase(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        try {
            writeAtomically(byteArrayOf(FORMAT_VERSION) + wrapper.wrap(passphrase))
        } catch (e: Exception) {
            passphrase.fill(0)
            throw e
        }
        return passphrase
    }

    /** Write to a temp file, fsync, then rename, so a crash never leaves a half-written key. */
    private fun writeAtomically(contents: ByteArray) {
        keyFile.parentFile?.mkdirs()
        val temp = File(keyFile.parentFile, "${keyFile.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(contents)
            out.fd.sync()
        }
        Files.move(temp.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PASSPHRASE_BYTES = 32
        private const val FORMAT_VERSION: Byte = 1

        /**
         * Writes [passphrase] wrapped by [wrapper] to [file] in this store's format, atomically.
         * Used to stage a restored database's key; the live key file is only ever replaced by
         * the restore journal.
         */
        fun writeWrapped(file: File, passphrase: ByteArray, wrapper: KeyWrapper) {
            require(passphrase.size == PASSPHRASE_BYTES)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temp).use { out ->
                out.write(byteArrayOf(FORMAT_VERSION) + wrapper.wrap(passphrase))
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
