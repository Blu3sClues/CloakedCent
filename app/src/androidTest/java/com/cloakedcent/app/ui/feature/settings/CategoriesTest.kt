package com.cloakedcent.app.ui.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.domain.model.Category
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.CategoryUsage
import com.cloakedcent.app.ui.theme.CloakedCentTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val food = Category(1, "Food", CategoryKind.EXPENSE, null)
    private val categories = listOf(
        food,
        Category(2, "Groceries", CategoryKind.EXPENSE, 1),
        Category(3, "Produce", CategoryKind.EXPENSE, 2),
        Category(4, "Transport", CategoryKind.EXPENSE, null),
        Category(5, "Salary", CategoryKind.INCOME, null),
    )

    private var added: Pair<CategoryKind, Category?>? = null
    private var deleted: Category? = null
    private var confirmed: Long? = -1
    private var dismissed = 0

    private fun show(state: CategoriesUiState) = compose.setContent {
        CloakedCentTheme {
            CategoriesScreen(
                state = state,
                onBack = {},
                onAdd = { kind, parent -> added = kind to parent },
                onDelete = { deleted = it },
                onConfirmAdd = {},
                onConfirmDelete = { confirmed = it },
                onDismiss = { dismissed++ },
            )
        }
    }

    @Test
    fun subCategoriesStopAtTheThirdLevel() {
        show(CategoriesUiState(categories))
        compose.onNodeWithText("Produce").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add a sub-category to Produce").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add a sub-category to Groceries").performClick()
        assertEquals(CategoryKind.EXPENSE to categories[1], added)
        compose.onNodeWithContentDescription("Delete Food").performClick()
        assertEquals(food, deleted)
    }

    @Test
    fun aCategoryInUseIsDeletedOnlyAfterChoosingWhereToMoveIt() {
        val usage = CategoryUsage(transactions = 2, budgets = 1, recurring = 0, subcategories = 2)
        show(CategoriesUiState(categories, deleting = DeleteRequest(food, usage, listOf(4L to "Transport"))))
        compose.onNodeWithText("This category and its sub-categories are used by 2 transactions and 1 budget", substring = true).assertIsDisplayed()
        compose.onNodeWithText("along with its 2 sub-categories", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Move and delete").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
        assertEquals(-1L, confirmed)

        compose.onNodeWithText("Move everything to").performClick()
        compose.onAllNodesWithText("Transport").onLast().performClick()
        compose.onNodeWithText("Move and delete").assertIsEnabled().performClick()
        assertEquals(4L, confirmed)
    }

    @Test
    fun anUnusedCategoryOnlyAsksForConfirmation() {
        show(CategoriesUiState(categories, deleting = DeleteRequest(categories[3], CategoryUsage(0, 0, 0, 0), emptyList())))
        compose.onNodeWithText("permanently erased", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(null, confirmed)
    }

    @Test
    fun withoutAnotherCategoryToMoveToItCannotBeDeleted() {
        val salary = categories[4]
        show(CategoriesUiState(categories, deleting = DeleteRequest(salary, CategoryUsage(3, 0, 0, 0), emptyList())))
        compose.onNodeWithText("There is no other category", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Move and delete").assertDoesNotExist()
    }
}
