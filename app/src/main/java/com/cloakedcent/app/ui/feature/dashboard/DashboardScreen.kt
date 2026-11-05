package com.cloakedcent.app.ui.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import com.cloakedcent.app.ui.feature.planning.BudgetProgressRow
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cloakedcent.app.domain.model.TransactionType
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloakedcent.app.R
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.Account
import com.cloakedcent.app.domain.model.AccountBalance
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.ConsolidatedBalance
import com.cloakedcent.app.domain.model.PeriodSummary
import com.cloakedcent.app.domain.model.StorageStatus
import com.cloakedcent.app.domain.repository.StorageUnavailableException.Reason
import com.cloakedcent.app.ui.components.AccountRow
import com.cloakedcent.app.ui.components.EmptyState
import com.cloakedcent.app.ui.components.ListCard
import com.cloakedcent.app.ui.components.MoneyText
import com.cloakedcent.app.ui.components.SectionHeader
import com.cloakedcent.app.ui.components.TransactionRow
import com.cloakedcent.app.ui.theme.MoneyTextStyles
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import java.time.Instant

class DashboardActions(
    val onRetry: () -> Unit = {},
    val onLoadSampleData: () -> Unit = {},
    val onAddTransaction: (TransactionType) -> Unit = {},
    val onAddAccount: () -> Unit = {},
    val onOpenAccount: (Long) -> Unit = {},
    val onOpenTransaction: (Long) -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenBudgets: () -> Unit = {},
    val onOpenUpcoming: () -> Unit = {},
)

@Composable
fun DashboardRoute(
    onAddTransaction: (TransactionType) -> Unit,
    onAddAccount: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenUpcoming: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        uiState = uiState,
        actions = DashboardActions(
            onRetry = viewModel::retry,
            onLoadSampleData = viewModel::loadSampleData,
            onAddTransaction = onAddTransaction,
            onAddAccount = onAddAccount,
            onOpenAccount = onOpenAccount,
            onOpenTransaction = onOpenTransaction,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
            onOpenBudgets = onOpenBudgets,
            onOpenUpcoming = onOpenUpcoming,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(uiState: DashboardUiState, actions: DashboardActions) {
    val ready = uiState as? DashboardUiState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = actions.onOpenHistory) {
                            Icon(painterResource(R.drawable.ic_search), stringResource(R.string.action_search))
                        }
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready != null && ready.accounts.isNotEmpty()) AddMenuButton(actions.onAddTransaction)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (uiState) {
                DashboardUiState.Loading -> LoadingCard()
                is DashboardUiState.Error -> ErrorCard(uiState.reason, actions.onRetry)
                is DashboardUiState.Ready -> ReadyContent(uiState, actions)
            }
            // Keeps the last row clear of the floating button.
            Spacer(Modifier.height(72.dp))
        }
    }
}

private const val DASHBOARD_BUDGETS = 3

/** Main sections live in the navigation bar; only overdue payments are surfaced here. */
@Composable
private fun OverdueNotice(overdueCount: Int, onOpenUpcoming: () -> Unit) {
    Card(
        onClick = onOpenUpcoming,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.upcoming_with_overdue, overdueCount), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun AddMenuButton(onAdd: (TransactionType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = { expanded = true },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.action_add)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                TransactionType.EXPENSE to R.string.action_add_expense,
                TransactionType.INCOME to R.string.action_add_income,
                TransactionType.TRANSFER to R.string.action_add_transfer,
            ).forEach { (type, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; onAdd(type) })
            }
        }
    }
}

