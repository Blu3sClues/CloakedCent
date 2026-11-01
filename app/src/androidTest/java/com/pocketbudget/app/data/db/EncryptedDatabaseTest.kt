package com.pocketbudget.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.data.db.entity.AppMetadataEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "encrypted-db-test.db"
    private val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    private fun open(key: ByteArray = passphrase) =
        PocketBudgetDatabaseFactory.create(context, key, name = dbName, clock = fixedClock)

    @Test
    fun databaseFileIsNotPlainSqlite() = runTest {
        open().apply {
            appMetadataDao().upsert(AppMetadataEntity("probe", "secret-financial-value"))
            close()
        }

        val bytes = context.getDatabasePath(dbName).readBytes()
        val plainSqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertFalse(
            "Database must not start with the plaintext SQLite header",
            bytes.copyOfRange(0, plainSqliteHeader.size).contentEquals(plainSqliteHeader),
        )
        assertFalse(
            "Stored values must not appear in plaintext",
            String(bytes, Charsets.ISO_8859_1).contains("secret-financial-value"),
        )
    }

    @Test
    fun reopeningWithTheSameKeyReadsData() = runTest {
        open().apply {
            appMetadataDao().upsert(AppMetadataEntity("probe", "value"))
            close()
        }

        val reopened = open()
        assertEquals("value", reopened.appMetadataDao().getValue("probe"))
        reopened.close()
    }

    @Test
    fun wrongKeyCannotOpenDatabase() = runTest {
        open().apply {
            appMetadataDao().upsert(AppMetadataEntity("probe", "value"))
            close()
        }

        val wrongKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val db = open(wrongKey)
        try {
            db.appMetadataDao().getValue("probe")
            fail("Opening with the wrong key must fail")
        } catch (expected: Exception) {
            // SQLCipher reports "file is not a database".
        } finally {
            db.close()
        }
        assertTrue("A failed open must not delete the database", context.getDatabasePath(dbName).exists())
    }

    @Test
    fun sqlCipherIsActive() {
        val db = open()
        val version = db.openHelper.readableDatabase.query("PRAGMA cipher_version").use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        assertNotNull("PRAGMA cipher_version must return a value under SQLCipher", version)
        db.close()
    }

    @Test
    fun foreignKeysAreEnforced() {
        val db = open()
        val enabled = db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(1, enabled)
        db.close()
    }

    @Test
    fun creationTimeIsRecordedOnCreate() = runTest {
        val db = open()
        assertEquals(
            fixedClock.millis().toString(),
            db.appMetadataDao().getValue(AppMetadataKeys.CREATED_AT),
        )
        db.close()
    }
}
