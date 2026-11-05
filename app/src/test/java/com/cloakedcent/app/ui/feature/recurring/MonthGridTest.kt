package com.cloakedcent.app.ui.feature.recurring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {

    @Test
    fun `weeks start on the locale's first day and pad both ends`() {
        // 1 November 2026 is a Sunday.
        val sundayFirst = monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), sundayFirst[0])
        assertEquals(35, sundayFirst.size)

        val mondayFirst = monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        (0 until 6).forEach { assertNull(mondayFirst[it]) }
        assertEquals(LocalDate.of(2026, 11, 1), mondayFirst[6])
        assertEquals(42, mondayFirst.size)
        assertEquals(LocalDate.of(2026, 11, 30), mondayFirst.filterNotNull().last())
    }

    @Test
    fun `every day of the month appears once, in order, in whole weeks`() {
        listOf(YearMonth.of(2026, 2), YearMonth.of(2028, 2), YearMonth.of(2027, 8)).forEach { month ->
            DayOfWeek.entries.forEach { first ->
                val grid = monthGrid(month, first)
                assertEquals(0, grid.size % 7)
                assertEquals((1..month.lengthOfMonth()).map { month.atDay(it) }, grid.filterNotNull())
                grid.chunked(7).first().first { it != null }!!.let { assertEquals(month.atDay(1), it) }
            }
        }
    }
}
