package com.cloakedcent.app.ui.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.cloakedcent.app.ui.feature.backup.restartApp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloakedcent.app.CloakedCentApp
import com.cloakedcent.app.R
import com.cloakedcent.app.domain.model.ThemeMode
import com.cloakedcent.app.domain.repository.SettingsRepository
import com.cloakedcent.app.domain.repository.StorageRepository
import com.cloakedcent.app.ui.components.BackTopBar
import com.cloakedcent.app.ui.components.ConfirmDialog
import com.cloakedcent.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val storage: StorageRepository,
    private val scheduleSecureDelete: suspend () -> Unit,
) : ViewModel() {
    private val _secureDeleteFailed = MutableStateFlow(false)
    val secureDeleteFailed: StateFlow<Boolean> = _secureDeleteFailed.asStateFlow()

    /** [restart] runs once the delete is safely recorded; it is carried out as the app starts again. */
    fun secureDelete(restart: () -> Unit) {
        viewModelScope.launch {
            _secureDeleteFailed.value = false
            runCatching { scheduleSecureDelete() }
                .onSuccess { restart() }
                .onFailure { _secureDeleteFailed.value = true }
        }
    }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    val keepReceiptImages: StateFlow<Boolean> = settings.keepReceiptImages
    fun setKeepReceiptImages(keep: Boolean) = settings.setKeepReceiptImages(keep)

    private val _cipherVersion = MutableStateFlow<String?>(null)

    /** SQLCipher version reported by the open database; null until checked. */
    val cipherVersion: StateFlow<String?> = _cipherVersion.asStateFlow()

    init {
        viewModelScope.launch {
            // A storage failure is already reported on the dashboard; here the line just stays pending.
            _cipherVersion.value = runCatching { storage.openStorage().cipherVersion }.getOrNull()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CloakedCentApp).container
                SettingsViewModel(container.settingsRepository, container.storageRepository, container::scheduleSecureDelete)
            }
        }
    }
}

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onLicences: () -> Unit = {},
    onCategories: () -> Unit = {},
    onLicenseInformation: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val secureDeleteFailed by viewModel.secureDeleteFailed.collectAsStateWithLifecycle()
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    val cipherVersion by viewModel.cipherVersion.collectAsStateWithLifecycle()
    val keepReceiptImages by viewModel.keepReceiptImages.collectAsStateWithLifecycle()
    SettingsScreen(
        mode,
        onBack,
        viewModel::setThemeMode,
        onBackup,
        onRestore,
        cipherVersion,
        onLicences,
        keepReceiptImages,
        viewModel::setKeepReceiptImages,
        onCategories,
        onLicenseInformation,
        { viewModel.secureDelete { restartApp(context) } },
        secureDeleteFailed,
    )
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    cipherVersion: String? = null,
    onLicences: () -> Unit = {},
    keepReceiptImages: Boolean = true,
    onKeepReceiptImagesChange: (Boolean) -> Unit = {},
    onCategories: () -> Unit = {},
    onLicenseInformation: () -> Unit = {},
    onSecureDelete: () -> Unit = {},
    secureDeleteFailed: Boolean = false,
) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionHeader(stringResource(R.string.settings_theme))
            Column(Modifier.selectableGroup()) {
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == themeMode, role = Role.RadioButton) { onThemeModeChange(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == themeMode, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            SectionHeader(stringResource(R.string.settings_categories))
            Text(stringResource(R.string.settings_categories_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = onCategories, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.categories_title)) }
            SectionHeader(stringResource(R.string.settings_receipts))
            var confirmingImageDeletion by rememberSaveable { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = keepReceiptImages, role = Role.Switch) { keep ->
                        // Turning images off loses data later, so it needs confirming; turning them back on does not.
                        if (keep) onKeepReceiptImagesChange(true) else confirmingImageDeletion = true
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_keep_receipt_images), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            if (keepReceiptImages) R.string.settings_keep_receipt_images_on else R.string.settings_keep_receipt_images_off,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepReceiptImages, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
            }
            if (confirmingImageDeletion) {
                ConfirmDialog(
                    title = stringResource(R.string.settings_keep_receipt_images_confirm_title),
                    text = stringResource(R.string.settings_keep_receipt_images_confirm_body),
                    confirmLabel = stringResource(R.string.settings_keep_receipt_images_confirm_action),
                    onConfirm = {
                        confirmingImageDeletion = false
                        onKeepReceiptImagesChange(false)
                    },
                    onDismiss = { confirmingImageDeletion = false },
                )
            }
            SectionHeader(stringResource(R.string.settings_backup))
            Text(stringResource(R.string.settings_backup_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_title)) }
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.restore_title)) }

            SectionHeader(stringResource(R.string.settings_about))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = cipherVersion?.let { stringResource(R.string.settings_storage_encrypted, it) }
                        ?: stringResource(R.string.settings_storage_checking),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium)
            AppVersion()
            OutlinedButton(onClick = onLicences, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_licences))
            }
            OutlinedButton(onClick = onLicenseInformation, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_license_information))
            }

            var confirmingSecureDelete by rememberSaveable { mutableStateOf(false) }
            Button(
                onClick = { confirmingSecureDelete = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SecureDeleteRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            ) {
                Text(stringResource(R.string.settings_secure_delete))
            }
            Text(
                stringResource(R.string.settings_secure_delete_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (secureDeleteFailed) {
                Text(
                    stringResource(R.string.settings_secure_delete_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (confirmingSecureDelete) {
                SecureDeleteDialog(
                    onConfirm = {
                        confirmingSecureDelete = false
                        onSecureDelete()
                    },
                    onDismiss = { confirmingSecureDelete = false },
                )
            }
        }
    }
}

/** The word must be typed exactly, so this cannot be confirmed by accident. */
@Composable
private fun SecureDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.secure_delete_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.secure_delete_confirm_body))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.secure_delete_confirm_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typed == SECURE_DELETE_WORD,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.settings_secure_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val SECURE_DELETE_WORD = "CONFIRM"

/** The theme's error colour is a pale pink in dark mode; this stays red in both themes. */
private val SecureDeleteRed = Color(0xFFC62828)

@Composable
private fun AppVersion() {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    } ?: return
    Text(
        stringResource(R.string.settings_version, version),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
