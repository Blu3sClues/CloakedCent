package com.cloakedcent.app.ui.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloakedcent.app.CloakedCentApp
import com.cloakedcent.app.R
import com.cloakedcent.app.domain.model.TransactionFilter
import com.cloakedcent.app.domain.model.TransactionListItem
import com.cloakedcent.app.domain.model.pathLabels
import com.cloakedcent.app.domain.repository.AccountRepository
import com.cloakedcent.app.domain.repository.CategoryRepository
import com.cloakedcent.app.domain.repository.PayeeRepository
import com.cloakedcent.app.domain.repository.TransactionRepository
import com.cloakedcent.app.ui.components.BackTopBar
import com.cloakedcent.app.ui.components.EmptyState
import com.cloakedcent.app.ui.components.TransactionRow
import com.cloakedcent.app.ui.components.toUtcDate
import com.cloakedcent.app.ui.components.toUtcMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class HistoryState(
    val filter: TransactionFilter = TransactionFilter(),
    val results: List<TransactionListItem>? = null,
    val accounts: List<Pair<Long, String>> = emptyList(),
    val categories: List<Pair<Long, String>> = emptyList(),
    val payees: List<Pair<Long, String>> = emptyList(),
)

/** Holds the filter; searching itself is done by [TransactionRepository.search]. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HistoryViewModel(
    /** Set when opened from an account: the screen becomes that account's history. */
    val accountId: Long?,
    transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    payees: PayeeRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    /** Kept separate so typing is never delayed by the search. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val criteria = MutableStateFlow(TransactionFilter(accountId = accountId))

    private val filter = combine(criteria, _query.debounce(QUERY_DEBOUNCE_MS)) { c, q ->
        c.copy(query = q.trim().ifEmpty { null })
    }

    val state: StateFlow<HistoryState> = combine(
        filter,
        filter.flatMapLatest(transactions::search),
        accounts.observeBalances(),
        categories.observeAll(),
        payees.observeAll(),
    ) { f, results, accountList, categoryList, payeeList ->
        val labels = categoryList.pathLabels()
        HistoryState(
            filter = f,
            results = results,
            accounts = accountList.map { it.account.id to it.account.name },
            categories = categoryList.map { it.id to labels.getValue(it.id) }.sortedBy { it.second },
            payees = payeeList.map { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState(TransactionFilter(accountId = accountId)))

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun setAccount(id: Long?) = criteria.update { it.copy(accountId = id) }
    fun setCategory(id: Long?) = criteria.update { it.copy(categoryId = id) }
    fun setPayee(id: Long?) = criteria.update { it.copy(payeeId = id) }
    fun setScheduled(scheduled: Boolean?) = criteria.update { it.copy(scheduled = scheduled) }
    fun setDates(start: LocalDate?, endInclusive: LocalDate?) =
        criteria.update { it.copy(start = start, endInclusive = endInclusive) }

    fun clear() {
        criteria.value = TransactionFilter()
        _query.value = ""
    }

    companion object {
        const val ARG_ACCOUNT_ID = "accountId"
        const val QUERY_DEBOUNCE_MS = 250L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CloakedCentApp).container
                HistoryViewModel(
                    accountId = createSavedStateHandle().get<Long>(ARG_ACCOUNT_ID)?.takeIf { it > 0 },
                    transactions = container.transactionRepository,
                    accounts = container.accountRepository,
                    categories = container.categoryRepository,
                    payees = container.payeeRepository,
                )
            }
        }
    }
}

@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onEditAccount: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val accountId = viewModel.accountId
    HistoryScreen(
        state = state,
        query = query,
        title = accountId?.let { id -> state.accounts.firstOrNull { it.first == id }?.second }
            ?: stringResource(R.string.history_title),
        onEditAccount = accountId?.let { id -> { onEditAccount(id) } },
        onBack = onBack,
        onOpen = onOpen,
        onQueryChange = viewModel::onQueryChange,
        onAccount = viewModel::setAccount,
        onCategory = viewModel::setCategory,
        onPayee = viewModel::setPayee,
        onScheduled = viewModel::setScheduled,
        onDates = viewModel::setDates,
        onClear = viewModel::clear,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryState,
    query: String,
    title: String,
    onEditAccount: (() -> Unit)?,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onAccount: (Long?) -> Unit,
    onCategory: (Long?) -> Unit,
    onPayee: (Long?) -> Unit,
    onScheduled: (Boolean?) -> Unit,
    onDates: (LocalDate?, LocalDate?) -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        topBar = {
            BackTopBar(title, onBack) {
                if (onEditAccount != null) {
                    IconButton(onClick = onEditAccount) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit_account))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.history_search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DateRangeChip(state.filter.start, state.filter.endInclusive, onDates)
                    OptionChip(stringResource(R.string.filter_account), state.accounts, state.filter.accountId, onAccount)
                    OptionChip(stringResource(R.string.filter_category), state.categories, state.filter.categoryId, onCategory)
                    OptionChip(stringResource(R.string.filter_merchant), state.payees, state.filter.payeeId, onPayee)
                    OptionChip(
                        stringResource(R.string.filter_status),
                        listOf(
                            true to stringResource(R.string.status_scheduled),
                            false to stringResource(R.string.filter_status_completed),
                        ),
                        state.filter.scheduled,
                        onScheduled,
                    )
                    if (!state.filter.isEmpty || query.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear_filters)) }
                    }
                }
            }
            val results = state.results
            when {
                results == null -> Unit
                results.isEmpty() -> item {
                    EmptyState(
                        title = stringResource(R.string.history_empty_title),
                        body = stringResource(R.string.history_empty_body),
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {
                    item {
                        Text(
                            stringResource(R.string.history_count, results.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(results, key = { it.id }) { item ->
                        TransactionRow(item, Modifier.clickable { onOpen(item.id) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> OptionChip(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(options.firstOrNull { it.first == selected }?.second ?: label) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.filter_any)) }, onClick = { onSelect(null); expanded = false })
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeChip(start: LocalDate?, end: LocalDate?, onDates: (LocalDate?, LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    FilterChip(
        selected = start != null || end != null,
        onClick = { open = true },
        label = {
            Text(
                if (start == null && end == null) {
                    stringResource(R.string.filter_dates)
                } else {
                    "${start?.format(format) ?: "…"} – ${end?.format(format) ?: "…"}"
                },
            )
        },
        trailingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
    )
    if (open) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start?.toUtcMillis(),
            initialSelectedEndDateMillis = end?.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onDates(pickerState.selectedStartDateMillis?.toUtcDate(), pickerState.selectedEndDateMillis?.toUtcDate())
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { onDates(null, null); open = false }) { Text(stringResource(R.string.filter_any)) }
            },
        ) { DateRangePicker(pickerState, Modifier.height(480.dp)) }
    }
}
