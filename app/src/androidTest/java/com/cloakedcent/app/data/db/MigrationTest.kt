package com.cloakedcent.app.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteException
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.repository.RoomAccountRepository
import com.cloakedcent.app.domain.model.TextKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        CloakedCentDatabaseFactory.loadSqlCipher()
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CloakedCentDatabase::class.java,
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
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
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

        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        val balances = RoomAccountRepository({ db }).observeBalances().first().associateBy { it.account.id }
        assertEquals(Money(12_000), balances.getValue(1).current) // 10,000 + 5,000 - 1,000 - 2,000
        assertEquals(Money(11_700), balances.getValue(1).projected)
        assertEquals(Money(12_000), balances.getValue(2).current)
        // Confirming a migrated scheduled expense uses the v4 rules.
        assertEquals(true, com.cloakedcent.app.data.repository.RoomTransactionRepository({ db }).confirm(5))
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
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
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
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
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
    fun migrate6To7AddsRefundsAndRekeysNamesWithoutMerging() = runTest {
        helper.createDatabase(TEST_DB, 6).apply {
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
            // v6 keys: spacing and format words differed, so these were separate.
            execSQL("INSERT INTO stores (id, name, normalized_name, created_at, updated_at) VALUES (1, 'Walmart Supercenter', 'WALMART SUPERCENTER', 0, 0)")
            execSQL("INSERT INTO stores (id, name, normalized_name, created_at, updated_at) VALUES (2, 'WAL-MART', 'WAL MART', 0, 0)")
            execSQL("INSERT INTO stores (id, name, normalized_name, created_at, updated_at) VALUES (3, 'Walgreens', 'WALGREENS', 0, 0)")
            execSQL("INSERT INTO products (id, name, normalized_name, created_at, updated_at) VALUES (1, 'Milk 1 L', 'MILK 1 L', 0, 0)")
            execSQL("INSERT INTO products (id, name, normalized_name, created_at, updated_at) VALUES (2, 'MILK 1L', 'MILK 1L', 0, 0)")
            execSQL("INSERT INTO products (id, name, normalized_name, created_at, updated_at) VALUES (3, 'Milk 2L', 'MILK 2L', 0, 0)")
            execSQL("INSERT INTO product_aliases (id, store_id, normalized_alias, product_id, times_confirmed, created_at, updated_at) VALUES (1, 1, 'GV MLK 1 LTR', 1, 3, 0, 0)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7).apply {
            fun strings(sql: String) = query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            assertEquals(listOf("WALMART", "WALMART", "WALGREENS"), strings("SELECT normalized_name FROM stores ORDER BY id"))
            // Same product spelled two ways: both kept (history untouched), the later one with a unique key.
            assertEquals(listOf("MILK 1L", "MILK 1L~2", "MILK 2L"), strings("SELECT normalized_name FROM products ORDER BY id"))
            assertEquals(listOf("GV MLK 1L"), strings("SELECT normalized_alias FROM product_aliases"))
            assertEquals(listOf("0"), strings("SELECT COUNT(*) FROM refunds"))
            close()
        }
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        assertEquals(Money(7_500), RoomAccountRepository({ db }).observeBalances().first().single().current)
        // v7 triggers are installed on open: refunds beyond the expense are refused.
        val writable = db.openHelper.writableDatabase
        writable.execSQL(
            "INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES (2, 'f', 1, 'REFUND', 3000, 'RECEIVED', 20401, 1, 0, 0, 0, 0)",
        )
        assertTrue(runCatching { writable.execSQL("INSERT INTO refunds (refund_transaction_id, original_transaction_id, created_at) VALUES (2, 1, 0)") }.isFailure)
        // Scheduled refunds are not valid entries.
        assertTrue(
            runCatching {
                writable.execSQL(
                    "INSERT INTO transactions (request_id, account_id, type, amount_cents, status, occurred_on, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES ('g', 1, 'REFUND', 100, 'SCHEDULED', 20401, 1, 0, 0, 0, 0)",
                )
            }.isFailure,
        )
    }

    @Test
    fun migrate7To8LetsConfirmedReceiptsBeDeletedButNothingElse() = runTest {
        helper.createDatabase(TEST_DB, 7).apply {
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
            execSQL(
                """
                INSERT INTO receipts (id, request_id, status, store_name, store_address, tax_cents, discount_cents,
                    total_cents, receipt_number, transaction_id, uncertain_fields, field_sources, created_at, updated_at)
                VALUES (1, 'q1', 'CONFIRMED', 'Shop', '', 0, 0, 2500, '', 1, '', '', 0, 0)
                """,
            )
            // The v7 rule, as an upgraded device has it: confirmed receipts never change.
            execSQL(
                """
                CREATE TRIGGER trg_receipts_final BEFORE UPDATE ON receipts
                WHEN OLD.status IN ('CONFIRMED', 'DISCARDED')
                    OR (NEW.status = 'CONFIRMED' AND (NEW.transaction_id IS NULL OR NEW.total_cents IS NULL))
                    OR (NEW.status <> 'CONFIRMED' AND NEW.transaction_id IS NOT NULL)
                BEGIN SELECT RAISE(ABORT, 'receipt is final or inconsistent'); END
                """,
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8).close()
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        val writable = db.openHelper.writableDatabase
        // Any other change to a confirmed receipt is still refused.
        assertTrue(runCatching { writable.execSQL("UPDATE receipts SET store_name = 'Other' WHERE id = 1") }.isFailure)
        assertTrue(runCatching { writable.execSQL("UPDATE receipts SET status = 'DELETED', transaction_id = NULL WHERE id = 1") }.isFailure)
        writable.execSQL("UPDATE receipts SET status = 'DELETED' WHERE id = 1")
        // Deleted is final.
        assertTrue(runCatching { writable.execSQL("UPDATE receipts SET status = 'CONFIRMED' WHERE id = 1") }.isFailure)
        assertEquals(Money(7_500), RoomAccountRepository({ db }).observeBalances().first().single().current)
    }

    @Test
    fun migrate8To9ErasesEverythingSoftDeletedAndUnusedNames() = runTest {
        val milk = TextKeys.descriptionKey("MILK")
        helper.createDatabase(TEST_DB, 8).apply {
            listOf(
                "INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at) VALUES (1, 'A', 'CHECKING', 10000, 1, 0, 0, 0), (2, 'B', 'SAVINGS', 0, 1, 0, 0, 0)",
                "INSERT INTO categories (id, name, kind, parent_id, is_archived, created_at) VALUES (7, 'Food', 'EXPENSE', NULL, 0, 0)",
                "INSERT INTO payees (id, name, type, created_at) VALUES (1, 'Kept shop', 'MERCHANT', 0), (2, 'Secret clinic', 'MERCHANT', 0), (3, 'Gone store', 'MERCHANT', 0)",
                "INSERT INTO transfers (id, request_id, from_account_id, to_account_id, created_at) VALUES (1, 'x1', 1, 2, 0)",
                // 1 is live; 2 and 3 are soft-deleted expenses, 4 and 5 a soft-deleted transfer.
                """
                INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on, category_id, payee_id,
                    transfer_id, note, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES
                    (1, 'r1', 1, 'EXPENSE', -2500, 'SPENT', 20400, 7, 1, NULL, NULL, 2, 0, 0, 0, 0),
                    (2, 'r2', 1, 'EXPENSE', -900, 'SPENT', 20400, 7, 2, NULL, 'private', 3, 1, 0, 0, 0),
                    (3, 'r3', 1, 'EXPENSE', -1000, 'SPENT', 20400, 7, NULL, NULL, NULL, 2, 1, 0, 0, 0),
                    (4, 'x1-out', 1, 'TRANSFER', -500, 'COMPLETED', 20400, NULL, NULL, 1, NULL, 2, 1, 0, 0, 0),
                    (5, 'x1-in', 2, 'TRANSFER', 500, 'COMPLETED', 20400, NULL, NULL, 1, NULL, 2, 1, 0, 0, 0)
                """,
                """
                INSERT INTO transaction_revisions (transaction_id, revision, action, account_id, type, amount_cents, status,
                    occurred_on, category_id, payee_id, note, recorded_at) VALUES
                    (1, 1, 'UPDATE', 1, 'EXPENSE', -2000, 'SPENT', 20400, 7, 1, 'old note', 0),
                    (2, 1, 'UPDATE', 1, 'EXPENSE', -800, 'SPENT', 20400, 7, 2, 'earlier private', 0),
                    (2, 2, 'DELETE', 1, 'EXPENSE', -900, 'SPENT', 20400, 7, 2, 'private', 0)
                """,
                """
                INSERT INTO recurring_schedules (id, name, kind, type, account_id, amount_cents, variable_amount, frequency,
                    interval_count, start_date, state, created_at, updated_at)
                VALUES (1, 'Rent', 'RENT', 'EXPENSE', 1, -900, 0, 'MONTHLY', 1, 20400, 'ACTIVE', 0, 0)
                """,
                "INSERT INTO recurring_occurrences (schedule_id, due_date, transaction_id, skipped, reminder_sent, overdue_sent, created_at) VALUES (1, 20400, 2, 0, 0, 0, 0)",
                "INSERT INTO stores (id, name, normalized_name, payee_id, created_at, updated_at) VALUES (1, 'Kept store', 'KEPT STORE', NULL, 0, 0), (2, 'Gone store', 'GONE STORE', 3, 0, 0)",
                "INSERT INTO products (id, name, normalized_name, created_at, updated_at) VALUES (1, 'Milk', 'MILK', 0, 0), (2, 'Pills', 'PILLS', 0, 0)",
                // 1 was deleted (v8 kept it, linked to live expense 1), 2 discarded, 3 kept though its expense was deleted.
                """
                INSERT INTO receipts (id, request_id, status, store_id, store_name, store_address, tax_cents, discount_cents,
                    total_cents, receipt_number, transaction_id, uncertain_fields, field_sources, created_at, updated_at) VALUES
                    (1, 'q1', 'DELETED', 2, 'Gone store', '', 0, 0, 2500, '', 1, '', '', 0, 0),
                    (2, 'q2', 'DISCARDED', NULL, 'Draft shop', '', 0, 0, 100, '', NULL, '', '', 0, 0),
                    (3, 'q3', 'CONFIRMED', 1, 'Kept store', '', 0, 0, 1000, '', 3, '', '', 0, 0)
                """,
                """
                INSERT INTO receipt_items (id, receipt_id, position, raw_text, description, product_id, quantity_milli, unit,
                    line_total_cents, discount_cents, category_id, confidence, uncertain, user_verified) VALUES
                    (1, 1, 0, 'PILLS', 'Pills', 2, 1000, 'EACH', 1500, 0, 7, 90, 0, 1),
                    (2, 1, 1, 'MILK', 'Milk', 1, 1000, 'EACH', 1000, 0, 7, 90, 0, 1),
                    (3, 3, 0, 'MILK', 'Milk', 1, 1000, 'EACH', 1000, 0, 7, 90, 0, 1)
                """,
                """
                INSERT INTO price_observations (product_id, store_id, receipt_item_id, observed_on, quantity_milli, unit, paid_cents, created_at)
                VALUES (2, 2, 1, 20400, 1000, 'EACH', 1500, 0), (1, 2, 2, 20400, 1000, 'EACH', 1000, 0), (1, 1, 3, 20400, 1000, 'EACH', 1000, 0)
                """,
                "INSERT INTO product_aliases (id, store_id, normalized_alias, product_id, times_confirmed, created_at, updated_at) VALUES (1, 2, 'PILLS', 2, 1, 0, 0), (2, 1, '$milk', 1, 3, 0, 0)",
                "INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, receipt_id, created_at) VALUES (1, 7, 2500, 1, 0), (3, 7, 1000, 3, 0)",
                "INSERT INTO receipt_ocr_lines (receipt_id, page, line_index, text, confidence) VALUES (1, 0, 0, 'GONE STORE', 90), (2, 0, 0, 'DRAFT SHOP', 90)",
            ).forEach(::execSQL)
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9).apply {
            fun longs(sql: String) = query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
            fun strings(sql: String) = query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            assertEquals(listOf(1L), longs("SELECT id FROM transactions"))
            assertEquals("a kept entry keeps its edit history", listOf("old note"), strings("SELECT note FROM transaction_revisions"))
            assertEquals(listOf(0L), longs("SELECT COUNT(*) FROM transfers"))
            assertEquals(listOf("Kept shop"), strings("SELECT name FROM payees"))
            assertEquals(listOf(3L), longs("SELECT id FROM receipts"))
            assertEquals("its deleted expense is unlinked", listOf(0L), longs("SELECT COUNT(*) FROM receipts WHERE transaction_id IS NOT NULL"))
            assertEquals(listOf(3L), longs("SELECT id FROM receipt_items"))
            assertEquals(listOf(3L), longs("SELECT receipt_item_id FROM price_observations"))
            assertEquals(listOf("Milk"), strings("SELECT name FROM products"))
            assertEquals(listOf("Kept store"), strings("SELECT name FROM stores"))
            assertEquals("confirmations from erased receipts are forgotten", listOf(1L), longs("SELECT times_confirmed FROM product_aliases"))
            assertEquals(listOf(0L), longs("SELECT COUNT(*) FROM receipt_ocr_lines"))
            assertEquals("the live expense keeps its split", listOf(1L), longs("SELECT transaction_id FROM transaction_splits WHERE receipt_id IS NULL"))
            assertEquals(listOf(1L), longs("SELECT COUNT(*) FROM transaction_splits"))
            assertEquals("the occurrence stays, so it is not generated again", listOf(1L), longs("SELECT COUNT(*) FROM recurring_occurrences WHERE transaction_id IS NULL"))
            assertEquals(listOf(1L), longs("SELECT COUNT(*) FROM app_metadata WHERE meta_key = '${AppMetadataKeys.VACUUM_PENDING}'"))
            close()
        }
        // Opening runs the requested VACUUM once, with secure_delete on from then on.
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        assertEquals(Money(7_500), RoomAccountRepository({ db }).observeBalances().first().first { it.account.id == 1L }.current)
        assertEquals(null, db.appMetadataDao().getValue(AppMetadataKeys.VACUUM_PENDING))
        assertTrue(SecureErase.isSecureDeleteOn(db.openHelper.writableDatabase))
        // v9 triggers: live rows still cannot be removed directly.
        val writable = db.openHelper.writableDatabase
        assertTrue(runCatching { writable.execSQL("DELETE FROM transactions WHERE id = 1") }.isFailure)
        assertTrue(runCatching { writable.execSQL("DELETE FROM transaction_revisions") }.isFailure)
        assertTrue(runCatching { writable.execSQL("DELETE FROM transaction_splits") }.isFailure)
        assertTrue(runCatching { writable.execSQL("DELETE FROM receipts WHERE id = 3") }.isFailure)
        assertTrue(runCatching { writable.execSQL("DELETE FROM price_observations") }.isFailure)
    }

    @Test
    fun migrate9To10LetsADeletedCategoryHandOverItsSplitsAndNothingElse() = runTest {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at) VALUES (1, 'A', 'CHECKING', 10000, 1, 0, 0, 0)")
            execSQL("INSERT INTO categories (id, name, kind, parent_id, is_archived, created_at) VALUES (7, 'Snacks', 'EXPENSE', NULL, 0, 0), (8, 'Food', 'EXPENSE', NULL, 0, 0)")
            execSQL(
                "INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on, category_id, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES (1, 'r1', 1, 'EXPENSE', -2500, 'SPENT', 20400, 7, 1, 0, 0, 0, 0)",
            )
            execSQL("INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, created_at) VALUES (1, 7, 2500, 0)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 10, true, Migrations.MIGRATION_9_10).close()
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        val writable = db.openHelper.writableDatabase
        // Locked as before while its category is live.
        assertTrue(runCatching { writable.execSQL("DELETE FROM transaction_splits") }.isFailure)
        // Once its category is being deleted, the split may be rebuilt.
        writable.execSQL("UPDATE categories SET is_archived = 1 WHERE id = 7")
        writable.execSQL("DELETE FROM transaction_splits")
        assertTrue("not into a category being deleted", runCatching {
            writable.execSQL("INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, created_at) VALUES (1, 7, 2500, 0)")
        }.isFailure)
        writable.execSQL("INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, created_at) VALUES (1, 8, 2500, 0)")
        assertEquals(Money(7_500), RoomAccountRepository({ db }).observeBalances().first().single().current)
    }

    @Test
    fun migrate10To11ErasesTheReadingOfConfirmedReceiptsOnly() = runTest {
        helper.createDatabase(TEST_DB, 10).apply {
            listOf(
                "INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at) VALUES (1, 'A', 'CHECKING', 10000, 1, 0, 0, 0)",
                "INSERT INTO categories (id, name, kind, parent_id, is_archived, created_at) VALUES (7, 'Food', 'EXPENSE', NULL, 0, 0)",
                "INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on, category_id, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES (1, 'r1', 1, 'EXPENSE', -1000, 'SPENT', 20400, 7, 1, 0, 0, 0, 0)",
                """
                INSERT INTO receipts (id, request_id, status, store_name, store_address, tax_cents, discount_cents,
                    total_cents, receipt_number, transaction_id, uncertain_fields, field_sources, created_at, updated_at) VALUES
                    (1, 'q1', 'CONFIRMED', 'Kept store', '', 0, 0, 1000, '', 1, '', 'TOTAL=0:1', 0, 0),
                    (2, 'q2', 'NEEDS_REVIEW', 'Draft shop', '', 0, 0, 100, '', NULL, '', 'TOTAL=0:1', 0, 0)
                """,
                """
                INSERT INTO receipt_items (id, receipt_id, position, raw_text, description, quantity_milli, unit,
                    line_total_cents, discount_cents, category_id, confidence, uncertain, source_page, source_line, user_verified) VALUES
                    (1, 1, 0, 'MILK', 'Milk', 1000, 'EACH', 1000, 0, 7, 90, 0, 0, 0, 1),
                    (2, 2, 0, 'BREAD', 'Bread', 1000, 'EACH', 100, 0, 7, 90, 0, 0, 0, 0)
                """,
                """
                INSERT INTO receipt_ocr_lines (receipt_id, page, line_index, text, confidence, box_left, box_top, box_right, box_bottom) VALUES
                    (1, 0, 0, 'MILK 10.00', 90, 0, 0, 10, 10), (1, 0, 1, 'TOTAL 10.00', 90, 0, 10, 10, 20),
                    (2, 0, 0, 'BREAD 1.00', 90, 0, 0, 10, 10)
                """,
            ).forEach(::execSQL)
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 11, true, Migrations.MIGRATION_10_11).apply {
            fun strings(sql: String) = query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            assertEquals("a draft keeps its reading for review", listOf("2"), strings("SELECT DISTINCT receipt_id FROM receipt_ocr_lines"))
            assertEquals(listOf("", "TOTAL=0:1"), strings("SELECT field_sources FROM receipts ORDER BY id"))
            assertEquals(listOf("-", "0"), strings("SELECT COALESCE(source_line, '-') FROM receipt_items ORDER BY id"))
            assertEquals(listOf("1"), strings("SELECT COUNT(*) FROM app_metadata WHERE meta_key = '${AppMetadataKeys.VACUUM_PENDING}'"))
            close()
        }
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
        helper.closeWhenFinished(db)
        // The finality trigger is back: the confirmed receipt cannot be reopened.
        assertTrue(runCatching { db.openHelper.writableDatabase.execSQL("UPDATE receipts SET status = 'NEEDS_REVIEW', transaction_id = NULL WHERE id = 1") }.isFailure)
    }

    @Test
    fun migrate11To12ErasesSavedCoordinatesAndKeepsAddresses() = runTest {
        helper.createDatabase(TEST_DB, 11).apply {
            listOf(
                """
                INSERT INTO stores (id, name, normalized_name, address, latitude_e7, longitude_e7, created_at, updated_at) VALUES
                    (1, 'Mart', 'mart', '1 Main St', 123456789, -987654321, 0, 0)
                """,
                """
                INSERT INTO receipts (id, request_id, status, store_id, store_name, store_address, tax_cents, discount_cents,
                    receipt_number, latitude_e7, longitude_e7, uncertain_fields, field_sources, created_at, updated_at) VALUES
                    (1, 'q1', 'NEEDS_REVIEW', 1, 'Mart', '1 Main St', 0, 0, '', 123456789, -987654321, '', '', 0, 0)
                """,
            ).forEach(::execSQL)
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 12, true, Migrations.MIGRATION_11_12).apply {
            fun strings(sql: String) = query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            listOf("stores", "receipts").forEach { table ->
                val columns = strings("SELECT name FROM pragma_table_info('$table')")
                assertFalse(columns.toString(), columns.any { "latitude" in it || "longitude" in it })
            }
            assertEquals(listOf("1 Main St"), strings("SELECT address FROM stores"))
            assertEquals(listOf("1 Main St"), strings("SELECT store_address FROM receipts"))
            assertEquals(listOf("1"), strings("SELECT COUNT(*) FROM app_metadata WHERE meta_key = '${AppMetadataKeys.VACUUM_PENDING}'"))
            close()
        }
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
        val db = CloakedCentDatabaseFactory.create(context, passphrase, name = TEST_DB)
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
