package com.pocketbudget.app.ui.feature.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.ConsolidatedBalance
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: DashboardUiState, onRetry: () -> Unit = {}, onSample: () -> Unit = {}) =
        composeRule.setContent {
            PocketBudgetTheme {
                DashboardScreen(state, DashboardActions(onRetry = onRetry, onLoadSampleData = onSample))
            }
        }

    private fun ready(
        accounts: List<AccountBalance> = emptyList(),
        recent: List<TransactionListItem> = emptyList(),
        canLoadSampleData: Boolean = false,
        upcoming: List<TransactionListItem> = emptyList(),
    ) = DashboardUiState.Ready(
        upcoming = upcoming,
        storage = StorageStatus(null, "4.x community"),
        totals = ConsolidatedBalance.ZERO,
        accounts = accounts,
        thisMonth = PeriodSummary.ZERO,
        recent = recent,
        canLoadSampleData = canLoadSampleData,
    )

    @Test
    fun showsAccountsAndActivityWithStatus() {
        val checking = Account(1, "Everyday checking", AccountType.CHECKING, Money.ZERO, true)
        show(
            ready(
                accounts = listOf(AccountBalance(checking, Money(1_000), Money(500))),
                upcoming = listOf(scheduledTransfer),
            ),
        )

        composeRule.onNodeWithText("Everyday checking").assertIsDisplayed()
        composeRule.onNodeWithText("Transfer to Savings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Scheduled").performScrollTo().assertIsDisplayed()
    }

    private val scheduledTransfer = TransactionListItem(
        id = 1, type = TransactionType.TRANSFER, status = TransactionStatus.SCHEDULED,
        amount = Money(-500), occurredOn = LocalDate.of(2026, 11, 20),
        accountName = "Everyday checking", categoryName = null, payeeName = null,
        counterpartAccountName = "Savings",
    )

    @Test
    fun upcomingActivitySitsBetweenAccountsAndRecentActivity() {
        show(ready(upcoming = listOf(scheduledTransfer)))

        fun top(text: String) = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot().top
        assertTrue(top("Accounts") < top("Upcoming activity"))
        assertTrue(top("Upcoming activity") < top("Transfer to Savings"))
        assertTrue(top("Transfer to Savings") < top("Recent activity"))
    }

    @Test
    fun upcomingActivityIsHiddenWithNothingScheduled() {
        show(ready())

        composeRule.onNodeWithText("Upcoming activity").assertDoesNotExist()
        composeRule.onNodeWithText("Recent activity").assertExists()
    }

    @Test
    fun creditCardsShowWhatIsOwedAsAPositiveAmount() {
        val card = Account(2, "Visa", AccountType.CREDIT_CARD, Money(-10_000), true)
        val checking = Account(1, "Checking", AccountType.CHECKING, Money.ZERO, true)
        show(
            ready(
                accounts = listOf(
                    AccountBalance(card, current = Money(-15_005), projected = Money(-20_005)),
                    AccountBalance(checking, current = Money(-4_000), projected = Money(-4_000)),
                ),
            ),
        )
        composeRule.onNodeWithText("$150.05").assertIsDisplayed()
        composeRule.onNodeWithText("$200.05").assertIsDisplayed()
        composeRule.onNodeWithText("-$150.05").assertDoesNotExist()
        // Other accounts still show their own sign.
        composeRule.onNodeWithText("-$40.00").assertIsDisplayed()
    }

    @Test
    fun emptyLedgerOffersSampleDataWhenAvailable() {
        var requested = false
        show(ready(canLoadSampleData = true), onSample = { requested = true })

        composeRule.onNodeWithText("No accounts yet").assertIsDisplayed()
        composeRule.onNodeWithText("Load sample data").performClick()
        assertTrue(requested)
    }

    @Test
    fun storageErrorReassuresAndOffersRetry() {
        var retried = false
        show(DashboardUiState.Error(Reason.KEY_MISSING), onRetry = { retried = true })

        composeRule.onNodeWithText("Your data has not been deleted", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }
}
