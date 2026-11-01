package com.pocketbudget.app.ui.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import com.pocketbudget.app.ui.theme.PocketBudgetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyStateShowsBrandingAndEncryptionStatus() {
        composeRule.setContent {
            PocketBudgetTheme {
                HomeScreen(
                    uiState = HomeUiState.Ready(StorageStatus(null, "4.x community")),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Pocket Budget").assertIsDisplayed()
        composeRule.onNodeWithText("Database encrypted with SQLCipher (4.x community)", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun errorStateReassuresAndOffersRetry() {
        var retried = false
        composeRule.setContent {
            PocketBudgetTheme {
                HomeScreen(uiState = HomeUiState.Error(Reason.KEY_MISSING), onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithText("Your data has not been deleted", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }
}
