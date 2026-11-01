package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.core.money.sum
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(endInclusive)
}

// ---------------------------------------------------------------- Budgets

enum class BudgetPeriodType { WEEKLY, MONTHLY, QUARTERLY, YEARLY, CUSTOM }

enum class BudgetState { ACTIVE, PAUSED, ARCHIVED }

data class Budget(
    val id: Long,
    val name: String,
    val categoryId: Long,
    val periodType: BudgetPeriodType,
    /** First day tracked; also the weekly alignment anchor and the custom range start. */
    val startDate: LocalDate,
    /** Only for CUSTOM: last day of the range. */
    val endDate: LocalDate?,
    /** Rollover accumulates from here; "Restart" moves it to the current period. */
    val cycleStart: LocalDate,
    val rollover: Boolean,
    /** Percentages that trigger alerts, ascending, e.g. [50, 75, 90, 100]. */
    val alertThresholds: List<Int>,
    val state: BudgetState,
    /** Limit history, oldest first. A change applies from its effective date's period on. */
    val limits: List<BudgetLimit>,
) {
    val currentLimit: Money get() = limits.last().amount
}

data class BudgetLimit(val effectiveFrom: LocalDate, val amount: Money)

/** Input for creating or editing a budget. */
data class BudgetDraft(
    val name: String,
    val categoryId: Long,
    val amount: Money,
    val periodType: BudgetPeriodType,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val rollover: Boolean = false,
    val alertThresholds: List<Int> = listOf(50, 75, 90, 100),
)

/** One period's figures. Only completed (non-scheduled) expenses count as spent. */
data class BudgetPeriodResult(
    val range: DateRange,
    val limit: Money,
    val carriedIn: Money,
    val spent: Money,
) {
    val allocated: Money get() = limit + carriedIn
    val remaining: Money get() = allocated - spent

    /** Whole percent of the allocation used (floor). */
    val percentUsed: Int
        get() = when {
            allocated.cents <= 0 -> if (spent.isPositive) 100 else 0
            else -> (Math.multiplyExact(spent.cents, 100L) / allocated.cents).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
}

data class BudgetProgress(
    val budget: Budget,
    val categoryLabel: String,
    /** Nesting depth among budgets (0 = top level). */
    val depth: Int,
    val parentBudgetId: Long?,
    /** Null when today is outside a CUSTOM range. */
    val current: BudgetPeriodResult?,
    /** All periods since the start, newest first (including current). */
    val history: List<BudgetPeriodResult>,
    val thresholdsReached: List<Int>,
    /** Sum of direct child budgets' current limits exceeds this budget's limit. */
    val childLimitsExceedParent: Boolean,
)

object BudgetPeriods {

    fun periodContaining(type: BudgetPeriodType, anchor: LocalDate, customEnd: LocalDate?, date: LocalDate): DateRange? =
        when (type) {
            BudgetPeriodType.WEEKLY -> {
                val offset = Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), 7L)
                val start = anchor.plusWeeks(offset)
                DateRange(start, start.plusDays(6))
            }
            BudgetPeriodType.MONTHLY -> date.withDayOfMonth(1).let { DateRange(it, it.plusMonths(1).minusDays(1)) }
            BudgetPeriodType.QUARTERLY -> {
                val start = LocalDate.of(date.year, ((date.monthValue - 1) / 3) * 3 + 1, 1)
                DateRange(start, start.plusMonths(3).minusDays(1))
            }
            BudgetPeriodType.YEARLY -> DateRange(LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31))
            BudgetPeriodType.CUSTOM -> customEnd?.let { DateRange(anchor, it) }?.takeIf { date in it }
        }

    /** Consecutive periods from the one containing [from] through the one containing [through]. */
    fun periods(type: BudgetPeriodType, anchor: LocalDate, customEnd: LocalDate?, from: LocalDate, through: LocalDate): List<DateRange> {
        if (type == BudgetPeriodType.CUSTOM) {
            val range = customEnd?.let { DateRange(anchor, it) } ?: return emptyList()
            return if (range.start.isAfter(through)) emptyList() else listOf(range)
        }
        val result = mutableListOf<DateRange>()
        var period = periodContaining(type, anchor, customEnd, from) ?: return emptyList()
        while (!period.start.isAfter(through)) {
            result += period
            period = periodContaining(type, anchor, customEnd, period.endInclusive.plusDays(1))!!
        }
        return result
    }
}

object BudgetCalculator {

