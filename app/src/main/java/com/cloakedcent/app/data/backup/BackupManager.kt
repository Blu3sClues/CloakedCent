package com.cloakedcent.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteException
import com.cloakedcent.app.data.db.AppMetadataKeys
import com.cloakedcent.app.data.db.CloakedCentDatabase
import com.cloakedcent.app.data.db.CloakedCentDatabaseFactory
import com.cloakedcent.app.data.receipt.ReceiptImageStore
import com.cloakedcent.app.data.security.DatabaseKeyStore
import com.cloakedcent.app.data.security.KeyWrapper
import com.cloakedcent.app.domain.repository.BackupException
import com.cloakedcent.app.domain.repository.BackupProgress
import com.cloakedcent.app.domain.repository.BackupRepository
import com.cloakedcent.app.domain.repository.MIN_BACKUP_PASSWORD
import com.cloakedcent.app.domain.repository.RestorePreview
import kotlinx.coroutines.CancellationException
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/**
 * Creates and restores encrypted portable backups (see [BackupFormat]).
 *
 * The payload is a simple archive of entries, each `type (1) | name (2 + UTF-8) | size (8) |
 * bytes`, ending with type 0:
 * - `metadata.json`: format and schema version, app version, creation time.
 * - `database.key`: the 32-byte SQLCipher passphrase. It is only ever stored encrypted:
 *   in the backup under the password, and on restore re-wrapped with this device's Keystore.
 * - `database/cloaked_cent.db`: a consistent SQLCipher-encrypted copy (sqlcipher_export).
 * - `receipts/<uuid>.ccr`: receipt page images (photos and imported PDF pages), still
 *   AES-GCM encrypted under the media key that lives inside the database.
 * - `manifest.json`: size and SHA-256 of every other entry; restore requires an exact match.
 *
 * Nothing is written in plain form: the snapshot is SQLCipher-encrypted, images stay
 * encrypted, and the database key exists only in memory.
 */
