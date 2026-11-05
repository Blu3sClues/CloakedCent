package com.cloakedcent.app.ui.feature.budget

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.cloakedcent.app.domain.model.BudgetCalculator
import com.cloakedcent.app.domain.model.BudgetDraft
import com.cloakedcent.app.domain.model.BudgetPeriodType
import com.cloakedcent.app.domain.model.BudgetProgress
import com.cloakedcent.app.domain.model.BudgetState
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.pathLabels
import com.cloakedcent.app.domain.repository.BudgetRepository
import com.cloakedcent.app.domain.repository.CategoryRepository
import com.cloakedcent.app.ui.components.AmountInput
import com.cloakedcent.app.ui.components.BackTopBar
import com.cloakedcent.app.ui.components.ConfirmDialog
import com.cloakedcent.app.ui.components.DateField
import com.cloakedcent.app.ui.components.EmptyState
import com.cloakedcent.app.ui.components.MoneyField
import com.cloakedcent.app.ui.components.MoneyText
import com.cloakedcent.app.ui.components.PickerField
import com.cloakedcent.app.ui.components.SectionHeader
import com.cloakedcent.app.ui.components.ledgerErrorMessage
import com.cloakedcent.app.ui.feature.planning.BudgetProgressRow
import com.cloakedcent.app.ui.feature.planning.NotificationPermissionCard
import com.cloakedcent.app.ui.feature.planning.label
import com.cloakedcent.app.ui.feature.planning.periodLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

const val ARG_BUDGET_ID = "budgetId"

private val CloakedCentApp.budgets get() = container.budgetRepository

// ------------------------------------------------------------------ List + detail

class BudgetsViewModel(
    private val budgets: BudgetRepository,
    private val budgetId: Long? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    val progress: StateFlow<List<BudgetProgress>?> = budgets.observeProgress(LocalDate.now(clock))
        .map { list -> if (budgetId == null) list else list.filter { it.budget.id == budgetId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    private val _archived = MutableStateFlow(false)
    val archived: StateFlow<Boolean> = _archived.asStateFlow()

    fun pause() = act { budgets.setState(it, BudgetState.PAUSED) }
    fun resume() = act { budgets.setState(it, BudgetState.ACTIVE) }
    fun restart() = act { budgets.restart(it, LocalDate.now(clock)) }
    fun archive() = act { budgets.setState(it, BudgetState.ARCHIVED); _archived.value = true }

    private fun act(action: suspend (Long) -> Unit) {
        val id = budgetId ?: return
        viewModelScope.launch {
            try {
                action(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = ledgerErrorMessage(e)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                BudgetsViewModel(
                    budgets = (this[APPLICATION_KEY] as CloakedCentApp).budgets,
                    budgetId = createSavedStateHandle().get<Long>(ARG_BUDGET_ID)?.takeIf { it > 0 },
                )
            }
        }
    }
}

@Composable
fun BudgetsRoute(
    onBack: (() -> Unit)?,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    viewModel: BudgetsViewModel = viewModel(factory = BudgetsViewModel.Factory),
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    BudgetsScreen(progress, onBack, onOpen, onAdd)
}

@Composable
fun BudgetsScreen(progress: List<BudgetProgress>?, onBack: (() -> Unit)?, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.budgets_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text(stringResource(R.string.budget_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NotificationPermissionCard() }
            if (progress != null && progress.isEmpty()) {
                item { EmptyState(stringResource(R.string.budgets_empty_title), stringResource(R.string.budgets_empty_body)) }
            }
            items(progress.orEmpty(), key = { it.budget.id }) { item ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpen(item.budget.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) { BudgetProgressRow(item, Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
fun BudgetDetailRoute(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: BudgetsViewModel = viewModel(factory = BudgetsViewModel.Factory),
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val archived by viewModel.archived.collectAsStateWithLifecycle()
    LaunchedEffect(archived) { if (archived) onBack() }
    val item = progress?.firstOrNull()
    var confirmArchive by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar(item?.budget?.name ?: stringResource(R.string.budgets_title), onBack) {
                if (item != null) {
                    IconButton(onClick = { onEdit(item.budget.id) }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit)) }
                }
            }
        },
    ) { padding ->
        if (item == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    BudgetProgressRow(item.copy(depth = 0))
                    item.current?.let { Text(it.range.label(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.budget.state) {
                    BudgetState.ACTIVE -> OutlinedButton(onClick = viewModel::pause, Modifier.weight(1f)) { Text(stringResource(R.string.action_pause)) }
                    else -> OutlinedButton(onClick = viewModel::resume, Modifier.weight(1f)) { Text(stringResource(R.string.action_resume)) }
                }
                OutlinedButton(onClick = viewModel::restart, Modifier.weight(1f)) { Text(stringResource(R.string.action_restart)) }
            }
            OutlinedButton(onClick = { confirmArchive = true }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_archive_budget), color = MaterialTheme.colorScheme.error)
            }
            SectionHeader(stringResource(R.string.budget_history))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                item.history.forEachIndexed { index, period ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(period.range.label(), style = MaterialTheme.typography.bodyMedium)
                            Row {
                                Text(stringResource(R.string.budget_history_spent) + " ", style = MaterialTheme.typography.bodySmall)
                                MoneyText(period.spent, style = MaterialTheme.typography.bodySmall)
                                Text(" / ", style = MaterialTheme.typography.bodySmall)
                                MoneyText(period.allocated, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        MoneyText(period.remaining, signed = true)
                    }
                }
            }
        }
    }
    if (confirmArchive) {
        ConfirmDialog(
            stringResource(R.string.confirm_archive_budget_title),
            stringResource(R.string.confirm_archive_budget_body),
            stringResource(R.string.action_archive_budget),
            onConfirm = { confirmArchive = false; viewModel.archive() },
            onDismiss = { confirmArchive = false },
        )
    }
}

// ------------------------------------------------------------------ Editor

data class BudgetEditorState(
    val isNew: Boolean,
    val name: String = "",
    val categoryId: Long? = null,
    val categories: List<Pair<Long, String>> = emptyList(),
    val amount: String = "",
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val rollover: Boolean = false,
    val thresholds: String = "50, 75, 90, 100",
    val amountInvalid: Boolean = false,
    val categoryMissing: Boolean = false,
    val thresholdsInvalid: Boolean = false,
    @StringRes val error: Int? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
)

/** Form state only; the repository validates and stores. */
class BudgetEditorViewModel(
    private val budgetId: Long?,
    private val budgets: BudgetRepository,
    categories: CategoryRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val today = LocalDate.now(clock)
    private val _state = MutableStateFlow(
        BudgetEditorState(isNew = budgetId == null, startDate = today.withDayOfMonth(1), endDate = today.plusMonths(1)),
    )
    val state: StateFlow<BudgetEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val all = categories.observeAll().first()
            val labels = all.pathLabels()
            _state.update { s ->
                s.copy(categories = all.filter { it.kind == CategoryKind.EXPENSE }.map { it.id to labels.getValue(it.id) }.sortedBy { it.second })
            }
            budgetId?.let { budgets.get(it) }?.let { b ->
                _state.update {
                    it.copy(
                        name = b.name, categoryId = b.categoryId, amount = AmountInput.format(b.currentLimit),
                        periodType = b.periodType, startDate = b.startDate, endDate = b.endDate ?: it.endDate,
                        rollover = b.rollover, thresholds = b.alertThresholds.joinToString(", "),
                    )
                }
            }
        }
    }

    fun update(change: (BudgetEditorState) -> BudgetEditorState) =
        _state.update { change(it).copy(error = null, amountInvalid = false, categoryMissing = false, thresholdsInvalid = false) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val amount = AmountInput.parse(s.amount)
        val thresholds = BudgetCalculator.parseThresholds(s.thresholds)
        if (amount == null || s.categoryId == null || thresholds == null) {
            _state.update { it.copy(amountInvalid = amount == null, categoryMissing = s.categoryId == null, thresholdsInvalid = thresholds == null) }
            return
        }
        val draft = BudgetDraft(s.name, s.categoryId, amount, s.periodType, s.startDate, s.endDate, s.rollover, thresholds)
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                if (budgetId == null) budgets.create(draft) else budgets.update(budgetId, draft, LocalDate.now(clock))
                _state.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = ledgerErrorMessage(e)) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CloakedCentApp).container
                BudgetEditorViewModel(
                    budgetId = createSavedStateHandle().get<Long>(ARG_BUDGET_ID)?.takeIf { it > 0 },
                    budgets = container.budgetRepository,
                    categories = container.categoryRepository,
                )
            }
        }
    }
}

