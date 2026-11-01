package com.pocketbudget.app.data.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pocket Budget backup file, format version 1 (".pbbackup").
 *
 * ```
 * "PBBK" | version (1 byte) | header length (2 bytes, big-endian) | header (UTF-8 JSON)
 * then chunks: ciphertext length (4 bytes) | AES-256-GCM ciphertext + 16-byte tag
 * ```
 * The header holds only what is needed to decrypt: the Argon2id parameters and salt, the
 * chunk size and a random nonce prefix. Everything else (schema and app version, the
 * database, its key and the receipt images) is inside the encrypted payload.
 *
 * Chunk `i` uses nonce = prefix (8 bytes) || i (4 bytes) and authenticates, as associated
 * data, the whole preamble (magic, version, header) plus `i` and a final-chunk flag. So the
 * header cannot be altered, chunks cannot be reordered, dropped or duplicated, and a file
 * cut short is detected because its last chunk is not marked final.
 */
object BackupFormat {
    const val EXTENSION = "pbbackup"
    const val FORMAT_VERSION = 1
    private val MAGIC = "PBBK".toByteArray(Charsets.US_ASCII)

    const val DEFAULT_CHUNK = 1 shl 20
    private const val MAX_HEADER = 4096
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val TAG_BYTES = 16

    /** Argon2id cost. Defaults follow current guidance for interactive use on phones. */
    data class KdfParams(val memoryKiB: Int = 64 * 1024, val iterations: Int = 3, val parallelism: Int = 1) {
        init {
            // Bounds also protect restore from files that demand huge memory or time.
            require(memoryKiB in 8 * 1024..256 * 1024) { "Argon2 memory out of range" }
            require(iterations in 1..10) { "Argon2 iterations out of range" }
            require(parallelism in 1..4) { "Argon2 parallelism out of range" }
        }
    }

    class Header(val kdf: KdfParams, val salt: ByteArray, val chunkSize: Int, val noncePrefix: ByteArray) {
        fun toJson(): String = JSONObject()
            .put("kdf", "argon2id")
            .put("argon2Version", 0x13)
            .put("memoryKiB", kdf.memoryKiB)
            .put("iterations", kdf.iterations)
            .put("parallelism", kdf.parallelism)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("cipher", "AES-256-GCM")
            .put("chunkSize", chunkSize)
            .put("noncePrefix", Base64.getEncoder().encodeToString(noncePrefix))
            .toString()

        companion object {
            fun parse(json: String): Header {
                val o = try {
                    JSONObject(json)
                } catch (e: Exception) {
                    throw BackupFormatException("header", e)
                }
                fun string(name: String) = o.optString(name).takeIf { it.isNotEmpty() } ?: throw BackupFormatException("header")
                if (string("kdf") != "argon2id" || o.optInt("argon2Version") != 0x13 || string("cipher") != "AES-256-GCM") {
                    throw BackupFormatException("algorithm")
                }
                val kdf = try {
                    KdfParams(o.getInt("memoryKiB"), o.getInt("iterations"), o.getInt("parallelism"))
                } catch (e: Exception) {
                    throw BackupFormatException("kdf", e)
                }
                val salt = runCatching { Base64.getDecoder().decode(string("salt")) }.getOrNull()
                val prefix = runCatching { Base64.getDecoder().decode(string("noncePrefix")) }.getOrNull()
                val chunk = o.optInt("chunkSize")
                if (salt?.size != SALT_BYTES || prefix?.size != PREFIX_BYTES || chunk !in 64 * 1024..4 * 1024 * 1024) {
                    throw BackupFormatException("header")
                }
                return Header(kdf, salt, chunk, prefix)
            }
        }
    }

    fun newHeader(kdf: KdfParams, random: SecureRandom, chunkSize: Int = DEFAULT_CHUNK) =
        Header(kdf, ByteArray(SALT_BYTES).also(random::nextBytes), chunkSize, ByteArray(PREFIX_BYTES).also(random::nextBytes))

