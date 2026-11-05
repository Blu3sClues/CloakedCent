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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.isOwed
import com.cloakedcent.app.domain.model.openingBalanceFromInput
import com.cloakedcent.app.domain.repository.AccountRepository
import com.cloakedcent.app.ui.components.AmountInput
import com.cloakedcent.app.ui.components.BackTopBar
import com.cloakedcent.app.ui.components.ConfirmDialog
import com.cloakedcent.app.ui.components.MoneyField
import com.cloakedcent.app.ui.components.PickerField
import com.cloakedcent.app.ui.components.accountTypeLabel
import com.cloakedcent.app.ui.components.ledgerErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditorState(
    val isNew: Boolean,
    val loading: Boolean = !isNew,
    val name: String = "",
    val type: AccountType = AccountType.CHECKING,
    val openingBalance: String = "",
    val includeInTotals: Boolean = true,
    val openingBalanceInvalid: Boolean = false,
    @StringRes val error: Int? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
    val archived: Boolean = false,
)

/** Form state only: every rule is enforced by [AccountRepository]. */
class AccountEditorViewModel(
    val accountId: Long?,
    private val accounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountEditorState(isNew = accountId == null))
    val state: StateFlow<AccountEditorState> = _state.asStateFlow()

    init {
        if (accountId != null) {
            viewModelScope.launch {
                val account = accounts.get(accountId)
                _state.update {
                    if (account == null) {
                        it.copy(loading = false, error = R.string.error_account_unavailable)
                    } else {
                        it.copy(
                            loading = false,
                            name = account.name,
                            type = account.type,
                            includeInTotals = account.includeInTotals,
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onTypeChange(value: AccountType) = _state.update { it.copy(type = value) }
    fun onIncludeInTotalsChange(value: Boolean) = _state.update { it.copy(includeInTotals = value) }
    fun onOpeningBalanceChange(value: String) =
        _state.update { it.copy(openingBalance = value, openingBalanceInvalid = false) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val opening = if (s.openingBalance.isBlank()) Money.ZERO else AmountInput.parse(s.openingBalance)
        if (s.isNew && opening == null) {
            _state.update { it.copy(openingBalanceInvalid = true) }
            return
        }
        run {
            if (accountId == null) {
                accounts.create(s.name, s.type, s.type.openingBalanceFromInput(opening!!), s.includeInTotals)
            } else {
                accounts.update(accountId, s.name, s.type, s.includeInTotals)
            }
        }
    }

    fun archive() {
        val id = accountId ?: return
        run(archiving = true) { accounts.archive(id) }
    }

    private fun run(archiving: Boolean = false, action: suspend () -> Unit) {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(saving = false, finished = true, archived = archiving) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = ledgerErrorMessage(e)) }
            }
        }
    }

    companion object {
        const val ARG_ACCOUNT_ID = "accountId"

        val Factory = viewModelFactory {
            initializer {
                val id = createSavedStateHandle().accountIdArg()
                AccountEditorViewModel(id, (this[APPLICATION_KEY] as CloakedCentApp).container.accountRepository)
            }
        }

        private fun SavedStateHandle.accountIdArg(): Long? = get<Long>(ARG_ACCOUNT_ID)?.takeIf { it > 0 }
    }
}

@Composable
fun AccountEditorRoute(
    onDone: () -> Unit,
    onArchived: () -> Unit,
    onAdjustBalance: (Long) -> Unit,
    viewModel: AccountEditorViewModel = viewModel(factory = AccountEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) {
        if (state.finished) if (state.archived) onArchived() else onDone()
    }
    AccountEditorScreen(
        state = state,
        onBack = onDone,
        onNameChange = viewModel::onNameChange,
        onTypeChange = viewModel::onTypeChange,
        onOpeningBalanceChange = viewModel::onOpeningBalanceChange,
        onIncludeInTotalsChange = viewModel::onIncludeInTotalsChange,
        onSave = viewModel::save,
        onArchive = viewModel::archive,
        onAdjustBalance = { viewModel.accountId?.let(onAdjustBalance) },
    )
}

@Composable
fun AccountEditorScreen(
    state: AccountEditorState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onTypeChange: (AccountType) -> Unit,
    onOpeningBalanceChange: (String) -> Unit,
    onIncludeInTotalsChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onArchive: () -> Unit,
    onAdjustBalance: () -> Unit = {},
) {
    var confirmArchive by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar(
                title = stringResource(if (state.isNew) R.string.account_new_title else R.string.account_edit_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.field_account_name)) },
                singleLine = true,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(
                label = stringResource(R.string.field_account_type),
                options = AccountType.entries.map { it to stringResource(accountTypeLabel(it)) },
                selected = state.type,
                onSelect = onTypeChange,
            )
            // The opening balance is set once. Later corrections go through "Adjust balance",
            // which records an auditable transaction instead of rewriting history.
            if (state.isNew) {
                MoneyField(
                    value = state.openingBalance,
                    onValueChange = onOpeningBalanceChange,
                    // A credit card asks for what is owed, as a plain amount; it is saved as a negative balance.
                    label = stringResource(if (state.type.isOwed) R.string.field_amount_owed else R.string.field_opening_balance),
                    supportingText = stringResource(
                        when {
                            state.openingBalanceInvalid -> R.string.error_amount_invalid
                            state.type.isOwed -> R.string.hint_amount_owed
                            else -> R.string.hint_opening_balance
                        },
                    ),
                    isError = state.openingBalanceInvalid,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_include_in_totals), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.hint_include_in_totals),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.includeInTotals, onCheckedChange = onIncludeInTotalsChange)
            }
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onSave, enabled = !state.saving && !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
            if (!state.isNew) {
                OutlinedButton(
                    onClick = onAdjustBalance,
                    enabled = !state.saving && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_adjust_balance)) }
                OutlinedButton(
                    onClick = { confirmArchive = true },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_archive), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_archive_title),
            text = stringResource(R.string.confirm_archive_body),
            confirmLabel = stringResource(R.string.action_archive),
            onConfirm = { confirmArchive = false; onArchive() },
            onDismiss = { confirmArchive = false },
        )
    }
}
