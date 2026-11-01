package com.pocketbudget.app.ui.feature.transaction

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.domain.model.RevisionAction
import com.pocketbudget.app.domain.model.TransactionDetail
import com.pocketbudget.app.domain.model.TransactionRevision
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.ReceiptRepository
import com.pocketbudget.app.domain.repository.TransactionRepository
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.ui.components.AmountInput
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.MoneyField
import com.pocketbudget.app.ui.components.ConfirmDialog
import com.pocketbudget.app.ui.components.MoneyText
import com.pocketbudget.app.ui.components.SectionHeader
import com.pocketbudget.app.ui.components.StatusChip
import com.pocketbudget.app.ui.components.ledgerErrorMessage
import com.pocketbudget.app.ui.components.rememberMoneyFormatter
import com.pocketbudget.app.ui.theme.MoneyTextStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed interface DetailState {
    data object Loading : DetailState
    data object Missing : DetailState
    data class Loaded(val detail: TransactionDetail) : DetailState
}

class TransactionDetailViewModel(
    private val id: Long,
    private val transactions: TransactionRepository,
    receipts: ReceiptRepository? = null,
) : ViewModel() {

    val state: StateFlow<DetailState> = transactions.observeDetail(id)
        .map { it?.let(DetailState::Loaded) ?: DetailState.Missing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    /** The receipt attached to this expense, if any. */
    val receiptId: StateFlow<Long?> = (receipts?.observeReceiptFor(id) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    /** [actualAmount] replaces the projected amount (variable bills) in the same atomic step. */
    fun markCompleted(actualAmount: Money?) = act { transactions.confirm(id, actualAmount) }
    fun delete() = act { transactions.delete(id) }

    private fun act(action: suspend () -> Unit) {
        _error.value = null
        viewModelScope.launch {
            try {
                action()
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
                TransactionDetailViewModel(
                    id = createSavedStateHandle().get<Long>(TransactionEditorViewModel.ARG_TRANSACTION_ID) ?: 0L,
                    transactions = (this[APPLICATION_KEY] as PocketBudgetApp).container.transactionRepository,
                    receipts = (this[APPLICATION_KEY] as PocketBudgetApp).container.receiptRepository,
                )
            }
        }
    }
}

@Composable
fun TransactionDetailRoute(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenReceipt: (Long) -> Unit = {},
    viewModel: TransactionDetailViewModel = viewModel(factory = TransactionDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val receiptId by viewModel.receiptId.collectAsStateWithLifecycle()
    TransactionDetailScreen(state, error, onBack, onEdit, viewModel::markCompleted, viewModel::delete, receiptId, onOpenReceipt)
}

@Composable
fun TransactionDetailScreen(
    state: DetailState,
    @StringRes error: Int?,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onMarkCompleted: (Money?) -> Unit,
    onDelete: () -> Unit,
    receiptId: Long? = null,
    onOpenReceipt: (Long) -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAmount by remember { mutableStateOf<String?>(null) }
    val detail = (state as? DetailState.Loaded)?.detail
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.transaction_detail_title), onBack) {
                if (detail != null && !detail.isDeleted) {
                    IconButton(onClick = { onEdit(detail.id) }) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                DetailState.Loading -> Unit
                DetailState.Missing -> Text(stringResource(R.string.error_not_found))
                is DetailState.Loaded -> {
                    SummaryCard(state.detail)
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    receiptId?.let { id ->
                        OutlinedButton(onClick = { onOpenReceipt(id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.receipt_view))
                        }
                    }
                    if (!state.detail.isDeleted) {
                        if (state.detail.status.isScheduled) {
                            Button({ confirmAmount = AmountInput.format(state.detail.amount.abs()) }, Modifier.fillMaxWidth()) {
                                Text(stringResource(markCompletedLabel(state.detail.type)))
                            }
                        }
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    AuditTrail(state.detail)
                }
            }
        }
    }
    val amountText = confirmAmount
    if (amountText != null && detail != null) {
        val parsed = AmountInput.parse(amountText)
        AlertDialog(
            onDismissRequest = { confirmAmount = null },
            title = { Text(stringResource(markCompletedLabel(detail.type))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.confirm_amount_body))
                    MoneyField(
                        amountText, { confirmAmount = it }, stringResource(R.string.field_amount),
                        isError = parsed == null || !parsed.isPositive,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmAmount = null; onMarkCompleted(parsed) },
                    enabled = parsed != null && parsed.isPositive,
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmAmount = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(
                if (detail?.type == TransactionType.TRANSFER) R.string.confirm_delete_transfer_body else R.string.confirm_delete_body,
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SummaryCard(detail: TransactionDetail) {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(typeLabel(detail.type)), style = MaterialTheme.typography.labelLarge)
                StatusChip(detail.status)
                if (detail.isAdjustment && detail.type != TransactionType.ADJUSTMENT) {
                    Text(stringResource(R.string.label_adjustment), style = MaterialTheme.typography.labelLarge)
                }
                if (detail.isDeleted) {
                    Text(stringResource(R.string.label_deleted), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
            val isTransfer = detail.type == TransactionType.TRANSFER
            MoneyText(
                amount = if (isTransfer) detail.amount.abs() else detail.amount,
                signed = !isTransfer,
                style = MoneyTextStyles.hero,
            )
            if (isTransfer) {
                val (from, to) = if (detail.amount.isNegative) {
                    detail.accountName to detail.counterpartAccountName
                } else {
                    detail.counterpartAccountName to detail.accountName
                }
                Field(R.string.field_from_account, from)
                Field(R.string.field_to_account, to)
            } else {
                Field(R.string.field_account, detail.accountName)
                if (detail.type == TransactionType.ADJUSTMENT) {
                    Field(R.string.field_reporting, stringResource(R.string.adjustment_kind_balance_only))
                }
                Field(R.string.field_category, detail.categoryName)
                Field(R.string.field_payee, detail.payeeName)
            }
            Field(R.string.field_date, detail.occurredOn.format(dateFormat))
            Field(R.string.field_note, detail.note)
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AuditTrail(detail: TransactionDetail) {
    val timestamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    SectionHeader(stringResource(R.string.audit_title))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.audit_created, timestamp.format(detail.createdAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
            detail.history.forEach { RevisionEntry(it, timestamp) }
            if (detail.history.isEmpty()) {
                Text(
                    stringResource(R.string.audit_no_changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RevisionEntry(revision: TransactionRevision, timestamp: DateTimeFormatter) {
    val formatter = rememberMoneyFormatter()
    val action = when (revision.action) {
        RevisionAction.UPDATE -> R.string.audit_edited
        RevisionAction.STATUS_CHANGE -> R.string.audit_status_changed
        RevisionAction.DELETE -> R.string.audit_deleted
    }
    val previous = listOfNotNull(
        formatter.format(revision.amount),
        stringResource(statusLabel(revision.status)),
        revision.occurredOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        revision.categoryName,
        revision.payeeName,
        revision.note,
    ).joinToString(" · ")
    Column {
        Text(
            stringResource(action, timestamp.format(revision.recordedAt)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.audit_previous, previous),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
