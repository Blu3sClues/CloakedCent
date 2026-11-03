package com.pocketbudget.app.data.settings

import android.content.Context
import androidx.core.content.edit
import com.pocketbudget.app.domain.model.ThemeMode
import com.pocketbudget.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-financial UI preferences in app-private SharedPreferences (excluded from
 * all backups by the manifest). Financial data never goes here.
 */
class SharedPreferencesSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        // Synchronous commit: a tiny write, and the choice must survive an immediate restart.
        prefs.edit(commit = true) { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    private val _keepReceiptImages = MutableStateFlow(prefs.getBoolean(KEY_KEEP_RECEIPT_IMAGES, true))
    override val keepReceiptImages: StateFlow<Boolean> = _keepReceiptImages.asStateFlow()

    override fun setKeepReceiptImages(keep: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_KEEP_RECEIPT_IMAGES, keep) }
        _keepReceiptImages.value = keep
    }

    private fun readThemeMode(): ThemeMode =
        prefs.getString(KEY_THEME, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM

    companion object {
        const val FILE_NAME = "pocket_budget_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_KEEP_RECEIPT_IMAGES = "keep_receipt_images"
    }
}
