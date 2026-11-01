package com.pocketbudget.app.data.repository

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Database-level guarantees that hold even if repository code is bypassed. */
@RunWith(AndroidJUnit4::class)
class LedgerIntegrityTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val epochDay = LocalDate.of(2026, 11, 15).toEpochDay()

    private fun rawInsert(accountId: Long, type: String, cents: Long, transferId: Long? = null) = t.execSql(
        """
        INSERT INTO transactions (account_id, type, amount_cents, status, occurred_on, transfer_id,
            revision, is_deleted, created_at, updated_at)
        VALUES (?, ?, ?, 'PENDING', ?, ?, 1, 0, 0, 0)
        """,
        accountId, type, cents, epochDay, transferId,
    )

    @Test
    fun foreignKeysRejectOrphanedEntries() = runTest {
        assertThrows(SQLiteException::class.java) { rawInsert(accountId = 999, type = "INCOME", cents = 100) }
    }

    @Test
    fun triggersRejectEntriesThatBreakLedgerRules() = runTest {
        val a = t.accounts.create("A", AccountType.CASH)
        assertThrows("negative income", SQLiteException::class.java) { rawInsert(a, "INCOME", -100) }
        assertThrows("positive expense", SQLiteException::class.java) { rawInsert(a, "EXPENSE", 100) }
        assertThrows("transfer without group", SQLiteException::class.java) { rawInsert(a, "TRANSFER", 100) }
        assertThrows("same-account transfer", SQLiteException::class.java) {
            t.execSql("INSERT INTO transfers (request_id, from_account_id, to_account_id, created_at) VALUES ('x', ?, ?, 0)", a, a)
        }
    }

    @Test
    fun transactionsAndHistoryCannotBeErased() = runTest {
        val a = t.accounts.create("A", AccountType.CASH)
        val id = t.ledger.record(
            NewTransaction("r", a, TransactionType.INCOME, Money(500), LocalDate.of(2026, 11, 15)),
        )
        t.ledger.edit(id, TransactionEdit(Money(700), LocalDate.of(2026, 11, 15)))

        assertThrows(SQLiteException::class.java) { t.execSql("DELETE FROM transactions WHERE id = ?", id) }
        assertThrows(SQLiteException::class.java) { t.execSql("UPDATE transaction_revisions SET amount_cents = 1") }
        assertThrows(SQLiteException::class.java) { t.execSql("DELETE FROM transaction_revisions") }
        assertThrows("accounts with history", SQLiteException::class.java) {
            t.execSql("DELETE FROM accounts WHERE id = ?", a)
        }
    }

    @Test
    fun categoriesInUseCannotBeDeleted() = runTest {
        val a = t.accounts.create("A", AccountType.CASH)
        val groceries = t.categories.observeAll().first().first { it.name == "Groceries" }.id
        t.ledger.record(
            NewTransaction("g", a, TransactionType.EXPENSE, Money(100), LocalDate.of(2026, 11, 15), categoryId = groceries),
        )
        assertThrows(SQLiteException::class.java) { t.execSql("DELETE FROM categories WHERE id = ?", groceries) }
    }

    @Test
    fun defaultCategoriesAreSeededAndNested() = runTest {
        val all = t.categories.observeAll().first().associateBy { it.name }
        assertEquals(11, all.size)
        assertEquals(all.getValue("Food").id, all.getValue("Groceries").parentId)
        assertEquals(CategoryKind.INCOME, all.getValue("Salary").kind)
    }

    @Test
    fun nestedCategoriesRejectCyclesAndKindMismatches() = runTest {
        val all = t.categories.observeAll().first().associateBy { it.name }
        val food = all.getValue("Food").id
        val groceries = all.getValue("Groceries").id

        val produce = t.categories.create("Produce", CategoryKind.EXPENSE, parentId = groceries)
        expectLedgerError<LedgerException.InvalidOperation> { t.categories.setParent(food, produce) }
        expectLedgerError<LedgerException.InvalidOperation> { t.categories.setParent(food, food) }
        expectLedgerError<LedgerException.CategoryMismatch> {
            t.categories.create("Bonus", CategoryKind.INCOME, parentId = food)
        }
    }

    @Test
    fun payeesAreDeduplicatedIgnoringCase() = runTest {
        val first = t.payees.getOrCreate("Fresh Market")
        assertEquals(first, t.payees.getOrCreate("  fresh market "))
        assertEquals(1L, t.count("SELECT COUNT(*) FROM payees"))
    }

    private suspend inline fun <reified E : LedgerException> expectLedgerError(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${E::class.simpleName}")
        } catch (e: LedgerException) {
            if (e !is E) throw e
        }
    }
}
