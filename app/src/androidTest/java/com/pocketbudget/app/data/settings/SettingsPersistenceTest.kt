package com.pocketbudget.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.domain.model.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clear() {
        context.deleteSharedPreferences(SharedPreferencesSettingsRepository.FILE_NAME)
    }

    @Test
    fun defaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, SharedPreferencesSettingsRepository(context).themeMode.value)
    }

    @Test
    fun choiceSurvivesRestart() {
        ThemeMode.entries.forEach { mode ->
            SharedPreferencesSettingsRepository(context).setThemeMode(mode)
            // A new instance re-reads storage, as after a process restart.
            assertEquals(mode, SharedPreferencesSettingsRepository(context).themeMode.value)
        }
    }

    @Test
    fun changeIsPublishedImmediately() {
        val repo = SharedPreferencesSettingsRepository(context)
        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.themeMode.value)
    }

    @Test
    fun unknownStoredValueFallsBackToSystem() {
        context.getSharedPreferences(SharedPreferencesSettingsRepository.FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString("theme_mode", "PURPLE").commit()
        assertEquals(ThemeMode.SYSTEM, SharedPreferencesSettingsRepository(context).themeMode.value)
    }
}
