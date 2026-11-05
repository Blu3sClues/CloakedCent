package com.cloakedcent.app.data.repository

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.AdjustmentKind
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.NewAdjustment
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.NewTransfer
import com.cloakedcent.app.domain.model.PeriodSummary
import com.cloakedcent.app.domain.model.RevisionAction
import com.cloakedcent.app.domain.model.TransactionEdit
import com.cloakedcent.app.domain.model.TransactionFilter
import com.cloakedcent.app.domain.model.TransactionStatus
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.domain.model.consolidated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AdjustmentAndStatusTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val today = LocalDate.of(2026, 11, 15)

    private suspend fun balance(id: Long) = t.accounts.observeBalances().first().single { it.account.id == id }

    private suspend fun summary(): PeriodSummary =
        t.ledger.observePeriodSummary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).first()

    private suspend fun adjust(account: Long, cents: Long, kind: AdjustmentKind, id: String = "adj-$cents-$kind") =
        t.ledger.adjustBalance(NewAdjustment(id, account, Money(cents), kind, today))

    private suspend inline fun <reified E : LedgerException> expect(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${E::class.simpleName}")
        } catch (e: LedgerException) {
            if (e !is E) throw e
        }
    }

    // --- Balance adjustments ---

    @Test
    fun balanceOnlyAdjustmentsChangeBalanceButNotReports() = runTest {
        val a = t.accounts.create("Checking", AccountType.CHECKING, Money(10_000))
        adjust(a, 2_500, AdjustmentKind.BALANCE_ONLY)
        assertEquals(Money(12_500), balance(a).current)
        adjust(a, -4_000, AdjustmentKind.BALANCE_ONLY)
        assertEquals(Money(8_500), balance(a).current)
        assertEquals(Money(8_500), balance(a).projected)
        assertEquals("excluded from income and spending", PeriodSummary.ZERO, summary())
    }

    @Test
    fun incomeAdjustmentCountsAsIncome() = runTest {
        val a = t.accounts.create("Cash", AccountType.CASH)
        val id = adjust(a, 1_500, AdjustmentKind.INCOME)
        assertEquals(Money(1_500), balance(a).current)
        assertEquals(PeriodSummary(Money(1_500), Money.ZERO), summary())
        val entry = t.db.transactionDao().getById(id)!!
        assertEquals(TransactionType.INCOME, entry.type)
        assertEquals(TransactionStatus.RECEIVED, entry.status)
        assertTrue(entry.isAdjustment)
    }

    @Test
    fun expenseAdjustmentCountsAsSpending() = runTest {
        val a = t.accounts.create("Cash", AccountType.CASH, Money(5_000))
        val id = adjust(a, -700, AdjustmentKind.EXPENSE)
        assertEquals(Money(4_300), balance(a).current)
        assertEquals(PeriodSummary(Money.ZERO, Money(700)), summary())
        assertEquals(TransactionStatus.SPENT, t.db.transactionDao().getById(id)!!.status)
    }

    @Test
    fun classificationMustMatchDirectionAndAmountMustBeNonZero() = runTest {
        val a = t.accounts.create("A", AccountType.CASH)
        expect<LedgerException.AdjustmentKindMismatch> { adjust(a, -100, AdjustmentKind.INCOME) }
        expect<LedgerException.AdjustmentKindMismatch> { adjust(a, 100, AdjustmentKind.EXPENSE) }
        expect<LedgerException.InvalidAmount> { adjust(a, 0, AdjustmentKind.BALANCE_ONLY) }
        expect<LedgerException.AccountUnavailable> { adjust(999, 100, AdjustmentKind.BALANCE_ONLY) }
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun adjustmentsAreIdempotentAuditableAndNeverOverwriteBalances() = runTest {
        val a = t.accounts.create("A", AccountType.CASH, Money(1_000))
        val first = adjust(a, -300, AdjustmentKind.BALANCE_ONLY, id = "same")
        val repeat = adjust(a, -300, AdjustmentKind.BALANCE_ONLY, id = "same")
        assertEquals(first, repeat)
        assertEquals(Money(700), balance(a).current)
        // The opening balance itself is never rewritten.
        assertEquals(1_000L, t.db.accountDao().getById(a)!!.openingBalanceCents)

        // Editing keeps the direction and records history until it is deleted.
        t.ledger.edit(first, TransactionEdit(Money(500), today, note = "recount"))
        assertEquals(Money(500), balance(a).current)
        val edited = t.ledger.observeDetail(first).first()!!
        assertTrue(edited.isAdjustment)
        assertEquals(listOf(RevisionAction.UPDATE), edited.history.map { it.action })
        assertEquals(Money(-300), edited.history.first().amount)
        // Deleting erases it and its history; the opening balance is untouched.
        t.ledger.delete(first)
        assertEquals(Money(1_000), balance(a).current)
        assertNull(t.ledger.observeDetail(first).first())
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_revisions WHERE transaction_id = $first"))
    }

    @Test
    fun adjustmentsAppearInHistoryAndCannotBeScheduled() = runTest {
        val a = t.accounts.create("A", AccountType.CASH)
        adjust(a, 100, AdjustmentKind.BALANCE_ONLY)
        val item = t.ledger.search(TransactionFilter(accountId = a)).first().single()
        assertEquals(TransactionType.ADJUSTMENT, item.type)
        assertEquals(TransactionStatus.COMPLETED, item.status)
        assertTrue(item.isAdjustment)
        assertThrows(SQLiteException::class.java) {
            t.execSql(
                """
                INSERT INTO transactions (account_id, type, amount_cents, status, occurred_on, revision, is_deleted,
                    is_adjustment, created_at, updated_at)
                VALUES (?, 'ADJUSTMENT', 100, 'SCHEDULED', 0, 1, 0, 1, 0, 0)
                """,
                a,
            )
        }
    }

    // --- Type-specific statuses ---

    @Test
    fun scheduledEntriesConfirmToTheirTypesStatusAndOnlyOnce() = runTest {
        val a = t.accounts.create("A", AccountType.CHECKING, Money(10_000))
        val b = t.accounts.create("B", AccountType.SAVINGS)
        val income = t.ledger.record(NewTransaction("i", a, TransactionType.INCOME, Money(3_000), today, scheduled = true))
        val expense = t.ledger.record(NewTransaction("e", a, TransactionType.EXPENSE, Money(1_000), today, scheduled = true))
        val transferId = t.ledger.recordTransfer(NewTransfer("t", a, b, Money(2_000), today, scheduled = true))
        val leg = t.db.transactionDao().getTransferLegs(transferId).first()

        assertEquals(Money(10_000), balance(a).current)
        assertEquals(Money(10_000), balance(a).projected)  // +3,000 -1,000 -2,000
        assertEquals(Money(2_000), balance(b).projected)
        assertEquals(PeriodSummary.ZERO, summary())

        assertTrue(t.ledger.confirm(income))
        assertTrue(t.ledger.confirm(expense))
        assertTrue(t.ledger.confirm(leg.id))
        listOf(income, expense, leg.id).forEach { assertFalse(t.ledger.confirm(it)) }

        assertEquals(TransactionStatus.RECEIVED, t.db.transactionDao().getById(income)!!.status)
        assertEquals(TransactionStatus.SPENT, t.db.transactionDao().getById(expense)!!.status)
        assertEquals(
            "both transfer legs updated together",
            listOf(TransactionStatus.COMPLETED, TransactionStatus.COMPLETED),
            t.db.transactionDao().getTransferLegs(transferId).map { it.status },
        )
        assertEquals(Money(10_000), balance(a).current)
        assertEquals(Money(2_000), balance(b).current)
        assertEquals(Money(12_000), t.accounts.observeBalances().first().consolidated().current)
        assertEquals("transfer stays out of reports", PeriodSummary(Money(3_000), Money(1_000)), summary())
    }

    @Test
    fun statusFilterSeparatesScheduledFromCompleted() = runTest {
        val a = t.accounts.create("A", AccountType.CHECKING)
        t.ledger.record(NewTransaction("done", a, TransactionType.INCOME, Money(100), today))
        t.ledger.record(NewTransaction("plan", a, TransactionType.INCOME, Money(200), today, scheduled = true))
        assertEquals(listOf(Money(200)), t.ledger.search(TransactionFilter(scheduled = true)).first().map { it.amount })
        assertEquals(listOf(Money(100)), t.ledger.search(TransactionFilter(scheduled = false)).first().map { it.amount })
    }
}
