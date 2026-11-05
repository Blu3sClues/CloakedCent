package com.cloakedcent.app.ui.feature.planning

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cloakedcent.app.R
import com.cloakedcent.app.domain.model.BudgetPeriodType
import com.cloakedcent.app.domain.model.BudgetProgress
import com.cloakedcent.app.domain.model.BudgetState
import com.cloakedcent.app.domain.model.DateRange
import com.cloakedcent.app.domain.model.RecurrenceFrequency
import com.cloakedcent.app.domain.model.RecurringKind
import com.cloakedcent.app.ui.components.MoneyText
import com.cloakedcent.app.ui.components.rememberMoneyFormatter
import com.cloakedcent.app.ui.theme.MoneyTextStyles
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@StringRes
fun periodLabel(type: BudgetPeriodType): Int = when (type) {
    BudgetPeriodType.WEEKLY -> R.string.period_weekly
    BudgetPeriodType.MONTHLY -> R.string.period_monthly
    BudgetPeriodType.QUARTERLY -> R.string.period_quarterly
    BudgetPeriodType.YEARLY -> R.string.period_yearly
    BudgetPeriodType.CUSTOM -> R.string.period_custom
}

@StringRes
fun frequencyLabel(frequency: RecurrenceFrequency): Int = when (frequency) {
    RecurrenceFrequency.DAILY -> R.string.freq_daily
    RecurrenceFrequency.WEEKLY -> R.string.freq_weekly
    RecurrenceFrequency.BIWEEKLY -> R.string.freq_biweekly
    RecurrenceFrequency.MONTHLY -> R.string.freq_monthly
    RecurrenceFrequency.QUARTERLY -> R.string.freq_quarterly
    RecurrenceFrequency.YEARLY -> R.string.freq_yearly
    RecurrenceFrequency.CUSTOM_DAYS -> R.string.freq_custom_days
    RecurrenceFrequency.CUSTOM_WEEKS -> R.string.freq_custom_weeks
    RecurrenceFrequency.CUSTOM_MONTHS -> R.string.freq_custom_months
}

@StringRes
fun recurringKindLabel(kind: RecurringKind): Int = when (kind) {
    RecurringKind.PAYCHECK -> R.string.kind_paycheck
    RecurringKind.OTHER_INCOME -> R.string.kind_other_income
    RecurringKind.RENT -> R.string.kind_rent
    RecurringKind.MORTGAGE -> R.string.kind_mortgage
    RecurringKind.UTILITY -> R.string.kind_utility
    RecurringKind.SUBSCRIPTION -> R.string.kind_subscription
    RecurringKind.LOAN -> R.string.kind_loan
    RecurringKind.INSURANCE -> R.string.kind_insurance
    RecurringKind.OTHER -> R.string.kind_other
}

fun DateRange.label(): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return "${start.format(format)} – ${endInclusive.format(format)}"
}

/** Spent vs allocated for a budget's current period. */
@Composable
fun BudgetProgressRow(progress: BudgetProgress, modifier: Modifier = Modifier) {
    val current = progress.current
    val colors = CloakedCentTheme.ledgerColors
    val formatter = rememberMoneyFormatter()
    Column(modifier.fillMaxWidth().padding(start = (progress.depth * 16).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(progress.budget.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        progress.categoryLabel,
                        stringResource(periodLabel(progress.budget.periodType)),
                        stringResource(R.string.budget_paused).takeIf { progress.budget.state == BudgetState.PAUSED },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current != null) MoneyText(current.remaining, color = if (current.remaining.isNegative) colors.expense else colors.income)
        }
        if (current == null) {
            Text(stringResource(R.string.budget_not_in_period), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val over = current.percentUsed >= 100
        LinearProgressIndicator(
            progress = { (current.percentUsed / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = when {
                over -> MaterialTheme.colorScheme.error
                progress.thresholdsReached.isNotEmpty() -> colors.scheduled
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Text(
            stringResource(
                if (current.remaining.isNegative) R.string.budget_spent_over else R.string.budget_spent_of,
                formatter.format(current.spent),
                formatter.format(current.allocated),
                formatter.format(current.remaining.abs()),
            ),
            style = MoneyTextStyles.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (current.carriedIn.isPositive) {
            Text(
                stringResource(R.string.budget_rolled_over, formatter.format(current.carriedIn)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress.thresholdsReached.isNotEmpty() && progress.budget.state == BudgetState.ACTIVE) {
            Text(
                stringResource(R.string.budget_alert_reached, progress.thresholdsReached.max()),
                style = MaterialTheme.typography.labelMedium,
                color = if (over) MaterialTheme.colorScheme.error else colors.scheduled,
            )
        }
        if (progress.childLimitsExceedParent) {
            Text(stringResource(R.string.budget_children_exceed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Asks for notification permission in context (Android 13+), only when not yet granted. */
@Composable
fun NotificationPermissionCard(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.notify_permission_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.notify_permission_body), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.notify_permission_action))
            }
        }
    }
}
