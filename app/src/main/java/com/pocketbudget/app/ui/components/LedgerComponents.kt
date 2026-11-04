package com.pocketbudget.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.core.money.MoneyFormatter
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.displayBalance
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.ui.feature.transaction.statusLabel
import com.pocketbudget.app.ui.feature.transaction.typeLabel
import com.pocketbudget.app.ui.theme.MoneyTextStyles
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun rememberMoneyFormatter(): MoneyFormatter = remember { MoneyFormatter() }

/** Formats an amount; [signed] adds +/- and income/expense coloring. */
@Composable
fun MoneyText(
    amount: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MoneyTextStyles.row,
    signed: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val formatter = rememberMoneyFormatter()
    val colors = PocketBudgetTheme.ledgerColors
    val resolved = when {
        color != Color.Unspecified -> color
        signed && amount.isPositive -> colors.income
        signed && amount.isNegative -> colors.expense
        else -> Color.Unspecified
    }
    Text(
        text = if (signed) formatter.formatSigned(amount) else formatter.format(amount),
        modifier = modifier,
        style = style,
        color = resolved,
        maxLines = 1,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Card holding a list of rows separated by dividers. */
@Composable
fun <T> ListCard(items: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row(item)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(actionLabel) }
            }
        }
    }
}

/** Makes clear whether money has actually moved: scheduled entries are projections. */
@Composable
fun StatusChip(status: TransactionStatus, modifier: Modifier = Modifier) {
    val (container, content) = if (status.isScheduled) {
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(modifier = modifier, color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = stringResource(statusLabel(status)),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun AccountRow(balance: AccountBalance, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(balance.account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(accountTypeLabel(balance.account.type)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A credit card shows what is owed as a positive amount; totals still count it as negative.
        val type = balance.account.type
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(type.displayBalance(balance.current))
            if (balance.projected != balance.current) {
                Row {
                    Text(
                        text = stringResource(R.string.balance_projected) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(type.displayBalance(balance.projected), style = MoneyTextStyles.caption, color = PocketBudgetTheme.ledgerColors.scheduled)
                }
            }
        }
    }
}

@Composable
fun TransactionRow(item: TransactionListItem, modifier: Modifier = Modifier) {
    val title = when (item.type) {
        TransactionType.TRANSFER -> stringResource(
            if (item.amount.isNegative) R.string.transfer_to else R.string.transfer_from,
            item.counterpartAccountName.orEmpty(),
        )
        else -> item.payeeName ?: item.categoryName ?: stringResource(
            if (item.isAdjustment) R.string.type_adjustment else typeLabel(item.type),
        )
    }
    val subtitle = listOfNotNull(
        item.occurredOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        item.accountName,
        item.categoryName.takeIf { item.payeeName != null },
    ).joinToString(" · ")

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Transfers move money between own accounts: shown neutrally, never as income/expense.
            MoneyText(
                amount = if (item.type == TransactionType.TRANSFER) item.amount.abs() else item.amount,
                signed = item.type != TransactionType.TRANSFER,
            )
            StatusChip(item.status)
            if (item.refunded.isPositive) {
                val full = item.refunded >= item.amount.abs()
                Text(
                    stringResource(if (full) R.string.refund_state_full else R.string.refund_state_partial),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun accountTypeLabel(type: AccountType): Int = when (type) {
    AccountType.CHECKING -> R.string.account_type_checking
    AccountType.SAVINGS -> R.string.account_type_savings
    AccountType.CASH -> R.string.account_type_cash
    AccountType.CREDIT_CARD -> R.string.account_type_credit_card
    AccountType.LOAN -> R.string.account_type_loan
    AccountType.INVESTMENT -> R.string.account_type_investment
    AccountType.OTHER -> R.string.account_type_other
}
