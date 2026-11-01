package com.pocketbudget.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.RevisionAction
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionSearchTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private var checking = 0L
    private var savings = 0L
    private var food = 0L
    private var groceries = 0L
    private var market = 0L
    private val nov1 = LocalDate.of(2026, 11, 1)

    @Before
    fun seed() = runTest {
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(100_000))
        savings = t.accounts.create("Savings", AccountType.SAVINGS)
        val categories = t.categories.observeAll().first().associateBy { it.name }
        food = categories.getValue("Food").id
        groceries = categories.getValue("Groceries").id
        market = t.payees.getOrCreate("Fresh Market")

        suspend fun expense(id: String, cents: Long, day: Int, category: Long?, payee: Long?, note: String? = null) =
            t.ledger.record(
                NewTransaction(id, checking, TransactionType.EXPENSE, Money(cents), nov1.plusDays(day.toLong()),
                    false, category, payee, note),
            )
        expense("groceries", 4_000, 0, groceries, market)
        expense("restaurant", 2_500, 5, food, null, note = "100% tasty")
        expense("bus", 300, 10, null, null)
        t.ledger.recordTransfer(NewTransfer("move", checking, savings, Money(10_000), nov1.plusDays(12)))
    }

    private suspend fun search(filter: TransactionFilter) = t.ledger.search(filter).first()

    @Test
    fun emptyFilterListsEverythingWithTransfersOnce() = runTest {
        assertEquals(4, search(TransactionFilter()).size)
    }

    @Test
    fun accountFilterShowsThatAccountsTransferLeg() = runTest {
        val results = search(TransactionFilter(accountId = savings))
        assertEquals(1, results.size)
        assertEquals(Money(10_000), results.single().amount)
        assertEquals("Checking", results.single().counterpartAccountName)
    }

    @Test
    fun dateRangeIsInclusive() = runTest {
        val results = search(TransactionFilter(start = nov1.plusDays(5), endInclusive = nov1.plusDays(10)))
        assertEquals(listOf(Money(-300), Money(-2_500)), results.map { it.amount })
    }

    @Test
    fun categoryFilterIncludesSubcategories() = runTest {
        assertEquals(2, search(TransactionFilter(categoryId = food)).size)
        assertEquals(1, search(TransactionFilter(categoryId = groceries)).size)
    }

    @Test
    fun merchantAndTextQueriesMatch() = runTest {
        assertEquals(1, search(TransactionFilter(payeeId = market)).size)
        assertEquals(1, search(TransactionFilter(query = "fresh")).size)
        assertEquals(1, search(TransactionFilter(query = "food")).size) // category name
        assertEquals(4, search(TransactionFilter(query = "checking")).size) // account name
        // '%' is matched literally, not as a wildcard.
        assertEquals(listOf(Money(-2_500)), search(TransactionFilter(query = "100%")).map { it.amount })
    }

    @Test
    fun deletedEntriesDisappearFromSearchButKeepAuditDetail() = runTest {
        val id = search(TransactionFilter(payeeId = market)).single().id
        t.ledger.edit(id, TransactionEdit(Money(4_500), nov1, groceries, market, "bigger shop"))
        t.ledger.delete(id)

        assertTrue(search(TransactionFilter(payeeId = market)).isEmpty())
        val detail = t.ledger.observeDetail(id).first()!!
        assertTrue(detail.isDeleted)
        assertEquals(listOf(RevisionAction.UPDATE, RevisionAction.DELETE), detail.history.map { it.action })
        assertEquals(Money(-4_000), detail.history.first().amount)
        assertEquals("Groceries", detail.history.first().categoryName)
        assertEquals("Fresh Market", detail.history.first().payeeName)
    }

    @Test
    fun transferDetailNamesTheOtherAccount() = runTest {
        val leg = search(TransactionFilter(accountId = savings)).single()
        val detail = t.ledger.observeDetail(leg.id).first()!!
        assertEquals(checking, detail.counterpartAccountId)
        assertEquals(savings, detail.accountId)
    }

    @Test
    fun accountsCanBeRenamedButNotAfterArchiving() = runTest {
        t.accounts.update(savings, "Rainy day", AccountType.SAVINGS, includeInTotals = false)
        val renamed = t.accounts.get(savings)!!
        assertEquals("Rainy day", renamed.name)
        assertEquals(false, renamed.includeInTotals)

        val empty = t.accounts.create("Old", AccountType.CASH)
        t.accounts.archive(empty)
        assertNull(t.accounts.get(empty))
        try {
            t.accounts.update(empty, "Again", AccountType.CASH, true)
            fail("Archived accounts are read-only")
        } catch (expected: LedgerException.AccountUnavailable) {
        }
        try {
            t.accounts.update(savings, "  ", AccountType.SAVINGS, true)
            fail("Blank names are rejected")
        } catch (expected: LedgerException.NameRequired) {
        }
    }
}
