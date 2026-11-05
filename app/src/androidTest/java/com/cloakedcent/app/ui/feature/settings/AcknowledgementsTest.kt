package com.cloakedcent.app.ui.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cloakedcent.app.domain.model.ThemeMode
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcknowledgementsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyShippedLicenceTextIsBundledAndReadable() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        License.entries.mapNotNull { it.text }.forEach { id ->
            val text = resources.openRawResource(id).bufferedReader().use { it.readText() }
            assertTrue("licence ${resources.getResourceEntryName(id)} is empty", text.length > 200)
        }
        // libjpeg requires this exact acknowledgement wherever it is credited.
        assertTrue(ACKNOWLEDGEMENTS.any { "based in part on the work of the Independent JPEG Group" in it.copyright })
    }

    @Test
    fun settingsShowsEncryptionStatusAndOpensLicences() {
        var opened = 0
        compose.setContent {
            CloakedCentTheme { SettingsScreen(ThemeMode.SYSTEM, {}, {}, cipherVersion = "4.19.1 community", onLicences = { opened++ }) }
        }
        compose.onNodeWithText("Encrypted on this device with SQLCipher 4.19.1 community · Offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun tappingAnEntryShowsItsFullLicence() {
        compose.setContent { CloakedCentTheme { AcknowledgementsRoute(onBack = {}) } }
        compose.onNodeWithText("Bouncy Castle (bcprov-jdk18on) 1.86").performClick()
        compose.onNodeWithText("MIT licence (Bouncy Castle)").assertIsDisplayed()
        compose.onNodeWithText("Permission is hereby granted", substring = true).assertIsDisplayed()
    }
}
