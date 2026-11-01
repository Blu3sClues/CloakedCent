package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.core.money.sum
import java.time.Instant
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
 * Balances for one account. Both include the opening balance.
 * - [current]: actual balance from SPENT entries only.
 * - [projected]: current plus SCHEDULED (not yet happened) entries.
 */
data class AccountBalance(
    val account: Account,
    val current: Money,
    val projected: Money,
)

data class ConsolidatedBalance(val current: Money, val projected: Money) {
    companion object {
        val ZERO = ConsolidatedBalance(Money.ZERO, Money.ZERO)
    }
}

/** Totals across accounts marked "include in totals". Overflow-checked. */
fun List<AccountBalance>.consolidated(): ConsolidatedBalance {
    val included = filter { it.account.includeInTotals }
    return ConsolidatedBalance(
        current = included.map { it.current }.sum(),
        projected = included.map { it.projected }.sum(),
    )
}

enum class CategoryKind { INCOME, EXPENSE }

data class Category(val id: Long, val name: String, val kind: CategoryKind, val parentId: Long?)

enum class PayeeType { PERSON, MERCHANT }

enum class TransactionType { INCOME, EXPENSE, TRANSFER }

/**
 * Lifecycle of a ledger entry. SPENT means the money has actually moved (for
 * income too); SCHEDULED entries are projections and never count as current.
 * Schema v3 merged the former PENDING and CLEARED statuses into SPENT.
 */
enum class TransactionStatus { SCHEDULED, SPENT }

/** App-wide appearance choice. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
    val status: TransactionStatus = TransactionStatus.SPENT,
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
    val status: TransactionStatus = TransactionStatus.SPENT,
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

data class Payee(val id: Long, val name: String, val type: PayeeType)

/** Returns [rootId] and every category nested beneath it. */
fun List<Category>.withDescendants(rootId: Long): Set<Long> {
    val children = groupBy { it.parentId }
    val result = mutableSetOf<Long>()
    val pending = ArrayDeque(listOf(rootId))
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (result.add(id)) children[id]?.forEach { pending.add(it.id) }
    }
    return result
}

/** Display path for each category, e.g. "Food › Groceries". */
fun List<Category>.pathLabels(): Map<Long, String> {
    val byId = associateBy { it.id }
    return associate { category ->
        val names = generateSequence(category) { it.parentId?.let(byId::get) }.map { it.name }.toList()
        category.id to names.asReversed().joinToString(" › ")
    }
}

/** All criteria are optional and combined with AND. */
data class TransactionFilter(
    val query: String? = null,
    val start: LocalDate? = null,
    val endInclusive: LocalDate? = null,
    val accountId: Long? = null,
    /** Matches this category and its subcategories. */
    val categoryId: Long? = null,
    val payeeId: Long? = null,
) {
    val isEmpty: Boolean get() = this == TransactionFilter()
}

enum class RevisionAction { UPDATE, STATUS_CHANGE, DELETE }

/** Values an entry had before a change, as recorded in the audit history. */
data class TransactionRevision(
    val revision: Int,
    val action: RevisionAction,
    val amount: Money,
    val status: TransactionStatus,
    val occurredOn: LocalDate,
    val categoryName: String?,
    val payeeName: String?,
    val note: String?,
    val recordedAt: Instant,
)

data class TransactionDetail(
    val id: Long,
    val type: TransactionType,
    val status: TransactionStatus,
    /** Signed from [accountId]'s perspective. */
    val amount: Money,
    val occurredOn: LocalDate,
    val accountId: Long,
    val accountName: String,
    val categoryId: Long?,
    val categoryName: String?,
    val payeeId: Long?,
    val payeeName: String?,
    val counterpartAccountId: Long?,
    val counterpartAccountName: String?,
    val note: String?,
    val revision: Int,
    val isDeleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Oldest first. */
    val history: List<TransactionRevision>,
)

/** Violations of ledger rules, raised before anything is written. */
sealed class LedgerException(message: String) : Exception(message) {
    class InvalidAmount : LedgerException("Amount must be greater than zero")
    class NameRequired : LedgerException("A name is required")
    class AccountUnavailable(id: Long) : LedgerException("Account $id does not exist or is archived")
    class CategoryMismatch : LedgerException("Category does not exist or does not match the entry type")
    class SameAccountTransfer : LedgerException("Transfer source and destination must differ")
    class AccountHasBalance : LedgerException("Only accounts with a zero balance can be archived")
    class NotFound(what: String) : LedgerException("$what not found")
    class InvalidOperation(message: String) : LedgerException(message)
}
