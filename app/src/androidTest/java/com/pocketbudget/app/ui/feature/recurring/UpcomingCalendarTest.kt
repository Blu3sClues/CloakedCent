package com.pocketbudget.app.ui.feature.recurring

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.model.UpcomingItem
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UpcomingCalendarTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 10)

    private fun item(title: String, date: LocalDate, overdue: Boolean = false) =
        UpcomingItem(date, 1, title, TransactionType.EXPENSE, Money.ofCents(-1_500), null, overdue)

    @Before
    fun usLocale() = Locale.setDefault(Locale.US)

    @Test
    fun selectingADayListsOnlyThatDaysItems() {
        val items = listOf(
            item("Rent", LocalDate.of(2026, 11, 15)),
            item("Gym", LocalDate.of(2026, 11, 20)),
            item("Phone", LocalDate.of(2026, 11, 8), overdue = true),
        )
        compose.setContent { PocketBudgetTheme { UpcomingScreen(items, {}, {}, today) } }

        // Today is selected first and has nothing on it.
        compose.onNodeWithContentDescription("Tuesday, November 10, 2026").assertIsSelected()
        compose.onNodeWithText("Nothing scheduled on this day.").assertExists()
        compose.onNodeWithText("Rent").assertDoesNotExist()

        compose.onNodeWithContentDescription("Sunday, November 15, 2026, 1 scheduled item").performClick()
        compose.onNodeWithText("Rent").assertExists()
        compose.onNodeWithText("Gym").assertDoesNotExist()

        compose.onNodeWithContentDescription("Sunday, November 8, 2026, 1 scheduled item, Overdue").performClick()
        compose.onNodeWithText("Phone").assertExists()
        compose.onNodeWithText("Rent").assertDoesNotExist()
    }

    @Test
    fun monthsCanBeBrowsedAcrossTheProjectionWindow() {
        val items = listOf(item("Insurance", LocalDate.of(2026, 12, 3)))
        compose.setContent { PocketBudgetTheme { UpcomingScreen(items, {}, {}, today) } }

        compose.onNodeWithText("November 2026").assertExists()
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithText("December 2026").assertExists()
        compose.onNodeWithContentDescription("Thursday, December 3, 2026, 1 scheduled item").performClick()
        compose.onNodeWithText("Insurance").assertExists()
    }

    @Test
    fun overdueCardJumpsToTheOldestOverdueDay() {
        val items = listOf(item("Water bill", LocalDate.of(2026, 10, 28), overdue = true))
        compose.setContent { PocketBudgetTheme { UpcomingScreen(items, {}, {}, today) } }

        compose.onNodeWithText("Overdue (1)").performClick()
        compose.onNodeWithText("October 2026").assertExists()
        compose.onNodeWithText("Water bill").assertExists()
    }
}
