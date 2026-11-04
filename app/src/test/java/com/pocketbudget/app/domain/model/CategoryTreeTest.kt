package com.pocketbudget.app.domain.model

import com.pocketbudget.app.ui.feature.settings.tree
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {

    private val tree = listOf(
        Category(1, "Food", CategoryKind.EXPENSE, null),
        Category(2, "Groceries", CategoryKind.EXPENSE, 1),
        Category(3, "Produce", CategoryKind.EXPENSE, 2),
        Category(4, "Transport", CategoryKind.EXPENSE, null),
    )

    @Test
    fun `descendants include every nested level`() {
        assertEquals(setOf(1L, 2L, 3L), tree.withDescendants(1))
        assertEquals(setOf(3L), tree.withDescendants(3))
        assertEquals(setOf(4L), tree.withDescendants(4))
    }

    @Test
    fun `labels show the full path`() {
        val labels = tree.pathLabels()
        assertEquals("Food › Groceries › Produce", labels[3])
        assertEquals("Transport", labels[4])
    }

    @Test
    fun `depth counts levels from the top`() {
        assertEquals(1, tree.depthOf(1))
        assertEquals(3, tree.depthOf(3))
        assertEquals(0, tree.depthOf(99))
    }

    @Test
    fun `height counts levels in the subtree`() {
        assertEquals(3, tree.heightOf(1))
        assertEquals(2, tree.heightOf(2))
        assertEquals(1, tree.heightOf(4))
    }

    @Test
    fun `tree lists each category before its children, siblings by name`() {
        val all = tree + Category(5, "dining", CategoryKind.EXPENSE, 1) + Category(6, "Salary", CategoryKind.INCOME, null)
        val expense = all.tree(CategoryKind.EXPENSE)
        assertEquals(listOf("Food", "dining", "Groceries", "Produce", "Transport"), expense.map { it.category.name })
        assertEquals(listOf(1, 2, 2, 3, 1), expense.map { it.depth })
        assertEquals(listOf("Salary"), all.tree(CategoryKind.INCOME).map { it.category.name })
    }
}
