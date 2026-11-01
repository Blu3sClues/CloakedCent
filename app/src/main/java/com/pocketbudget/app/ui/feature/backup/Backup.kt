package com.pocketbudget.app.ui.feature.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.domain.repository.BackupException
import com.pocketbudget.app.domain.repository.BackupProgress
import com.pocketbudget.app.domain.repository.BackupRepository
import com.pocketbudget.app.domain.repository.MIN_BACKUP_PASSWORD
import com.pocketbudget.app.domain.repository.RestorePreview
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the screen is in a backup or restore. Messages never include financial data. */
sealed interface BackupStage {
    data object Idle : BackupStage
    data class Working(val message: Int, val progress: BackupProgress?) : BackupStage
    data object BackupDone : BackupStage
    data class Previewed(val preview: RestorePreview) : BackupStage
    data object RestartToFinish : BackupStage
    data class Failed(val message: Int) : BackupStage
}

/** Storage Access Framework access, injected so the ViewModel does no Android I/O itself. */
class DocumentAccess(
    val openOutput: (Uri) -> OutputStream,
    val openInput: (Uri) -> InputStream,
    val size: (Uri) -> Long?,
    val delete: (Uri) -> Unit,
)

class BackupViewModel(private val backups: BackupRepository, private val documents: DocumentAccess) : ViewModel() {

    private val _stage = MutableStateFlow<BackupStage>(BackupStage.Idle)
    val stage: StateFlow<BackupStage> = _stage.asStateFlow()
    private var job: Job? = null

    /** Writes a backup to the document the user created; a failed or cancelled one is deleted. */
    fun createBackup(uri: Uri, password: CharArray) {
        if (job?.isActive == true) return password.fill('\u0000')
        _stage.value = BackupStage.Working(R.string.backup_working, null)
        job = viewModelScope.launch {
            var ok = false
            try {
                documents.openOutput(uri).use { out ->
                    backups.createBackup(out, password) { p -> _stage.update { BackupStage.Working(R.string.backup_working, p) } }
                }
                ok = true
                _stage.value = BackupStage.BackupDone
            } catch (e: CancellationException) {
                _stage.value = BackupStage.Failed(R.string.backup_cancelled)
                throw e
            } catch (e: Exception) {
                _stage.value = BackupStage.Failed(message(e))
            } finally {
                password.fill('\u0000')
                if (!ok) runCatching { documents.delete(uri) }
            }
        }
    }