@Composable
private fun ReadyContent(state: DashboardUiState.Ready, actions: DashboardActions) {
    BalanceHeroCard(state.totals, state.thisMonth)
    if (state.overdueCount > 0) OverdueNotice(state.overdueCount, actions.onOpenUpcoming)
    if (state.budgets.isNotEmpty()) {
        SectionHeader(
            stringResource(R.string.budgets_title),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = actions.onOpenBudgets,
        )
        ListCard(state.budgets.take(DASHBOARD_BUDGETS)) { BudgetProgressRow(it, Modifier.clickable { actions.onOpenBudgets() }.padding(16.dp)) }
    }

    SectionHeader(
        stringResource(R.string.dashboard_accounts),
        actionLabel = stringResource(R.string.action_add_account),
        onAction = actions.onAddAccount,
    )
    if (state.accounts.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.dashboard_no_accounts_title),
            body = stringResource(R.string.dashboard_no_accounts_body),
            actionLabel = stringResource(
                if (state.canLoadSampleData) R.string.dashboard_load_sample else R.string.action_add_account,
            ),
            onAction = if (state.canLoadSampleData) actions.onLoadSampleData else actions.onAddAccount,
        )
    } else {
        ListCard(state.accounts) { AccountRow(it, Modifier.clickable { actions.onOpenAccount(it.account.id) }) }
    }

    if (state.upcoming.isNotEmpty()) {
        SectionHeader(stringResource(R.string.dashboard_upcoming))
        ListCard(state.upcoming) { TransactionRow(it, Modifier.clickable { actions.onOpenTransaction(it.id) }) }
    }

    SectionHeader(
        stringResource(R.string.dashboard_recent),
        actionLabel = stringResource(R.string.action_see_all).takeIf { state.recent.isNotEmpty() },
        onAction = actions.onOpenHistory,
    )
    if (state.recent.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.dashboard_no_activity_title),
            body = stringResource(R.string.dashboard_no_activity_body),
        )
    } else {
        ListCard(state.recent) { TransactionRow(it, Modifier.clickable { actions.onOpenTransaction(it.id) }) }
    }
}

@Composable
private fun BalanceHeroCard(totals: ConsolidatedBalance, thisMonth: PeriodSummary) {
    val ledgerColors = CloakedCentTheme.ledgerColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dashboard_total_balance), style = MaterialTheme.typography.labelLarge)
            MoneyText(totals.current, style = MoneyTextStyles.hero)
            if (totals.projected != totals.current) {
                LabeledAmount(stringResource(R.string.balance_projected_with_scheduled), totals.projected)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_income_month), style = MaterialTheme.typography.bodySmall)
                    MoneyText(thisMonth.income, color = ledgerColors.income)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_spent_month), style = MaterialTheme.typography.bodySmall)
                    MoneyText(thisMonth.expense, color = ledgerColors.expense)
                }
            }
        }
    }
}

@Composable
private fun LabeledAmount(label: String, amount: Money) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.bodySmall)
        MoneyText(amount, style = MoneyTextStyles.caption)
    }
}

@Composable
private fun LoadingCard() {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.home_storage_loading))
        }
    }
}

@Composable
private fun ErrorCard(reason: Reason, onRetry: () -> Unit) {
    val message = when (reason) {
        Reason.KEY_MISSING -> R.string.home_error_key_missing
        Reason.KEY_CORRUPTED -> R.string.home_error_key_corrupted
        Reason.KEYSTORE_UNAVAILABLE -> R.string.home_error_keystore
        Reason.UNKNOWN -> R.string.home_error_unknown
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_error_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text(stringResource(R.string.home_error_retry)) }
        }
    }
}

@Preview
@Composable
private fun DashboardPreview() {
    val checking = Account(1, "Everyday checking", AccountType.CHECKING, Money(100_000), true)
    CloakedCentTheme {
        DashboardScreen(
            uiState = DashboardUiState.Ready(
                storage = StorageStatus(Instant.now(), "4.x community"),
                totals = ConsolidatedBalance(Money(245_000), Money(230_000)),
                accounts = listOf(AccountBalance(checking, Money(245_000), Money(230_000))),
                thisMonth = PeriodSummary(Money(320_000), Money(75_050)),
                recent = emptyList(),
                canLoadSampleData = false,
            ),
            actions = DashboardActions(),
        )
    }
}
