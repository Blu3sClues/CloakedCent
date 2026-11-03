package com.pocketbudget.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.db.SecureErase
import com.pocketbudget.app.domain.model.RevisionAction
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionStatus.COMPLETED
import com.pocketbudget.app.domain.model.TransactionStatus.RECEIVED
import com.pocketbudget.app.domain.model.TransactionStatus.SCHEDULED
import com.pocketbudget.app.domain.model.TransactionStatus.SPENT
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.model.TransactionType.EXPENSE
import com.pocketbudget.app.domain.model.TransactionType.INCOME
import com.pocketbudget.app.domain.model.consolidated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val today = LocalDate.of(2026, 11, 15)

    private suspend fun balances(): Map<Long, AccountBalance> =
        t.accounts.observeBalances().first().associateBy { it.account.id }

    private suspend fun monthSummary(): PeriodSummary =
        t.ledger.observePeriodSummary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).first()

    private suspend fun account(name: String, openingCents: Long = 0) =
        t.accounts.create(name, AccountType.CHECKING, Money(openingCents))

    private fun entry(
        id: String,
        accountId: Long,
        type: TransactionType,
        cents: Long,
        scheduled: Boolean = false,
        categoryId: Long? = null,
    ) = NewTransaction("req-$id", accountId, type, Money(cents), today, scheduled, categoryId)

    private fun transfer(id: String, from: Long, to: Long, cents: Long, scheduled: Boolean = false) =
        NewTransfer("xfer-$id", from, to, Money(cents), today, scheduled)

    private suspend inline fun <reified E : Throwable> assertFails(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${E::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is E) throw e
        }
    }

    @Test
    fun incomeAndExpenseUpdateBalancesAndMonthlySummary() = runTest {
        val a = account("Checking", openingCents = 100_000)
        t.ledger.record(entry("pay", a, INCOME, 50_000))
        t.ledger.record(entry("food", a, EXPENSE, 20_000))

        val b = balances().getValue(a)
        assertEquals(Money(130_000), b.current)
        assertEquals(Money(130_000), b.projected)
        assertEquals(PeriodSummary(Money(50_000), Money(20_000)), monthSummary())
    }

    @Test
    fun newEntriesDefaultToTheirTypesCompletedStatus() = runTest {
        val a = account("A")
        val id = t.ledger.record(NewTransaction("default", a, INCOME, Money(100), today))
        assertEquals(RECEIVED, t.db.transactionDao().getById(id)!!.status)
        val expense = t.ledger.record(entry("spend", a, EXPENSE, 50))
        assertEquals(SPENT, t.db.transactionDao().getById(expense)!!.status)
        val b = account("B")
        val transferId = t.ledger.recordTransfer(transfer("move", a, b, 25))
        assertEquals(listOf(COMPLETED, COMPLETED), t.db.transactionDao().getTransferLegs(transferId).map { it.status })
    }

    @Test
    fun transfersMoveMoneyWithoutCountingAsIncomeOrExpense() = runTest {
        val a = account("A", 100_000)
        val b = account("B")
        t.ledger.recordTransfer(transfer("1", a, b, 30_000))

        val all = balances()
        assertEquals(Money(70_000), all.getValue(a).current)
        assertEquals(Money(30_000), all.getValue(b).current)
        assertEquals(Money(100_000), all.values.toList().consolidated().current)
        assertEquals(PeriodSummary.ZERO, monthSummary())

        val recent = t.ledger.observeRecent(10).first()
        assertEquals(1, recent.size)
        assertEquals(TransactionType.TRANSFER, recent.single().type)
        assertEquals("B", recent.single().counterpartAccountName)
    }

    @Test
    fun scheduledEntriesAreProjectedUntilMarkedSpentExactlyOnce() = runTest {
        val a = account("A", 10_000)
        val id = t.ledger.record(entry("bill", a, EXPENSE, 2_500, scheduled = true))

        balances().getValue(a).let {
            assertEquals("scheduled must not affect the actual balance", Money(10_000), it.current)
            assertEquals(Money(7_500), it.projected)
        }
        assertEquals(PeriodSummary.ZERO, monthSummary())

        assertTrue(t.ledger.confirm(id))
        assertFalse("second conversion must be a no-op", t.ledger.confirm(id))
        balances().getValue(a).let {
            assertEquals(Money(7_500), it.current)
            assertEquals(Money(7_500), it.projected)
        }
        assertEquals(PeriodSummary(Money.ZERO, Money(2_500)), monthSummary())
        val history = t.db.transactionDao().getRevisions(id).single()
        assertEquals(RevisionAction.STATUS_CHANGE, history.action)
        assertEquals(SCHEDULED, history.status)
    }

    @Test
    fun scheduledTransferMovesMoneyWhenMarkedSpent() = runTest {
        val a = account("A", 10_000)
        val b = account("B")
        val transferId = t.ledger.recordTransfer(transfer("plan", a, b, 4_000, scheduled = true))
        val leg = t.db.transactionDao().getTransferLegs(transferId).first()

        assertEquals(Money(10_000), balances().getValue(a).current)
        assertEquals(Money(6_000), balances().getValue(a).projected)
        assertEquals(Money(4_000), balances().getValue(b).projected)

        assertTrue(t.ledger.confirm(leg.id))
        assertEquals(Money(6_000), balances().getValue(a).current)
        assertEquals(Money(4_000), balances().getValue(b).current)
        assertEquals(Money(10_000), balances().values.toList().consolidated().current)
    }

    @Test
    fun duplicateRequestsAreProcessedOnce() = runTest {
        val a = account("A")
        val b = account("B")
        val first = t.ledger.record(entry("dup", a, INCOME, 1_000))
        val second = t.ledger.record(entry("dup", a, INCOME, 1_000))
        val t1 = t.ledger.recordTransfer(transfer("dup", a, b, 500))
        val t2 = t.ledger.recordTransfer(transfer("dup", a, b, 500))

        assertEquals(first, second)
        assertEquals(t1, t2)
        assertEquals(Money(500), balances().getValue(a).current)
        assertEquals(1L, t.count("SELECT COUNT(*) FROM transfers"))
        assertEquals(3L, t.count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun transferRollsBackCompletelyWhenALegFails() = runTest {
        val a = account("A", 10_000)
        val b = account("B")
        // Make the second (incoming) leg fail after the transfer row and first leg are written.
        t.execSql(
            """
            CREATE TRIGGER test_fail_incoming_leg BEFORE INSERT ON transactions
            WHEN NEW.type = 'TRANSFER' AND NEW.amount_cents > 0
            BEGIN SELECT RAISE(ABORT, 'simulated failure'); END
            """,
        )

        assertFails<Exception> { t.ledger.recordTransfer(transfer("boom", a, b, 4_000)) }

        assertEquals(0L, t.count("SELECT COUNT(*) FROM transfers"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transactions"))
        assertEquals(Money(10_000), balances().getValue(a).current)
    }

    @Test
    fun editingKeepsThePreviousValuesInHistory() = runTest {
        val a = account("A")
        val id = t.ledger.record(entry("edit", a, EXPENSE, 1_000))
        t.ledger.edit(id, TransactionEdit(Money(2_500), today.minusDays(1), note = "corrected"))

        val current = t.db.transactionDao().getById(id)!!
        assertEquals(-2_500L, current.amountCents)
        assertEquals(2, current.revision)
        val history = t.db.transactionDao().getRevisions(id).single()
        assertEquals(RevisionAction.UPDATE, history.action)
        assertEquals(-1_000L, history.amountCents)
        assertEquals(today, history.occurredOn)
        assertEquals(Money(-2_500), balances().getValue(a).current)
    }

    @Test
    fun editingATransferUpdatesBothLegs() = runTest {
        val a = account("A", 10_000)
        val b = account("B")
        t.ledger.recordTransfer(transfer("e", a, b, 3_000))
        val legId = t.ledger.observeRecent(1).first().single().id

        t.ledger.edit(legId, TransactionEdit(Money(4_500), today))

        assertEquals(Money(5_500), balances().getValue(a).current)
        assertEquals(Money(4_500), balances().getValue(b).current)
        assertFails<LedgerException.InvalidOperation> {
            t.ledger.edit(legId, TransactionEdit(Money(1), today, categoryId = 1))
        }
    }

    @Test
    fun deletingErasesTheEntryWithItsHistoryAndUnusedPayees() = runTest {
        val a = account("A", 10_000)
        val b = account("B")
        val clinic = t.payees.getOrCreate("Clinic")
        val market = t.payees.getOrCreate("Market")
        val expense = t.ledger.record(entry("del", a, EXPENSE, 1_000).copy(payeeId = clinic, note = "private"))
        t.ledger.edit(expense, TransactionEdit(Money(1_200), today, payeeId = clinic, note = "still private"))
        val kept = t.ledger.record(entry("keep", a, EXPENSE, 300).copy(payeeId = market))
        val gone = t.ledger.record(entry("gone", a, EXPENSE, 400).copy(payeeId = market))
        t.ledger.recordTransfer(transfer("del", a, b, 2_000))
        val legId = t.ledger.observeRecent(10).first().first { it.type == TransactionType.TRANSFER }.id
        // Until it is deleted, an edited entry keeps its earlier values.
        assertEquals(Money(-1_000), t.ledger.observeDetail(expense).first()!!.history.single().amount)

        t.ledger.delete(expense)
        t.ledger.delete(expense) // repeat is harmless
        t.ledger.delete(gone)
        t.ledger.delete(legId)

        assertEquals(Money(9_700), balances().getValue(a).current)
        assertEquals(Money.ZERO, balances().getValue(b).current)
        assertEquals(listOf(kept), t.ledger.observeRecent(10).first().map { it.id })
        // Nothing of the erased entries remains: rows, edit history, transfer, or a payee only they used.
        assertEquals(1L, t.count("SELECT COUNT(*) FROM transactions"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transactions WHERE is_deleted = 1"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_revisions"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transfers"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM payees WHERE id = $clinic"))
        assertEquals("still used", 1L, t.count("SELECT COUNT(*) FROM payees WHERE id = $market"))
        assertNull(t.ledger.observeDetail(expense).first())
        assertFails<LedgerException.NotFound> { t.ledger.edit(expense, TransactionEdit(Money(1), today)) }
        // The file keeps no copies: deleted content is zeroed and the write-ahead log is emptied.
        assertTrue(SecureErase.isSecureDeleteOn(t.db.openHelper.writableDatabase))
        val wal = File(t.db.openHelper.writableDatabase.path + "-wal")
        assertTrue("write-ahead log truncated", !wal.exists() || wal.length() == 0L)
    }

    @Test
    fun aPayeeStaysWhileAKeptEntrysHistoryStillNamesIt() = runTest {
        val a = account("A", 10_000)
        val old = t.payees.getOrCreate("Old shop")
        val new = t.payees.getOrCreate("New shop")
        val first = t.ledger.record(entry("p1", a, EXPENSE, 500).copy(payeeId = old))
        t.ledger.edit(first, TransactionEdit(Money(500), today, payeeId = new))
        val other = t.ledger.record(entry("p2", a, EXPENSE, 100).copy(payeeId = old))
        t.ledger.delete(other)
        assertEquals("named in the kept entry's history", 1L, t.count("SELECT COUNT(*) FROM payees WHERE id = $old"))
        assertEquals("Old shop", t.ledger.observeDetail(first).first()!!.history.single().payeeName)
        t.ledger.delete(first)
        assertEquals(0L, t.count("SELECT COUNT(*) FROM payees"))
    }

    @Test
    fun invalidRequestsAreRejectedBeforeWriting() = runTest {
        val a = account("A")
        val salary = t.categories.observeAll().first().first { it.name == "Salary" }.id

        assertFails<LedgerException.InvalidAmount> { t.ledger.record(entry("z", a, EXPENSE, 0)) }
        assertFails<LedgerException.CategoryMismatch> { t.ledger.record(entry("c", a, EXPENSE, 100, categoryId = salary)) }
        assertFails<LedgerException.SameAccountTransfer> { t.ledger.recordTransfer(transfer("s", a, a, 100)) }
        assertFails<LedgerException.AccountUnavailable> { t.ledger.record(entry("x", 999, INCOME, 100)) }
        assertFails<LedgerException.InvalidOperation> {
            t.ledger.record(entry("t", a, TransactionType.TRANSFER, 100))
        }
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun onlyZeroBalanceAccountsCanBeArchived() = runTest {
        val funded = account("Funded", 1_000)
        val empty = account("Empty")

        assertFails<LedgerException.AccountHasBalance> { t.accounts.archive(funded) }
        t.accounts.archive(empty)

        assertEquals(setOf(funded), balances().keys)
        assertFails<LedgerException.AccountUnavailable> { t.ledger.record(entry("arch", empty, INCOME, 100)) }
    }
}
