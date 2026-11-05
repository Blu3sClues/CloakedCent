package com.cloakedcent.app.ui.feature.recurring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.RecurrenceFrequency
import com.cloakedcent.app.domain.model.RecurringKind
import com.cloakedcent.app.domain.model.RecurringSchedule
import com.cloakedcent.app.domain.model.ScheduleState
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@RunWith(AndroidJUnit4::class)
class RecurringDetailTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 15)

    private fun schedule(state: ScheduleState) = RecurringSchedule(
        id = 1, name = "Rent", kind = RecurringKind.RENT, type = TransactionType.EXPENSE, accountId = 1, toAccountId = null,
        amount = Money(150_000), variableAmount = false, categoryId = null, payeeId = null, note = null,
        frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = today, endDate = null, state = state, generatedThrough = null,
    )

    @Test
    fun aCancelledScheduleOffersReenableAndPermanentDelete() {
        var reenabled = 0
        var deleted = 0
        compose.setContent {
            CloakedCentTheme {
                RecurringDetailScreen(
                    schedule = schedule(ScheduleState.CANCELLED), today = today, message = null,
                    onBack = {}, onEdit = {}, onPause = {}, onResume = {}, onSkip = {}, onCancel = {},
                    onReenable = { reenabled++ }, onDelete = { deleted++ },
                )
            }
        }
        compose.onNodeWithText("Cancel schedule").assertDoesNotExist()
        compose.onNodeWithText("Re-enable schedule").performScrollTo().performClick()
        assertEquals(1, reenabled)

        compose.onNodeWithText("Delete schedule").performScrollTo().performClick()
        compose.onNodeWithText("permanently erased", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete schedule").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun anActiveScheduleOffersNeither() {
        compose.setContent {
            CloakedCentTheme {
                RecurringDetailScreen(
                    schedule = schedule(ScheduleState.ACTIVE), today = today, message = null,
                    onBack = {}, onEdit = {}, onPause = {}, onResume = {}, onSkip = {}, onCancel = {},
                )
            }
        }
        compose.onNodeWithText("Cancel schedule").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Re-enable schedule").assertDoesNotExist()
        compose.onNodeWithText("Delete schedule").assertDoesNotExist()
        // Only the next occurrence is listed.
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        compose.onNodeWithText("Next date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(today.format(format)).assertIsDisplayed()
        compose.onNodeWithText(today.plusMonths(1).format(format)).assertDoesNotExist()
    }
}
