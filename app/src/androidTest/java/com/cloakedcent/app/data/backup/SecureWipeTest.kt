package com.cloakedcent.app.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cloakedcent.app.data.db.entity.AccountEntity
import com.cloakedcent.app.data.security.DatabaseKeyException
import com.cloakedcent.app.data.security.KeyWrapper
import com.cloakedcent.app.domain.model.AccountType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SecureWipeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "secure-wipe-test")
    private val cache = File(root, "cache")
    private lateinit var install: TestInstallation

    @Before
    fun setUp() {
        root.deleteRecursively()
        cache.mkdirs()
        install = TestInstallation(context, root)
    }

    @After
    fun tearDown() {
        install.close()
        root.deleteRecursively()
    }

    private fun accounts() = install.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM accounts").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun savedData() {
        install.db.accountDao().insert(AccountEntity(name = "Savings", type = AccountType.SAVINGS, openingBalanceCents = 1000, includeInTotals = true, createdAt = 0, updatedAt = 0))
        assertEquals(1, accounts())
        install.locations.receiptsDir.mkdirs()
        File(install.locations.receiptsDir, "image.ccr").writeText("receipt")
        File(cache, "receipt.pdf").writeText("plain copy")
        install.locations.stagingDir.mkdirs()
        File(install.locations.stagingDir, "staged").writeText("staged")
        install.close()
    }

    private fun wipe(clear: () -> Unit = {}) = SecureWipe.finishIfScheduled(install.locations, install.wrapper, listOf(cache), clear)

    @Test
    fun nothingHappensUntilItIsScheduled() = runTest {
        savedData()
        assertFalse(wipe())
        assertEquals(1, accounts())
    }

    @Test
    fun everythingIsGoneAndTheAppStartsFresh() = runTest {
        savedData()
        val wrappedKey = install.locations.keyFile.readBytes()
        var cleared = 0

        SecureWipe.schedule(install.locations)
        assertTrue(wipe { cleared++ })

        assertEquals(1, cleared)
        with(install.locations) {
            listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"), keyFile, receiptsDir, stagingDir, wipeMarker)
                .forEach { assertFalse("$it survived", it.exists()) }
        }
        assertTrue("the cache folder stays", cache.isDirectory)
        assertEquals(emptyList<String>(), cache.list()!!.asList())
        assertThrows("the old key must be useless", DatabaseKeyException::class.java) { install.wrapper.unwrap(wrappedKey.copyOfRange(1, wrappedKey.size)) }

        assertEquals(0, accounts())
    }

    @Test
    fun anInterruptedWipeFinishesAtTheNextStart() = runTest {
        savedData()
        SecureWipe.schedule(install.locations)
        // The first attempt dies straight after the key is destroyed.
        val dying = object : KeyWrapper by install.wrapper {
            override fun destroy() {
                install.wrapper.destroy()
                throw IOException("power loss")
            }
        }
        assertThrows(IOException::class.java) { SecureWipe.finishIfScheduled(install.locations, dying) }
        assertTrue(SecureWipe.isScheduled(install.locations))

        assertTrue(wipe())
        assertFalse(install.locations.databaseFile.exists())
        assertFalse(SecureWipe.isScheduled(install.locations))
        assertEquals(0, accounts())
    }

    @Test
    fun aWipeOverridesARestoreWaitingToBeApplied() = runTest {
        savedData()
        install.locations.readyMarker.writeText("ready")
        RestoreJournal.schedule(install.locations)
        SecureWipe.schedule(install.locations)

        assertTrue(wipe())

        assertFalse(install.locations.journalFile.exists())
        assertFalse(install.locations.stagingDir.exists())
        assertFalse(RestoreJournal.recover(install.locations))
    }
}