@Composable
fun BudgetEditorRoute(onDone: () -> Unit, viewModel: BudgetEditorViewModel = viewModel(factory = BudgetEditorViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    BudgetEditorScreen(state, onDone, viewModel::update, viewModel::save)
}

@Composable
fun BudgetEditorScreen(
    state: BudgetEditorState,
    onBack: () -> Unit,
    onChange: ((BudgetEditorState) -> BudgetEditorState) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(stringResource(if (state.isNew) R.string.budget_new_title else R.string.budget_edit_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                state.name, { v -> onChange { it.copy(name = v) } },
                label = { Text(stringResource(R.string.budget_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            PickerField(
                stringResource(R.string.field_category), state.categories, state.categoryId, { v -> onChange { it.copy(categoryId = v) } },
                isError = state.categoryMissing,
                supportingText = stringResource(R.string.budget_category_hint),
            )
            MoneyField(
                state.amount, { v -> onChange { it.copy(amount = v) } }, stringResource(R.string.budget_limit),
                isError = state.amountInvalid,
                supportingText = stringResource(R.string.error_amount_invalid).takeIf { state.amountInvalid },
            )
            PickerField(
                stringResource(R.string.budget_period), BudgetPeriodType.entries.map { it to stringResource(periodLabel(it)) },
                state.periodType, { v -> onChange { it.copy(periodType = v) } },
            )
            DateField(stringResource(R.string.budget_start), state.startDate, { v -> onChange { it.copy(startDate = v) } })
            if (state.periodType == BudgetPeriodType.CUSTOM) {
                DateField(stringResource(R.string.budget_end), state.endDate, { v -> onChange { it.copy(endDate = v) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.budget_rollover), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.budget_rollover_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.rollover, { v -> onChange { it.copy(rollover = v) } })
            }
            OutlinedTextField(
                state.thresholds, { v -> onChange { it.copy(thresholds = v) } },
                label = { Text(stringResource(R.string.budget_alerts)) },
                supportingText = { Text(stringResource(if (state.thresholdsInvalid) R.string.budget_alerts_invalid else R.string.budget_alerts_hint)) },
                isError = state.thresholdsInvalid, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
}
