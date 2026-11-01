package com.pocketbudget.app.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseKeyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val wrapper = FakeKeyWrapper()

    private fun keyFile() = File(tempFolder.root, "database.key")

    private fun store(file: File = keyFile()) = DatabaseKeyStore(file, wrapper)

    @Test
    fun `first launch creates a 32 byte passphrase and persists it`() {
        val passphrase = store().getOrCreatePassphrase(databaseExists = false)

        assertEquals(DatabaseKeyStore.PASSPHRASE_BYTES, passphrase.size)
        assertTrue(keyFile().exists())
    }

    @Test
    fun `later launches load the same passphrase`() {
        val first = store().getOrCreatePassphrase(databaseExists = false)
        val second = store().getOrCreatePassphrase(databaseExists = true)

        assertArrayEquals(first, second)
    }

    @Test
    fun `key file never contains the plaintext passphrase`() {
        val passphrase = store().getOrCreatePassphrase(databaseExists = false)
        val onDisk = keyFile().readBytes()

        assertFalse(onDisk.containsSubsequence(passphrase))
    }

    @Test
    fun `passphrases are random per installation`() {
        val a = store(File(tempFolder.root, "a.key")).getOrCreatePassphrase(databaseExists = false)
        val b = store(File(tempFolder.root, "b.key")).getOrCreatePassphrase(databaseExists = false)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `missing key for an existing database fails instead of creating a new key`() {
        assertThrows(DatabaseKeyException.KeyMissing::class.java) {
            store().getOrCreatePassphrase(databaseExists = true)
        }
        assertFalse("A replacement key must not be written", keyFile().exists())
    }

    @Test
    fun `corrupted key file fails and is left untouched`() {
        store().getOrCreatePassphrase(databaseExists = false)
        val corrupted = keyFile().readBytes().also { it[it.size - 1] = (it.last() + 1).toByte() }
        keyFile().writeBytes(corrupted)

        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            store().getOrCreatePassphrase(databaseExists = true)
        }
        assertArrayEquals(corrupted, keyFile().readBytes())
    }

    @Test
    fun `unknown key file format is reported as corrupted`() {
        keyFile().writeBytes(byteArrayOf(99, 1, 2, 3))

        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            store().getOrCreatePassphrase(databaseExists = true)
        }
    }

    @Test
    fun `empty key file is reported as corrupted`() {
        keyFile().writeBytes(ByteArray(0))

        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            store().getOrCreatePassphrase(databaseExists = false)
        }
    }

    @Test
    fun `failed wrap leaves no key file behind`() {
        val failing = DatabaseKeyStore(keyFile(), FakeKeyWrapper(failWrap = true))

        assertThrows(DatabaseKeyException.KeystoreUnavailable::class.java) {
            failing.getOrCreatePassphrase(databaseExists = false)
        }
        assertFalse(keyFile().exists())
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }

    /**
     * Stand-in for Android Keystore: XOR with a fixed pad plus a checksum byte,
     * enough to verify DatabaseKeyStore's persistence and integrity rules.
     */
    private class FakeKeyWrapper(private val failWrap: Boolean = false) : KeyWrapper {

        override fun wrap(plaintext: ByteArray): ByteArray {
            if (failWrap) throw DatabaseKeyException.KeystoreUnavailable(IllegalStateException("test"))
            val body = ByteArray(plaintext.size) { (plaintext[it].toInt() xor PAD).toByte() }
            return body + checksum(body)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            val body = wrapped.copyOfRange(0, wrapped.size - 1)
            if (checksum(body) != wrapped.last()) {
                throw DatabaseKeyException.KeyCorrupted("checksum mismatch")
            }
            return ByteArray(body.size) { (body[it].toInt() xor PAD).toByte() }
        }

        private fun checksum(bytes: ByteArray): Byte = bytes.fold(0) { acc, b -> acc xor b.toInt() }.toByte()

        private companion object {
            const val PAD = 0x5A
        }
    }
}
