package com.cloakedcent.app.ui.feature.recurring

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.Recurrence
import com.cloakedcent.app.domain.model.RecurrenceFrequency
import com.cloakedcent.app.domain.model.RecurringDraft
import com.cloakedcent.app.domain.model.RecurringKind
import com.cloakedcent.app.domain.model.RecurringSchedule
import com.cloakedcent.app.domain.model.ScheduleState
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.domain.model.UpcomingItem
import com.cloakedcent.app.domain.model.canSendTransfers
import com.cloakedcent.app.domain.model.pathLabels
import com.cloakedcent.app.domain.repository.AccountRepository
import com.cloakedcent.app.domain.repository.CategoryRepository
import com.cloakedcent.app.domain.repository.PayeeRepository
import com.cloakedcent.app.domain.repository.RecurringRepository
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
import com.cloakedcent.app.ui.feature.planning.NotificationPermissionCard
import com.cloakedcent.app.ui.feature.planning.frequencyLabel
import com.cloakedcent.app.ui.feature.planning.recurringKindLabel
import com.cloakedcent.app.ui.feature.transaction.typeLabel
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val ARG_SCHEDULE_ID = "scheduleId"

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

// ------------------------------------------------------------------ List + detail

class RecurringViewModel(
    private val recurring: RecurringRepository,
    val scheduleId: Long? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    val today: LocalDate get() = LocalDate.now(clock)

    val schedules: StateFlow<List<RecurringSchedule>?> = recurring.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun pause() = act { recurring.pause(it) }
    fun resume() = act { recurring.resume(it, today) }
    fun cancel() = act { recurring.cancel(it) }
    fun reenable() = act { recurring.reenable(it, today) }

    private val _deleted = MutableStateFlow(false)

    /** Set once the schedule has been erased; the detail screen then closes. */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun delete() = act {
        recurring.delete(it)
        _deleted.value = true
    }
    fun skipNext() = act { if (recurring.skipNext(it, today) == null) _message.value = R.string.recurring_nothing_to_skip }

    private fun act(action: suspend (Long) -> Unit) {
        val id = scheduleId ?: return
        _message.value = null
        viewModelScope.launch {
            try {
                action(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = ledgerErrorMessage(e)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                RecurringViewModel(
                    (this[APPLICATION_KEY] as CloakedCentApp).container.recurringRepository,
                    createSavedStateHandle().get<Long>(ARG_SCHEDULE_ID)?.takeIf { it > 0 },
                )
            }
        }
    }
}

@Composable
fun RecurringListRoute(
    onBack: (() -> Unit)?,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onUpcoming: () -> Unit,
    viewModel: RecurringViewModel = viewModel(factory = RecurringViewModel.Factory),
) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.recurring_title), onBack) {
                IconButton(onClick = onUpcoming) { Icon(painterResource(R.drawable.ic_calendar), stringResource(R.string.upcoming_title)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(painterResource(R.drawable.ic_add), null) }, text = { Text(stringResource(R.string.recurring_add)) })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NotificationPermissionCard() }
            if (schedules?.isEmpty() == true) {
                item { EmptyState(stringResource(R.string.recurring_empty_title), stringResource(R.string.recurring_empty_body)) }
            }
            items(schedules.orEmpty(), key = { it.id }) { schedule ->
                ScheduleCard(schedule, viewModel.today, Modifier.clickable { onOpen(schedule.id) })
            }
        }
    }
}

@Composable
private fun ScheduleCard(schedule: RecurringSchedule, today: LocalDate, modifier: Modifier = Modifier) {
    val next = if (schedule.state == ScheduleState.ACTIVE) Recurrence.next(schedule, today.minusDays(1), 1).firstOrNull() else null
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(schedule.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        stringResource(recurringKindLabel(schedule.kind)),
                        frequencyText(schedule),
                        next?.let { stringResource(R.string.recurring_next, it.format(dateFormat)) },
                        stringResource(stateLabel(schedule.state)).takeIf { schedule.state != ScheduleState.ACTIVE },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(
                if (schedule.type == TransactionType.EXPENSE) -schedule.amount else schedule.amount,
                signed = schedule.type != TransactionType.TRANSFER,
            )
        }
    }
}