    /** Decrypts and validates the chosen backup without touching current data. */
    fun prepareRestore(uri: Uri, password: CharArray) {
        if (job?.isActive == true) return password.fill('\u0000')
        _stage.value = BackupStage.Working(R.string.restore_checking, null)
        job = viewModelScope.launch {
            try {
                val total = runCatching { documents.size(uri) }.getOrNull()
                val preview = documents.openInput(uri).use { input ->
                    backups.prepareRestore(input, total, password) { p -> _stage.update { BackupStage.Working(R.string.restore_checking, p) } }
                }
                _stage.value = BackupStage.Previewed(preview)
            } catch (e: CancellationException) {
                _stage.value = BackupStage.Failed(R.string.restore_cancelled)
                throw e
            } catch (e: Exception) {
                _stage.value = BackupStage.Failed(message(e))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** After the user confirmed: schedule the replacement, applied when the app restarts. */
    fun confirmRestore() {
        viewModelScope.launch {
            try {
                backups.commitPreparedRestore()
                _stage.value = BackupStage.RestartToFinish
            } catch (e: Exception) {
                _stage.value = BackupStage.Failed(message(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to the start; a validated but unconfirmed restore is discarded. */
    fun reset() {
        job?.cancel()
        viewModelScope.launch { runCatching { backups.discardPreparedRestore() } }
        _stage.value = BackupStage.Idle
    }

    override fun onCleared() {
        // Leaving the screen without confirming must not leave a staged restore around.
        if (_stage.value is BackupStage.Previewed) kotlinx.coroutines.runBlocking { runCatching { backups.discardPreparedRestore() } }
    }

    private fun message(e: Exception): Int = when (e) {
        is BackupException.PasswordTooShort -> R.string.backup_error_password_short
        is BackupException.WrongPasswordOrDamaged -> R.string.restore_error_password
        is BackupException.Incomplete -> R.string.restore_error_incomplete
        is BackupException.Unsupported, is BackupException.NewerVersion -> R.string.restore_error_newer
        is BackupException.MissingAttachment -> R.string.backup_error_attachment
        is BackupException.InsufficientStorage -> R.string.backup_error_storage
        is BackupException.Damaged -> R.string.restore_error_damaged
        else -> R.string.backup_error_generic
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PocketBudgetApp
                val resolver = app.contentResolver
                BackupViewModel(
                    app.container.backupRepository,
                    DocumentAccess(
                        openOutput = { uri -> resolver.openOutputStream(uri, "wt") ?: error("Cannot write") },
                        openInput = { uri -> resolver.openInputStream(uri) ?: error("Cannot read") },
                        size = { uri -> resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } },
                        delete = { uri -> DocumentsContract.deleteDocument(resolver, uri) },
                    ),
                )
            }
        }
    }
}

/** Restarts the app so a scheduled restore is applied before the database opens again. */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}

private val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

@Composable
fun BackupRoute(restore: Boolean, onBack: () -> Unit, viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Uri?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }

    fun takePassword(): CharArray = password.toCharArray().also {
        password = ""
        confirmation = ""
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.createBackup(uri, takePassword())
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> chosen = uri }
    val working = stage is BackupStage.Working

    Scaffold(topBar = { BackTopBar(stringResource(if (restore) R.string.restore_title else R.string.backup_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (restore) R.string.restore_intro else R.string.backup_intro), style = MaterialTheme.typography.bodyMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(
                    stringResource(R.string.backup_password_warning),
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            when (val s = stage) {
                is BackupStage.Working -> {
                    Text(stringResource(s.message))
                    val p = s.progress
                    if (p?.total != null && p.total > 0) {
                        LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) }
                }
                BackupStage.BackupDone -> {
                    Text(stringResource(R.string.backup_done), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onBack) { Text(stringResource(R.string.action_ok)) }
                }
                is BackupStage.Previewed -> {
                    val pv = s.preview
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.restore_valid), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.restore_created, dateTime.format(Instant.ofEpochMilli(pv.createdAt).atZone(ZoneId.systemDefault()))))
                            Text(stringResource(R.string.restore_contents, pv.accounts, pv.transactions, pv.receipts, pv.attachments))
                            if (pv.appVersion.isNotBlank()) Text(stringResource(R.string.restore_app_version, pv.appVersion), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringResource(R.string.restore_replace_warning), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { confirmReplace = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_replace)) }
                    TextButton(onClick = { viewModel.reset(); chosen = null }) { Text(stringResource(R.string.action_cancel)) }
                }
                BackupStage.RestartToFinish -> {
                    Text(stringResource(R.string.restore_restarting), fontWeight = FontWeight.SemiBold)
                    Button(onClick = { restartApp(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_restart)) }
                }
                else -> {
                    if (s is BackupStage.Failed) Text(stringResource(s.message), color = MaterialTheme.colorScheme.error)
                    if (restore) {
                        OutlinedButton(onClick = { openDocument.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (chosen == null) R.string.restore_choose else R.string.restore_choose_other))
                        }
                        chosen?.let { Text(stringResource(R.string.restore_selected, displayName(context, it)), style = MaterialTheme.typography.bodySmall) }
                    }
                    PasswordField(password, { password = it }, stringResource(R.string.backup_password))
                    if (!restore) {
                        PasswordField(confirmation, { confirmation = it }, stringResource(R.string.backup_password_confirm))
                        Text(pluralStringResource(R.plurals.backup_password_rules, MIN_BACKUP_PASSWORD, MIN_BACKUP_PASSWORD), style = MaterialTheme.typography.bodySmall)
                    }
                    val passwordOk = password.length >= MIN_BACKUP_PASSWORD && (restore || password == confirmation)
                    if (!restore && confirmation.isNotEmpty() && password != confirmation) {
                        Text(stringResource(R.string.backup_password_mismatch), color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            if (restore) {
                                chosen?.let { viewModel.prepareRestore(it, takePassword()) }
                            } else {
                                createDocument.launch("PocketBudget-${LocalDate.now()}.pbbackup")
                            }
                        },
                        enabled = passwordOk && !working && (!restore || chosen != null),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (restore) R.string.restore_check else R.string.backup_create))
                    }
                }
            }
        }
    }

    if (confirmReplace) {
        ConfirmDialog(
            title = stringResource(R.string.restore_confirm_title),
            text = stringResource(R.string.restore_confirm_body),
            confirmLabel = stringResource(R.string.restore_replace),
            onConfirm = { confirmReplace = false; viewModel.confirmRestore() },
            onDismiss = { confirmReplace = false },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
