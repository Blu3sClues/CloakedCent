package com.cloakedcent.app.ui.navigation

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun barListsEverySectionAndReportsTheChoice() {
        val chosen = mutableListOf<TopLevelDestination>()
        compose.setContent { CloakedCentTheme { CloakedCentNavigationBar(TopLevelDestination.HOME) { chosen += it } } }

        compose.onNodeWithText("Home").assertIsSelected()
        listOf("Analysis", "Receipts", "Budgets", "Recurring").forEach { compose.onNodeWithText(it).performClick() }
        // Re-selecting the current section does not navigate again.
        compose.onNodeWithText("Home").performClick()

        assertEquals(
            listOf(TopLevelDestination.ANALYSIS, TopLevelDestination.RECEIPTS, TopLevelDestination.BUDGETS, TopLevelDestination.RECURRING),
            chosen,
        )
    }
}