    /**
     * 32-byte key from the password with Argon2id. The password's UTF-8 bytes are wiped
     * afterwards; callers wipe the CharArray and the returned key.
     */
    fun deriveKey(password: CharArray, header: Header): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (encoded.hasArray()) encoded.array().fill(0)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(header.kdf.memoryKiB)
                    .withIterations(header.kdf.iterations)
                    .withParallelism(header.kdf.parallelism)
                    .withSalt(header.salt)
                    .build(),
            )
            return ByteArray(32).also { generator.generateBytes(bytes, it) }
        } finally {
            bytes.fill(0)
        }
    }

    fun preamble(header: Header): ByteArray {
        val json = header.toJson().toByteArray(Charsets.UTF_8)
        return MAGIC + byteArrayOf(FORMAT_VERSION.toByte()) + ByteBuffer.allocate(2).putShort(json.size.toShort()).array() + json
    }

    /** Reads and checks the unencrypted preamble; returns the header and its exact bytes (for authentication). */
    fun readPreamble(input: InputStream): Pair<Header, ByteArray> {
        val data = DataInputStream(input)
        val fixed = ByteArray(MAGIC.size + 3)
        try {
            data.readFully(fixed)
        } catch (e: EOFException) {
            throw BackupFormatException("not a backup", e)
        }
        if (!fixed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BackupFormatException("not a backup")
        val version = fixed[MAGIC.size].toInt() and 0xFF
        if (version != FORMAT_VERSION) throw UnsupportedBackupException()
        val length = ((fixed[MAGIC.size + 1].toInt() and 0xFF) shl 8) or (fixed[MAGIC.size + 2].toInt() and 0xFF)
        if (length !in 2..MAX_HEADER) throw BackupFormatException("header size")
        val json = ByteArray(length)
        try {
            data.readFully(json)
        } catch (e: EOFException) {
            throw IncompleteBackupException()
        }
        return Header.parse(String(json, Charsets.UTF_8)) to (fixed + json)
    }

    private fun nonce(header: Header, index: Int) = header.noncePrefix + ByteBuffer.allocate(4).putInt(index).array()

    private fun aad(preamble: ByteArray, index: Int, final: Boolean) =
        preamble + ByteBuffer.allocate(4).putInt(index).array() + byteArrayOf(if (final) 1 else 0)

    /** Encrypts everything written to it into authenticated chunks. [close] writes the final chunk. */
    class EncryptingStream(private val out: OutputStream, private val header: Header, key: ByteArray, private val preamble: ByteArray) : OutputStream() {
        private val keySpec = SecretKeySpec(key.copyOf(), "AES")
        private val buffer = ByteArray(header.chunkSize)
        private var filled = 0
        private var index = 0
        private var closed = false

        init {
            out.write(preamble)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed)
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // A full chunk is written only once more data follows, so the last one can be marked final.
                if (filled == buffer.size) flushChunk(final = false)
                val n = minOf(remaining, buffer.size - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                remaining -= n
            }
        }

        private fun flushChunk(final: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BYTES * 8, nonce(header, index)))
            cipher.updateAAD(aad(preamble, index, final))
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            buffer.fill(0, 0, filled)
            filled = 0
            index++
        }

        /** Seals the remaining data as the final chunk. Without it, the file reads as incomplete. */
        override fun close() {
            if (closed) return
            flushChunk(final = true)
            closed = true
            buffer.fill(0)
            out.flush()
        }
    }

    /**
     * Decrypts chunks as they are read. Any authentication failure, a missing final chunk
     * or data after it is an error; nothing is returned from a chunk that fails.
     */
    class DecryptingStream(private val input: InputStream, private val header: Header, key: ByteArray, private val preamble: ByteArray) : InputStream() {
        private val keySpec = SecretKeySpec(key.copyOf(), "AES")
        private val data = DataInputStream(input)
        private var plain = ByteArray(0)
        private var position = 0
        private var index = 0
        private var finished = false

        /** Bytes of ciphertext consumed so far, for progress. */
        var consumed = 0L
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= plain.size) {
                if (finished) return -1
                nextChunk()
            }
            val n = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        private fun nextChunk() {
            val length = try {
                data.readInt()
            } catch (e: EOFException) {
                throw IncompleteBackupException()
            }
            if (length < TAG_BYTES || length > header.chunkSize + TAG_BYTES) throw BackupFormatException("chunk size")
            val sealed = ByteArray(length)
            try {
                data.readFully(sealed)
            } catch (e: EOFException) {
                throw IncompleteBackupException()
            }
            consumed += 4 + length
            plain.fill(0)
            plain = open(sealed, final = false) ?: open(sealed, final = true)?.also { finished = true }
                ?: throw if (index == 0) WrongPasswordOrDamagedException() else BackupFormatException("authentication")
            position = 0
            index++
            if (finished && input.read() != -1) throw BackupFormatException("trailing data")
        }

        private fun open(sealed: ByteArray, final: Boolean): ByteArray? = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BYTES * 8, nonce(header, index)))
                updateAAD(aad(preamble, index, final))
                doFinal(sealed)
            }
        } catch (_: GeneralSecurityException) {
            null
        }

        fun wipe() {
            plain.fill(0)
        }
    }
}

/** The file is not a valid Pocket Budget backup (wrong structure or failed integrity). */
open class BackupFormatException(reason: String, cause: Throwable? = null) : IOException(reason, cause)

/** The first chunk did not authenticate: the password is wrong or the file is damaged. */
class WrongPasswordOrDamagedException : BackupFormatException("password")

/** The file ends before its final chunk. */
class IncompleteBackupException : BackupFormatException("incomplete")

/** Written by a newer, unknown format version. */
class UnsupportedBackupException : BackupFormatException("unsupported")
