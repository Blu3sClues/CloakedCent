package com.pocketbudget.app.domain.model

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
}