    /**
     * Evaluates every period from the budget start through [today].
     * [spending] must already be limited to the budget's category subtree, so a
     * parent budget counts each child expense exactly once.
     */
    fun evaluate(budget: Budget, spending: List<Pair<LocalDate, Money>>, today: LocalDate): List<BudgetPeriodResult> {
        val periods = BudgetPeriods.periods(budget.periodType, budget.startDate, budget.endDate, budget.startDate, today)
        var carry = Money.ZERO
        return periods.map { range ->
            val limit = (budget.limits.lastOrNull { !it.effectiveFrom.isAfter(range.start) } ?: budget.limits.first()).amount
            val carriedIn = if (budget.rollover && !range.start.isBefore(budget.cycleStart)) carry else Money.ZERO
            val spent = spending.filter { (date, _) -> date in range }.map { it.second }.sum()
            val result = BudgetPeriodResult(range, limit, carriedIn, spent)
            // Only unused allocation rolls forward; overspending is not carried as debt.
            carry = if (result.remaining.isPositive) result.remaining else Money.ZERO
            result
        }
    }

    fun thresholdsReached(result: BudgetPeriodResult, thresholds: List<Int>): List<Int> =
        thresholds.filter { result.percentUsed >= it }

    /** Parses "50, 75,90 ,100" into sorted unique percentages 1..1000; null if malformed. */
    fun parseThresholds(text: String): List<Int>? {
        val parts = text.split(',').map { it.trim().removeSuffix("%").trim() }.filter { it.isNotEmpty() }
        val values = parts.map { it.toIntOrNull() ?: return null }
        if (values.any { it !in 1..1000 }) return null
        return values.distinct().sorted()
    }

    /**
     * Figures for every budget. Each budget's spending comes from its own category
     * subtree, so a parent includes child spending exactly once and child budgets
     * are never summed into the parent. Results are ordered parent-first.
     */
    fun overview(
        budgets: List<Budget>,
        categories: List<Category>,
        expenses: List<Triple<LocalDate, Long, Money>>,
        today: LocalDate,
    ): List<BudgetProgress> {
        val labels = categories.pathLabels()
        val parents = budgets.associate { it.id to parentBudget(it, budgets, categories) }
        val progress = budgets.associate { budget ->
            val subtree = categories.withDescendants(budget.categoryId)
            val spending = expenses.filter { it.second in subtree }.map { it.first to it.third }
            val history = evaluate(budget, spending, today).asReversed()
            val current = history.firstOrNull { today in it.range }
            val children = budgets.filter { parents[it.id]?.id == budget.id }
            budget.id to BudgetProgress(
                budget = budget,
                categoryLabel = labels[budget.categoryId].orEmpty(),
                depth = generateSequence(parents[budget.id]) { parents[it.id] }.count(),
                parentBudgetId = parents[budget.id]?.id,
                current = current,
                history = history,
                thresholdsReached = current?.let { thresholdsReached(it, budget.alertThresholds) }.orEmpty(),
                childLimitsExceedParent = children.isNotEmpty() &&
                    children.map { it.currentLimit }.sum() > budget.currentLimit,
            )
        }
        fun subtree(parentId: Long?): List<BudgetProgress> =
            budgets.filter { parents[it.id]?.id == parentId }.sortedBy { it.name.lowercase() }
                .flatMap { listOf(progress.getValue(it.id)) + subtree(it.id) }
        return subtree(null)
    }

    /** Nearest ancestor category that has its own (non-archived) budget, for display nesting. */
    fun parentBudget(budget: Budget, others: List<Budget>, categories: List<Category>): Budget? {
        val byId = categories.associateBy { it.id }
        val budgetByCategory = others.filter { it.id != budget.id }.groupBy { it.categoryId }
        var ancestor = byId[budget.categoryId]?.parentId
        while (ancestor != null) {
            budgetByCategory[ancestor]?.let { return it.first() }
            ancestor = byId[ancestor]?.parentId
        }
        return null
    }
}

// ---------------------------------------------------------------- Recurring

enum class RecurrenceFrequency { DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, YEARLY, CUSTOM_DAYS, CUSTOM_WEEKS, CUSTOM_MONTHS }

enum class RecurringKind { PAYCHECK, OTHER_INCOME, RENT, MORTGAGE, UTILITY, SUBSCRIPTION, LOAN, INSURANCE, OTHER }

enum class ScheduleState { ACTIVE, PAUSED, CANCELLED }

