package com.cloakedcent.app.ui.feature.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.CategoryTransaction
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CategoryTransactionsCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val date = LocalDate.of(2026, 11, 5)

    @Test
    fun showsTheNameAmountAndDateButNoStatus() {
        compose.setContent {
            CloakedCentTheme {
                CategoryTransactionsCard(listOf(CategoryTransaction(1, "Fresh Market", Money(8_642), date)), "Food", {})
            }
        }
        compose.onNodeWithText("Fresh Market").assertIsDisplayed()
        compose.onNodeWithText("$86.42").assertIsDisplayed()
        compose.onNodeWithText(date.format(analysisDate)).assertIsDisplayed()
        compose.onNodeWithText("Spent").assertDoesNotExist()
        compose.onNodeWithText("Confirmed").assertDoesNotExist()
    }

    @Test
    fun aTransactionWithoutANameUsesTheCategory() {
        compose.setContent {
            CloakedCentTheme {
                CategoryTransactionsCard(listOf(CategoryTransaction(1, null, Money(100), date)), "Food", {})
            }
        }
        compose.onNodeWithText("Food").assertIsDisplayed()
    }

    @Test
    fun tappingARowOpensThatTransaction() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            CloakedCentTheme {
                CategoryTransactionsCard(
                    listOf(CategoryTransaction(7, "First", Money(200), date), CategoryTransaction(9, "Second", Money(100), date)),
                    "Food",
                    { opened += it },
                )
            }
        }
        compose.onNodeWithText("Second").performClick()
        assertEquals(listOf(9L), opened)
    }
}
