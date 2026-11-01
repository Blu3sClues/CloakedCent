package com.pocketbudget.app.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * Migration tests run against encrypted databases built from the exported
 * schemas in app/schemas, exactly as a real upgrade would.
 *
 * When adding migration N -> N+1:
 * 1. Add a test that creates version N with helper.createDatabase(TEST_DB, N),
 *    inserts representative rows with raw SQL, runs
 *    helper.runMigrationsAndValidate(TEST_DB, N + 1, true, MIGRATION_N_N+1),
 *    and asserts every row survived with correct values.
 * 2. migrateFromFirstVersionToLatest keeps covering the full chain.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)

    init {
        PocketBudgetDatabaseFactory.loadSqlCipher()
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketBudgetDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(passphrase),
    )

    @After
    fun deleteDatabase() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun version1SchemaMatchesExportedSchema() {
        helper.createDatabase(TEST_DB, FIRST_VERSION).close()
        helper.runMigrationsAndValidate(TEST_DB, FIRST_VERSION, true).close()
    }

    @Test
    fun migrate1To2CreatesLedgerAndKeepsExistingData() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO app_metadata (meta_key, meta_value) VALUES (?, ?)",
                arrayOf<Any>(AppMetadataKeys.CREATED_AT, "1700000000000"),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT meta_value FROM app_metadata").use {
            it.moveToFirst()
            assertEquals("1700000000000", it.getString(0))
        }
        db.query(
            """
            SELECT parent.name FROM categories child
            JOIN categories parent ON parent.id = child.parent_id
            WHERE child.name = 'Groceries'
            """,
        ).use {
            it.moveToFirst()
            assertEquals("Food", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM categories").use {
            it.moveToFirst()
            assertEquals(11, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrateFromFirstVersionToLatest() = runTest {
        helper.createDatabase(TEST_DB, FIRST_VERSION).apply {
            execSQL(
                "INSERT INTO app_metadata (meta_key, meta_value) VALUES (?, ?)",
                arrayOf<Any>(AppMetadataKeys.CREATED_AT, "1700000000000"),
            )
            close()
        }

        // Opening through the production factory applies Migrations.ALL and
        // Room validates the result against the current entities.
        val db = PocketBudgetDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)

        assertEquals(
            "1700000000000",
            db.appMetadataDao().getValue(AppMetadataKeys.CREATED_AT),
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val FIRST_VERSION = 1
    }
}
