package com.pocketbudget.app.ui.feature

import androidx.lifecycle.SavedStateHandle
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.TransactionDetail
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.testing.FakeLedger
import com.pocketbudget.app.ui.feature.account.AccountEditorViewModel
import com.pocketbudget.app.ui.feature.history.HistoryViewModel
import com.pocketbudget.app.ui.feature.transaction.TransactionEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val ledger = FakeLedger()
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)

    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(originalLocale)
    }

    private fun editor(
        type: TransactionType = TransactionType.EXPENSE,
        id: Long? = null,
        handle: SavedStateHandle = SavedStateHandle(),
    ) = TransactionEditorViewModel(
        id, type, handle, ledger.accountRepository, ledger.categoryRepository,
        ledger.payeeRepository, ledger.transactionRepository, clock,
    )

    @Test
    fun `invalid input is flagged and nothing is recorded`() = runTest(dispatcher) {
        val vm = editor()
        advanceUntilIdle()
        vm.onAmountChange("12.345")
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.amountInvalid)
        assertTrue(vm.state.value.accountMissing)
        assertTrue(ledger.recorded.isEmpty())
    }

    @Test
    fun `expense is recorded with parsed amount, payee and category`() = runTest(dispatcher) {
        val vm = editor()
        advanceUntilIdle()
        assertEquals("only expense categories are offered", listOf(20L, 21L), vm.state.value.categories.map { it.first })

        vm.onAccountChange(1)
        vm.onAmountChange("1,234.50")
        vm.onCategoryChange(21)
        vm.onPayeeChange("Corner Bistro")
        vm.onScheduledChange(true)
        vm.save()
        advanceUntilIdle()

        val saved = ledger.recorded.single()
        assertEquals(Money(123_450), saved.amount)
        assertEquals(TransactionType.EXPENSE, saved.type)
        assertEquals(21L, saved.categoryId)
        assertTrue(saved.scheduled)
        assertEquals(LocalDate.of(2026, 11, 15), saved.occurredOn)
        assertEquals("Corner Bistro", ledger.payees.value.first { it.id == saved.payeeId }.name)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `request id survives recreation so a resubmitted form records once`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        listOf(editor(handle = handle), editor(handle = handle)).forEach { vm ->
            advanceUntilIdle()
            vm.onAccountChange(1)
            vm.onAmountChange("5")
            vm.save()
            advanceUntilIdle()
        }
        assertEquals(1, ledger.recorded.map { it.requestId }.distinct().size)
    }

    @Test
    fun `transfer requires both accounts and uses recordTransfer`() = runTest(dispatcher) {
        val vm = editor(TransactionType.TRANSFER)
        advanceUntilIdle()
        vm.onAccountChange(1)
        vm.onAmountChange("100")
        vm.save()
        assertTrue(vm.state.value.toAccountMissing)

        vm.onToAccountChange(2)
        vm.save()
        advanceUntilIdle()
        val transfer = ledger.transfers.single()
        assertEquals(1L to 2L, transfer.fromAccountId to transfer.toAccountId)
        assertTrue(ledger.recorded.isEmpty())
    }

    @Test
    fun `ledger rule violations are shown, not swallowed`() = runTest(dispatcher) {
        ledger.failure = LedgerException.CategoryMismatch()
        val vm = editor()
        advanceUntilIdle()
        vm.onAccountChange(1)
        vm.onAmountChange("5")
        vm.save()
        advanceUntilIdle()

        assertEquals(R.string.error_category_mismatch, vm.state.value.error)
        assertFalse(vm.state.value.finished)
    }

    @Test
    fun `editing prefills from the ledger and saves through edit`() = runTest(dispatcher) {
        ledger.details[7] = TransactionDetail(
            id = 7, type = TransactionType.EXPENSE, status = TransactionStatus.SPENT, amount = Money(-4_215),
            occurredOn = LocalDate.of(2026, 11, 2), accountId = 1, accountName = "Checking",
            categoryId = 20, categoryName = "Food", payeeId = 100, payeeName = "Fresh Market",
            counterpartAccountId = null, counterpartAccountName = null, note = "lunch", revision = 1,
            isDeleted = false, isAdjustment = false, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, history = emptyList(),
        )
        val vm = editor(id = 7)
        advanceUntilIdle()
        assertEquals("$42.15", vm.state.value.amount)
        assertEquals("Fresh Market", vm.state.value.payee)

        vm.onAmountChange("50")
        vm.save()
        advanceUntilIdle()
        val (id, edit) = ledger.edits.single()
        assertEquals(7L, id)
        assertEquals(Money(5_000), edit.amount)
        assertEquals(100L, edit.payeeId)
        assertEquals("lunch", edit.note)
    }

    @Test
    fun `account editor saves what a credit card owes as negative and maps archive errors`() = runTest(dispatcher) {
        val create = AccountEditorViewModel(null, ledger.accountRepository)
        create.onNameChange("Visa")
        create.onTypeChange(AccountType.CREDIT_CARD)
        create.onOpeningBalanceChange("250.00")
        create.save()
        advanceUntilIdle()
        assertEquals(Triple("Visa", AccountType.CREDIT_CARD, Money(-25_000)), ledger.createdAccounts.single())

        val edit = AccountEditorViewModel(1, ledger.accountRepository)
        advanceUntilIdle()
        assertEquals("Checking", edit.state.value.name)
        ledger.failure = LedgerException.AccountHasBalance()
        edit.archive()
        advanceUntilIdle()
        assertEquals(R.string.error_account_has_balance, edit.state.value.error)
        assertFalse(edit.state.value.finished)
    }

    @Test
    fun `a minus sign typed out of habit still records a credit card as owed`() = runTest(dispatcher) {
        val vm = AccountEditorViewModel(null, ledger.accountRepository)
        vm.onNameChange("Store card")
        vm.onTypeChange(AccountType.CREDIT_CARD)
        vm.onOpeningBalanceChange("-80")
        vm.save()
        advanceUntilIdle()
        assertEquals(Money(-8_000), ledger.createdAccounts.single().third)
    }

    @Test
    fun `other account types keep the sign the user typed`() = runTest(dispatcher) {
        listOf(AccountType.CHECKING to "-40", AccountType.LOAN to "-5000", AccountType.SAVINGS to "120").forEach { (type, text) ->
            val vm = AccountEditorViewModel(null, ledger.accountRepository)
            vm.onNameChange(type.name)
            vm.onTypeChange(type)
            vm.onOpeningBalanceChange(text)
            vm.save()
            advanceUntilIdle()
        }
        assertEquals(listOf(Money(-4_000), Money(-500_000), Money(12_000)), ledger.createdAccounts.map { it.third })
    }

    @Test
    fun `invalid opening balance is flagged before reaching the ledger`() = runTest(dispatcher) {
        val vm = AccountEditorViewModel(null, ledger.accountRepository)
        vm.onNameChange("Cash")
        vm.onOpeningBalanceChange("ten")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.openingBalanceInvalid)
        assertTrue(ledger.createdAccounts.isEmpty())
    }

    @Test
    fun `history combines criteria and debounced text into one filter`() = runTest(dispatcher) {
        val vm = HistoryViewModel(1, ledger.transactionRepository, ledger.accountRepository, ledger.categoryRepository, ledger.payeeRepository)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(TransactionFilter(accountId = 1), ledger.searches.last())

        vm.setCategory(20)
        vm.setDates(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        vm.onQueryChange("mar")
        advanceTimeBy(HistoryViewModel.QUERY_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(
            TransactionFilter("mar", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 1, 20, null),
            ledger.searches.last(),
        )

        vm.clear()
        advanceTimeBy(HistoryViewModel.QUERY_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertTrue(ledger.searches.last().isEmpty)
    }
}
