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
import com.pocketbudget.app.domain.model.RefundSummary
import com.pocketbudget.app.domain.repository.ReceiptRepository
import com.pocketbudget.app.domain.repository.RefundRepository
import com.pocketbudget.app.ui.feature.receipt.receiptErrorMessage
import com.pocketbudget.app.ui.feature.refund.RefundCard
import com.pocketbudget.app.ui.feature.refund.RefundDialog
import com.pocketbudget.app.ui.feature.refund.RefundError
import com.pocketbudget.app.ui.feature.refund.RefundErrorText
import com.pocketbudget.app.ui.feature.refund.refundError
import com.pocketbudget.app.domain.repository.TransactionRepository
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.ui.components.AmountInput
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.MoneyField
import com.pocketbudget.app.ui.components.ConfirmDialog
import com.pocketbudget.app.ui.components.LinkedDeleteDialog
import com.pocketbudget.app.ui.components.MoneyText
import com.pocketbudget.app.ui.components.SectionHeader
import com.pocketbudget.app.ui.components.StatusChip
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
import java.time.LocalDate
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
    private val receipts: ReceiptRepository? = null,
    private val refunds: RefundRepository? = null,
) : ViewModel() {

    val state: StateFlow<DetailState> = transactions.observeDetail(id)
        .map { it?.let(DetailState::Loaded) ?: DetailState.Missing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    /** The receipt attached to this expense, if any. */
    val receiptId: StateFlow<Long?> = (receipts?.observeReceiptFor(id) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Refund position when this is a completed expense. */
    val refundSummary: StateFlow<RefundSummary?> = (refunds?.observeSummary(id) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** For a refund entry: the expense it refunds. */
    val originalId: StateFlow<Long?> = (refunds?.observeOriginal(id) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    private val _refundError = MutableStateFlow<RefundError?>(null)
    val refundError: StateFlow<RefundError?> = _refundError.asStateFlow()

    fun refund(amount: Money, date: LocalDate, requestId: String) {
        val repository = refunds ?: return
        _refundError.value = null
        viewModelScope.launch {
            try {
                repository.refundTransaction(id, amount, date, requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _refundError.value = refundError(e)
            }
        }
    }

    /** [actualAmount] replaces the projected amount (variable bills) in the same atomic step. */
    fun markCompleted(actualAmount: Money?) = act { transactions.confirm(id, actualAmount) }
    fun delete() = act { transactions.delete(id) }

    /** Deletes this expense and its receipt together, atomically. */
    fun deleteWithReceipt() = act {
        val receipt = receiptId.value
        if (receipt != null && receipts != null) receipts.delete(receipt, deleteTransaction = true) else transactions.delete(id)
    }

    private fun act(action: suspend () -> Unit) {
        _error.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = receiptErrorMessage(e)
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
                    refunds = (this[APPLICATION_KEY] as PocketBudgetApp).container.refundRepository,
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
    onOpenTransaction: (Long) -> Unit = {},
    viewModel: TransactionDetailViewModel = viewModel(factory = TransactionDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val receiptId by viewModel.receiptId.collectAsStateWithLifecycle()
    val refundSummary by viewModel.refundSummary.collectAsStateWithLifecycle()
    val originalId by viewModel.originalId.collectAsStateWithLifecycle()
    val refundError by viewModel.refundError.collectAsStateWithLifecycle()
    TransactionDetailScreen(
        state, error, onBack, onEdit, viewModel::markCompleted, viewModel::delete, receiptId, onOpenReceipt,
        RefundUi(refundSummary, originalId, refundError, viewModel::refund, onOpenTransaction),
        viewModel::deleteWithReceipt,
    )
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
    refund: RefundUi = RefundUi(),
    onDeleteWithReceipt: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var refunding by remember { mutableStateOf(false) }
    var confirmAmount by remember { mutableStateOf<String?>(null) }
    val detail = (state as? DetailState.Loaded)?.detail
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.transaction_detail_title), onBack) {
                // Refunds are fixed once recorded (delete and record again instead).
                if (detail != null && !detail.isDeleted && detail.type != TransactionType.REFUND) {
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
                    refund.originalId?.let { original ->
                        Text(stringResource(R.string.refund_of), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { refund.onOpenTransaction(original) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.refund_open_original))
                        }
                    }
                    refund.summary?.let { summary ->
                        RefundCard(summary, onRefund = { refunding = true }, onOpenRefund = refund.onOpenTransaction)
                        RefundErrorText(refund.error)
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
    val summary = refund.summary
    if (refunding && summary != null) {
        RefundDialog(
            remaining = summary.remaining,
            onConfirm = { amount, date, requestId -> refunding = false; refund.onRefund(amount, date, requestId) },
            onDismiss = { refunding = false },
        )
    }
    if (confirmDelete && receiptId != null) {
        LinkedDeleteDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.confirm_delete_with_receipt_body),
            bothLabel = stringResource(R.string.action_delete_expense_and_receipt),
            onlyLabel = stringResource(R.string.action_delete_expense_only),
            onBoth = { confirmDelete = false; onDeleteWithReceipt() },
            onOnly = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    } else if (confirmDelete) {
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

/** Refund state and actions for the detail screen; empty when not applicable. */
class RefundUi(
    val summary: RefundSummary? = null,
    val originalId: Long? = null,
    val error: RefundError? = null,
    val onRefund: (Money, LocalDate, String) -> Unit = { _, _, _ -> },
    val onOpenTransaction: (Long) -> Unit = {},
)

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