data class RecurringSchedule(
    val id: Long,
    val name: String,
    val kind: RecurringKind,
    val type: TransactionType,
    val accountId: Long,
    val toAccountId: Long?,
    /** Expected amount (positive). Variable schedules are confirmed with the actual amount. */
    val amount: Money,
    val variableAmount: Boolean,
    val categoryId: Long?,
    val payeeId: Long?,
    val note: String?,
    val frequency: RecurrenceFrequency,
    /** Multiplier for CUSTOM_* frequencies (e.g. every 3 days); 1 otherwise. */
    val interval: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val state: ScheduleState,
    /** Every occurrence on or before this date has been generated or skipped. */
    val generatedThrough: LocalDate?,
)

data class RecurringDraft(
    val name: String,
    val kind: RecurringKind,
    val type: TransactionType,
    val accountId: Long,
    val toAccountId: Long? = null,
    val amount: Money,
    val variableAmount: Boolean = false,
    val categoryId: Long? = null,
    val payeeId: Long? = null,
    val note: String? = null,
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
)

/** A future or due occurrence for the upcoming list. */
data class UpcomingItem(
    val date: LocalDate,
    val scheduleId: Long?,
    val title: String,
    val type: TransactionType,
    /** Signed from the main account's perspective. */
    val amount: Money,
    /** Set once generated as a scheduled transaction (confirmable). */
    val transactionId: Long?,
    val overdue: Boolean,
)

object Recurrence {

    /**
     * The [n]th occurrence (0-based), always computed from the start date so
     * month-end and leap-day anchors are preserved: Jan 31 monthly gives Feb 28/29
     * then Mar 31; Feb 29 yearly gives Feb 28 in common years.
     */
    fun occurrence(start: LocalDate, frequency: RecurrenceFrequency, interval: Int, n: Long): LocalDate {
        val k = interval.coerceAtLeast(1).toLong()
        return when (frequency) {
            RecurrenceFrequency.DAILY -> start.plusDays(n)
            RecurrenceFrequency.WEEKLY -> start.plusWeeks(n)
            RecurrenceFrequency.BIWEEKLY -> start.plusWeeks(2 * n)
            RecurrenceFrequency.MONTHLY -> start.plusMonths(n)
            RecurrenceFrequency.QUARTERLY -> start.plusMonths(3 * n)
            RecurrenceFrequency.YEARLY -> start.plusYears(n)
            RecurrenceFrequency.CUSTOM_DAYS -> start.plusDays(k * n)
            RecurrenceFrequency.CUSTOM_WEEKS -> start.plusWeeks(k * n)
            RecurrenceFrequency.CUSTOM_MONTHS -> start.plusMonths(k * n)
        }
    }

    /** Occurrence dates after [afterExclusive] (if set) up to [throughInclusive] and the end date. */
    fun between(
        start: LocalDate,
        frequency: RecurrenceFrequency,
        interval: Int,
        endDate: LocalDate?,
        afterExclusive: LocalDate?,
        throughInclusive: LocalDate,
    ): List<LocalDate> {
        val limit = listOfNotNull(endDate, throughInclusive).min()
        val result = mutableListOf<LocalDate>()
        var n = 0L
        while (n < MAX_OCCURRENCES) {
            val date = occurrence(start, frequency, interval, n++)
            if (date.isAfter(limit)) break
            if (afterExclusive == null || date.isAfter(afterExclusive)) result += date
        }
        return result
    }

    fun next(schedule: RecurringSchedule, after: LocalDate, count: Int): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        var n = 0L
        while (result.size < count && n < MAX_OCCURRENCES) {
            val date = occurrence(schedule.startDate, schedule.frequency, schedule.interval, n++)
            if (schedule.endDate != null && date.isAfter(schedule.endDate)) break
            if (date.isAfter(after)) result += date
        }
        return result
    }

    private const val MAX_OCCURRENCES = 100_000L
}

/** Which generated occurrences need a reminder today. Pure, so it is unit-tested. */
object ReminderPlanner {

    data class Candidate(val occurrenceId: Long, val dueDate: LocalDate, val reminderSent: Boolean, val overdueSent: Boolean)

    enum class Kind { DUE_SOON, OVERDUE }

    /** Due today or tomorrow → DUE_SOON once; past due → OVERDUE once. */
    fun plan(candidates: List<Candidate>, today: LocalDate): List<Pair<Candidate, Kind>> = candidates.mapNotNull {
        when {
            it.dueDate.isBefore(today) && !it.overdueSent -> it to Kind.OVERDUE
            !it.dueDate.isBefore(today) && !it.dueDate.isAfter(today.plusDays(1)) && !it.reminderSent -> it to Kind.DUE_SOON
            else -> null
        }
    }
}
