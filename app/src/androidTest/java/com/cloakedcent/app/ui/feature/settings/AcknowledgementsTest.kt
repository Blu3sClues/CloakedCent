package com.cloakedcent.app.ui.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextReplacement
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
    fun settingsOpensTheProjectLicense() {
        var opened = 0
        compose.setContent {
            CloakedCentTheme { SettingsScreen(ThemeMode.SYSTEM, {}, {}, onLicenseInformation = { opened++ }) }
        }
        compose.onNodeWithText("License Information").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun licenseInformationShowsTheApacheLicense() {
        compose.setContent { CloakedCentTheme { LicenseInformationRoute(onBack = {}) } }
        compose.onNodeWithText("License Information").assertIsDisplayed()
        compose.onNodeWithText("CloakedCent is licensed under the Apache License 2.0.").assertIsDisplayed()
        compose.onNodeWithText("TERMS AND CONDITIONS FOR USE", substring = true).assertExists()
    }

    @Test
    fun secureDeleteNeedsTheExactWordAndWarnsAboutBackups() {
        var deleted = 0
        compose.setContent {
            CloakedCentTheme { SettingsScreen(ThemeMode.SYSTEM, {}, {}, onSecureDelete = { deleted++ }) }
        }
        compose.onNodeWithText("It can only be restored if you took a backup beforehand", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Secure delete").performScrollTo().performClick()
        compose.onNodeWithText("cannot be undone", substring = true).assertIsDisplayed()

        val confirm = compose.onNode(hasText("Secure delete") and hasClickAction() and isInDialog())
        confirm.assertIsNotEnabled()
        val field = compose.onNode(hasSetTextAction())
        listOf("confirm", "CONFIRM ", "CONFIR").forEach { wrong ->
            field.performTextReplacement(wrong)
            confirm.assertIsNotEnabled()
        }
        field.performTextReplacement("CONFIRM")
        confirm.assertIsEnabled().performClick()
        assertEquals(1, deleted)
    }

    private fun isInDialog() = hasAnyAncestor(isDialog())

    @Test
    fun tappingAnEntryShowsItsFullLicence() {
        compose.setContent { CloakedCentTheme { AcknowledgementsRoute(onBack = {}) } }
        compose.onNodeWithText("Bouncy Castle (bcprov-jdk18on) 1.86").performClick()
        compose.onNodeWithText("MIT licence (Bouncy Castle)").assertIsDisplayed()
        compose.onNodeWithText("Permission is hereby granted", substring = true).assertIsDisplayed()
    }
}
