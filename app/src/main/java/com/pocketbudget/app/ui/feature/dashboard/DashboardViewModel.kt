package com.pocketbudget.app.ui.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.ConsolidatedBalance
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.model.consolidated
import com.pocketbudget.app.domain.repository.AccountRepository
import com.pocketbudget.app.domain.repository.StorageRepository
import com.pocketbudget.app.domain.repository.StorageUnavailableException
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import com.pocketbudget.app.domain.repository.TransactionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Error(val reason: Reason) : DashboardUiState
    data class Ready(
        val storage: StorageStatus,
        val totals: ConsolidatedBalance,
        val accounts: List<AccountBalance>,
        val thisMonth: PeriodSummary,
        val recent: List<TransactionListItem>,
        val canLoadSampleData: Boolean,
    ) : DashboardUiState
}

class DashboardViewModel(
    private val storageRepository: StorageRepository,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val sampleSeeder: (suspend () -> Unit)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    fun loadSampleData() {
        val seed = sampleSeeder ?: return
        viewModelScope.launch {
            try {
                seed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(Reason.UNKNOWN)
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.value = DashboardUiState.Loading
        loadJob = viewModelScope.launch {
            val storage = try {
                storageRepository.openStorage()
            } catch (e: StorageUnavailableException) {
                _uiState.value = DashboardUiState.Error(e.reason)
                return@launch
            }
            val month = YearMonth.now(clock)
            combine(
                accountRepository.observeBalances(),
                transactionRepository.observePeriodSummary(month.atDay(1), month.atEndOfMonth()),
                transactionRepository.observeRecent(RECENT_LIMIT),
            ) { accounts, summary, recent ->
                DashboardUiState.Ready(
                    storage = storage,
                    totals = accounts.consolidated(),
                    accounts = accounts,
                    thisMonth = summary,
                    recent = recent,
                    canLoadSampleData = sampleSeeder != null && accounts.isEmpty(),
                )
            }
                .catch { _uiState.value = DashboardUiState.Error(Reason.UNKNOWN) }
                .collect { _uiState.value = it }
        }
    }

    companion object {
        const val RECENT_LIMIT = 10

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PocketBudgetApp).container
                DashboardViewModel(
                    storageRepository = container.storageRepository,
                    accountRepository = container.accountRepository,
                    transactionRepository = container.transactionRepository,
                    sampleSeeder = container.sampleSeeder,
                )
            }
        }
    }
}
