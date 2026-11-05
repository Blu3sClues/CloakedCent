package com.cloakedcent.app.ui.feature.refund

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloakedcent.app.R
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.RefundException
import com.cloakedcent.app.domain.model.RefundMath
import com.cloakedcent.app.domain.model.RefundState
import com.cloakedcent.app.domain.model.RefundSummary
import com.cloakedcent.app.domain.model.RefundableItem
import com.cloakedcent.app.ui.components.AmountInput
import com.cloakedcent.app.ui.components.DateField
import com.cloakedcent.app.ui.components.MoneyField
import com.cloakedcent.app.ui.components.MoneyText
import com.cloakedcent.app.ui.components.ledgerErrorMessage
import com.cloakedcent.app.ui.feature.receipt.QuantityText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** A refund failure to show: a message, with the remaining amount where relevant. */
data class RefundError(val message: Int, val amount: Money? = null)

fun refundError(error: Throwable): RefundError = when (error) {
    is RefundException.NotRefundable -> RefundError(R.string.error_refund_not_refundable)
    is RefundException.InvalidAmount -> RefundError(R.string.error_refund_amount)
    is RefundException.ExceedsRemaining -> RefundError(R.string.error_refund_exceeds, error.remaining)
    is RefundException.InvalidQuantity -> RefundError(R.string.error_refund_quantity)
    else -> RefundError(ledgerErrorMessage(error))
}

@Composable
fun RefundErrorText(error: RefundError?) {
    error ?: return
    val text = error.amount?.let { stringResource(error.message, AmountInput.format(it)) } ?: stringResource(error.message)
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
fun refundStateLabel(state: RefundState): String? = when (state) {
    RefundState.NONE -> null
    RefundState.PARTIAL -> stringResource(R.string.refund_state_partial)
    RefundState.FULL -> stringResource(R.string.refund_state_full)
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** Original, refunded and remaining amounts, the refund action and the refund history. */
@Composable
fun RefundCard(summary: RefundSummary, onRefund: () -> Unit, onOpenRefund: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.refund_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                refundStateLabel(summary.state)?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            AmountRow(stringResource(R.string.refund_original), summary.original)
            AmountRow(stringResource(R.string.refund_refunded), summary.refunded)
            AmountRow(stringResource(R.string.refund_remaining), summary.remaining)
            if (summary.remaining.isPositive) {
                OutlinedButton(onClick = onRefund, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.refund_action))
                }
            }
            if (summary.refunds.isNotEmpty()) {
                Text(stringResource(R.string.refund_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                summary.refunds.forEach { refund ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenRefund(refund.transactionId) }.padding(vertical = 6.dp)) {
                        Text(
                            listOfNotNull(dateFormat.format(refund.occurredOn), refund.itemDescription ?: stringResource(R.string.refund_whole_expense))
                                .joinToString(" · "),
                            Modifier.weight(1f),
                        )
                        MoneyText(refund.amount)
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Money) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        MoneyText(amount)
    }
}

/** Amount (pre-filled with everything still refundable) and date of a refund. */
@Composable
fun RefundDialog(
    remaining: Money,
    onConfirm: (amount: Money, date: LocalDate, requestId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // One request id per dialog: tapping confirm twice records one refund.
    val requestId = remember { "refund-" + UUID.randomUUID() }
    var text by remember { mutableStateOf(AmountInput.format(remaining)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val amount = AmountInput.parse(text)
    val valid = amount != null && amount.isPositive && amount <= remaining
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refund_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.refund_hint), style = MaterialTheme.typography.bodySmall)
                AmountRow(stringResource(R.string.refund_remaining), remaining)
                MoneyField(text, { text = it }, stringResource(R.string.refund_amount), isError = !valid)
                TextButton(onClick = { text = AmountInput.format(remaining) }) { Text(stringResource(R.string.refund_full)) }
                DateField(stringResource(R.string.field_date), date, { date = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(amount!!, date, requestId) }, enabled = valid) { Text(stringResource(R.string.refund_record)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Refund part of one receipt item, by quantity (priced from the purchase) or by amount. */
@Composable
fun ItemRefundDialog(
    item: RefundableItem,
    onConfirm: (quantityMilli: Long?, amount: Money?, date: LocalDate, requestId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val requestId = remember { "refund-item-" + UUID.randomUUID() }
    var byQuantity by remember { mutableStateOf(item.remainingQuantityMilli > 0) }
    var quantity by remember { mutableStateOf(QuantityText.format(item.remainingQuantityMilli.coerceAtLeast(0))) }
    var amountText by remember { mutableStateOf(AmountInput.format(item.remaining)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val quantityMilli = QuantityText.parse(quantity)
    val quantityOk = quantityMilli != null && quantityMilli <= item.remainingQuantityMilli
    val calculated = if (byQuantity && quantityOk) RefundMath.amountForQuantity(item, quantityMilli!!) else null
    val amount = AmountInput.parse(amountText)
    val valid = if (byQuantity) calculated?.isPositive == true else amount != null && amount.isPositive && amount <= item.remaining
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.description) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.refund_item_summary, AmountInput.format(item.paid), AmountInput.format(item.refunded), AmountInput.format(item.remaining)),
                    style = MaterialTheme.typography.bodySmall,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(byQuantity, { byQuantity = true }, SegmentedButtonDefaults.itemShape(0, 2), enabled = item.remainingQuantityMilli > 0) {
                        Text(stringResource(R.string.refund_by_quantity))
                    }
                    SegmentedButton(!byQuantity, { byQuantity = false }, SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.refund_by_amount))
                    }
                }
                if (byQuantity) {
                    OutlinedTextField(
                        quantity,
                        { quantity = it },
                        label = { Text(stringResource(R.string.refund_quantity, QuantityText.format(item.remainingQuantityMilli), item.unit.symbol)) },
                        isError = !quantityOk,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    calculated?.let { Text(stringResource(R.string.refund_calculated, AmountInput.format(it)), fontWeight = FontWeight.SemiBold) }
                } else {
                    MoneyField(amountText, { amountText = it }, stringResource(R.string.refund_amount), isError = !valid)
                }
                DateField(stringResource(R.string.field_date), date, { date = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (byQuantity) onConfirm(quantityMilli, null, date, requestId) else onConfirm(null, amount, date, requestId) },
                enabled = valid,
            ) { Text(stringResource(R.string.refund_record)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
