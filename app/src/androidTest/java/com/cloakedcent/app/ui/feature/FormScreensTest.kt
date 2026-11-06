package com.cloakedcent.app.ui.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.Account
import com.cloakedcent.app.domain.model.AccountBalance
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.RevisionAction
import com.cloakedcent.app.domain.model.TransactionDetail
import com.cloakedcent.app.domain.model.TransactionRevision
import com.cloakedcent.app.domain.model.TransactionStatus
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.ui.feature.account.AccountEditorScreen
import com.cloakedcent.app.ui.feature.account.AccountEditorState
import com.cloakedcent.app.ui.feature.transaction.DetailState
import com.cloakedcent.app.ui.feature.transaction.ExpenseItemInput
import com.cloakedcent.app.ui.feature.transaction.TransactionDetailScreen
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorActions
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorScreen
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorState
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FormScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val detail = TransactionDetail(
        id = 1, type = TransactionType.EXPENSE, status = TransactionStatus.SCHEDULED, amount = Money(-6_430),
        occurredOn = LocalDate.of(2026, 11, 11), accountId = 1, accountName = "Checking",
        categoryId = 5, categoryName = "Utilities", payeeId = 2, payeeName = "City Power",
        counterpartAccountId = null, counterpartAccountName = null, note = null, revision = 2,
        isDeleted = false, isAdjustment = false, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        history = listOf(
            TransactionRevision(1, RevisionAction.UPDATE, Money(-6_000), TransactionStatus.SCHEDULED,
                LocalDate.of(2026, 11, 10), "Utilities", "City Power", null, Instant.EPOCH),
        ),
    )

    @Test
    fun editorShowsValidationErrors() {
        compose.setContent {
            CloakedCentTheme {
                TransactionEditorScreen(
                    TransactionEditorState(
                        isNew = true, loading = false, type = TransactionType.EXPENSE, date = LocalDate.of(2026, 11, 15),
                        amount = "abc", amountInvalid = true, accountMissing = true,
                    ),
                    TransactionEditorActions(),
                )
            }
        }
        compose.onNodeWithText("Enter an amount like 12.50").assertIsDisplayed()
        compose.onNodeWithText("Choose an account").assertIsDisplayed()
    }

    private val newExpense = TransactionEditorState(isNew = true, loading = false, type = TransactionType.EXPENSE, date = LocalDate.of(2026, 11, 15))

    @Test
    fun additionalOptionsAreRolledUpAboveSaveUntilOpened() {
        var opened = 0
        var state by mutableStateOf(newExpense)
        compose.setContent {
            CloakedCentTheme { TransactionEditorScreen(state, TransactionEditorActions(onOptionsToggle = { opened++ })) }
        }
        compose.onNodeWithText("Tax").assertDoesNotExist()
        compose.onNodeWithText("Store location").assertDoesNotExist()
        compose.onNodeWithText("Additional Options").performScrollTo().performClick()
        assertEquals(1, opened)

        state = state.copy(optionsExpanded = true)
        compose.onNodeWithText("Tax").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add item").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Store location").performScrollTo().assertIsDisplayed()
        val menu = compose.onNodeWithText("Additional Options").getUnclippedBoundsInRoot().bottom
        assertTrue(compose.onNodeWithText("Save").getUnclippedBoundsInRoot().top > menu)
    }

    @Test
    fun additionalOptionsAreOnlyOfferedOnANewExpense() {
        var state by mutableStateOf(newExpense.copy(type = TransactionType.INCOME))
        compose.setContent { CloakedCentTheme { TransactionEditorScreen(state, TransactionEditorActions()) } }
        compose.onNodeWithText("Additional Options").assertDoesNotExist()

        state = newExpense.copy(type = TransactionType.TRANSFER)
        compose.onNodeWithText("Additional Options").assertDoesNotExist()

        state = newExpense.copy(isNew = false)
        compose.onNodeWithText("Additional Options").assertDoesNotExist()
    }

    @Test
    fun exceedingTheAmountShowsTheErrorEvenWhenRolledUp() {
        val message = "Tax and items add up to more than the expense amount."
        var state by mutableStateOf(newExpense.copy(amount = "50", tax = "50"))
        compose.setContent { CloakedCentTheme { TransactionEditorScreen(state, TransactionEditorActions()) } }
        compose.onNodeWithText(message).assertDoesNotExist()

        state = state.copy(tax = "50.01")
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun itemRowsCanBeAddedEditedAndRemoved() {
        val removed = mutableListOf<Int>()
        var added = 0
        compose.setContent {
            CloakedCentTheme {
                TransactionEditorScreen(
                    newExpense.copy(optionsExpanded = true, items = listOf(ExpenseItemInput(7, "Milk", "2"))),
                    TransactionEditorActions(onAddItem = { added++ }, onRemoveItem = { removed += it }),
                )
            }
        }
        compose.onNodeWithText("Milk").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add item").performScrollTo().performClick()
        assertEquals(1, added)
        compose.onNodeWithContentDescription("Remove item").performScrollTo().performClick()
        assertEquals(listOf(7), removed)
    }

    @Test
    fun aNewTransferShowsTheDestinationBalanceAfterIt() {
        val savings = AccountBalance(Account(2, "Savings", AccountType.SAVINGS, Money.ZERO, true), Money(550_000), Money(550_000))
        var state by mutableStateOf(
            TransactionEditorState(
                isNew = true, loading = false, type = TransactionType.TRANSFER, date = LocalDate.of(2026, 11, 15),
                accounts = listOf(1L to "Checking", 2L to "Savings"), balances = listOf(savings),
                accountId = 1, toAccountId = 2, amount = "100",
            ),
        )
        compose.setContent { CloakedCentTheme { TransactionEditorScreen(state, TransactionEditorActions()) } }
        compose.onNodeWithText("Savings balance after transfer: $5,600.00").assertIsDisplayed()

        // Scheduled keeps the same wording.
        state = state.copy(amount = "250.50", scheduled = true)
        compose.onNodeWithText("Savings balance after transfer: $5,750.50").assertIsDisplayed()

        state = state.copy(amount = "")
        compose.onNodeWithText("Savings balance after transfer", substring = true).assertDoesNotExist()
    }

    @Test
    fun editingLocksAccountAndHidesTypeSwitch() {
        compose.setContent {
            CloakedCentTheme {
                TransactionEditorScreen(
                    TransactionEditorState(
                        isNew = false, loading = false, type = TransactionType.EXPENSE, date = LocalDate.of(2026, 11, 15),
                        accounts = listOf(1L to "Checking"), accountId = 1,
                    ),
                    TransactionEditorActions(),
                )
            }
        }
        compose.onNodeWithText("Edit transaction").assertIsDisplayed()
        compose.onNodeWithText("Checking").assertIsNotEnabled()
        compose.onNodeWithText("Transfer").assertDoesNotExist()
    }

    @Test
    fun deleteRequiresConfirmation() {
        var deletes = 0
        compose.setContent {
            CloakedCentTheme {
                TransactionDetailScreen(DetailState.Loaded(detail), null, {}, {}, {}, { deletes++ })
            }
        }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete this transaction?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deletes)

        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onAllNodesWithTextAndClickLast("Delete")
        assertEquals(1, deletes)
    }

    @Test
    fun deletingAnExpenseWithAReceiptAsksAboutTheReceipt() {
        var expenseOnly = 0
        var withReceipt = 0
        compose.setContent {
            CloakedCentTheme {
                TransactionDetailScreen(
                    DetailState.Loaded(detail), null, {}, {}, {}, { expenseOnly++ },
                    receiptId = 7, onDeleteWithReceipt = { withReceipt++ },
                )
            }
        }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("This expense has a receipt", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0 to 0, expenseOnly to withReceipt)

        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete expense only").performClick()
        assertEquals(1 to 0, expenseOnly to withReceipt)

        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete expense and receipt").performClick()
        assertEquals(1 to 1, expenseOnly to withReceipt)
    }

    @Test
    fun detailShowsScheduledActionAndAuditTrail() {
        compose.setContent {
            CloakedCentTheme { TransactionDetailScreen(DetailState.Loaded(detail), null, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Mark as spent").assertIsDisplayed()
        compose.onNodeWithText("Before:", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun archiveRequiresConfirmation() {
        var archives = 0
        compose.setContent {
            CloakedCentTheme {
                AccountEditorScreen(
                    AccountEditorState(isNew = false, loading = false, name = "Savings"),
                    {}, {}, {}, {}, {}, {}, { archives++ },
                )
            }
        }
        compose.onNodeWithText("Archive account").performScrollTo().performClick()
        compose.onNodeWithText("Archive this account?").assertIsDisplayed()
        compose.onAllNodesWithTextAndClickLast("Archive account")
        assertEquals(1, archives)
    }

    /** The dialog's confirm button shares its label with the button that opened it. */
    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithTextAndClickLast(text: String) {
        val nodes = onAllNodes(androidx.compose.ui.test.hasText(text))
        nodes.fetchSemanticsNodes().size.let { nodes[it - 1].performClick() }
    }
}
