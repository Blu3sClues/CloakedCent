package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerModelTest {

    private fun balance(id: Long, current: Long, projected: Long, included: Boolean = true) =
        AccountBalance(
            account = Account(id, "Account $id", AccountType.CHECKING, Money.ZERO, included),
            current = Money(current),
            projected = Money(projected),
        )

    @Test
    fun `consolidated sums actual and projected balances of included accounts`() {
        val totals = listOf(
            balance(1, current = 10_000, projected = 8_000),
            balance(2, current = -2_500, projected = -3_000),
        ).consolidated()

        assertEquals(ConsolidatedBalance(Money(7_500), Money(5_000)), totals)
    }

    @Test
    fun `accounts excluded from totals are ignored`() {
        val totals = listOf(balance(1, 10_000, 10_000), balance(2, 99_999, 99_999, included = false)).consolidated()
        assertEquals(Money(10_000), totals.current)
    }

    @Test
    fun `no accounts consolidate to zero`() {
        assertEquals(ConsolidatedBalance.ZERO, emptyList<AccountBalance>().consolidated())
    }

    @Test
    fun `consolidation overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) {
            listOf(balance(1, Long.MAX_VALUE, 0), balance(2, 1, 0)).consolidated()
        }
    }

    @Test
    fun `period net is income minus expense`() {
        assertEquals(Money(-1_500), PeriodSummary(Money(1_000), Money(2_500)).net)
    }

    @Test
    fun `each type completes with its own status`() {
        assertEquals(TransactionStatus.RECEIVED, TransactionType.INCOME.completedStatus)
        assertEquals(TransactionStatus.SPENT, TransactionType.EXPENSE.completedStatus)
        assertEquals(TransactionStatus.COMPLETED, TransactionType.TRANSFER.completedStatus)
        assertEquals(TransactionStatus.COMPLETED, TransactionType.ADJUSTMENT.completedStatus)
        assertEquals(listOf(true, false, false, false), TransactionStatus.entries.map { it.isScheduled })
    }
}
