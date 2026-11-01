package com.pocketbudget.app.ui.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.ConsolidatedBalance
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.model.ThemeMode
import com.pocketbudget.app.ui.components.MoneyField
import com.pocketbudget.app.ui.feature.dashboard.DashboardActions
import com.pocketbudget.app.ui.feature.dashboard.DashboardScreen
import com.pocketbudget.app.ui.feature.dashboard.DashboardUiState
import com.pocketbudget.app.ui.feature.history.HistoryScreen
import com.pocketbudget.app.ui.feature.history.HistoryState
import com.pocketbudget.app.ui.feature.settings.SettingsScreen
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UsabilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun usLocale() = Locale.setDefault(Locale.US)

    @Test
    fun moneyFieldCompletesCentsWhenLeavingTheField() {
        listOf("38" to "$38.00", "38.5" to "$38.50", "38.56" to "$38.56", "1234.5" to "$1,234.50").forEach { (typed, shown) ->
            moneyFieldWithNeighbourOnce()
            compose.onNodeWithTag("amount").performClick().performTextInput(typed)
            compose.onNodeWithTag("amount").assertTextEquals("Amount", typed) // untouched while typing
            compose.onNodeWithTag("other").performClick()
            compose.onNodeWithTag("amount").assertTextEquals("Amount", shown)
            resetContent()
        }
    }

    @Test
    fun moneyFieldRemovesSymbolWhenEditingAgain() {
        moneyFieldWithNeighbourOnce()
        compose.onNodeWithTag("amount").performClick().performTextInput("38")
        compose.onNodeWithTag("other").performClick()
        compose.onNodeWithTag("amount").performClick()
        compose.onNodeWithTag("amount").assertTextEquals("Amount", "38.00")
        // Appending works from the end: the cursor was not moved into the middle.
        compose.onNodeWithTag("amount").performTextInput("1")
        compose.onNodeWithTag("amount").assertTextEquals("Amount", "38.001")
    }

    @Test
    fun accountRowOpensThatAccountsHistory() {
        var opened: Long? = null
        val account = Account(42, "Holiday fund", AccountType.SAVINGS, Money.ZERO, true)
        compose.setContent {
            PocketBudgetTheme {
                DashboardScreen(
                    DashboardUiState.Ready(
                        storage = StorageStatus(null, "4.x"),
                        totals = ConsolidatedBalance.ZERO,
                        accounts = listOf(AccountBalance(account, Money.ZERO, Money.ZERO)),
                        thisMonth = PeriodSummary.ZERO,
                        recent = emptyList(),
                        canLoadSampleData = false,
                    ),
                    DashboardActions(onOpenAccount = { opened = it }),
                )
            }
        }
        compose.onNodeWithText("Holiday fund").performClick()
        assertEquals(42L, opened)
    }

    @Test
    fun accountHistoryShowsNameAndEditButton() {
        var edits = 0
        compose.setContent {
            PocketBudgetTheme {
                HistoryScreen(
                    state = HistoryState(results = emptyList()),
                    query = "",
                    title = "Holiday fund",
                    onEditAccount = { edits++ },
                    onBack = {}, onOpen = {}, onQueryChange = {}, onAccount = {}, onCategory = {},
                    onPayee = {}, onScheduled = {}, onDates = { _, _ -> }, onClear = {},
                )
            }
        }
        compose.onNodeWithText("Holiday fund").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit account").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun themeSelectorReportsChoice() {
        var chosen: ThemeMode? = null
        compose.setContent { PocketBudgetTheme { SettingsScreen(ThemeMode.SYSTEM, {}, { chosen = it }) } }
        compose.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.DARK, chosen)
        compose.onNodeWithText("Light").performClick()
        assertEquals(ThemeMode.LIGHT, chosen)
    }

    // setContent may be called once per rule; the loop test swaps content via this state instead.
    private var contentKey by mutableStateOf(0)
    private var contentSet = false

    private fun moneyFieldWithNeighbourOnce() {
        if (contentSet) return
        contentSet = true
        compose.setContent {
            PocketBudgetTheme {
                androidx.compose.runtime.key(contentKey) {
                    var amount by remember { mutableStateOf("") }
                    Column {
                        MoneyField(amount, { amount = it }, "Amount", Modifier.testTag("amount"))
                        OutlinedTextField("", {}, label = { Text("Other") }, modifier = Modifier.testTag("other"))
                    }
                }
            }
        }
    }

    private fun resetContent() {
        contentKey++
        compose.waitForIdle()
    }
}
