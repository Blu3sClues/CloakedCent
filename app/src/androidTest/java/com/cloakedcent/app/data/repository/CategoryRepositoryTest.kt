package com.cloakedcent.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.BudgetDraft
import com.cloakedcent.app.domain.model.BudgetPeriodType
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.CategoryUsage
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.RevisionAction
import com.cloakedcent.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CategoryRepositoryTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val today = LocalDate.now()
    private var account = 0L
    private var food = 0L
    private var groceries = 0L
    private var salary = 0L

    @Before
    fun setUp() = runTest {
        account = t.accounts.create("Checking", AccountType.CHECKING, Money(100_000))
        val all = t.categories.observeAll().first().associateBy { it.name }
        food = all.getValue("Food").id
        groceries = all.getValue("Groceries").id
        salary = all.getValue("Salary").id
    }

    private suspend inline fun <reified E : Throwable> expect(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${E::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is E) throw e
        }
    }

    private suspend fun expense(id: String, cents: Long, category: Long?) =
        t.ledger.record(NewTransaction(id, account, TransactionType.EXPENSE, Money(cents), today, categoryId = category))

    private fun categoryCount() = t.count("SELECT COUNT(*) FROM categories")

    @Test
    fun nestingIsLimitedToThreeLevelsAndSiblingNamesAreUnique() = runTest {
        val produce = t.categories.create("Produce", CategoryKind.EXPENSE, groceries)
        expect<LedgerException.CategoryTooDeep> { t.categories.create("Apples", CategoryKind.EXPENSE, produce) }
        expect<LedgerException.CategoryNameTaken> { t.categories.create(" groceries ", CategoryKind.EXPENSE, food) }
        // The same name is fine elsewhere in the tree, or for the other kind.
        t.categories.create("Produce", CategoryKind.EXPENSE, food)
        t.categories.create("Food", CategoryKind.INCOME)
        expect<LedgerException.CategoryMismatch> { t.categories.create("Bonus", CategoryKind.INCOME, food) }
        expect<LedgerException.NameRequired> { t.categories.create("  ", CategoryKind.EXPENSE) }
    }

    @Test
    fun reparentingRespectsTheNestingLimit() = runTest {
        val travel = t.categories.create("Travel", CategoryKind.EXPENSE)
        t.categories.create("Flights", CategoryKind.EXPENSE, travel)
        // Travel › Flights under Food › Groceries would be four levels deep.
        expect<LedgerException.CategoryTooDeep> { t.categories.setParent(travel, groceries) }
        t.categories.setParent(travel, food)
        assertEquals(food, t.categories.observeAll().first().single { it.id == travel }.parentId)
    }

    @Test
    fun anUnusedCategoryIsErasedWithItsSubcategories() = runTest {
        val produce = t.categories.create("Produce", CategoryKind.EXPENSE, groceries)
        val before = categoryCount()
        assertEquals("Groceries, Dining out and Produce", CategoryUsage(0, 0, 0, 3), t.categories.usage(food))
        t.categories.delete(food)
        t.categories.delete(food) // repeat is harmless
        assertEquals(before - 4, categoryCount())
        val ids = t.categories.observeAll().first().map { it.id }
        assertTrue(listOf(food, groceries, produce).none { it in ids })
    }

    @Test
    fun aCategoryInUseCannotBeDeletedWithoutMovingWhatUsesIt() = runTest {
        val produce = t.categories.create("Produce", CategoryKind.EXPENSE, groceries)
        val other = t.categories.observeAll().first().single { it.name == "Other expenses" }.id
        val tx = expense("e1", 1_200, produce)
        val before = categoryCount()

        // In use through a sub-category: refused, and nothing changes.
        assertEquals(CategoryUsage(1, 0, 0, 3), t.categories.usage(food))
        expect<LedgerException.CategoryInUse> { t.categories.delete(food) }
        // The target must be the same kind and outside what is being deleted.
        expect<LedgerException.CategoryMismatch> { t.categories.delete(food, moveTo = salary) }
        expect<LedgerException.InvalidOperation> { t.categories.delete(food, moveTo = groceries) }
        assertEquals(before, categoryCount())
        assertEquals(produce, t.db.transactionDao().getById(tx)!!.categoryId)
        assertEquals(0L, t.count("SELECT COUNT(*) FROM categories WHERE is_archived = 1"))

        t.categories.delete(food, moveTo = other)
        assertEquals(before - 4, categoryCount())
        val detail = t.ledger.observeDetail(tx).first()!!
        assertEquals(other, detail.categoryId)
        // The move is an edit: the earlier category is kept in the history row.
        assertEquals(RevisionAction.UPDATE, detail.history.single().action)
        assertEquals(produce, t.db.transactionDao().getRevisions(tx).single().categoryId)
        assertEquals(Money(100_000 - 1_200), t.accounts.observeBalances().first().single().current)
    }

    @Test
    fun movingCarriesSplitsRefundsReceiptsBudgetsAndRecurringAlong() = runTest {
        val snacks = t.categories.create("Snacks", CategoryKind.EXPENSE, food)
        // A receipt expense split between Snacks and Groceries, as confirming a receipt writes it.
        val tx = expense("split", 1_000, groceries)
        t.execSql("INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, created_at) VALUES (?, ?, 400, 0), (?, ?, 600, 0)", tx, snacks, tx, groceries)
        t.execSql(
            "INSERT INTO receipts (id, request_id, status, store_name, store_address, tax_cents, discount_cents, total_cents, receipt_number, default_category_id, transaction_id, uncertain_fields, field_sources, created_at, updated_at) VALUES (1, 'q', 'CONFIRMED', 'Shop', '', 0, 0, 1000, '', ?, ?, '', '', 0, 0)",
            snacks, tx,
        )
        t.execSql(
            "INSERT INTO receipt_items (receipt_id, position, raw_text, description, quantity_milli, unit, line_total_cents, discount_cents, category_id, confidence, uncertain, user_verified) VALUES (1, 0, 'CHIPS', 'Chips', 1000, 'EACH', 400, 0, ?, 90, 0, 1)",
            snacks,
        )
        // A refund of a Snacks expense, a budget and a recurring item on Snacks.
        val chips = expense("chips", 300, snacks)
        RoomRefundRepository({ t.db }).refundTransaction(chips, Money(100), today, "refund")
        RoomBudgetRepository({ t.db }).create(BudgetDraft("Snacks", snacks, Money(5_000), BudgetPeriodType.MONTHLY, today.withDayOfMonth(1)))
        t.execSql(
            "INSERT INTO recurring_schedules (name, kind, type, account_id, amount_cents, variable_amount, category_id, frequency, interval_count, start_date, state, created_at, updated_at) VALUES ('Snack box', 'SUBSCRIPTION', 'EXPENSE', ?, -1500, 0, ?, 'MONTHLY', 1, 20400, 'ACTIVE', 0, 0)",
            account, snacks,
        )
        assertEquals(CategoryUsage(transactions = 3, budgets = 1, recurring = 1, subcategories = 0), t.categories.usage(snacks))

        t.categories.delete(snacks, moveTo = groceries)

        // The split expense now has one Groceries split for the whole amount.
        assertEquals(listOf(groceries to 1_000L), t.db.receiptDao().getSplits(tx).map { it.categoryId to it.amountCents })
        assertEquals(groceries, t.db.transactionDao().getById(chips)!!.categoryId)
        assertEquals(0L, t.count("SELECT COUNT(*) FROM refund_allocations WHERE category_id = $snacks"))
        assertEquals(1L, t.count("SELECT COUNT(*) FROM refund_allocations WHERE category_id = $groceries"))
        assertEquals(groceries, t.count("SELECT default_category_id FROM receipts WHERE id = 1"))
        assertEquals(groceries, t.count("SELECT category_id FROM receipt_items"))
        assertEquals(groceries, t.count("SELECT category_id FROM budgets"))
        assertEquals(groceries, t.count("SELECT category_id FROM recurring_schedules"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM categories WHERE id = $snacks OR is_archived = 1"))
        // Balances are untouched by recategorising.
        assertEquals(Money(100_000 - 1_000 - 300 + 100), t.accounts.observeBalances().first().single().current)
    }

    @Test
    fun lockedRecordsStillCannotChangeCategoryOutsideADeletion() = runTest {
        val snacks = t.categories.create("Snacks", CategoryKind.EXPENSE, food)
        val tx = expense("split", 1_000, groceries)
        t.execSql("INSERT INTO transaction_splits (transaction_id, category_id, amount_cents, created_at) VALUES (?, ?, 1000, 0)", tx, snacks)
        assertTrue(runCatching { t.execSql("DELETE FROM transaction_splits") }.isFailure)
        assertTrue(runCatching { t.execSql("UPDATE transaction_splits SET category_id = ?", groceries) }.isFailure)
        assertTrue(runCatching { t.execSql("DELETE FROM categories WHERE id = ?", snacks) }.isFailure)
    }
}
