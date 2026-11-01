package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.core.money.sum
import java.time.LocalDate

enum class AccountType { CHECKING, SAVINGS, CASH, CREDIT_CARD, LOAN, INVESTMENT, OTHER }

data class Account(
    val id: Long,
    val name: String,
    val type: AccountType,
    val openingBalance: Money,
    val includeInTotals: Boolean,
)

/**
 * Balances for one account. All three include the opening balance.
 * - [current]: confirmed activity (pending + cleared). What the user has actually spent/received.
 * - [cleared]: only activity marked as cleared by the bank.
 * - [projected]: current plus scheduled (not yet happened) activity.
 */
data class AccountBalance(
    val account: Account,
    val current: Money,
    val cleared: Money,
    val projected: Money,
)

data class ConsolidatedBalance(val current: Money, val cleared: Money, val projected: Money) {
    companion object {
        val ZERO = ConsolidatedBalance(Money.ZERO, Money.ZERO, Money.ZERO)
    }
}

/** Totals across accounts marked "include in totals". Overflow-checked. */
fun List<AccountBalance>.consolidated(): ConsolidatedBalance {
    val included = filter { it.account.includeInTotals }
    return ConsolidatedBalance(
        current = included.map { it.current }.sum(),
        cleared = included.map { it.cleared }.sum(),
        projected = included.map { it.projected }.sum(),
    )
}

enum class CategoryKind { INCOME, EXPENSE }

data class Category(val id: Long, val name: String, val kind: CategoryKind, val parentId: Long?)

enum class PayeeType { PERSON, MERCHANT }

enum class TransactionType { INCOME, EXPENSE, TRANSFER }

/**
 * Lifecycle of a ledger entry. Only PENDING and CLEARED represent money that has
 * actually moved; SCHEDULED entries are projections and never count as current.
 */
enum class TransactionStatus { SCHEDULED, PENDING, CLEARED }

/**
 * A new income or expense. [amount] is a positive magnitude; the ledger applies the sign.
 * [requestId] makes recording idempotent: submitting the same id twice records once.
 */
data class NewTransaction(
    val requestId: String,
    val accountId: Long,
    val type: TransactionType,
    val amount: Money,
    val occurredOn: LocalDate,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val categoryId: Long? = null,
    val payeeId: Long? = null,
    val note: String? = null,
)

/** Moves [amount] (positive) between two accounts. Never counted as income or expense. */
data class NewTransfer(
    val requestId: String,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Money,
    val occurredOn: LocalDate,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val note: String? = null,
)

/** Replacement values for an entry's editable fields. Category/payee must be null for transfers. */
data class TransactionEdit(
    val amount: Money,
    val occurredOn: LocalDate,
    val categoryId: Long? = null,
    val payeeId: Long? = null,
    val note: String? = null,
)

data class TransactionListItem(
    val id: Long,
    val type: TransactionType,
    val status: TransactionStatus,
    /** Signed from the account's perspective: inflow positive, outflow negative. */
    val amount: Money,
    val occurredOn: LocalDate,
    val accountName: String,
    val categoryName: String?,
    val payeeName: String?,
    val counterpartAccountName: String?,
)

/** Confirmed income and expense for a period. Transfers are excluded by definition. */
data class PeriodSummary(val income: Money, val expense: Money) {
    val net: Money get() = income - expense

    companion object {
        val ZERO = PeriodSummary(Money.ZERO, Money.ZERO)
    }
}

/** Violations of ledger rules, raised before anything is written. */
sealed class LedgerException(message: String) : Exception(message) {
    class InvalidAmount : LedgerException("Amount must be greater than zero")
    class AccountUnavailable(id: Long) : LedgerException("Account $id does not exist or is archived")
    class CategoryMismatch : LedgerException("Category does not exist or does not match the entry type")
    class SameAccountTransfer : LedgerException("Transfer source and destination must differ")
    class AccountHasBalance : LedgerException("Only accounts with a zero balance can be archived")
    class NotFound(what: String) : LedgerException("$what not found")
    class InvalidOperation(message: String) : LedgerException(message)
}
