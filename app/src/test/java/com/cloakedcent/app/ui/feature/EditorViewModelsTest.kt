package com.cloakedcent.app.ui.feature

import androidx.lifecycle.SavedStateHandle
import com.cloakedcent.app.R
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.Account
import com.cloakedcent.app.domain.model.AccountBalance
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.TransactionDetail
import com.cloakedcent.app.domain.model.TransactionFilter
import com.cloakedcent.app.domain.model.TransactionStatus
import com.cloakedcent.app.domain.model.ExpenseDetails
import com.cloakedcent.app.domain.model.ExpenseItem
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.testing.FakeLedger
import com.cloakedcent.app.ui.feature.account.AccountEditorViewModel
import com.cloakedcent.app.ui.feature.history.HistoryViewModel
import com.cloakedcent.app.ui.feature.recurring.RecurringEditorState
import com.cloakedcent.app.ui.feature.transaction.DestinationBalance
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorState
import com.cloakedcent.app.ui.feature.transaction.TransactionEditorViewModel
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
    private val withDetails = mutableListOf<Triple<NewTransaction, String, ExpenseDetails>>()
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
        ledger.payeeRepository, ledger.transactionRepository,
        { expense, store, details -> withDetails += Triple(expense, store, details); 1L },
        clock,
    )

    private suspend fun readyExpense(amount: String = "50") = editor().also { vm ->
        vm.onAccountChange(1)
        vm.onAmountChange(amount)
    }

    @Test
    fun `additional options start rolled up and an untouched menu records a plain expense`() = runTest(dispatcher) {
        val vm = readyExpense()
        advanceUntilIdle()
        assertFalse(vm.state.value.optionsExpanded)

        vm.save()
        advanceUntilIdle()

        assertEquals(1, ledger.recorded.size)
        assertTrue(withDetails.isEmpty())
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `additional options are saved with the payee as the store`() = runTest(dispatcher) {
        val vm = readyExpense()
        advanceUntilIdle()
        vm.onPayeeChange(" Fresh Market ")
        vm.onTaxChange("5")
        vm.onStoreLocationChange(" 1 Main St ")
        vm.onAddItem()
        vm.onAddItem()
        vm.onAddItem()
        val (milk, unnamed, blank) = vm.state.value.items.map { it.key }
        vm.onItemNameChange(milk, " Milk ")
        vm.onItemPriceChange(milk, "20")
        vm.onItemPriceChange(unnamed, "10")
        assertEquals(blank, vm.state.value.items.last().key)
        vm.save()
        advanceUntilIdle()

        val (expense, store, details) = withDetails.single()
        assertEquals(Money(5_000), expense.amount)
        assertEquals("Fresh Market", store)
        assertEquals(Money(500), details.tax)
        assertEquals("1 Main St", details.storeAddress)
        assertEquals("a completely blank row is left out", listOf(ExpenseItem("Milk", Money(2_000)), ExpenseItem("", Money(1_000))), details.items)
        assertTrue(ledger.recorded.isEmpty())
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `only exceeding the amount is an error in additional options`() = runTest(dispatcher) {
        val vm = readyExpense()
        advanceUntilIdle()
        vm.onAddItem()
        val item = vm.state.value.items.single().key

        vm.onItemPriceChange(item, "45")
        vm.onTaxChange("5")
        assertFalse("exactly the amount is fine", vm.state.value.optionsExceedAmount)

        vm.onTaxChange("5.01")
        assertTrue(vm.state.value.optionsExceedAmount)

        vm.onTaxChange("not a number")
        vm.onItemNameChange(item, "")
        assertFalse("anything unreadable counts as left blank", vm.state.value.optionsExceedAmount)

        vm.onAmountChange("")
        vm.onTaxChange("999")
        assertFalse("there is no amount to compare with", vm.state.value.optionsExceedAmount)
    }

    @Test
    fun `saving over the amount is refused and opens the rolled up menu`() = runTest(dispatcher) {
        val vm = readyExpense()
        advanceUntilIdle()
        vm.onOptionsToggle()
        vm.onTaxChange("60")
        vm.onOptionsToggle()
        assertFalse(vm.state.value.optionsExpanded)

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.optionsExpanded)
        assertFalse(vm.state.value.finished)
        assertTrue(ledger.recorded.isEmpty())
        assertTrue(withDetails.isEmpty())

        vm.onTaxChange("50")
        vm.save()
        advanceUntilIdle()
        assertEquals(1, withDetails.size)
    }

    @Test
    fun `removing an item takes its price out of the limit`() = runTest(dispatcher) {
        val vm = readyExpense()
        advanceUntilIdle()
        vm.onAddItem()
        val key = vm.state.value.items.single().key
        vm.onItemPriceChange(key, "60")
        assertTrue(vm.state.value.optionsExceedAmount)

        vm.onRemoveItem(key)

        assertFalse(vm.state.value.optionsExceedAmount)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `additional options do not apply to income`() = runTest(dispatcher) {
        val vm = readyExpense()
        advanceUntilIdle()
        vm.onTaxChange("60")
        vm.onTypeChange(TransactionType.INCOME)

        assertFalse(vm.state.value.hasAdditionalOptions)
        assertFalse(vm.state.value.optionsExceedAmount)
        vm.save()
        advanceUntilIdle()

        assertEquals(1, ledger.recorded.size)
        assertTrue(withDetails.isEmpty())
    }

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

    private fun destinations() {
        ledger.accounts.value = listOf(
            AccountBalance(Account(1, "Checking", AccountType.CHECKING, Money.ZERO, true), Money(100_000), Money(100_000)),
            AccountBalance(Account(2, "Savings", AccountType.SAVINGS, Money.ZERO, true), Money(50_000), Money(45_000)),
            AccountBalance(Account(3, "Visa", AccountType.CREDIT_CARD, Money.ZERO, true), Money(-16_368), Money(-16_368)),
        )
    }

    @Test
    fun `destination balance appears once source, destination and amount are set and follows the amount`() = runTest(dispatcher) {
        destinations()
        val vm = editor(TransactionType.TRANSFER)
        advanceUntilIdle()
        fun shown() = vm.state.value.destinationAfterTransfer

        vm.onAccountChange(1)
        vm.onAmountChange("100")
        assertEquals("no destination yet", null, shown())
        vm.onToAccountChange(2)
        assertEquals(DestinationBalance("Savings", Money(60_000)), shown())

        vm.onAmountChange("100.5")
        assertEquals(Money(60_050), shown()?.balance)
        vm.onAmountChange("12.345")
        assertEquals("invalid amount", null, shown())
        vm.onAmountChange("0")
        assertEquals("nothing moved", null, shown())
        vm.onAmountChange("")
        assertEquals("no amount", null, shown())

        vm.onAmountChange("100")
        vm.onToAccountChange(1)
        assertEquals("same account is not a transfer", null, shown())
    }

    @Test
    fun `destination balance needs a source account`() = runTest(dispatcher) {
        destinations()
        val vm = editor(TransactionType.TRANSFER)
        advanceUntilIdle()
        vm.onToAccountChange(2)
        vm.onAmountChange("100")
        assertEquals(null, vm.state.value.destinationAfterTransfer)
    }

    @Test
    fun `a scheduled transfer builds on the projected balance`() = runTest(dispatcher) {
        destinations()
        val vm = editor(TransactionType.TRANSFER)
        advanceUntilIdle()
        vm.onAccountChange(1)
        vm.onToAccountChange(2)
        vm.onAmountChange("100")
        vm.onScheduledChange(true)
        assertEquals(Money(55_000), vm.state.value.destinationAfterTransfer?.balance)
    }

    @Test
    fun `paying a credit card shows what is still owed`() = runTest(dispatcher) {
        destinations()
        val vm = editor(TransactionType.TRANSFER)
        advanceUntilIdle()
        vm.onAccountChange(1)
        vm.onToAccountChange(3)
        vm.onAmountChange("100")
        assertEquals(DestinationBalance("Visa", Money(6_368)), vm.state.value.destinationAfterTransfer)
    }

    @Test
    fun `other types and existing transfers show no destination balance`() = runTest(dispatcher) {
        destinations()
        val expense = editor(TransactionType.EXPENSE)
        advanceUntilIdle()
        expense.onAccountChange(1)
        expense.onAmountChange("100")
        assertEquals(null, expense.state.value.destinationAfterTransfer)

        val state = TransactionEditorState(
            isNew = false, loading = false, type = TransactionType.TRANSFER, date = LocalDate.of(2026, 11, 15),
            balances = ledger.accounts.value, accountId = 1, toAccountId = 2, amount = "100",
        )
        assertEquals(null, state.destinationAfterTransfer)
    }

    @Test
    fun `credit cards are offered as a transfer's destination but not its source`() = runTest(dispatcher) {
        destinations()
        val vm = editor(TransactionType.TRANSFER)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.state.value.sourceAccounts.map { it.first })
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.accounts.map { it.first })
    }

    @Test
    fun `switching to a transfer clears a credit card source but keeps any other`() = runTest(dispatcher) {
        destinations()
        val vm = editor(TransactionType.EXPENSE)
        advanceUntilIdle()
        assertEquals("an expense can use a card", listOf(1L, 2L, 3L), vm.state.value.sourceAccounts.map { it.first })

        vm.onAccountChange(3)
        vm.onTypeChange(TransactionType.TRANSFER)
        assertEquals(null, vm.state.value.accountId)

        vm.onTypeChange(TransactionType.EXPENSE)
        vm.onAccountChange(1)
        vm.onTypeChange(TransactionType.TRANSFER)
        assertEquals(1L, vm.state.value.accountId)
    }

    @Test
    fun `the recurring form treats credit card sources the same and shows one already saved`() {
        val form = RecurringEditorState(
            isNew = true, startDate = LocalDate.of(2026, 11, 15), endDate = LocalDate.of(2027, 11, 15),
            accounts = listOf(1L to "Checking", 3L to "Visa"), cardAccountIds = setOf(3L), accountId = 3,
        )
        assertEquals("an expense can use a card", listOf(1L, 3L), form.sourceAccounts.map { it.first })

        val transfer = form.withType(TransactionType.TRANSFER)
        assertEquals(null, transfer.accountId)
        assertEquals(listOf(1L), transfer.sourceAccounts.map { it.first })

        val saved = form.copy(type = TransactionType.TRANSFER)
        assertEquals(listOf(1L, 3L), saved.sourceAccounts.map { it.first })
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
