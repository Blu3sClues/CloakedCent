package com.pocketbudget.app.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteException
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.repository.RoomAccountRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun migrate2To3ConvertsStatusesAndKeepsBalancesAndHistory() = runTest {
        helper.createDatabase(TEST_DB, 2).apply {
            // Triggers exactly as a v2 install had them.
            execSQL(
                """
                CREATE TRIGGER trg_transactions_validate_update BEFORE UPDATE ON transactions
                WHEN NEW.status NOT IN ('SCHEDULED', 'PENDING', 'CLEARED')
                BEGIN SELECT RAISE(ABORT, 'v2 rule'); END
                """,
            )
            execSQL(
                """
                CREATE TRIGGER trg_revisions_immutable_update BEFORE UPDATE ON transaction_revisions
                BEGIN SELECT RAISE(ABORT, 'revisions are immutable'); END
                """,
            )
            execSQL(
                """
                INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at)
                VALUES (1, 'Checking', 'CHECKING', 10000, 1, 0, 0, 0)
                """,
            )
            fun entry(id: Int, cents: Long, status: String) = execSQL(
                """
                INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on,
                    revision, is_deleted, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?, ?, 20400, 2, 0, 0, 0)
                """,
                arrayOf<Any>(id, "r$id", if (cents > 0) "INCOME" else "EXPENSE", cents, status),
            )
            entry(1, 5_000, "CLEARED")
            entry(2, -1_000, "PENDING")
            entry(3, -2_000, "SCHEDULED")
            execSQL(
                """
                INSERT INTO transaction_revisions (transaction_id, revision, action, account_id, type, amount_cents,
                    status, occurred_on, recorded_at)
                VALUES (2, 1, 'UPDATE', 1, 'EXPENSE', -900, 'PENDING', 20400, 0)
                """,
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)
        migrated.query("SELECT id, status FROM transactions ORDER BY id").use {
            val statuses = generateSequence { if (it.moveToNext()) it.getString(1) else null }.toList()
            assertEquals(listOf("SPENT", "SPENT", "SCHEDULED"), statuses)
        }
        migrated.query("SELECT status, amount_cents FROM transaction_revisions").use {
            it.moveToFirst()
            assertEquals("SPENT", it.getString(0))
            assertEquals(-900L, it.getLong(1))
        }
        migrated.close()

        // Open as the app does: triggers are recreated with v3 rules.
        val db = PocketBudgetDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        val balance = RoomAccountRepository({ db }).observeBalances().first().single()
        assertEquals("opening + income - expense", Money(14_000), balance.current)
        assertEquals("scheduled stays projected only", Money(12_000), balance.projected)
        val writable = db.openHelper.writableDatabase
        assertThrows(SQLiteException::class.java) {
            writable.execSQL("UPDATE transactions SET status = 'PENDING' WHERE id = 1")
        }
        assertThrows(SQLiteException::class.java) {
            writable.execSQL("UPDATE transaction_revisions SET amount_cents = 0")
        }
    }

    @Test
    fun migrate3To4SplitsStatusesByTypeAndKeepsHistory() = runTest {
        helper.createDatabase(TEST_DB, 3).apply {
            // v3 trigger and immutability, as on a Stage 4.1 install.
            execSQL(
                """
                CREATE TRIGGER trg_transactions_validate_update BEFORE UPDATE ON transactions
                WHEN NEW.status NOT IN ('SCHEDULED', 'SPENT')
                BEGIN SELECT RAISE(ABORT, 'v3 rule'); END
                """,
            )
            execSQL(
                """
                CREATE TRIGGER trg_revisions_immutable_update BEFORE UPDATE ON transaction_revisions
                BEGIN SELECT RAISE(ABORT, 'revisions are immutable'); END
                """,
            )
            listOf(1, 2).forEach { id ->
                execSQL(
                    """
                    INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at)
                    VALUES (?, 'A$id', 'CHECKING', 10000, 1, 0, 0, 0)
                    """,
                    arrayOf<Any>(id),
                )
            }
            execSQL("INSERT INTO transfers (id, request_id, from_account_id, to_account_id, created_at) VALUES (1, 'x', 1, 2, 0)")
            fun entry(id: Int, account: Int, type: String, cents: Long, status: String, transfer: Int? = null) = execSQL(
                """
                INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on,
                    transfer_id, revision, is_deleted, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 20400, ?, 2, 0, 0, 0)
                """,
                arrayOf<Any?>(id, if (transfer == null) "r$id" else null, account, type, cents, status, transfer),
            )
            entry(1, 1, "INCOME", 5_000, "SPENT")
            entry(2, 1, "EXPENSE", -1_000, "SPENT")
            entry(3, 1, "TRANSFER", -2_000, "SPENT", transfer = 1)
            entry(4, 2, "TRANSFER", 2_000, "SPENT", transfer = 1)
            entry(5, 1, "EXPENSE", -300, "SCHEDULED")
            execSQL(
                """
                INSERT INTO transaction_revisions (transaction_id, revision, action, account_id, type, amount_cents,
                    status, occurred_on, recorded_at)
                VALUES (1, 1, 'UPDATE', 1, 'INCOME', 4500, 'SPENT', 20400, 0)
                """,
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)
        migrated.query("SELECT status, is_adjustment FROM transactions ORDER BY id").use {
            val rows = generateSequence { if (it.moveToNext()) it.getString(0) to it.getInt(1) else null }.toList()
            assertEquals(listOf("RECEIVED", "SPENT", "COMPLETED", "COMPLETED", "SCHEDULED"), rows.map { r -> r.first })
            assertEquals(listOf(0, 0, 0, 0, 0), rows.map { r -> r.second })
        }
        migrated.query("SELECT status, amount_cents FROM transaction_revisions").use {
            it.moveToFirst()
            assertEquals("RECEIVED", it.getString(0))
            assertEquals(4_500L, it.getLong(1))
        }
        migrated.close()

        val db = PocketBudgetDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        val balances = RoomAccountRepository({ db }).observeBalances().first().associateBy { it.account.id }
        assertEquals(Money(12_000), balances.getValue(1).current) // 10,000 + 5,000 - 1,000 - 2,000
        assertEquals(Money(11_700), balances.getValue(1).projected)
        assertEquals(Money(12_000), balances.getValue(2).current)
        // Confirming a migrated scheduled expense uses the v4 rules.
        assertEquals(true, com.pocketbudget.app.data.repository.RoomTransactionRepository({ db }).confirm(5))
        db.openHelper.readableDatabase.query("SELECT status FROM transactions WHERE id = 5").use {
            it.moveToFirst()
            assertEquals("SPENT", it.getString(0))
        }
        val writable = db.openHelper.writableDatabase
        assertThrows(SQLiteException::class.java) {
            writable.execSQL("UPDATE transactions SET status = 'SPENT' WHERE id = 1") // income cannot be SPENT
        }
        assertThrows(SQLiteException::class.java) {
            writable.execSQL("UPDATE transaction_revisions SET amount_cents = 0")
        }
    }

    @Test
    fun migrate4To5AddsPlanningTablesAndKeepsLedger() = runTest {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at)
                VALUES (1, 'Checking', 'CHECKING', 10000, 1, 0, 0, 0)
                """,
            )
            execSQL(
                """
                INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on,
                    revision, is_deleted, is_adjustment, created_at, updated_at)
                VALUES (1, 'r1', 1, 'EXPENSE', -2500, 'SPENT', 20400, 1, 0, 0, 0, 0)
                """,
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5).apply {
            query("SELECT COUNT(*) FROM budgets").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            query("SELECT COUNT(*) FROM recurring_schedules").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }
        val db = PocketBudgetDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        assertEquals(Money(7_500), RoomAccountRepository({ db }).observeBalances().first().single().current)
    }

    @Test
    fun migrate5To6AddsReceiptTablesAndKeepsLedgerAndBudgets() = runTest {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at)
                VALUES (1, 'Checking', 'CHECKING', 10000, 1, 0, 0, 0)
                """,
            )
            execSQL("INSERT INTO categories (id, name, kind, parent_id, is_archived, created_at) VALUES (7, 'Food', 'EXPENSE', NULL, 0, 0)")
            execSQL(
                """
                INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on,
                    category_id, revision, is_deleted, is_adjustment, created_at, updated_at)
                VALUES (1, 'r1', 1, 'EXPENSE', -2500, 'SPENT', 20400, 7, 1, 0, 0, 0, 0)
                """,
            )
            execSQL(
                """
                INSERT INTO budgets (id, name, category_id, period_type, start_date, end_date, cycle_start, rollover,
                    alert_thresholds, state, created_at, updated_at)
                VALUES (1, 'Food', 7, 'MONTHLY', 20393, NULL, 20393, 0, '80,100', 'ACTIVE', 0, 0)
                """,
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6).apply {
            listOf("receipts", "receipt_items", "products", "price_observations", "transaction_splits").forEach { table ->
                query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
            close()
        }
        val db = PocketBudgetDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        assertEquals(Money(7_500), RoomAccountRepository({ db }).observeBalances().first().single().current)
        // An ordinary expense without splits still counts once, in its own category.
        val spending = db.budgetDao().getExpenses(java.time.LocalDate.ofEpochDay(0))
        assertEquals(listOf(7L to 2500L), spending.map { it.categoryId to it.spentCents })
        // v6 triggers are installed on open: a split larger than its expense is refused.
        val failed = runCatching {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, created_at) VALUES (1, 7, 2501, 0)",
            )
        }
        assertTrue(failed.isFailure)
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
