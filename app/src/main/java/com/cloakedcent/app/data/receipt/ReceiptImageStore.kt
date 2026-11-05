package com.cloakedcent.app.data.receipt

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A file was missing, altered, or written with another key. Never shows partial data. */
class ReceiptImageUnreadableException(message: String) : IOException(message)

data class StoredImage(val fileName: String, val contentSha256: String, val byteSize: Long)

/**
 * Receipt images at rest: one AES-256-GCM encrypted file per page in app-private,
 * non-backed-up storage. Decrypted bytes exist only in memory; nothing is shared
 * through a provider or written in plain form.
 *
 * File format (version 1): "CCRI" | version byte | 12-byte nonce | ciphertext+tag.
 * The magic, version and file name are authenticated as associated data, so a file
 * cannot be swapped for another record's file without detection.
 */
class ReceiptImageStore(
    private val directory: File,
    private val key: suspend () -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun write(plain: ByteArray): StoredImage {
        val fileName = UUID.randomUUID().toString() + EXTENSION
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, fileName)
        val sealed = cipher.doFinal(plain)
        directory.mkdirs()
        val temp = File(directory, fileName + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(MAGIC)
                out.write(FORMAT_VERSION)
                out.write(nonce)
                out.write(sealed)
                out.fd.sync()
            }
            if (!temp.renameTo(File(directory, fileName))) throw IOException("Could not store receipt image")
        } finally {
            temp.delete()
        }
        return StoredImage(fileName, sha256(plain), plain.size.toLong())
    }

    /** Decrypts into memory and checks the content hash recorded at import. */
    suspend fun read(fileName: String, expectedSha256: String): ByteArray {
        val file = File(directory, fileName)
        if (!file.isFile) throw ReceiptImageUnreadableException("Receipt image is missing")
        val bytes = file.readBytes()
        val header = MAGIC.size + 1 + NONCE_BYTES
        val magic = bytes.copyOfRange(0, minOf(MAGIC.size, bytes.size))
        if (bytes.size <= header || (!magic.contentEquals(MAGIC) && !magic.contentEquals(LEGACY_MAGIC)) ||
            bytes[MAGIC.size].toInt() != FORMAT_VERSION
        ) {
            throw ReceiptImageUnreadableException("Receipt image has an unknown format")
        }
        val nonce = bytes.copyOfRange(MAGIC.size + 1, header)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, nonce, fileName, magic).doFinal(bytes, header, bytes.size - header)
        } catch (_: GeneralSecurityException) {
            throw ReceiptImageUnreadableException("Receipt image failed its integrity check")
        }
        if (sha256(plain) != expectedSha256) throw ReceiptImageUnreadableException("Receipt image does not match its record")
        return plain
    }

    fun delete(fileName: String) {
        File(directory, fileName).delete()
    }

    /**
     * Removes interrupted writes and files no record refers to. Only files older than
     * [graceMillis] are touched, so an import that has written its files but not yet
     * its database rows is left alone. Returns the number of files removed.
     */
    fun removeOrphans(referenced: Set<String>, graceMillis: Long = ORPHAN_GRACE_MILLIS): Int {
        val cutoff = clock() - graceMillis
        val files = directory.listFiles() ?: return 0
        return files.count { file ->
            val orphan = file.name.endsWith(TEMP_SUFFIX) || file.name !in referenced
            orphan && file.isFile && file.lastModified() < cutoff && file.delete()
        }
    }

    private suspend fun cipher(mode: Int, nonce: ByteArray, fileName: String, magic: ByteArray = MAGIC): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(magic + FORMAT_VERSION.toByte() + fileName.toByteArray(Charsets.UTF_8))
        }

    companion object {
        const val FORMAT_VERSION = 1
        const val EXTENSION = ".ccr"
        const val ORPHAN_GRACE_MILLIS = 10 * 60 * 1000L
        private const val TEMP_SUFFIX = ".tmp"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val MAGIC = "CCRI".toByteArray(Charsets.US_ASCII)

        /** Images restored from before the rename keep this magic, which their authentication covers. */
        private val LEGACY_MAGIC = "PBRI".toByteArray(Charsets.US_ASCII)
        private val random = SecureRandom()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
