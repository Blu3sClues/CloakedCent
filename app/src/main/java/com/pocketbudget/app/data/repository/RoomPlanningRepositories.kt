package com.pocketbudget.app.data.repository

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.db.dao.ExpenseRow
import com.pocketbudget.app.data.db.entity.BudgetAlertEntity
import com.pocketbudget.app.data.db.entity.BudgetEntity
import com.pocketbudget.app.data.db.entity.BudgetLimitEntity
import com.pocketbudget.app.data.db.entity.CategoryEntity
import com.pocketbudget.app.data.db.entity.RecurringOccurrenceEntity
import com.pocketbudget.app.data.db.entity.RecurringScheduleEntity
import com.pocketbudget.app.domain.model.Budget
import com.pocketbudget.app.domain.model.BudgetCalculator
import com.pocketbudget.app.domain.model.BudgetDraft
import com.pocketbudget.app.domain.model.BudgetLimit
import com.pocketbudget.app.domain.model.BudgetPeriodType
import com.pocketbudget.app.domain.model.BudgetPeriods
import com.pocketbudget.app.domain.model.BudgetProgress
import com.pocketbudget.app.domain.model.BudgetState
import com.pocketbudget.app.domain.model.Category
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.Recurrence
import com.pocketbudget.app.domain.model.RecurringDraft
import com.pocketbudget.app.domain.model.RecurringSchedule
import com.pocketbudget.app.domain.model.ReminderPlanner
import com.pocketbudget.app.domain.model.ScheduleState
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.model.UpcomingItem
import com.pocketbudget.app.domain.repository.BudgetRepository
import com.pocketbudget.app.domain.repository.RecurringRepository
import com.pocketbudget.app.domain.repository.RecurringRepository.Companion.GENERATION_HORIZON_DAYS
import com.pocketbudget.app.domain.repository.Reminder
import com.pocketbudget.app.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

private val EARLIEST = LocalDate.of(1900, 1, 1)

private fun CategoryEntity.toDomain() = Category(id, name, kind, parentId)

private fun ExpenseRow.toTriple() = Triple(occurredOn, categoryId, Money(spentCents))

