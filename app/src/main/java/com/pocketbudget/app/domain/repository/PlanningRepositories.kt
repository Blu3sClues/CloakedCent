package com.pocketbudget.app.domain.repository

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Budget
import com.pocketbudget.app.domain.model.BudgetDraft
import com.pocketbudget.app.domain.model.BudgetProgress
import com.pocketbudget.app.domain.model.BudgetState
import com.pocketbudget.app.domain.model.RecurringDraft
import com.pocketbudget.app.domain.model.RecurringSchedule
import com.pocketbudget.app.domain.model.ReminderPlanner
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.model.UpcomingItem
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface BudgetRepository {
    suspend fun create(draft: BudgetDraft): Long

    /**
     * Updates settings. A new limit applies from the period containing [today];
     * earlier periods keep the limit they had, so history is not rewritten.
     */
    suspend fun update(id: Long, draft: BudgetDraft, today: LocalDate)

    /** Pause (no alerts, hidden from the overview), resume, or archive. */
    suspend fun setState(id: Long, state: BudgetState)

    /** Resumes and starts a fresh rollover cycle at the current period. */
    suspend fun restart(id: Long, today: LocalDate)

    suspend fun get(id: Long): Budget?

    /** Non-archived budgets with live figures, parents before their children. */
    fun observeProgress(today: LocalDate): Flow<List<BudgetProgress>>

    /** Thresholds newly reached in active budgets' current periods; each is returned once. */
    suspend fun claimNewAlerts(today: LocalDate): List<Pair<BudgetProgress, Int>>
}

data class Reminder(
    val kind: ReminderPlanner.Kind,
    val title: String,
    val type: TransactionType,
    val amount: Money,
    val dueDate: LocalDate,
    val transactionId: Long,
)

interface RecurringRepository {
    suspend fun create(draft: RecurringDraft): Long

    /** Applies to occurrences not yet generated; generated transactions are never rewritten. */
    suspend fun update(id: Long, draft: RecurringDraft)

    suspend fun pause(id: Long)

    /** Occurrences missed while paused (before [today]) are not generated afterwards. */
    suspend fun resume(id: Long, today: LocalDate)

    /** Stops future generation. Past and already generated transactions are kept. */
    suspend fun cancel(id: Long)

    /**
     * Makes a cancelled schedule active again from [today]. Like resuming, dates that passed
     * while it was cancelled are not generated; upcoming ones are.
     */
    suspend fun reenable(id: Long, today: LocalDate)

    /**
     * Permanently erases a cancelled schedule and its occurrence records, and its payee if
     * nothing else uses it. Transactions it already created stay as ordinary entries.
     * Erasing one already gone does nothing.
     */
    suspend fun delete(id: Long)

    /** Skips the next occurrence (removing its scheduled projection if generated). Returns its date. */
    suspend fun skipNext(id: Long, today: LocalDate): LocalDate?

    suspend fun get(id: Long): RecurringSchedule?

    fun observeAll(): Flow<List<RecurringSchedule>>

    /**
     * Creates scheduled transactions for occurrences due within the generation
     * horizon. Idempotent: safe to run repeatedly and after restarts. Returns how
     * many were created.
     */
    suspend fun generateDue(today: LocalDate): Int

    /** Overdue scheduled entries plus everything due in the next [days] days. */
    fun observeUpcoming(today: LocalDate, days: Int): Flow<List<UpcomingItem>>

    /** Reminders to show now; each is returned once. */
    suspend fun claimReminders(today: LocalDate): List<Reminder>

    companion object {
        /** Occurrences this close are generated as scheduled transactions (projected balances). */
        const val GENERATION_HORIZON_DAYS = 14L
    }
}
