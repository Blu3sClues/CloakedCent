package com.pocketbudget.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.BudgetDraft
import com.pocketbudget.app.domain.model.BudgetPeriodType
import com.pocketbudget.app.domain.model.BudgetState
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.RecurrenceFrequency
import com.pocketbudget.app.domain.model.RecurringDraft
import com.pocketbudget.app.domain.model.RecurringKind
import com.pocketbudget.app.domain.model.ReminderPlanner
import com.pocketbudget.app.domain.model.RevisionAction
import com.pocketbudget.app.domain.model.ScheduleState
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class PlanningRepositoryTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val today = LocalDate.of(2026, 11, 15)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private lateinit var budgets: RoomBudgetRepository
    private lateinit var recurring: RoomRecurringRepository
    private var checking = 0L
    private var savings = 0L
    private var food = 0L
    private var groceries = 0L
    private var housing = 0L

    @Before
    fun setUp() = runTest {
        budgets = RoomBudgetRepository({ t.db }, clock)
        recurring = RoomRecurringRepository({ t.db }, t.ledger, clock)
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(500_000))
        savings = t.accounts.create("Savings", AccountType.SAVINGS)
        val categories = t.categories.observeAll().first().associateBy { it.name }
        food = categories.getValue("Food").id
        groceries = categories.getValue("Groceries").id
        housing = categories.getValue("Housing").id
    }

    private suspend fun spend(id: String, cents: Long, category: Long, date: LocalDate = today, scheduled: Boolean = false) =
        t.ledger.record(NewTransaction(id, checking, TransactionType.EXPENSE, Money(cents), date, scheduled, category))

    private suspend fun progress() = budgets.observeProgress(today).first().associateBy { it.budget.id }

    private fun monthly(name: String, category: Long, cents: Long, start: LocalDate = LocalDate.of(2026, 11, 1), rollover: Boolean = false) =
        BudgetDraft(name, category, Money(cents), BudgetPeriodType.MONTHLY, start, rollover = rollover)

    // ---------------------------------------------------------------- budgets

    @Test
    fun budgetsCountOnlyCompletedExpensesAndNeverDoubleCount() = runTest {
        val foodBudget = budgets.create(monthly("Food", food, 50_000))
        val groceryBudget = budgets.create(monthly("Groceries", groceries, 30_000))
        spend("a", 10_000, groceries)
        spend("b", 5_000, food)
        spend("c", 99_000, groceries, scheduled = true) // projection: never spent
        t.ledger.record(NewTransaction("inc", checking, TransactionType.INCOME, Money(70_000), today))
        t.ledger.recordTransfer(NewTransfer("x", checking, savings, Money(20_000), today))

        val all = progress()
        assertEquals(Money(15_000), all.getValue(foodBudget).current!!.spent) // includes child once
        assertEquals(Money(10_000), all.getValue(groceryBudget).current!!.spent)
        assertEquals(foodBudget, all.getValue(groceryBudget).parentBudgetId)
        assertEquals(Money(35_000), all.getValue(foodBudget).current!!.remaining)
    }

    @Test
    fun editingALimitKeepsEarlierPeriodsAndRolloverCarriesUnused() = runTest {
        val id = budgets.create(monthly("Housing", housing, 100_000, start = LocalDate.of(2026, 9, 1), rollover = true))
        spend("sep", 80_000, housing, LocalDate.of(2026, 9, 3))
        spend("oct", 100_000, housing, LocalDate.of(2026, 10, 3))
        budgets.update(id, monthly("Housing", housing, 120_000, start = LocalDate.of(2026, 9, 1), rollover = true), today)

        val history = progress().getValue(id).history.asReversed() // oldest first
        assertEquals(listOf(100_000L, 100_000L, 120_000L), history.map { it.limit.cents })
        assertEquals(listOf(0L, 20_000L, 20_000L), history.map { it.carriedIn.cents })
        assertEquals(Money(140_000), history.last().allocated)
    }

    @Test
    fun pauseResumeRestartAndArchive() = runTest {
        val id = budgets.create(monthly("Food", food, 10_000, rollover = true))
        budgets.setState(id, BudgetState.PAUSED)
        assertEquals(BudgetState.PAUSED, budgets.get(id)!!.state)
        budgets.restart(id, today)
        assertEquals(BudgetState.ACTIVE, budgets.get(id)!!.state)
        assertEquals(LocalDate.of(2026, 11, 1), budgets.get(id)!!.cycleStart)
        budgets.setState(id, BudgetState.ARCHIVED)
        assertTrue(progress().isEmpty())
        assertNotNull("archived budgets are kept", budgets.get(id))
    }

    @Test
    fun alertsAreRaisedOncePerThresholdAndNotWhilePaused() = runTest {
        val id = budgets.create(monthly("Food", food, 10_000))
        spend("a", 7_600, food)
        assertEquals(listOf(50, 75), budgets.claimNewAlerts(today).map { it.second })
        assertTrue(budgets.claimNewAlerts(today).isEmpty())
        spend("b", 3_000, food)
        assertEquals(listOf(90, 100), budgets.claimNewAlerts(today).map { it.second })
        budgets.setState(id, BudgetState.PAUSED)
        spend("c", 50_000, food)
        assertTrue(budgets.claimNewAlerts(today).isEmpty())
    }

    // ---------------------------------------------------------------- recurring

    private fun rent(start: LocalDate = LocalDate.of(2026, 11, 20), amount: Long = 150_000, variable: Boolean = false) = RecurringDraft(
        name = "Rent", kind = RecurringKind.RENT, type = TransactionType.EXPENSE, accountId = checking,
        amount = Money(amount), variableAmount = variable, categoryId = housing,
        frequency = RecurrenceFrequency.MONTHLY, startDate = start,
    )

    private suspend fun scheduled() = t.ledger.search(TransactionFilter(scheduled = true)).first()

    @Test
    fun generationIsIdempotentAcrossRunsAndRestarts() = runTest {
        recurring.create(rent()) // generates Nov 20 (within 14 days), not Dec 20
        assertEquals(listOf(LocalDate.of(2026, 11, 20)), scheduled().map { it.occurredOn })
        assertEquals(0, recurring.generateDue(today))
        // A fresh repository instance simulates an app restart.
        assertEquals(0, RoomRecurringRepository({ t.db }, t.ledger, clock).generateDue(today))
        assertEquals(1L, t.count("SELECT COUNT(*) FROM transactions"))

        assertEquals(1, recurring.generateDue(LocalDate.of(2026, 12, 10)))
        assertEquals(2, scheduled().size)
    }

    @Test
    fun scheduledOccurrencesAreProjectedUntilConfirmedWithTheActualAmount() = runTest {
        recurring.create(rent(variable = true))
        val occurrence = scheduled().single()
        val balanceBefore = t.accounts.observeBalances().first().first { it.account.id == checking }
        assertEquals(Money(500_000), balanceBefore.current)
        assertEquals(Money(350_000), balanceBefore.projected)
        assertEquals(PeriodSummary.ZERO, t.ledger.observePeriodSummary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).first())

        assertTrue(t.ledger.confirm(occurrence.id, Money(152_500)))
        val entry = t.db.transactionDao().getById(occurrence.id)!!
        assertEquals(TransactionStatus.SPENT, entry.status)
        assertEquals(-152_500L, entry.amountCents)
        assertEquals(listOf(RevisionAction.UPDATE, RevisionAction.STATUS_CHANGE), t.db.transactionDao().getRevisions(occurrence.id).map { it.action })
        assertEquals(Money(347_500), t.accounts.observeBalances().first().first { it.account.id == checking }.current)
    }

    @Test
    fun editingAScheduleNeverRewritesGeneratedTransactions() = runTest {
        val id = recurring.create(rent())
        recurring.update(id, rent(amount = 175_000))
        assertEquals(Money(-150_000), scheduled().single().amount)
        recurring.generateDue(LocalDate.of(2026, 12, 10))
        assertEquals(listOf(-150_000L, -175_000L), scheduled().sortedBy { it.occurredOn }.map { it.amount.cents })
    }

    @Test
    fun skipPauseResumeAndCancel() = runTest {
        val id = recurring.create(rent())
        assertEquals(LocalDate.of(2026, 11, 20), recurring.skipNext(id, today))
        assertTrue("projection withdrawn", scheduled().isEmpty())
        assertEquals(0, recurring.generateDue(today)) // skipped occurrence is never regenerated

        recurring.pause(id)
        assertEquals(0, recurring.generateDue(LocalDate.of(2027, 1, 10)))
        recurring.resume(id, LocalDate.of(2027, 1, 10)) // Dec 20 was missed while paused: not generated
        assertEquals(listOf(LocalDate.of(2027, 1, 20)), scheduled().map { it.occurredOn })

        recurring.cancel(id)
        assertEquals(ScheduleState.CANCELLED, recurring.get(id)!!.state)
        assertTrue("future projections removed", scheduled().isEmpty())
        assertEquals(0, recurring.generateDue(LocalDate.of(2027, 6, 1)))
        assertEquals("history kept (soft deleted)", 2L, t.count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun recurringTransfersAndIncomeUseTheirOwnStatuses() = runTest {
        recurring.create(
            RecurringDraft("Save", RecurringKind.OTHER, TransactionType.TRANSFER, checking, savings, Money(10_000),
                frequency = RecurrenceFrequency.WEEKLY, startDate = today),
        )
        recurring.create(
            RecurringDraft("Pay", RecurringKind.PAYCHECK, TransactionType.INCOME, checking, amount = Money(300_000),
                frequency = RecurrenceFrequency.BIWEEKLY, startDate = today.plusDays(1)),
        )
        val pending = scheduled()
        assertEquals(3, pending.count { it.type == TransactionType.TRANSFER }) // Nov 15, 22, 29
        assertEquals(1, pending.count { it.type == TransactionType.INCOME }) // Nov 16 (Nov 30 is past the horizon)
        pending.forEach { assertTrue(t.ledger.confirm(it.id)) }
        val statuses = t.ledger.search(TransactionFilter(scheduled = false)).first().map { it.type to it.status }.toSet()
        assertEquals(setOf(TransactionType.TRANSFER to TransactionStatus.COMPLETED, TransactionType.INCOME to TransactionStatus.RECEIVED), statuses)
    }

    @Test
    fun remindersAndOverdueTrackingAreClaimedOnce() = runTest {
        recurring.create(rent(start = today.plusDays(1)))
        val first = recurring.claimReminders(today)
        assertEquals(listOf(ReminderPlanner.Kind.DUE_SOON), first.map { it.kind })
        assertEquals("Rent", first.single().title)
        assertTrue(recurring.claimReminders(today).isEmpty())

        val later = today.plusDays(3)
        assertEquals(listOf(ReminderPlanner.Kind.OVERDUE), recurring.claimReminders(later).map { it.kind })
        assertTrue(recurring.observeUpcoming(later, 60).first().first().overdue)
        assertTrue(recurring.claimReminders(later).isEmpty())
    }
}
