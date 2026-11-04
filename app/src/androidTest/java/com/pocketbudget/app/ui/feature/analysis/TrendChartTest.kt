package com.pocketbudget.app.ui.feature.analysis

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.DateRange
import com.pocketbudget.app.domain.model.TrendPoint
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TrendChartTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(points: List<TrendPoint>, range: DateRange) {
        compose.setContent { PocketBudgetTheme { TrendChart(points, range) } }
    }

    /** Taps the middle of bar [index] of [count]. */
    private fun tapBar(index: Int, count: Int) {
        compose.onNodeWithTag(TREND_CHART_TAG).performTouchInput {
            click(Offset(width * (index + 0.5f) / count, height * 0.9f))
        }
    }

    /**
     * A real tap on the screen near the bottom of bar [index], delivered through the window
     * manager like a finger, so an open tooltip sees it as a touch outside itself.
     */
    private fun touchScreenAtBar(index: Int, count: Int) {
        val chart = compose.onNodeWithTag(TREND_CHART_TAG).fetchSemanticsNode()
        val x = chart.positionOnScreen.x + chart.size.width * (index + 0.5f) / count
        val y = chart.positionOnScreen.y + chart.size.height * 0.9f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        compose.waitForIdle()
    }

    private val start = LocalDate.of(2026, 11, 1)
    private val days = (0 until 10).map { TrendPoint(start.plusDays(it.toLong()), Money(if (it == 3) 4_250 else 1_000)) }

    @Test
    fun tappingABarShowsItsDayAndAmount() {
        show(days, DateRange(start, start.plusDays(9)))
        compose.onNodeWithText("Nov 4, 2026").assertDoesNotExist()

        tapBar(3, 10)
        compose.onNodeWithText("Nov 4, 2026").assertIsDisplayed()
        compose.onNodeWithText("$42.50").assertIsDisplayed()
    }

    @Test
    fun touchingAnywhereElseHidesTheTooltipWithoutActingOnIt() {
        show(days, DateRange(start, start.plusDays(9)))
        tapBar(3, 10)
        compose.onNodeWithText("Nov 4, 2026").assertIsDisplayed()

        // The touch only takes the tooltip away; it does not also open another bar's.
        touchScreenAtBar(6, 10)
        compose.onNodeWithText("Nov 4, 2026").assertDoesNotExist()
        compose.onNodeWithText("Nov 7, 2026").assertDoesNotExist()

        touchScreenAtBar(6, 10)
        compose.onNodeWithText("Nov 7, 2026").assertIsDisplayed()
    }

    @Test
    fun backOrTappingTheTooltipHidesIt() {
        show(days, DateRange(start, start.plusDays(9)))
        tapBar(6, 10)
        Espresso.pressBack()
        compose.onNodeWithText("Nov 7, 2026").assertDoesNotExist()

        tapBar(6, 10)
        compose.onNodeWithText("Nov 7, 2026").performClick()
        compose.onNodeWithText("Nov 7, 2026").assertDoesNotExist()
    }

    @Test
    fun weeksShowTheirDaysWithinTheRangeAndMonthsTheirName() {
        // Jan 1 2026 is a Thursday: the first week bar counts only Jan 1-4.
        val range = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        val weeks = (0 until 14).map { TrendPoint(LocalDate.of(2025, 12, 29).plusWeeks(it.toLong()), Money(500)) }
        show(weeks, range)
        tapBar(0, weeks.size)
        compose.onNodeWithText("Jan 1, 2026 – Jan 4, 2026").assertIsDisplayed()
        compose.onNodeWithText("$5.00").assertIsDisplayed()
    }

    @Test
    fun aFullMonthIsShownByName() {
        val range = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        val months = (0 until 12).map { TrendPoint(LocalDate.of(2026, it + 1, 1), Money(12_345)) }
        show(months, range)
        tapBar(1, months.size)
        compose.onNodeWithText("February 2026").assertIsDisplayed()
        compose.onNodeWithText("$123.45").assertIsDisplayed()
    }
}
