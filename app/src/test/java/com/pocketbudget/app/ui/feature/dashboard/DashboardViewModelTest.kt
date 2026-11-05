package com.pocketbudget.app.ui.feature.dashboard

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.model.TransactionDetail
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.AccountRepository
import com.pocketbudget.app.domain.repository.StorageRepository
import com.pocketbudget.app.domain.repository.StorageUnavailableException
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import com.pocketbudget.app.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)
    private val storage = StorageStatus(null, "4.x community")
    private val accounts = MutableStateFlow<List<AccountBalance>>(emptyList())
    private val transactions = FakeTransactionRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        storageRepository: StorageRepository = storageRepository { storage },
        seeder: (suspend () -> Unit)? = null,
    ) = DashboardViewModel(storageRepository, FakeAccountRepository(accounts), transactions, clock, seeder)

    @Test
    fun `ready state reflects live database values`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(DashboardUiState.Loading, vm.uiState.value)
        advanceUntilIdle()

        val checking = Account(1, "Checking", AccountType.CHECKING, Money.ZERO, true)
        accounts.value = listOf(AccountBalance(checking, Money(5_000), Money(3_000)))
        advanceUntilIdle()

        val ready = vm.uiState.value as DashboardUiState.Ready
        assertEquals(Money(5_000), ready.totals.current)
        assertEquals(Money(3_000), ready.totals.projected)
        assertEquals(transactions.summary, ready.thisMonth)
    }

    @Test
    fun `summary covers the current calendar month`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 30), transactions.requestedRange)
    }

    @Test
    fun `sample data is offered only when available and the ledger is empty`() = runTest(dispatcher) {
        var seeded = false
        val vm = viewModel(seeder = { seeded = true })
        advanceUntilIdle()
        assertTrue((vm.uiState.value as DashboardUiState.Ready).canLoadSampleData)

        vm.loadSampleData()
        advanceUntilIdle()
        assertTrue(seeded)

        accounts.value = listOf(
            AccountBalance(Account(1, "A", AccountType.CASH, Money.ZERO, true), Money.ZERO, Money.ZERO),
        )
        advanceUntilIdle()
        assertFalse((vm.uiState.value as DashboardUiState.Ready).canLoadSampleData)
    }

    @Test
    fun `upcoming activity lists scheduled entries soonest first`() = runTest(dispatcher) {
        fun scheduled(id: Long, date: String) = TransactionListItem(
            id = id, type = TransactionType.EXPENSE, status = TransactionStatus.SCHEDULED, amount = Money(-100),
            occurredOn = LocalDate.parse(date), accountName = "Checking", categoryName = null, payeeName = null,
            counterpartAccountName = null,
        )
        // The search returns newest first.
        transactions.scheduled = listOf(scheduled(3, "2026-11-20"), scheduled(2, "2026-11-16"), scheduled(1, "2026-11-10"))
        val vm = viewModel()
        advanceUntilIdle()

        val ready = vm.uiState.value as DashboardUiState.Ready
        assertEquals(listOf(1L, 2L, 3L), ready.upcoming.map { it.id })
        assertEquals(1, ready.overdueCount)
    }

    @Test
    fun `storage failure is shown and ledger is not queried`() = runTest(dispatcher) {
        val vm = viewModel(storageRepository { throw StorageUnavailableException(Reason.KEY_MISSING) })
        advanceUntilIdle()
        assertEquals(DashboardUiState.Error(Reason.KEY_MISSING), vm.uiState.value)
        assertEquals(null, transactions.requestedRange)
    }

    private fun storageRepository(open: suspend () -> StorageStatus) = object : StorageRepository {
        override suspend fun openStorage() = open()
    }

    private class FakeAccountRepository(private val balances: Flow<List<AccountBalance>>) : AccountRepository {
        override suspend fun create(name: String, type: AccountType, openingBalance: Money, includeInTotals: Boolean) = 0L
        override suspend fun get(id: Long): Account? = null
        override suspend fun update(id: Long, name: String, type: AccountType, includeInTotals: Boolean) = Unit
        override suspend fun archive(id: Long) = Unit
        override fun observeBalances() = balances
    }

    private class FakeTransactionRepository : TransactionRepository {
        val summary = PeriodSummary(Money(10_000), Money(2_500))
        var requestedRange: Pair<LocalDate, LocalDate>? = null
        var scheduled: List<TransactionListItem> = emptyList()

        override suspend fun record(transaction: NewTransaction) = 0L
        override suspend fun recordTransfer(transfer: NewTransfer) = 0L
        override suspend fun edit(id: Long, edit: TransactionEdit) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun confirm(id: Long, actualAmount: Money?) = false
        override suspend fun adjustBalance(adjustment: com.pocketbudget.app.domain.model.NewAdjustment) = 0L
        override fun observeRecent(limit: Int): Flow<List<TransactionListItem>> = flowOf(emptyList())
        override fun search(filter: TransactionFilter): Flow<List<TransactionListItem>> = flowOf(scheduled)
        override fun observeDetail(id: Long): Flow<TransactionDetail?> = flowOf(null)
        override fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate): Flow<PeriodSummary> {
            requestedRange = start to endInclusive
            return flowOf(summary)
        }
    }
}
