package com.pocketbudget.app.ui.feature.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketbudget.app.R
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.DateField
import com.pocketbudget.app.ui.components.MoneyField
import com.pocketbudget.app.ui.components.PickerField
import com.pocketbudget.app.ui.components.rememberMoneyFormatter
import java.time.LocalDate

@Composable
fun TransactionEditorRoute(
    onDone: () -> Unit,
    viewModel: TransactionEditorViewModel = viewModel(factory = TransactionEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    TransactionEditorScreen(
        state = state,
        actions = TransactionEditorActions(
            onBack = onDone,
            onTypeChange = viewModel::onTypeChange,
            onAccountChange = viewModel::onAccountChange,
            onToAccountChange = viewModel::onToAccountChange,
            onAmountChange = viewModel::onAmountChange,
            onDateChange = viewModel::onDateChange,
            onCategoryChange = viewModel::onCategoryChange,
            onPayeeChange = viewModel::onPayeeChange,
            onNoteChange = viewModel::onNoteChange,
            onScheduledChange = viewModel::onScheduledChange,
            onSave = viewModel::save,
        ),
    )
}

class TransactionEditorActions(
    val onBack: () -> Unit = {},
    val onTypeChange: (TransactionType) -> Unit = {},
    val onAccountChange: (Long) -> Unit = {},
    val onToAccountChange: (Long) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onDateChange: (LocalDate) -> Unit = {},
    val onCategoryChange: (Long?) -> Unit = {},
    val onPayeeChange: (String) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onScheduledChange: (Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
)

@Composable
fun TransactionEditorScreen(state: TransactionEditorState, actions: TransactionEditorActions) {
    val title = when {
        !state.isNew -> R.string.transaction_edit_title
        else -> R.string.transaction_new_title
    }
    Scaffold(topBar = { BackTopBar(stringResource(title), actions.onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isNew) TypeSelector(state.type, actions.onTypeChange)

            PickerField(
                label = stringResource(if (state.isTransfer) R.string.field_from_account else R.string.field_account),
                options = state.accounts,
                selected = state.accountId,
                onSelect = actions.onAccountChange,
                isError = state.accountMissing,
                enabled = state.isNew,
                supportingText = stringResource(R.string.error_account_required).takeIf { state.accountMissing },
            )
            if (state.isTransfer) {
                PickerField(
                    label = stringResource(R.string.field_to_account),
                    options = state.accounts,
                    selected = state.toAccountId,
                    onSelect = actions.onToAccountChange,
                    isError = state.toAccountMissing,
                    enabled = state.isNew,
                    supportingText = stringResource(R.string.error_account_required).takeIf { state.toAccountMissing },
                )
            }
            MoneyField(
                value = state.amount,
                onValueChange = actions.onAmountChange,
                label = stringResource(R.string.field_amount),
                isError = state.amountInvalid,
                supportingText = stringResource(R.string.error_amount_invalid).takeIf { state.amountInvalid },
            )
            state.destinationAfterTransfer?.let {
                Text(
                    stringResource(
                        R.string.transfer_balance_after,
                        it.accountName,
                        rememberMoneyFormatter().format(it.balance),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DateField(stringResource(R.string.field_date), state.date, actions.onDateChange)
            if (state.hasCategory) {
                PickerField(
                    label = stringResource(R.string.field_category),
                    options = listOf<Pair<Long?, String>>(null to stringResource(R.string.category_none)) + state.categories,
                    selected = state.categoryId,
                    onSelect = actions.onCategoryChange,
                )
                PayeeField(state.payee, state.payeeNames, actions.onPayeeChange)
            }
            OutlinedTextField(
                value = state.note,
                onValueChange = actions.onNoteChange,
                label = { Text(stringResource(R.string.field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isNew) {
                // Completed wording follows the type: Spent / Received / Completed.
                PickerField(
                    label = stringResource(R.string.field_status),
                    options = listOf(
                        false to stringResource(statusLabel(state.type.completedStatus)),
                        true to stringResource(R.string.status_scheduled),
                    ),
                    selected = state.scheduled,
                    onSelect = actions.onScheduledChange,
                    supportingText = stringResource(
                        R.string.hint_status,
                        stringResource(statusLabel(state.type.completedStatus)),
                    ),
                )
            }
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = actions.onSave,
                enabled = !state.saving && !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun TypeSelector(selected: TransactionType, onSelect: (TransactionType) -> Unit) {
    // Adjustments are created from account settings ("Adjust balance"), not here.
    val types = listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selected,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
            ) { Text(stringResource(typeLabel(type))) }
        }
    }
}

/** Free text with suggestions from existing payees; new names create a payee on save. */
@Composable
private fun PayeeField(value: String, knownNames: List<String>, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(value, knownNames) {
        if (value.isBlank()) emptyList()
        else knownNames.filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }.take(5)
    }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); focused = true },
            label = { Text(stringResource(R.string.field_payee)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = focused && suggestions.isNotEmpty(),
            onDismissRequest = { focused = false },
            properties = PopupProperties(focusable = false),
        ) {
            suggestions.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onChange(name); focused = false })
            }
        }
    }
}

fun typeLabel(type: TransactionType): Int = when (type) {
    TransactionType.INCOME -> R.string.type_income
    TransactionType.EXPENSE -> R.string.type_expense
    TransactionType.TRANSFER -> R.string.type_transfer
    TransactionType.ADJUSTMENT -> R.string.type_adjustment
    TransactionType.REFUND -> R.string.type_refund
}

fun statusLabel(status: TransactionStatus): Int = when (status) {
    TransactionStatus.SCHEDULED -> R.string.status_scheduled
    TransactionStatus.SPENT -> R.string.status_spent
    TransactionStatus.RECEIVED -> R.string.status_received
    TransactionStatus.COMPLETED -> R.string.status_completed
}

/** Action that turns a scheduled entry of this type into its completed status. */
fun markCompletedLabel(type: TransactionType): Int = when (type) {
    TransactionType.INCOME -> R.string.action_mark_received
    TransactionType.EXPENSE -> R.string.action_mark_spent
    TransactionType.TRANSFER, TransactionType.ADJUSTMENT, TransactionType.REFUND -> R.string.action_mark_completed
}
