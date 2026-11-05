package com.cloakedcent.app.data.backup

import android.content.Context
import com.cloakedcent.app.data.db.CloakedCentDatabase
import com.cloakedcent.app.data.db.CloakedCentDatabaseFactory
import com.cloakedcent.app.data.security.DatabaseKeyException
import com.cloakedcent.app.data.security.DatabaseKeyStore
import com.cloakedcent.app.data.security.KeyWrapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stands in for an Android Keystore key: each instance is a different device/installation. */
class FakeKeystoreWrapper : KeyWrapper {
    private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")

    override fun wrap(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            doFinal(wrapped, 12, wrapped.size - 12)
        }
    } catch (e: GeneralSecurityException) {
        throw DatabaseKeyException.KeyCorrupted("test wrapper", e)
    }
}

/**
 * A complete, isolated app installation in its own directory with its own "Keystore":
 * database, wrapped key, receipt images and restore areas, laid out like the real app.
 * Nothing here touches the app's real data.
 */
class TestInstallation(private val context: Context, val root: File, val wrapper: KeyWrapper = FakeKeystoreWrapper()) {
    val locations = StorageLocations(
        databaseFile = File(root, "databases/${CloakedCentDatabase.NAME}"),
        keyFile = File(root, "database.key"),
        receiptsDir = File(root, "receipts"),
        stagingDir = File(root, "restore-staging"),
        previousDir = File(root, "restore-previous"),
        journalFile = File(root, "restore.journal"),
    )
    private var opened: CloakedCentDatabase? = null
    val db: CloakedCentDatabase get() = opened ?: open()

    /** Opens like the app does: apply any scheduled restore first, then load the key. */
    fun open(): CloakedCentDatabase {
        RestoreJournal.recover(locations)
        locations.databaseFile.parentFile!!.mkdirs()
        val passphrase = keyStore().getOrCreatePassphrase(locations.databaseFile.exists())
        return CloakedCentDatabaseFactory.create(context, passphrase, name = locations.databaseFile.absolutePath).also { opened = it }
    }

    fun close() {
        opened?.close()
        opened = null
    }

    fun keyStore() = DatabaseKeyStore(locations.keyFile, wrapper)

    fun manager(
        kdf: BackupFormat.KdfParams = FAST_KDF,
        usableSpace: (File) -> Long = { it.usableSpace },
    ) = BackupManager(
        context = context,
        locations = locations,
        database = { db },
        passphrase = { keyStore().getOrCreatePassphrase(true) },
        keyWrapper = wrapper,
        appVersion = "test-1.0",
        kdf = kdf,
        usableSpace = usableSpace,
    )

    /** SHA-256 of every live file, to prove a failed restore changed nothing. */
    fun fingerprint(): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to sha(it.readBytes()) }

    companion object {
        /** Low-cost Argon2 for most tests; one test uses the production parameters. */
        val FAST_KDF = BackupFormat.KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Reads and writes backup archives directly, to build damaged or malicious backups for tests. */
object BackupArchive {
    data class Entry(val name: String, val bytes: ByteArray)

    fun read(backup: ByteArray, password: String): List<Entry> {
        val input = ByteArrayInputStream(backup)
        val (header, preamble) = BackupFormat.readPreamble(input)
        val key = BackupFormat.deriveKey(password.toCharArray(), header)
        val data = DataInputStream(BackupFormat.DecryptingStream(input, header, key, preamble))
        val entries = mutableListOf<Entry>()
        while (data.readUnsignedByte() == 1) {
            val name = String(ByteArray(data.readUnsignedShort()).also(data::readFully))
            entries += Entry(name, ByteArray(data.readLong().toInt()).also(data::readFully))
        }
        return entries
    }

    /** Writes [entries] as given; with [manifest] a matching manifest is appended. */
    fun write(entries: List<Entry>, password: String, manifest: Boolean = true, kdf: BackupFormat.KdfParams = TestInstallation.FAST_KDF): ByteArray {
        val header = BackupFormat.newHeader(kdf, SecureRandom())
        val key = BackupFormat.deriveKey(password.toCharArray(), header)
        val out = ByteArrayOutputStream()
        val encrypted = BackupFormat.EncryptingStream(out, header, key, BackupFormat.preamble(header))
        val data = DataOutputStream(encrypted)
        fun entry(name: String, bytes: ByteArray) {
            data.writeByte(1)
            name.toByteArray().let { data.writeShort(it.size); data.write(it) }
            data.writeLong(bytes.size.toLong())
            data.write(bytes)
        }
        val list = JSONArray()
        entries.filter { it.name != BackupManager.MANIFEST }.forEach {
            entry(it.name, it.bytes)
            list.put(JSONObject().put("name", it.name).put("size", it.bytes.size).put("sha256", TestInstallation.sha(it.bytes)))
        }
        if (manifest) entry(BackupManager.MANIFEST, JSONObject().put("entries", list).toString().toByteArray())
        data.writeByte(0)
        data.flush()
        encrypted.close()
        return out.toByteArray()
    }
}
