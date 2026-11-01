package com.pocketbudget.app.ui.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.ConsolidatedBalance
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import com.pocketbudget.app.ui.components.AccountRow
import com.pocketbudget.app.ui.components.EmptyState
import com.pocketbudget.app.ui.components.ListCard
import com.pocketbudget.app.ui.components.MoneyText
import com.pocketbudget.app.ui.components.SectionHeader
import com.pocketbudget.app.ui.components.TransactionRow
import com.pocketbudget.app.ui.theme.MoneyTextStyles
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import java.time.Instant

@Composable
fun DashboardRoute(viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(uiState = uiState, onRetry = viewModel::retry, onLoadSampleData = viewModel::loadSampleData)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(uiState: DashboardUiState, onRetry: () -> Unit, onLoadSampleData: () -> Unit) {
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
            )
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
                is DashboardUiState.Error -> ErrorCard(uiState.reason, onRetry)
                is DashboardUiState.Ready -> ReadyContent(uiState, onLoadSampleData)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: DashboardUiState.Ready, onLoadSampleData: () -> Unit) {
    BalanceHeroCard(state.totals, state.thisMonth)

    SectionHeader(stringResource(R.string.dashboard_accounts))
    if (state.accounts.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.dashboard_no_accounts_title),
            body = stringResource(R.string.dashboard_no_accounts_body),
            actionLabel = stringResource(R.string.dashboard_load_sample).takeIf { state.canLoadSampleData },
            onAction = onLoadSampleData.takeIf { state.canLoadSampleData },
        )
    } else {
        ListCard(state.accounts) { AccountRow(it) }
    }

    SectionHeader(stringResource(R.string.dashboard_recent))
    if (state.recent.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.dashboard_no_activity_title),
            body = stringResource(R.string.dashboard_no_activity_body),
        )
    } else {
        ListCard(state.recent) { TransactionRow(it) }
    }

    StorageFooter(state.storage)
}

@Composable
private fun BalanceHeroCard(totals: ConsolidatedBalance, thisMonth: PeriodSummary) {
    val ledgerColors = PocketBudgetTheme.ledgerColors
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledAmount(stringResource(R.string.balance_cleared), totals.cleared)
                LabeledAmount(stringResource(R.string.balance_projected), totals.projected)
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
private fun StorageFooter(storage: StorageStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.dashboard_storage_footer, storage.cipherVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    PocketBudgetTheme {
        DashboardScreen(
            uiState = DashboardUiState.Ready(
                storage = StorageStatus(Instant.now(), "4.x community"),
                totals = ConsolidatedBalance(Money(245_000), Money(240_000), Money(230_000)),
                accounts = listOf(AccountBalance(checking, Money(245_000), Money(240_000), Money(230_000))),
                thisMonth = PeriodSummary(Money(320_000), Money(75_050)),
                recent = emptyList(),
                canLoadSampleData = false,
            ),
            onRetry = {},
            onLoadSampleData = {},
        )
    }
}
