package com.pocketbudget.app.dev

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.di.AppContainer
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Debug-only demo ledger, written through the real repositories so it obeys
 * every ledger rule. Fixed request ids make it safe to run more than once.
 */
object SampleData {

    fun seeder(container: AppContainer): (suspend () -> Unit)? = { seed(container) }

    private suspend fun seed(container: AppContainer) {
        val accounts = container.accountRepository
        if (accounts.observeBalances().first().isNotEmpty()) return

        val categories = container.categoryRepository.observeAll().first().associateBy { it.name }
        val payees = container.payeeRepository
        val ledger = container.transactionRepository
        val today = LocalDate.now()
        val firstOfMonth = today.withDayOfMonth(1)

        val checking = accounts.create("Everyday checking", AccountType.CHECKING, Money.parse("1250.00"))
        val savings = accounts.create("Savings", AccountType.SAVINGS, Money.parse("5000.00"))
        val card = accounts.create("Credit card", AccountType.CREDIT_CARD)

        suspend fun entry(
            id: String,
            account: Long,
            type: TransactionType,
            amount: String,
            date: LocalDate,
            status: TransactionStatus,
            category: String,
            payee: String? = null,
        ) = ledger.record(
            NewTransaction(
                requestId = "sample-$id",
                accountId = account,
                type = type,
                amount = Money.parse(amount),
                occurredOn = date,
                status = status,
                categoryId = categories[category]?.id,
                payeeId = payee?.let { payees.getOrCreate(it) },
            ),
        )

        entry("salary", checking, TransactionType.INCOME, "3200.00", firstOfMonth, TransactionStatus.SPENT, "Salary", "Employer")
        entry("rent", checking, TransactionType.EXPENSE, "1100.00", firstOfMonth, TransactionStatus.SPENT, "Housing", "Landlord")
        entry("groceries", card, TransactionType.EXPENSE, "86.42", today, TransactionStatus.SPENT, "Groceries", "Fresh Market")
        entry("dinner", card, TransactionType.EXPENSE, "42.15", today, TransactionStatus.SPENT, "Dining out", "Corner Bistro")
        entry("power", checking, TransactionType.EXPENSE, "64.30", today.plusDays(10), TransactionStatus.SCHEDULED, "Utilities", "City Power")
        ledger.recordTransfer(
            NewTransfer(
                requestId = "sample-savings",
                fromAccountId = checking,
                toAccountId = savings,
                amount = Money.parse("500.00"),
                occurredOn = firstOfMonth.plusDays(1),
                status = TransactionStatus.SPENT,
            ),
        )
    }
}
