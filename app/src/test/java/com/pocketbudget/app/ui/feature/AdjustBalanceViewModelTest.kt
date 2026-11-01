package com.pocketbudget.app.ui.feature

import androidx.lifecycle.SavedStateHandle
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.AdjustmentKind
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.testing.FakeLedger
import com.pocketbudget.app.ui.feature.account.AdjustBalanceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AdjustBalanceViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val ledger = FakeLedger()
    private val originalLocale = Locale.getDefault()
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Locale.setDefault(Locale.US)
        ledger.accounts.value = listOf(
            AccountBalance(Account(1, "Checking", AccountType.CHECKING, Money.ZERO, true), Money(10_000), Money(10_000)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(originalLocale)
    }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        AdjustBalanceViewModel(1, handle, ledger.accountRepository, ledger.transactionRepository, clock)

    @Test
    fun `increase previews the new balance and can be reported as income`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Money(10_000), vm.state.value.currentBalance)
        assertEquals(listOf(AdjustmentKind.BALANCE_ONLY, AdjustmentKind.INCOME), vm.state.value.kindOptions)

        vm.onAmountChange("25.50")
        assertEquals(Money(12_550), vm.state.value.previewBalance)
        vm.onKindChange(AdjustmentKind.INCOME)
        vm.save()
        advanceUntilIdle()

        val saved = ledger.adjustments.single()
        assertEquals(Money(2_550), saved.amount)
        assertEquals(AdjustmentKind.INCOME, saved.kind)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `decrease is negative and switches income reporting back to balance-only`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onKindChange(AdjustmentKind.INCOME)
        vm.onDirectionChange(increase = false)
        assertEquals(AdjustmentKind.BALANCE_ONLY, vm.state.value.kind)
        assertEquals(listOf(AdjustmentKind.BALANCE_ONLY, AdjustmentKind.EXPENSE), vm.state.value.kindOptions)

        vm.onAmountChange("40")
        assertEquals(Money(6_000), vm.state.value.previewBalance)
        vm.onKindChange(AdjustmentKind.EXPENSE)
        vm.save()
        advanceUntilIdle()

        assertEquals(Money(-4_000), ledger.adjustments.single().amount)
        assertEquals(AdjustmentKind.EXPENSE, ledger.adjustments.single().kind)
    }

    @Test
    fun `invalid amount is flagged and nothing is recorded`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onAmountChange("abc")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.amountInvalid)
        assertTrue(ledger.adjustments.isEmpty())
    }

    @Test
    fun `ledger errors are shown and the screen stays open`() = runTest(dispatcher) {
        ledger.failure = LedgerException.InvalidAmount()
        val vm = vm()
        advanceUntilIdle()
        vm.onAmountChange("0")
        vm.save()
        advanceUntilIdle()
        assertEquals(R.string.error_amount_positive, vm.state.value.error)
        assertFalse(vm.state.value.finished)
    }

    @Test
    fun `request id survives recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        repeat(2) {
            val vm = vm(handle)
            advanceUntilIdle()
            vm.onAmountChange("1")
            vm.save()
            advanceUntilIdle()
        }
        assertEquals(1, ledger.adjustments.map { it.requestId }.distinct().size)
    }
}
