package com.pocketbudget.app.ui.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.RevisionAction
import com.pocketbudget.app.domain.model.TransactionDetail
import com.pocketbudget.app.domain.model.TransactionRevision
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.ui.feature.account.AccountEditorScreen
import com.pocketbudget.app.ui.feature.account.AccountEditorState
import com.pocketbudget.app.ui.feature.transaction.DetailState
import com.pocketbudget.app.ui.feature.transaction.TransactionDetailScreen
import com.pocketbudget.app.ui.feature.transaction.TransactionEditorActions
import com.pocketbudget.app.ui.feature.transaction.TransactionEditorScreen
import com.pocketbudget.app.ui.feature.transaction.TransactionEditorState
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import org.junit.Assert.assertEquals
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
        isDeleted = false, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        history = listOf(
            TransactionRevision(1, RevisionAction.UPDATE, Money(-6_000), TransactionStatus.SCHEDULED,
                LocalDate.of(2026, 11, 10), "Utilities", "City Power", null, Instant.EPOCH),
        ),
    )

    @Test
    fun editorShowsValidationErrors() {
        compose.setContent {
            PocketBudgetTheme {
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

    @Test
    fun editingLocksAccountAndHidesTypeSwitch() {
        compose.setContent {
            PocketBudgetTheme {
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
            PocketBudgetTheme {
                TransactionDetailScreen(DetailState.Loaded(detail), null, {}, {}, {}, {}, { deletes++ })
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
    fun detailShowsScheduledActionAndAuditTrail() {
        compose.setContent {
            PocketBudgetTheme { TransactionDetailScreen(DetailState.Loaded(detail), null, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Confirm it happened").assertIsDisplayed()
        compose.onNodeWithText("Before:", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun archiveRequiresConfirmation() {
        var archives = 0
        compose.setContent {
            PocketBudgetTheme {
                AccountEditorScreen(
                    AccountEditorState(isNew = false, loading = false, name = "Savings"),
                    {}, {}, {}, {}, {}, {}, { archives++ }, null,
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
