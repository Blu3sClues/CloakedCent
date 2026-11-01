package com.pocketbudget.app.testing

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.Category
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.Payee
import com.pocketbudget.app.domain.model.PayeeType
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.TransactionDetail
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.repository.AccountRepository
import com.pocketbudget.app.domain.repository.CategoryRepository
import com.pocketbudget.app.domain.repository.PayeeRepository
import com.pocketbudget.app.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * In-memory stand-ins that record calls. Ledger rules are not reimplemented;
 * tests that need a rule violation set [failure].
 */
class FakeLedger {
    var failure: Exception? = null

    val accounts = MutableStateFlow(
        listOf(
            balance(Account(1, "Checking", AccountType.CHECKING, Money.ZERO, true)),
            balance(Account(2, "Savings", AccountType.SAVINGS, Money.ZERO, true)),
        ),
    )
    val categories = MutableStateFlow(
        listOf(
            Category(10, "Salary", CategoryKind.INCOME, null),
            Category(20, "Food", CategoryKind.EXPENSE, null),
            Category(21, "Groceries", CategoryKind.EXPENSE, 20),
        ),
    )
    val payees = MutableStateFlow(listOf(Payee(100, "Fresh Market", PayeeType.MERCHANT)))
    val details = mutableMapOf<Long, TransactionDetail>()

    val recorded = mutableListOf<NewTransaction>()
    val transfers = mutableListOf<NewTransfer>()
    val adjustments = mutableListOf<com.pocketbudget.app.domain.model.NewAdjustment>()
    val edits = mutableListOf<Pair<Long, TransactionEdit>>()
    val searches = mutableListOf<TransactionFilter>()
    val createdAccounts = mutableListOf<Triple<String, AccountType, Money>>()
    val updatedAccounts = mutableListOf<Long>()
    val archivedAccounts = mutableListOf<Long>()

    private fun check() = failure?.let { throw it }

    private fun balance(a: Account) = AccountBalance(a, Money.ZERO, Money.ZERO)

    val accountRepository = object : AccountRepository {
        override suspend fun create(name: String, type: AccountType, openingBalance: Money, includeInTotals: Boolean): Long {
            check(); createdAccounts += Triple(name, type, openingBalance); return 99
        }
        override suspend fun get(id: Long) = accounts.value.firstOrNull { it.account.id == id }?.account
        override suspend fun update(id: Long, name: String, type: AccountType, includeInTotals: Boolean) {
            check(); updatedAccounts += id
        }
        override suspend fun archive(id: Long) {
            check(); archivedAccounts += id
        }
        override fun observeBalances(): Flow<List<AccountBalance>> = accounts
    }

    val categoryRepository = object : CategoryRepository {
        override suspend fun create(name: String, kind: CategoryKind, parentId: Long?) = 0L
        override suspend fun setParent(id: Long, parentId: Long?) = Unit
        override fun observeAll(): Flow<List<Category>> = categories
    }

    val payeeRepository = object : PayeeRepository {
        override suspend fun getOrCreate(name: String, type: PayeeType): Long =
            payees.value.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                ?: (payees.value.size + 100L).also { payees.value = payees.value + Payee(it, name, type) }
        override fun observeAll(): Flow<List<Payee>> = payees
    }

    val transactionRepository = object : TransactionRepository {
        override suspend fun record(transaction: NewTransaction): Long {
            check(); recorded += transaction; return recorded.size.toLong()
        }
        override suspend fun recordTransfer(transfer: NewTransfer): Long {
            check(); transfers += transfer; return transfers.size.toLong()
        }
        override suspend fun edit(id: Long, edit: TransactionEdit) {
            check(); edits += id to edit
        }
        override suspend fun delete(id: Long) {
            check()
        }
        override suspend fun confirm(id: Long) = true
        override suspend fun adjustBalance(adjustment: com.pocketbudget.app.domain.model.NewAdjustment): Long {
            check(); adjustments += adjustment; return adjustments.size.toLong()
        }
        override fun observeRecent(limit: Int): Flow<List<TransactionListItem>> = flowOf(emptyList())
        override fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate) = flowOf(PeriodSummary.ZERO)
        override fun search(filter: TransactionFilter): Flow<List<TransactionListItem>> {
            searches += filter
            return flowOf(emptyList())
        }
        override fun observeDetail(id: Long): Flow<TransactionDetail?> = MutableStateFlow(details[id]).map { it }
    }
}
