package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanningLogicTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun budget(
        type: BudgetPeriodType = BudgetPeriodType.MONTHLY,
        start: LocalDate = d(2026, 1, 1),
        end: LocalDate? = null,
        limits: List<BudgetLimit> = listOf(BudgetLimit(start, Money(10_000))),
        rollover: Boolean = false,
        cycleStart: LocalDate = start,
        id: Long = 1,
        categoryId: Long = 1,
        thresholds: List<Int> = listOf(50, 75, 90, 100),
    ) = Budget(id, "B$id", categoryId, type, start, end, cycleStart, rollover, thresholds, BudgetState.ACTIVE, limits)

    // ---------------------------------------------------------------- periods

    @Test
    fun `weekly periods align to the anchor weekday, including before it`() {
        val anchor = d(2026, 11, 4) // Wednesday
        assertEquals(DateRange(d(2026, 11, 11), d(2026, 11, 17)), BudgetPeriods.periodContaining(BudgetPeriodType.WEEKLY, anchor, null, d(2026, 11, 15)))
        assertEquals(DateRange(d(2026, 10, 28), d(2026, 11, 3)), BudgetPeriods.periodContaining(BudgetPeriodType.WEEKLY, anchor, null, d(2026, 11, 3)))
    }

    @Test
    fun `monthly quarterly and yearly periods follow the calendar`() {
        val any = d(2026, 1, 1)
        assertEquals(DateRange(d(2028, 2, 1), d(2028, 2, 29)), BudgetPeriods.periodContaining(BudgetPeriodType.MONTHLY, any, null, d(2028, 2, 10)))
        assertEquals(DateRange(d(2026, 10, 1), d(2026, 12, 31)), BudgetPeriods.periodContaining(BudgetPeriodType.QUARTERLY, any, null, d(2026, 11, 15)))
        assertEquals(DateRange(d(2026, 1, 1), d(2026, 3, 31)), BudgetPeriods.periodContaining(BudgetPeriodType.QUARTERLY, any, null, d(2026, 3, 31)))
        assertEquals(DateRange(d(2026, 1, 1), d(2026, 12, 31)), BudgetPeriods.periodContaining(BudgetPeriodType.YEARLY, any, null, d(2026, 7, 4)))
    }

    @Test
    fun `custom period is a single range`() {
        val range = BudgetPeriods.periodContaining(BudgetPeriodType.CUSTOM, d(2026, 6, 10), d(2026, 8, 20), d(2026, 7, 1))
        assertEquals(DateRange(d(2026, 6, 10), d(2026, 8, 20)), range)
        assertNull(BudgetPeriods.periodContaining(BudgetPeriodType.CUSTOM, d(2026, 6, 10), d(2026, 8, 20), d(2026, 9, 1)))
        assertEquals(1, BudgetPeriods.periods(BudgetPeriodType.CUSTOM, d(2026, 6, 10), d(2026, 8, 20), d(2026, 6, 10), d(2027, 1, 1)).size)
    }

    @Test
    fun `period lists are contiguous for every type`() {
        BudgetPeriodType.entries.filter { it != BudgetPeriodType.CUSTOM }.forEach { type ->
            val periods = BudgetPeriods.periods(type, d(2026, 1, 7), null, d(2026, 1, 7), d(2027, 6, 30))
            periods.zipWithNext().forEach { (a, b) -> assertEquals(type.name, a.endInclusive.plusDays(1), b.start) }
            assertTrue(type.name, d(2027, 6, 30) in periods.last())
        }
    }

    // ---------------------------------------------------------------- budget math

    @Test
    fun `only spending inside each period counts`() {
        val results = BudgetCalculator.evaluate(
            budget(),
            listOf(d(2026, 1, 5) to Money(2_000), d(2026, 2, 1) to Money(3_000), d(2025, 12, 31) to Money(9_999)),
            d(2026, 2, 15),
        )
        assertEquals(listOf(Money(2_000), Money(3_000)), results.map { it.spent })
        assertEquals(Money(7_000), results.last().remaining)
    }

    @Test
    fun `limit changes apply from their period and leave earlier periods alone`() {
        val b = budget(limits = listOf(BudgetLimit(d(2026, 1, 1), Money(10_000)), BudgetLimit(d(2026, 3, 1), Money(15_000))))
        val results = BudgetCalculator.evaluate(b, emptyList(), d(2026, 4, 2))
        assertEquals(listOf(10_000L, 10_000L, 15_000L, 15_000L), results.map { it.limit.cents })
    }

    @Test
    fun `rollover carries only unused budget and resets on restart`() {
        val spending = listOf(d(2026, 1, 10) to Money(4_000), d(2026, 2, 10) to Money(20_000), d(2026, 3, 10) to Money(1_000))
        val results = BudgetCalculator.evaluate(budget(rollover = true), spending, d(2026, 4, 1))
        assertEquals(listOf(0L, 6_000L, 0L, 9_000L), results.map { it.carriedIn.cents }) // overspend is not carried
        assertEquals(Money(16_000), results[1].allocated)

        val restarted = BudgetCalculator.evaluate(budget(rollover = true, cycleStart = d(2026, 3, 1)), spending, d(2026, 4, 1))
        assertEquals(listOf(0L, 0L, 0L, 9_000L), restarted.map { it.carriedIn.cents })

        val noRollover = BudgetCalculator.evaluate(budget(rollover = false), spending, d(2026, 4, 1))
        assertTrue(noRollover.all { it.carriedIn.isZero })
    }

    @Test
    fun `percent and thresholds use exact integer math`() {
        fun result(spent: Long, limit: Long = 10_000) = BudgetPeriodResult(DateRange(d(2026, 1, 1), d(2026, 1, 31)), Money(limit), Money.ZERO, Money(spent))
        assertEquals(49, result(4_999).percentUsed)
        assertEquals(listOf(50), BudgetCalculator.thresholdsReached(result(5_000), listOf(50, 75, 90, 100)))
        assertEquals(listOf(50, 75, 90, 100), BudgetCalculator.thresholdsReached(result(10_000), listOf(50, 75, 90, 100)))
        assertEquals(120, result(12_000).percentUsed)
        assertEquals(Money(-2_000), result(12_000).remaining)
        assertEquals(0, result(0, limit = 0).percentUsed)
    }

    @Test
    fun `threshold text is parsed strictly`() {
        assertEquals(listOf(50, 75, 90, 100), BudgetCalculator.parseThresholds("90, 50,75 ,100, 50"))
        assertEquals(listOf(80), BudgetCalculator.parseThresholds("80%"))
        assertEquals(emptyList<Int>(), BudgetCalculator.parseThresholds(""))
        assertNull(BudgetCalculator.parseThresholds("50, abc"))
        assertNull(BudgetCalculator.parseThresholds("0"))
    }

    @Test
    fun `parent budgets include child spending exactly once`() {
        val categories = listOf(
            Category(1, "Food", CategoryKind.EXPENSE, null),
            Category(2, "Groceries", CategoryKind.EXPENSE, 1),
            Category(3, "Produce", CategoryKind.EXPENSE, 2),
            Category(4, "Transport", CategoryKind.EXPENSE, null),
        )
        val food = budget(id = 1, categoryId = 1, limits = listOf(BudgetLimit(d(2026, 1, 1), Money(30_000))))
        val groceries = budget(id = 2, categoryId = 2, limits = listOf(BudgetLimit(d(2026, 1, 1), Money(20_000))))
        val produce = budget(id = 3, categoryId = 3, limits = listOf(BudgetLimit(d(2026, 1, 1), Money(15_000))))
        val expenses = listOf(
            Triple(d(2026, 1, 3), 1L, Money(1_000)),
            Triple(d(2026, 1, 4), 2L, Money(2_000)),
            Triple(d(2026, 1, 5), 3L, Money(3_000)),
            Triple(d(2026, 1, 6), 4L, Money(9_000)),
        )
        val overview = BudgetCalculator.overview(listOf(produce, food, groceries), categories, expenses, d(2026, 1, 20))
        assertEquals(listOf(1L, 2L, 3L), overview.map { it.budget.id }) // parent first
        assertEquals(listOf(0, 1, 2), overview.map { it.depth })
        assertEquals(listOf(6_000L, 5_000L, 3_000L), overview.map { it.current!!.spent.cents })
        assertEquals(listOf(null, 1L, 2L), overview.map { it.parentBudgetId })
        assertFalse(overview[0].childLimitsExceedParent)
        val tight = BudgetCalculator.overview(listOf(food.copy(limits = listOf(BudgetLimit(d(2026, 1, 1), Money(10_000)))), groceries), categories, expenses, d(2026, 1, 20))
        assertTrue(tight[0].childLimitsExceedParent)
    }

    // ---------------------------------------------------------------- recurrence

    @Test
    fun `monthly keeps month-end anchors through short months and leap years`() {
        val dates = (0L..3L).map { Recurrence.occurrence(d(2028, 1, 31), RecurrenceFrequency.MONTHLY, 1, it) }
        assertEquals(listOf(d(2028, 1, 31), d(2028, 2, 29), d(2028, 3, 31), d(2028, 4, 30)), dates)
        assertEquals(d(2027, 2, 28), Recurrence.occurrence(d(2027, 1, 31), RecurrenceFrequency.MONTHLY, 1, 1))
        val quarterly = (0L..2L).map { Recurrence.occurrence(d(2027, 11, 30), RecurrenceFrequency.QUARTERLY, 1, it) }
        assertEquals(listOf(d(2027, 11, 30), d(2028, 2, 29), d(2028, 5, 30)), quarterly)
    }

    @Test
    fun `yearly leap-day anchor falls back to Feb 28 and returns on leap years`() {
        val dates = (0L..4L).map { Recurrence.occurrence(d(2028, 2, 29), RecurrenceFrequency.YEARLY, 1, it) }
        assertEquals(listOf(d(2028, 2, 29), d(2029, 2, 28), d(2030, 2, 28), d(2031, 2, 28), d(2032, 2, 29)), dates)
    }

    @Test
    fun `every frequency produces the expected spacing`() {
        val start = d(2026, 1, 1)
        fun second(f: RecurrenceFrequency, interval: Int = 1) = Recurrence.occurrence(start, f, interval, 1)
        assertEquals(d(2026, 1, 2), second(RecurrenceFrequency.DAILY))
        assertEquals(d(2026, 1, 8), second(RecurrenceFrequency.WEEKLY))
        assertEquals(d(2026, 1, 15), second(RecurrenceFrequency.BIWEEKLY))
        assertEquals(d(2026, 2, 1), second(RecurrenceFrequency.MONTHLY))
        assertEquals(d(2026, 4, 1), second(RecurrenceFrequency.QUARTERLY))
        assertEquals(d(2027, 1, 1), second(RecurrenceFrequency.YEARLY))
        assertEquals(d(2026, 1, 4), second(RecurrenceFrequency.CUSTOM_DAYS, 3))
        assertEquals(d(2026, 1, 29), second(RecurrenceFrequency.CUSTOM_WEEKS, 4))
        assertEquals(d(2026, 7, 1), second(RecurrenceFrequency.CUSTOM_MONTHS, 6))
    }

    @Test
    fun `between respects the already-generated marker and the end date`() {
        val dates = Recurrence.between(
            d(2026, 1, 15), RecurrenceFrequency.MONTHLY, 1, endDate = d(2026, 5, 1),
            afterExclusive = d(2026, 2, 15), throughInclusive = d(2026, 12, 31),
        )
        assertEquals(listOf(d(2026, 3, 15), d(2026, 4, 15)), dates)
        assertEquals(365, Recurrence.between(d(2026, 1, 1), RecurrenceFrequency.DAILY, 1, null, null, d(2026, 12, 31)).size)
    }

    // ---------------------------------------------------------------- reminders

    @Test
    fun `reminders fire once for due-soon and once more when overdue`() {
        val today = d(2026, 11, 15)
        fun c(id: Long, due: LocalDate, reminded: Boolean = false, overdue: Boolean = false) =
            ReminderPlanner.Candidate(id, due, reminded, overdue)
        val plan = ReminderPlanner.plan(
            listOf(
                c(1, today), c(2, today.plusDays(1)), c(3, today.plusDays(2)),
                c(4, today.minusDays(1), reminded = true), c(5, today, reminded = true), c(6, today.minusDays(3), overdue = true),
            ),
            today,
        )
        assertEquals(
            listOf(1L to ReminderPlanner.Kind.DUE_SOON, 2L to ReminderPlanner.Kind.DUE_SOON, 4L to ReminderPlanner.Kind.OVERDUE),
            plan.map { it.first.occurrenceId to it.second },
        )
    }
}