@Composable
private fun frequencyText(schedule: RecurringSchedule): String =
    stringResource(frequencyLabel(schedule.frequency), schedule.interval.toString()) +
        if (schedule.variableAmount) " · " + stringResource(R.string.recurring_variable_short) else ""

@StringRes
private fun stateLabel(state: ScheduleState) = when (state) {
    ScheduleState.ACTIVE -> R.string.recurring_state_active
    ScheduleState.PAUSED -> R.string.recurring_state_paused
    ScheduleState.CANCELLED -> R.string.recurring_state_cancelled
}

@Composable
fun RecurringDetailRoute(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: RecurringViewModel = viewModel(factory = RecurringViewModel.Factory),
) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) onBack() }
    RecurringDetailScreen(
        schedule = schedules?.firstOrNull { it.id == viewModel.scheduleId },
        today = viewModel.today,
        message = message,
        onBack = onBack,
        onEdit = onEdit,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onSkip = viewModel::skipNext,
        onCancel = viewModel::cancel,
        onReenable = viewModel::reenable,
        onDelete = viewModel::delete,
    )
}

@Composable
fun RecurringDetailScreen(
    schedule: RecurringSchedule?,
    today: LocalDate,
    @StringRes message: Int?,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onReenable: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar(schedule?.name ?: stringResource(R.string.recurring_title), onBack) {
                if (schedule != null && schedule.state != ScheduleState.CANCELLED) {
                    IconButton(onClick = { onEdit(schedule.id) }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit)) }
                }
            }
        },
    ) { padding ->
        if (schedule == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScheduleCard(schedule, today)
            message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (schedule.state != ScheduleState.CANCELLED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (schedule.state == ScheduleState.ACTIVE) {
                        OutlinedButton(onPause, Modifier.weight(1f)) { Text(stringResource(R.string.action_pause)) }
                        OutlinedButton(onSkip, Modifier.weight(1f)) { Text(stringResource(R.string.action_skip_next)) }
                    } else {
                        OutlinedButton(onResume, Modifier.weight(1f)) { Text(stringResource(R.string.action_resume)) }
                    }
                }
                OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel_schedule), color = MaterialTheme.colorScheme.error)
                }
            } else {
                // A cancelled schedule is either brought back or erased.
                Text(stringResource(R.string.recurring_cancelled_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onReenable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_reenable_schedule)) }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_delete_schedule), color = MaterialTheme.colorScheme.error)
                }
            }
            // Only the next occurrence; a schedule that has ended has none to show.
            val next = if (schedule.state == ScheduleState.ACTIVE) Recurrence.next(schedule, today.minusDays(1), 1).firstOrNull() else null
            if (next != null) {
                SectionHeader(stringResource(R.string.recurring_next_date))
                Text(next.format(dateFormat), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
            Text(stringResource(R.string.recurring_history_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmCancel) {
        ConfirmDialog(
            stringResource(R.string.confirm_cancel_schedule_title),
            stringResource(R.string.confirm_cancel_schedule_body),
            stringResource(R.string.action_cancel_schedule),
            onConfirm = { confirmCancel = false; onCancel() },
            onDismiss = { confirmCancel = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.confirm_delete_schedule_title),
            stringResource(R.string.confirm_delete_schedule_body),
            stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ------------------------------------------------------------------ Upcoming

class UpcomingViewModel(recurring: RecurringRepository, clock: Clock = Clock.systemDefaultZone()) : ViewModel() {
    val today: LocalDate = LocalDate.now(clock)
    val items: StateFlow<List<UpcomingItem>?> = recurring.observeUpcoming(today, UPCOMING_DAYS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        const val UPCOMING_DAYS = 60
        val Factory = viewModelFactory {
            initializer { UpcomingViewModel((this[APPLICATION_KEY] as CloakedCentApp).container.recurringRepository) }
        }
    }
}

@Composable
fun UpcomingRoute(onBack: () -> Unit, onOpenTransaction: (Long) -> Unit, viewModel: UpcomingViewModel = viewModel(factory = UpcomingViewModel.Factory)) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    UpcomingScreen(items, onBack, onOpenTransaction, viewModel.today)
}

/**
 * A month calendar marking days with scheduled or projected items; the list below shows
 * only the selected day. Months can be browsed from the oldest overdue item to the end
 * of the projection window.
 */
@Composable
fun UpcomingScreen(
    items: List<UpcomingItem>?,
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    var selectedDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedDay)
    var shownMonth by rememberSaveable { mutableIntStateOf(monthIndex(YearMonth.from(today))) }
    val month = YearMonth.of(shownMonth / 12, shownMonth % 12 + 1)

    val all = items.orEmpty()
    val markers = remember(all) {
        all.groupBy { it.date }.mapValues { (_, group) -> DayMarker(group.size, group.any { it.overdue }) }
    }
    val firstMonth = monthIndex(YearMonth.from(minOf(today, all.minOfOrNull { it.date } ?: today)))
    val lastMonth = monthIndex(YearMonth.from(today.plusDays(UpcomingViewModel.UPCOMING_DAYS.toLong())))
    val overdue = all.filter { it.overdue }
    val select = { date: LocalDate ->
        selectedDay = date.toEpochDay()
        shownMonth = monthIndex(YearMonth.from(date))
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.upcoming_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    MonthCalendar(
                        month = month,
                        selected = selected,
                        today = today,
                        markers = markers,
                        onSelect = { selectedDay = it.toEpochDay() },
                        onPreviousMonth = { shownMonth -= 1 }.takeIf { shownMonth > firstMonth },
                        onNextMonth = { shownMonth += 1 }.takeIf { shownMonth < lastMonth },
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (overdue.isNotEmpty()) {
                item {
                    // Overdue items sit on past days; this jumps to the oldest one.
                    Card(
                        onClick = { select(overdue.minOf { it.date }) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            stringResource(R.string.upcoming_overdue, overdue.size),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
            if (items?.isEmpty() == true) {
                item { EmptyState(stringResource(R.string.upcoming_empty_title), stringResource(R.string.upcoming_empty_body), Modifier.padding(top = 8.dp)) }
            } else if (items != null) {
                val onDay = all.filter { it.date == selected }
                item { SectionHeader(selected.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))) }
                if (onDay.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.upcoming_nothing_on_day),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(onDay) { UpcomingRow(it, onOpenTransaction) }
            }
            item { Text(stringResource(R.string.upcoming_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

private fun monthIndex(month: YearMonth) = month.year * 12 + month.monthValue - 1

@Composable
private fun UpcomingRow(item: UpcomingItem, onOpenTransaction: (Long) -> Unit) {
    val colors = CloakedCentTheme.ledgerColors
    Card(
        Modifier.fillMaxWidth().then(if (item.transactionId != null) Modifier.clickable { onOpenTransaction(item.transactionId) } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (item.overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(
                        item.date.format(dateFormat),
                        stringResource(typeLabel(item.type)),
                        stringResource(
                            when {
                                item.overdue -> R.string.upcoming_state_overdue
                                item.transactionId != null -> R.string.status_scheduled
                                else -> R.string.upcoming_state_projected
                            },
                        ),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            MoneyText(item.amount, signed = item.type != TransactionType.TRANSFER, color = if (item.type == TransactionType.TRANSFER) colors.scheduled else androidx.compose.ui.graphics.Color.Unspecified)
        }
    }
}

// ------------------------------------------------------------------ Editor

data class RecurringEditorState(
    val isNew: Boolean,
    val name: String = "",
    val kind: RecurringKind = RecurringKind.OTHER,
    val type: TransactionType = TransactionType.EXPENSE,
    val accounts: List<Pair<Long, String>> = emptyList(),
    val cardAccountIds: Set<Long> = emptySet(),
    val categories: List<Pair<Long, String>> = emptyList(),
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val amount: String = "",
    val variableAmount: Boolean = false,
    val categoryId: Long? = null,
    val payee: String = "",
    val note: String = "",
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val interval: String = "1",
    val startDate: LocalDate,
    val hasEnd: Boolean = false,
    val endDate: LocalDate,
    val amountInvalid: Boolean = false,
    val accountMissing: Boolean = false,
    val intervalInvalid: Boolean = false,
    @StringRes val error: Int? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
) {
    val isCustom get() = frequency.name.startsWith("CUSTOM")

    /** Credit cards cannot send a transfer; one already saved stays visible until it is changed. */
    val sourceAccounts: List<Pair<Long, String>>
        get() = if (type == TransactionType.TRANSFER) accounts.filter { it.first !in cardAccountIds || it.first == accountId } else accounts

    /** This form with a new [type], dropping a source that type cannot use. */
    fun withType(type: TransactionType) =
        copy(type = type, accountId = accountId.takeUnless { type == TransactionType.TRANSFER && it in cardAccountIds })
}

class RecurringEditorViewModel(
    private val scheduleId: Long?,
    private val recurring: RecurringRepository,
    accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val payees: PayeeRepository,
    clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val today = LocalDate.now(clock)
    private val _state = MutableStateFlow(RecurringEditorState(isNew = scheduleId == null, startDate = today, endDate = today.plusYears(1)))
    val state: StateFlow<RecurringEditorState> = _state.asStateFlow()
    private var allCategories = emptyList<com.cloakedcent.app.domain.model.Category>()

    init {
        viewModelScope.launch {
            combine(accounts.observeBalances(), categories.observeAll()) { a, c -> a to c }.collect { (a, c) ->
                allCategories = c
                _state.update {
                    it.copy(
                        accounts = a.map { b -> b.account.id to b.account.name },
                        cardAccountIds = a.filterNot { b -> b.account.type.canSendTransfers }.map { b -> b.account.id }.toSet(),
                        categories = categoryOptions(it.type),
                    )
                }
            }
        }
        if (scheduleId != null) viewModelScope.launch {
            val s = recurring.get(scheduleId) ?: return@launch
            val payeeName = s.payeeId?.let { id -> payees.observeAll().first().firstOrNull { it.id == id }?.name }.orEmpty()
            _state.update {
                it.copy(
                    name = s.name, kind = s.kind, type = s.type, accountId = s.accountId, toAccountId = s.toAccountId,
                    amount = AmountInput.format(s.amount), variableAmount = s.variableAmount, categoryId = s.categoryId,
                    payee = payeeName, note = s.note.orEmpty(), frequency = s.frequency, interval = s.interval.toString(),
                    startDate = s.startDate, hasEnd = s.endDate != null, endDate = s.endDate ?: it.endDate,
                    categories = categoryOptions(s.type),
                )
            }
        }
    }

    private fun categoryOptions(type: TransactionType): List<Pair<Long, String>> {
        val kind = when (type) {
            TransactionType.INCOME -> CategoryKind.INCOME
            TransactionType.EXPENSE -> CategoryKind.EXPENSE
            else -> return emptyList()
        }
        val labels = allCategories.pathLabels()
        return allCategories.filter { it.kind == kind }.map { it.id to labels.getValue(it.id) }.sortedBy { it.second }
    }

    fun update(change: (RecurringEditorState) -> RecurringEditorState) = _state.update { old ->
        val next = change(old).copy(error = null, amountInvalid = false, accountMissing = false, intervalInvalid = false)
        if (next.type != old.type) next.copy(categoryId = null, categories = categoryOptions(next.type)) else next
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val amount = AmountInput.parse(s.amount)
        val interval = if (s.isCustom) s.interval.trim().toIntOrNull()?.takeIf { it >= 1 } else 1
        val accountMissing = s.accountId == null || (s.type == TransactionType.TRANSFER && s.toAccountId == null)
        if (amount == null || interval == null || accountMissing) {
            _state.update { it.copy(amountInvalid = amount == null, intervalInvalid = interval == null, accountMissing = accountMissing) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val payeeId = s.payee.trim().takeIf { it.isNotEmpty() && s.type != TransactionType.TRANSFER }?.let { payees.getOrCreate(it) }
                val draft = RecurringDraft(
                    name = s.name, kind = s.kind, type = s.type, accountId = s.accountId, toAccountId = s.toAccountId,
                    amount = amount, variableAmount = s.variableAmount, categoryId = s.categoryId, payeeId = payeeId,
                    note = s.note, frequency = s.frequency, interval = interval, startDate = s.startDate,
                    endDate = s.endDate.takeIf { s.hasEnd },
                )
                if (scheduleId == null) recurring.create(draft) else recurring.update(scheduleId, draft)
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
                val c = (this[APPLICATION_KEY] as CloakedCentApp).container
                RecurringEditorViewModel(
                    createSavedStateHandle().get<Long>(ARG_SCHEDULE_ID)?.takeIf { it > 0 },
                    c.recurringRepository, c.accountRepository, c.categoryRepository, c.payeeRepository,
                )
            }
        }
    }
}

@Composable
fun RecurringEditorRoute(onDone: () -> Unit, viewModel: RecurringEditorViewModel = viewModel(factory = RecurringEditorViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    RecurringEditorScreen(state, onDone, viewModel::update, viewModel::save)
}

@Composable
fun RecurringEditorScreen(
    state: RecurringEditorState,
    onBack: () -> Unit,
    onChange: ((RecurringEditorState) -> RecurringEditorState) -> Unit,
    onSave: () -> Unit,
) {
    val isTransfer = state.type == TransactionType.TRANSFER
    Scaffold(topBar = { BackTopBar(stringResource(if (state.isNew) R.string.recurring_new_title else R.string.recurring_edit_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isNew) Text(stringResource(R.string.recurring_edit_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val types = listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                types.forEachIndexed { index, type ->
                    SegmentedButton(state.type == type, { onChange { it.withType(type) } }, SegmentedButtonDefaults.itemShape(index, types.size)) {
                        Text(stringResource(typeLabel(type)))
                    }
                }
            }
            OutlinedTextField(state.name, { v -> onChange { it.copy(name = v) } }, label = { Text(stringResource(R.string.recurring_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PickerField(stringResource(R.string.recurring_kind), RecurringKind.entries.map { it to stringResource(recurringKindLabel(it)) }, state.kind, { v -> onChange { it.copy(kind = v) } })
            PickerField(
                stringResource(if (isTransfer) R.string.field_from_account else R.string.field_account), state.sourceAccounts, state.accountId,
                { v -> onChange { it.copy(accountId = v) } }, isError = state.accountMissing && state.accountId == null,
            )
            if (isTransfer) {
                PickerField(stringResource(R.string.field_to_account), state.accounts, state.toAccountId, { v -> onChange { it.copy(toAccountId = v) } }, isError = state.accountMissing && state.toAccountId == null)
            }
            MoneyField(
                state.amount, { v -> onChange { it.copy(amount = v) } },
                stringResource(if (state.variableAmount) R.string.recurring_amount_estimate else R.string.field_amount),
                isError = state.amountInvalid, supportingText = stringResource(R.string.error_amount_invalid).takeIf { state.amountInvalid },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.recurring_variable), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.recurring_variable_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.variableAmount, { v -> onChange { it.copy(variableAmount = v) } })
            }
            if (!isTransfer) {
                PickerField(
                    stringResource(R.string.field_category),
                    listOf<Pair<Long?, String>>(null to stringResource(R.string.category_none)) + state.categories,
                    state.categoryId, { v -> onChange { it.copy(categoryId = v) } },
                )
                OutlinedTextField(state.payee, { v -> onChange { it.copy(payee = v) } }, label = { Text(stringResource(R.string.field_payee)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            PickerField(
                stringResource(R.string.recurring_frequency),
                RecurrenceFrequency.entries.map { it to stringResource(frequencyLabel(it), "n") },
                state.frequency, { v -> onChange { it.copy(frequency = v) } },
            )
            if (state.isCustom) {
                OutlinedTextField(
                    state.interval, { v -> onChange { it.copy(interval = v) } }, label = { Text(stringResource(R.string.recurring_interval)) },
                    isError = state.intervalInvalid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
            DateField(stringResource(R.string.recurring_first_date), state.startDate, { v -> onChange { it.copy(startDate = v) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recurring_has_end), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(state.hasEnd, { v -> onChange { it.copy(hasEnd = v) } })
            }
            if (state.hasEnd) DateField(stringResource(R.string.recurring_end_date), state.endDate, { v -> onChange { it.copy(endDate = v) } })
            OutlinedTextField(state.note, { v -> onChange { it.copy(note = v) } }, label = { Text(stringResource(R.string.field_note)) }, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Button(onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
}
