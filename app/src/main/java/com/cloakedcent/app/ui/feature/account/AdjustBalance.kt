package com.cloakedcent.app.ui.feature.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
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
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.AdjustmentKind
import com.cloakedcent.app.domain.model.NewAdjustment
import com.cloakedcent.app.domain.repository.AccountRepository
import com.cloakedcent.app.domain.repository.TransactionRepository
import com.cloakedcent.app.ui.components.AmountInput
import com.cloakedcent.app.ui.components.BackTopBar
import com.cloakedcent.app.ui.components.DateField
import com.cloakedcent.app.ui.components.MoneyField
import com.cloakedcent.app.ui.components.MoneyText
import com.cloakedcent.app.ui.components.ledgerErrorMessage
import com.cloakedcent.app.ui.theme.MoneyTextStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class AdjustBalanceState(
    val date: LocalDate,
    val accountName: String = "",
    val currentBalance: Money? = null,
    val increase: Boolean = true,
    val amount: String = "",
    val kind: AdjustmentKind = AdjustmentKind.BALANCE_ONLY,
    val note: String = "",
    val amountInvalid: Boolean = false,
    @StringRes val error: Int? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
) {
    /** Reporting choices that make sense for the direction. */
    val kindOptions: List<AdjustmentKind>
        get() = listOf(AdjustmentKind.BALANCE_ONLY, if (increase) AdjustmentKind.INCOME else AdjustmentKind.EXPENSE)

    /** Signed change, or null while the amount is not valid. */
    val change: Money?
        get() = AmountInput.parse(amount)?.abs()?.let { if (increase) it else -it }

    /** Display-only preview; the ledger computes the real balance from entries. */
    val previewBalance: Money?
        get() = currentBalance?.let { current -> change?.let { runCatching { current + it }.getOrNull() } }
}

/** Collects the adjustment; the ledger validates and records it as a new entry. */
class AdjustBalanceViewModel(
    private val accountId: Long,
    savedState: SavedStateHandle,
    accounts: AccountRepository,
    private val transactions: TransactionRepository,
    clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val requestId: String =
        savedState.get<String>(KEY_REQUEST_ID) ?: UUID.randomUUID().toString().also { savedState[KEY_REQUEST_ID] = it }

    private val _state = MutableStateFlow(AdjustBalanceState(date = LocalDate.now(clock)))
    val state: StateFlow<AdjustBalanceState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accounts.observeBalances().collect { balances ->
                val balance = balances.firstOrNull { it.account.id == accountId }
                _state.update {
                    if (balance == null) {
                        it.copy(error = R.string.error_account_unavailable)
                    } else {
                        it.copy(accountName = balance.account.name, currentBalance = balance.current)
                    }
                }
            }
        }
    }

    fun onDirectionChange(increase: Boolean) = _state.update {
        val next = it.copy(increase = increase, error = null)
        if (it.kind in next.kindOptions) next else next.copy(kind = AdjustmentKind.BALANCE_ONLY)
    }

    fun onAmountChange(text: String) = _state.update { it.copy(amount = text, amountInvalid = false, error = null) }
    fun onKindChange(kind: AdjustmentKind) = _state.update { it.copy(kind = kind, error = null) }
    fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }
    fun onNoteChange(text: String) = _state.update { it.copy(note = text) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val change = s.change
        if (change == null) {
            _state.update { it.copy(amountInvalid = true) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                transactions.adjustBalance(
                    NewAdjustment(requestId, accountId, change, s.kind, s.date, s.note.trim().ifEmpty { null }),
                )
                _state.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = ledgerErrorMessage(e)) }
            }
        }
    }

    companion object {
        private const val KEY_REQUEST_ID = "requestId"

        val Factory = viewModelFactory {
            initializer {
                val handle = createSavedStateHandle()
                val container = (this[APPLICATION_KEY] as CloakedCentApp).container
                AdjustBalanceViewModel(
                    accountId = handle.get<Long>(AccountEditorViewModel.ARG_ACCOUNT_ID) ?: 0L,
                    savedState = handle,
                    accounts = container.accountRepository,
                    transactions = container.transactionRepository,
                )
            }
        }
    }
}

@Composable
fun AdjustBalanceRoute(
    onDone: () -> Unit,
    viewModel: AdjustBalanceViewModel = viewModel(factory = AdjustBalanceViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    AdjustBalanceScreen(
        state = state,
        onBack = onDone,
        onDirectionChange = viewModel::onDirectionChange,
        onAmountChange = viewModel::onAmountChange,
        onKindChange = viewModel::onKindChange,
        onDateChange = viewModel::onDateChange,
        onNoteChange = viewModel::onNoteChange,
        onSave = viewModel::save,
    )
}

@Composable
fun AdjustBalanceScreen(
    state: AdjustBalanceState,
    onBack: () -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onAmountChange: (String) -> Unit,
    onKindChange: (AdjustmentKind) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.adjust_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalancePreview(state)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to R.string.adjust_increase, false to R.string.adjust_decrease).forEachIndexed { index, (increase, label) ->
                    SegmentedButton(
                        selected = state.increase == increase,
                        onClick = { onDirectionChange(increase) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(label)) }
                }
            }
            MoneyField(
                value = state.amount,
                onValueChange = onAmountChange,
                label = stringResource(R.string.adjust_amount),
                isError = state.amountInvalid,
                supportingText = stringResource(R.string.error_amount_invalid).takeIf { state.amountInvalid },
            )
            Text(stringResource(R.string.adjust_report_as), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                state.kindOptions.forEach { kind ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = kind == state.kind, role = Role.RadioButton) { onKindChange(kind) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == state.kind, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(kindLabel(kind)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(kindHint(kind)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            DateField(stringResource(R.string.field_date), state.date, onDateChange)
            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChange,
                label = { Text(stringResource(R.string.field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSave, enabled = !state.saving && state.currentBalance != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.adjust_save))
            }
            Text(
                stringResource(R.string.adjust_audit_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BalancePreview(state: AdjustBalanceState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.accountName, style = MaterialTheme.typography.titleMedium)
            state.currentBalance?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.adjust_current) + " ", style = MaterialTheme.typography.bodyMedium)
                    MoneyText(it)
                }
            }
            state.previewBalance?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.adjust_new) + " ", style = MaterialTheme.typography.bodyMedium)
                    MoneyText(it, style = MoneyTextStyles.row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                }
            }
        }
    }
}

@StringRes
fun kindLabel(kind: AdjustmentKind): Int = when (kind) {
    AdjustmentKind.BALANCE_ONLY -> R.string.adjustment_kind_balance_only
    AdjustmentKind.INCOME -> R.string.adjustment_kind_income
    AdjustmentKind.EXPENSE -> R.string.adjustment_kind_expense
}

@StringRes
private fun kindHint(kind: AdjustmentKind): Int = when (kind) {
    AdjustmentKind.BALANCE_ONLY -> R.string.adjustment_hint_balance_only
    AdjustmentKind.INCOME -> R.string.adjustment_hint_income
    AdjustmentKind.EXPENSE -> R.string.adjustment_hint_expense
}