class BackupManager(
    private val context: Context,
    private val locations: StorageLocations,
    private val database: () -> CloakedCentDatabase,
    private val passphrase: () -> ByteArray,
    private val keyWrapper: KeyWrapper,
    private val appVersion: String,
    private val kdf: BackupFormat.KdfParams = BackupFormat.KdfParams(),
    private val clock: Clock = Clock.systemUTC(),
    private val usableSpace: (File) -> Long = { allocatableBytes(context, it) },
    private val random: SecureRandom = SecureRandom(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupRepository {

    private val snapshotDir get() = File(locations.stagingDir.parentFile, "backup-snapshot")

    override suspend fun createBackup(output: OutputStream, password: CharArray, onProgress: (BackupProgress) -> Unit) {
        try {
            if (password.size < MIN_BACKUP_PASSWORD) throw BackupException.PasswordTooShort()
            withContext(ioDispatcher) { writeBackup(output, password, onProgress) }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun writeBackup(output: OutputStream, password: CharArray, onProgress: (BackupProgress) -> Unit) {
        snapshotDir.deleteRecursively()
        snapshotDir.mkdirs()
        val dbKey = passphrase()
        var derived: ByteArray? = null
        try {
            val snapshot = File(snapshotDir, locations.databaseFile.name)
            val schema = exportSnapshot(snapshot, dbKey)
            val images = database().receiptDao().getAllImageFileNames().sorted().map { File(locations.receiptsDir, it) }
            if (images.any { !it.isFile }) throw BackupException.MissingAttachment()

            val total = snapshot.length() + images.sumOf { it.length() } + dbKey.size
            var done = 0L
            val header = BackupFormat.newHeader(kdf, random)
            derived = withContext(Dispatchers.Default) { BackupFormat.deriveKey(password, header) }
            val encrypted = BackupFormat.EncryptingStream(output, header, derived, BackupFormat.preamble(header))
            val archive = DataOutputStream(encrypted)
            val manifest = JSONArray()

            suspend fun entry(name: String, size: Long, source: InputStream) {
                archive.writeByte(1)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                archive.writeShort(nameBytes.size)
                archive.write(nameBytes)
                archive.writeLong(size)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                source.use {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = it.read(buffer)
                        if (n < 0) break
                        archive.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        written += n
                        done += n
                        onProgress(BackupProgress(done, total))
                    }
                }
                buffer.fill(0)
                // A file that changed while being read would make an inconsistent backup.
                if (written != size) throw IOException("Backup source changed")
                manifest.put(JSONObject().put("name", name).put("size", size).put("sha256", hex(digest.digest())))
            }

            val metadata = JSONObject()
                .put("formatVersion", BackupFormat.FORMAT_VERSION)
                .put("schemaVersion", schema)
                .put("appVersion", appVersion)
                .put("createdAt", clock.millis())
                .put("database", locations.databaseFile.name)
                .toString().toByteArray(Charsets.UTF_8)
            entry(METADATA, metadata.size.toLong(), metadata.inputStream())
            entry(KEY, dbKey.size.toLong(), dbKey.inputStream())
            entry("$DATABASE_PREFIX${locations.databaseFile.name}", snapshot.length(), snapshot.inputStream())
            for (image in images) entry("$RECEIPT_PREFIX${image.name}", image.length(), image.inputStream())
            val manifestBytes = JSONObject().put("entries", manifest).toString().toByteArray(Charsets.UTF_8)
            archive.writeByte(1)
            MANIFEST.toByteArray(Charsets.UTF_8).let { archive.writeShort(it.size); archive.write(it) }
            archive.writeLong(manifestBytes.size.toLong())
            archive.write(manifestBytes)
            archive.writeByte(0)
            archive.flush()
            // Only a fully written backup gets its final chunk; anything interrupted reads as incomplete.
            encrypted.close()
            output.flush()
        } finally {
            dbKey.fill(0)
            derived?.fill(0)
            snapshotDir.deleteRecursively()
        }
    }

    /**
     * A consistent copy of the live database, encrypted with the same key, made inside
     * SQLite (no copying of files that may be mid-write). It uses its own short-lived
     * connection, so the app's Room connections are never altered. Returns the schema version.
     */
    private fun exportSnapshot(target: File, dbKey: ByteArray): Int {
        // Make sure the live database exists and is fully migrated before copying it.
        val version = database().openHelper.writableDatabase.version
        // The no-op error handler matters: the default one deletes a database it thinks is corrupt.
        // CREATE_IF_NECESSARY lets the attached snapshot file be created (attached files share the flags).
        val connection = SQLiteDatabase.openDatabase(locations.databaseFile.path, dbKey, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, NEVER_DELETE, null)
        try {
            connection.execSQL("ATTACH DATABASE ? AS backup KEY ?", arrayOf<Any?>(target.path, dbKey))
            try {
                connection.rawQuery("SELECT sqlcipher_export('backup')", null).use { it.moveToFirst() }
                connection.execSQL("PRAGMA backup.user_version = $version")
            } finally {
                connection.execSQL("DETACH DATABASE backup")
            }
        } finally {
            connection.close()
        }
        return version
    }
    override suspend fun prepareRestore(input: InputStream, totalBytes: Long?, password: CharArray, onProgress: (BackupProgress) -> Unit): RestorePreview {
        try {
            if (password.size < MIN_BACKUP_PASSWORD) throw BackupException.WrongPasswordOrDamaged()
            return withContext(ioDispatcher) {
                try {
                    stage(input, totalBytes, password, onProgress)
                } catch (e: CancellationException) {
                    locations.stagingDir.deleteRecursively()
                    throw e
                } catch (e: Exception) {
                    locations.stagingDir.deleteRecursively()
                    throw translate(e)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun stage(input: InputStream, totalBytes: Long?, password: CharArray, onProgress: (BackupProgress) -> Unit): RestorePreview {
        if (RestoreJournal.isScheduled(locations)) throw IOException("A restore is already scheduled")
        locations.stagingDir.deleteRecursively()
        locations.stagedReceipts.mkdirs()
        locations.stagedDatabase.parentFile!!.mkdirs()

        val counted = CountingInputStream(input)
        val (header, preamble) = BackupFormat.readPreamble(counted)
        val derived = withContext(Dispatchers.Default) { BackupFormat.deriveKey(password, header) }
        val plain = BackupFormat.DecryptingStream(counted, header, derived, preamble)
        derived.fill(0)
        val archive = DataInputStream(plain)
        var dbKey: ByteArray? = null
        try {
            var metadata: JSONObject? = null
            var manifest: JSONObject? = null
            val seen = linkedMapOf<String, Pair<Long, String>>()
            while (true) {
                currentCoroutineContext().ensureActive()
                val type = try {
                    archive.readUnsignedByte()
                } catch (e: EOFException) {
                    throw IncompleteBackupException()
                }
                if (type == 0) break
                if (type != 1 || manifest != null) throw BackupFormatException("entry")
                val nameLength = archive.readUnsignedShort()
                if (nameLength !in 1..MAX_NAME) throw BackupFormatException("entry name")
                val name = String(ByteArray(nameLength).also(archive::readFully), Charsets.UTF_8)
                val size = archive.readLong()
                val kind = kindOf(name) ?: throw BackupFormatException("entry name")
                if (size < 0 || size > kind.maxSize || name in seen || seen.size >= MAX_ENTRIES) throw BackupFormatException("entry")
                when (kind) {
                    Kind.METADATA, Kind.MANIFEST -> {
                        val bytes = ByteArray(size.toInt()).also(archive::readFully)
                        val json = try {
                            JSONObject(String(bytes, Charsets.UTF_8))
                        } catch (e: Exception) {
                            throw BackupFormatException("metadata", e)
                        }
                        if (kind == Kind.METADATA) {
                            metadata = json
                            seen[name] = size to hex(sha256(bytes))
                        } else {
                            manifest = json
                        }
                    }
                    Kind.KEY -> {
                        if (size != DatabaseKeyStore.PASSPHRASE_BYTES.toLong()) throw BackupFormatException("key")
                        dbKey = ByteArray(size.toInt()).also(archive::readFully)
                        seen[name] = size to hex(sha256(dbKey!!))
                    }
                    Kind.DATABASE, Kind.RECEIPT -> {
                        val target = if (kind == Kind.DATABASE) locations.stagedDatabase else File(locations.stagedReceipts, name.removePrefix(RECEIPT_PREFIX))
                        // Leave room for migrations and the live data that stays until the swap.
                        if (usableSpace(locations.stagingDir) < size + SPACE_MARGIN) throw BackupException.InsufficientStorage()
                        seen[name] = size to copy(archive, size, target) { onProgress(BackupProgress(counted.count, totalBytes)) }
                    }
                }
                onProgress(BackupProgress(counted.count, totalBytes))
            }
            // Nothing may follow the end marker inside the authenticated payload.
            if (plain.read() != -1) throw BackupFormatException("trailing entries")

            val meta = metadata ?: throw BackupFormatException("metadata")
            if (meta.optInt("formatVersion") != BackupFormat.FORMAT_VERSION) throw UnsupportedBackupException()
            val schema = meta.optInt("schemaVersion")
            if (schema > CloakedCentDatabase.SCHEMA_VERSION) throw BackupException.NewerVersion()
            if (schema < 1 || meta.optString("database") !in setOf(locations.databaseFile.name, LEGACY_DATABASE_NAME)) throw BackupFormatException("metadata")
            verifyManifest(manifest ?: throw BackupFormatException("manifest"), seen)
            val key = dbKey ?: throw BackupFormatException("key")
            if (!locations.stagedDatabase.isFile) throw BackupFormatException("database")

            val preview = validateStagedDatabase(key.copyOf(), meta)
            // The database key is re-protected with this installation's Keystore key.
            DatabaseKeyStore.writeWrapped(locations.stagedKey, key, keyWrapper)
            RestoreJournal.writeAtomically(locations.readyMarker, "ready")
            return preview
        } finally {
            dbKey?.fill(0)
            plain.wipe()
        }
    }

    private fun verifyManifest(manifest: JSONObject, seen: Map<String, Pair<Long, String>>) {
        val listed = manifest.optJSONArray("entries") ?: throw BackupFormatException("manifest")
        val expected = (0 until listed.length()).associate { i ->
            val e = listed.getJSONObject(i)
            e.getString("name") to (e.getLong("size") to e.getString("sha256"))
        }
        if (expected.size != listed.length() || expected != seen) throw BackupFormatException("manifest")
    }

    /**
     * Opens the staged copy with its own key through Room (running any migrations from an
     * older schema), checks SQLCipher and SQLite integrity and foreign keys, and decrypts
     * every receipt image the database refers to. Nothing live is touched.
     */
    private suspend fun validateStagedDatabase(key: ByteArray, metadata: JSONObject): RestorePreview {
        val staged = CloakedCentDatabaseFactory.create(context, key, name = locations.stagedDatabase.absolutePath)
        try {
            val db = staged.openHelper.writableDatabase
            db.query("PRAGMA cipher_integrity_check").use { if (it.moveToFirst()) throw BackupFormatException("database integrity") }
            db.query("PRAGMA integrity_check").use { if (!it.moveToFirst() || it.getString(0) != "ok") throw BackupFormatException("database integrity") }
            db.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) throw BackupFormatException("database relationships") }

            val images = mutableListOf<Pair<String, String>>()
            db.query("SELECT file_name, content_sha256 FROM receipt_images").use { c -> while (c.moveToNext()) images += c.getString(0) to c.getString(1) }
            val staged = locations.stagedReceipts.listFiles().orEmpty().map { it.name }.toSet()
            if (images.map { it.first }.toSet() != staged) throw BackupException.MissingAttachment()
            if (images.isNotEmpty()) {
                val mediaKey = db.query("SELECT meta_value FROM app_metadata WHERE meta_key = ?", arrayOf(AppMetadataKeys.MEDIA_KEY))
                    .use { if (it.moveToFirst()) Base64.getDecoder().decode(it.getString(0)) else null }
                    ?: throw BackupException.MissingAttachment()
                val store = ReceiptImageStore(locations.stagedReceipts, { mediaKey })
                try {
                    images.forEach { (file, sha) -> store.read(file, sha).fill(0) }
                } catch (e: IOException) {
                    throw BackupException.MissingAttachment()
                } finally {
                    mediaKey.fill(0)
                }
            }
            fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            val preview = RestorePreview(
                createdAt = metadata.optLong("createdAt"),
                appVersion = metadata.optString("appVersion"),
                schemaVersion = metadata.optInt("schemaVersion"),
                accounts = count("accounts"),
                transactions = count("transactions"),
                receipts = count("receipts"),
                attachments = images.size,
            )
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            return preview
        } finally {
            staged.close()
            // The checkpoint above emptied the WAL; leftover empty sidecars are not needed.
            listOf("-wal", "-shm").map { File(locations.stagedDatabase.path + it) }.filter { it.length() == 0L }.forEach { it.delete() }
        }
    }

    override suspend fun discardPreparedRestore() = withContext(ioDispatcher) {
        if (!RestoreJournal.isScheduled(locations)) locations.stagingDir.deleteRecursively()
        Unit
    }

    override suspend fun commitPreparedRestore() = withContext(ioDispatcher) {
        if (!locations.readyMarker.isFile) throw BackupException.NothingPrepared()
        RestoreJournal.schedule(locations)
    }

    private suspend fun copy(input: DataInputStream, size: Long, target: File, onChunk: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileOutputStream(target).use { out ->
            var remaining = size
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IncompleteBackupException()
                out.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                remaining -= n
                onChunk()
            }
            out.fd.sync()
        }
        return hex(digest.digest())
    }

    private fun translate(e: Exception): Exception = when (e) {
        is BackupException -> e
        is WrongPasswordOrDamagedException -> BackupException.WrongPasswordOrDamaged()
        is IncompleteBackupException -> BackupException.Incomplete()
        is UnsupportedBackupException -> BackupException.Unsupported()
        is EOFException -> BackupException.Incomplete()
        is IOException, is SQLiteException, is IllegalStateException, is IllegalArgumentException,
        is org.json.JSONException, is android.database.SQLException -> BackupException.Damaged()
        else -> BackupException.Damaged()
    }

    private enum class Kind(val maxSize: Long) {
        METADATA(64 * 1024L), MANIFEST(32L * 1024 * 1024), KEY(DatabaseKeyStore.PASSPHRASE_BYTES.toLong()),
        DATABASE(4L * 1024 * 1024 * 1024), RECEIPT(64L * 1024 * 1024),
    }

    /** Only these exact names are accepted, so no entry can point outside the staging area. */
    private fun kindOf(name: String): Kind? = when {
        name == METADATA -> Kind.METADATA
        name == MANIFEST -> Kind.MANIFEST
        name == KEY -> Kind.KEY
        name == "$DATABASE_PREFIX${locations.databaseFile.name}" || name == "$DATABASE_PREFIX$LEGACY_DATABASE_NAME" -> Kind.DATABASE
        RECEIPT_NAME.matches(name) -> Kind.RECEIPT
        else -> null
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        const val METADATA = "metadata.json"
        const val MANIFEST = "manifest.json"
        const val KEY = "database.key"
        const val DATABASE_PREFIX = "database/"
        const val RECEIPT_PREFIX = "receipts/"

        /** What the database file was called in backups from before the rename. */
        private const val LEGACY_DATABASE_NAME = "pocket_budget.db"
        private val RECEIPT_NAME = Regex("""^receipts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(ccr|pbr)$""")
        private const val MAX_NAME = 128
        private const val MAX_ENTRIES = 200_000
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        private val NEVER_DELETE = DatabaseErrorHandler { _, _ -> }

        /** Space the system can give this app at [dir], including cache it is able to clear. */
        fun allocatableBytes(context: Context, dir: File): Long {
            val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
            return runCatching { storage.getAllocatableBytes(storage.getUuidForPath(dir.also { it.mkdirs() })) }.getOrElse { dir.usableSpace }
        }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