class RoomBudgetRepository(
    database: () -> PocketBudgetDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), BudgetRepository {

    override suspend fun create(draft: BudgetDraft): Long = write { db ->
        validate(db, draft)
        val firstPeriod = periodStart(draft, draft.startDate)
        val now = clock.millis()
        val id = db.budgetDao().insert(
            BudgetEntity(
                name = draft.name.trim(),
                categoryId = draft.categoryId,
                periodType = draft.periodType,
                startDate = draft.startDate,
                endDate = draft.endDate.takeIf { draft.periodType == BudgetPeriodType.CUSTOM },
                cycleStart = firstPeriod,
                rollover = draft.rollover,
                alertThresholds = draft.alertThresholds.joinToString(","),
                state = BudgetState.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.budgetDao().upsertLimit(BudgetLimitEntity(budgetId = id, effectiveFrom = firstPeriod, amountCents = draft.amount.cents))
        id
    }

    override suspend fun update(id: Long, draft: BudgetDraft, today: LocalDate) = write { db ->
        validate(db, draft)
        val existing = requireBudget(db, id)
        db.budgetDao().update(
            existing.copy(
                name = draft.name.trim(),
                categoryId = draft.categoryId,
                periodType = draft.periodType,
                startDate = draft.startDate,
                endDate = draft.endDate.takeIf { draft.periodType == BudgetPeriodType.CUSTOM },
                rollover = draft.rollover,
                alertThresholds = draft.alertThresholds.joinToString(","),
                updatedAt = clock.millis(),
            ),
        )
        val limits = db.budgetDao().getLimits(id)
        if (limits.last().amountCents != draft.amount.cents) {
            // Effective from the current period (or the start, if not started yet): past periods keep their limit.
            val from = maxOf(periodStart(draft, today.coerceAtLeast(draft.startDate)), limits.first().effectiveFrom)
            db.budgetDao().upsertLimit(BudgetLimitEntity(budgetId = id, effectiveFrom = from, amountCents = draft.amount.cents))
        }
    }

    override suspend fun setState(id: Long, state: BudgetState) = write { db ->
        val existing = requireBudget(db, id)
        db.budgetDao().update(existing.copy(state = state, updatedAt = clock.millis()))
    }

    override suspend fun restart(id: Long, today: LocalDate) = write { db ->
        val existing = requireBudget(db, id)
        val period = BudgetPeriods.periodContaining(existing.periodType, existing.startDate, existing.endDate, today)
        db.budgetDao().update(
            existing.copy(
                state = BudgetState.ACTIVE,
                cycleStart = period?.start ?: today,
                updatedAt = clock.millis(),
            ),
        )
    }

    override suspend fun get(id: Long): Budget? = withContextRead { db ->
        db.budgetDao().getById(id)?.let { it.toDomain(db.budgetDao().getLimits(id)) }
    }

    override fun observeProgress(today: LocalDate): Flow<List<BudgetProgress>> = observe { db ->
        combine(
            db.budgetDao().observeVisible(),
            db.budgetDao().observeAllLimits(),
            db.categoryDao().observeAll(),
            db.budgetDao().observeExpenses(EARLIEST),
        ) { budgets, limits, categories, expenses ->
            val byBudget = limits.groupBy { it.budgetId }
            BudgetCalculator.overview(
                budgets.mapNotNull { b -> byBudget[b.id]?.let { b.toDomain(it) } },
                categories.map { it.toDomain() },
                expenses.map { it.toTriple() },
                today,
            )
        }
    }

    override suspend fun claimNewAlerts(today: LocalDate): List<Pair<BudgetProgress, Int>> = write { db ->
        val limits = db.budgetDao().getAllLimits().groupBy { it.budgetId }
        val budgets = db.budgetDao().getVisible().mapNotNull { b -> limits[b.id]?.let { b.toDomain(it) } }
        val overview = BudgetCalculator.overview(
            budgets,
            db.categoryDao().getAll().map { it.toDomain() },
            db.budgetDao().getExpenses(EARLIEST).map { it.toTriple() },
            today,
        )
        overview.filter { it.budget.state == BudgetState.ACTIVE && it.current != null }.flatMap { progress ->
            progress.thresholdsReached.filter { threshold ->
                db.budgetDao().insertAlert(
                    BudgetAlertEntity(progress.budget.id, progress.current!!.range.start, threshold, clock.millis()),
                ) != -1L
            }.map { progress to it }
        }
    }

    private fun periodStart(draft: BudgetDraft, date: LocalDate): LocalDate =
        BudgetPeriods.periodContaining(draft.periodType, draft.startDate, draft.endDate, date)?.start ?: draft.startDate

    private suspend fun requireBudget(db: PocketBudgetDatabase, id: Long) =
        db.budgetDao().getById(id) ?: throw LedgerException.NotFound("Budget $id")

    private suspend fun validate(db: PocketBudgetDatabase, draft: BudgetDraft) {
        if (draft.name.isBlank()) throw LedgerException.NameRequired()
        if (!draft.amount.isPositive) throw LedgerException.InvalidAmount()
        val category = db.categoryDao().getById(draft.categoryId)
        if (category == null || category.isArchived || category.kind != CategoryKind.EXPENSE) {
            throw LedgerException.CategoryMismatch()
        }
        if (draft.periodType == BudgetPeriodType.CUSTOM && (draft.endDate == null || draft.endDate.isBefore(draft.startDate))) {
            throw LedgerException.InvalidOperation("A custom budget needs an end date on or after its start")
        }
        if (draft.alertThresholds.any { it !in 1..1000 }) throw LedgerException.InvalidOperation("Invalid alert threshold")
    }

    private fun BudgetEntity.toDomain(limits: List<BudgetLimitEntity>) = Budget(
        id = id,
        name = name,
        categoryId = categoryId,
        periodType = periodType,
        startDate = startDate,
        endDate = endDate,
        cycleStart = cycleStart,
        rollover = rollover,
        alertThresholds = alertThresholds.split(',').mapNotNull { it.trim().toIntOrNull() },
        state = state,
        limits = limits.map { BudgetLimit(it.effectiveFrom, Money(it.amountCents)) },
    )
}

/**
 * Recurring schedules generate ordinary scheduled transactions through the
 * existing [TransactionRepository], keyed by a deterministic request id
 * ("recurring-<schedule>-<epochDay>"), so a run interrupted at any point can be
 * repeated without creating duplicates.
 */
class RoomRecurringRepository(
    database: () -> PocketBudgetDatabase,
    private val transactions: TransactionRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), RecurringRepository {

    private val db: () -> PocketBudgetDatabase = database

    override suspend fun create(draft: RecurringDraft): Long {
        val id = write { db ->
            validate(db, draft)
            val now = clock.millis()
            val createdOn = LocalDate.now(clock)
            db.recurringDao().insert(
                draft.toEntity(
                    // Occurrences before the schedule was created are history the user already has.
                    generatedThrough = createdOn.minusDays(1).takeIf { draft.startDate.isBefore(createdOn) },
                    state = ScheduleState.ACTIVE,
                    createdAt = now,
                ),
            )
        }
        generateDue(LocalDate.now(clock))
        return id
    }

    override suspend fun update(id: Long, draft: RecurringDraft) {
        write { db ->
            validate(db, draft)
            val existing = requireSchedule(db, id)
            // Keeps state and generatedThrough: only not-yet-generated occurrences follow the new settings.
            db.recurringDao().update(
                draft.toEntity(existing.generatedThrough, existing.state, existing.createdAt).copy(id = id),
            )
        }
        generateDue(LocalDate.now(clock))
    }

    override suspend fun pause(id: Long) = setState(id, ScheduleState.PAUSED)

    override suspend fun resume(id: Long, today: LocalDate) {
        write { db ->
            val existing = requireSchedule(db, id)
            if (existing.state == ScheduleState.CANCELLED) throw LedgerException.InvalidOperation("Cancelled schedules cannot resume")
            val missedThrough = today.minusDays(1)
            db.recurringDao().update(
                existing.copy(
                    state = ScheduleState.ACTIVE,
                    generatedThrough = listOfNotNull(existing.generatedThrough, missedThrough).max(),
                    updatedAt = clock.millis(),
                ),
            )
        }
        generateDue(today)
    }

    override suspend fun cancel(id: Long) {
        setState(id, ScheduleState.CANCELLED)
        // Future projections from a cancelled schedule are withdrawn (soft delete, audited).
        val today = LocalDate.now(clock)
        withContextRead { it.recurringDao().getPendingOccurrences() }
            .filter { it.scheduleId == id && it.dueDate.isAfter(today) }
            .forEach { transactions.delete(it.transactionId) }
    }

    override suspend fun skipNext(id: Long, today: LocalDate): LocalDate? {
        val schedule = get(id) ?: throw LedgerException.NotFound("Schedule $id")
        val pending = withContextRead { it.recurringDao().getPendingOccurrences() }
            .filter { it.scheduleId == id && !it.dueDate.isBefore(today) }
            .minByOrNull { it.dueDate }
        if (pending != null) {
            transactions.delete(pending.transactionId)
            write { it.recurringDao().markSkipped(pending.id) }
            return pending.dueDate
        }
        val after = listOfNotNull(schedule.generatedThrough, today.minusDays(1)).max()
        val next = Recurrence.next(schedule, after, count = 10).firstOrNull { date ->
            withContextRead { it.recurringDao().getOccurrence(id, date) } == null
        } ?: return null
        write { db ->
            db.recurringDao().insertOccurrence(
                RecurringOccurrenceEntity(scheduleId = id, dueDate = next, transactionId = null, skipped = true, createdAt = clock.millis()),
            )
        }
        return next
    }

    override suspend fun get(id: Long): RecurringSchedule? = withContextRead { db -> db.recurringDao().getById(id)?.toDomain() }

    override fun observeAll(): Flow<List<RecurringSchedule>> =
        observe { it.recurringDao().observeAll() }.map { rows -> rows.map { it.toDomain() } }

    override suspend fun generateDue(today: LocalDate): Int = withContext(ioDispatcher) {
        val horizon = today.plusDays(GENERATION_HORIZON_DAYS)
        var created = 0
        for (entity in db().recurringDao().getActive()) {
            val schedule = entity.toDomain()
            val dates = Recurrence.between(
                schedule.startDate, schedule.frequency, schedule.interval, schedule.endDate,
                afterExclusive = schedule.generatedThrough, throughInclusive = horizon,
            )
            for (date in dates) {
                if (db().recurringDao().getOccurrence(schedule.id, date) != null) continue
                val transactionId = record(schedule, date)
                val inserted = write { db ->
                    db.recurringDao().insertOccurrence(
                        RecurringOccurrenceEntity(
                            scheduleId = schedule.id, dueDate = date, transactionId = transactionId,
                            skipped = false, createdAt = clock.millis(),
                        ),
                    )
                }
                if (inserted != -1L) created++
            }
            val through = listOfNotNull(horizon, schedule.endDate).min()
            if (schedule.generatedThrough == null || through.isAfter(schedule.generatedThrough)) {
                write { db ->
                    // Re-read so a concurrent edit to other fields is not overwritten.
                    db.recurringDao().getById(schedule.id)?.let { db.recurringDao().update(it.copy(generatedThrough = through)) }
                }
            }
        }
        created
    }

    /** Idempotent via the deterministic request id. Returns the (outgoing) ledger entry id. */
    private suspend fun record(schedule: RecurringSchedule, date: LocalDate): Long {
        val requestId = "recurring-${schedule.id}-${date.toEpochDay()}"
        return if (schedule.type == TransactionType.TRANSFER) {
            val transferId = transactions.recordTransfer(
                NewTransfer(requestId, schedule.accountId, schedule.toAccountId!!, schedule.amount, date, scheduled = true, note = schedule.note ?: schedule.name),
            )
            withContextRead { db -> db.transactionDao().getTransferLegs(transferId).first { it.amountCents < 0 }.id }
        } else {
            transactions.record(
                NewTransaction(
                    requestId, schedule.accountId, schedule.type, schedule.amount, date, scheduled = true,
                    categoryId = schedule.categoryId, payeeId = schedule.payeeId, note = schedule.note ?: schedule.name,
                ),
            )
        }
    }

    override fun observeUpcoming(today: LocalDate, days: Int): Flow<List<UpcomingItem>> {
        val until = today.plusDays(days.toLong())
        return combine(observeAll(), transactions.search(TransactionFilter(scheduled = true))) { schedules, scheduled ->
            val names = withContextRead { it.recurringDao().getPendingOccurrences() }.associate { it.transactionId to it.name }
            val generated = scheduled.filter { !it.occurredOn.isAfter(until) }.map {
                UpcomingItem(
                    date = it.occurredOn,
                    scheduleId = null,
                    title = names[it.id] ?: it.payeeName ?: it.categoryName ?: it.counterpartAccountName.orEmpty(),
                    type = it.type,
                    amount = it.amount,
                    transactionId = it.id,
                    overdue = it.occurredOn.isBefore(today),
                )
            }
            // Beyond the generation horizon: projections computed from active schedules.
            val projected = schedules.filter { it.state == ScheduleState.ACTIVE }.flatMap { s ->
                Recurrence.between(s.startDate, s.frequency, s.interval, s.endDate, s.generatedThrough ?: today.minusDays(1), until)
                    .filter { date -> withContextRead { it.recurringDao().getOccurrence(s.id, date) } == null }
                    .map { date ->
                        UpcomingItem(date, s.id, s.name, s.type, if (s.type == TransactionType.INCOME) s.amount else -s.amount, null, false)
                    }
            }
            (generated + projected).sortedWith(compareBy({ it.date }, { it.title }))
        }
    }

    override suspend fun claimReminders(today: LocalDate): List<Reminder> = write { db ->
        val rows = db.recurringDao().getPendingOccurrences()
        val byId = rows.associateBy { it.id }
        ReminderPlanner.plan(rows.map { ReminderPlanner.Candidate(it.id, it.dueDate, it.reminderSent, it.overdueSent) }, today)
            .map { (candidate, kind) ->
                if (kind == ReminderPlanner.Kind.OVERDUE) db.recurringDao().markOverdueSent(candidate.occurrenceId)
                else db.recurringDao().markReminderSent(candidate.occurrenceId)
                val row = byId.getValue(candidate.occurrenceId)
                Reminder(kind, row.name, row.type, Money(row.amountCents), row.dueDate, row.transactionId)
            }
    }

    private suspend fun setState(id: Long, state: ScheduleState) = write { db ->
        val existing = requireSchedule(db, id)
        if (existing.state == ScheduleState.CANCELLED) throw LedgerException.InvalidOperation("Schedule is cancelled")
        db.recurringDao().update(existing.copy(state = state, updatedAt = clock.millis()))
    }

    private suspend fun requireSchedule(db: PocketBudgetDatabase, id: Long) =
        db.recurringDao().getById(id) ?: throw LedgerException.NotFound("Schedule $id")

    private suspend fun validate(db: PocketBudgetDatabase, draft: RecurringDraft) {
        if (draft.name.isBlank()) throw LedgerException.NameRequired()
        if (!draft.amount.isPositive) throw LedgerException.InvalidAmount()
        if (draft.type == TransactionType.ADJUSTMENT) throw LedgerException.InvalidOperation("Adjustments cannot recur")
        if (draft.interval < 1) throw LedgerException.InvalidOperation("Interval must be at least 1")
        if (draft.endDate != null && draft.endDate.isBefore(draft.startDate)) {
            throw LedgerException.InvalidOperation("End date must be on or after the start date")
        }
        listOfNotNull(draft.accountId, draft.toAccountId).forEach { accountId ->
            val account = db.accountDao().getById(accountId)
            if (account == null || account.isArchived) throw LedgerException.AccountUnavailable(accountId)
        }
        if (draft.type == TransactionType.TRANSFER) {
            if (draft.toAccountId == null || draft.toAccountId == draft.accountId) throw LedgerException.SameAccountTransfer()
        } else if (draft.categoryId != null) {
            val expected = if (draft.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            if (db.categoryDao().getById(draft.categoryId)?.kind != expected) throw LedgerException.CategoryMismatch()
        }
    }

    private fun RecurringDraft.toEntity(generatedThrough: LocalDate?, state: ScheduleState, createdAt: Long) =
        RecurringScheduleEntity(
            name = name.trim(),
            kind = kind,
            type = type,
            accountId = accountId,
            toAccountId = toAccountId.takeIf { type == TransactionType.TRANSFER },
            amountCents = amount.cents,
            variableAmount = variableAmount,
            categoryId = categoryId.takeIf { type != TransactionType.TRANSFER },
            payeeId = payeeId.takeIf { type != TransactionType.TRANSFER },
            note = note?.trim()?.ifEmpty { null },
            frequency = frequency,
            intervalCount = interval,
            startDate = startDate,
            endDate = endDate,
            state = state,
            generatedThrough = generatedThrough,
            createdAt = createdAt,
            updatedAt = clock.millis(),
        )

    private fun RecurringScheduleEntity.toDomain() = RecurringSchedule(
        id, name, kind, type, accountId, toAccountId, Money(amountCents), variableAmount, categoryId, payeeId, note,
        frequency, intervalCount, startDate, endDate, state, generatedThrough,
    )
}

private fun LocalDate.coerceAtLeast(other: LocalDate) = if (isBefore(other)) other else this
