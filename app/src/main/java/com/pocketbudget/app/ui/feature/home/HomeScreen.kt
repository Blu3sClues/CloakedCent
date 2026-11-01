package com.pocketbudget.app.ui.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(uiState = uiState, onRetry = viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(uiState: HomeUiState, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
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
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WelcomeCard()
            when (uiState) {
                HomeUiState.Loading -> LoadingCard()
                is HomeUiState.Ready -> StorageCard(uiState.storage)
                is HomeUiState.Error -> ErrorCard(uiState.reason, onRetry)
            }
        }
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.home_welcome_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.home_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.home_storage_loading))
        }
    }
}

@Composable
private fun StorageCard(storage: StorageStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_storage_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            StatusLine(stringResource(R.string.home_storage_encrypted, storage.cipherVersion))
            StatusLine(stringResource(R.string.home_storage_key))
            StatusLine(stringResource(R.string.home_storage_offline))
            storage.createdAt?.let { createdAt ->
                StatusLine(stringResource(R.string.home_storage_created, formatDate(createdAt)))
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = "•  $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
            Text(
                text = stringResource(R.string.home_error_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) {
                Text(stringResource(R.string.home_error_retry))
            }
        }
    }
}

private fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(instant)

@Preview(name = "Light")
@Composable
private fun HomeScreenLightPreview() {
    PocketBudgetTheme(darkTheme = false) {
        HomeScreen(
            uiState = HomeUiState.Ready(StorageStatus(Instant.now(), "4.11.0 community")),
            onRetry = {},
        )
    }
}

@Preview(name = "Dark")
@Composable
private fun HomeScreenDarkPreview() {
    PocketBudgetTheme(darkTheme = true) {
        HomeScreen(uiState = HomeUiState.Error(Reason.KEY_MISSING), onRetry = {})
    }
}
