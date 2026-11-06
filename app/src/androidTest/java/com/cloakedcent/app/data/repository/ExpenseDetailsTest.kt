package com.cloakedcent.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.receipt.ReceiptImageStore
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.AnalysisPeriods
import com.cloakedcent.app.domain.model.DatePreset
import com.cloakedcent.app.domain.model.DateRange
import com.cloakedcent.app.domain.model.ExpenseDetails
import com.cloakedcent.app.domain.model.ExpenseItem
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.ReceiptStatus
import com.cloakedcent.app.domain.model.TransactionEdit
import com.cloakedcent.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Additional Options on a new expense: saved as a confirmed receipt with no image. */
@RunWith(AndroidJUnit4::class)
class ExpenseDetailsTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "expense-details-test")
    private lateinit var receipts: RoomReceiptRepository
    private lateinit var analysis: RoomAnalysisRepository
    private lateinit var products: RoomProductRepository
    private val today = LocalDate.of(2026, 11, 30)
    private val day = LocalDate.of(2026, 11, 3)
    private val november = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, DateRange(LocalDate.of(2026, 11, 1), today))
    private var checking = 0L
    private var groceries = 0L

    @Before
    fun setUp() = runTest {
        dir.deleteRecursively()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        receipts = RoomReceiptRepository({ t.db }, t.ledger, t.payees, ReceiptImageStore(dir, { key }), ReceiptRepositoryTest.FakeOcr())
        analysis = RoomAnalysisRepository({ t.db })
        products = RoomProductRepository({ t.db })
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(1_000_000))
        groceries = t.categories.observeAll().first().first { it.name == "Groceries" }.id
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun expense(
        cents: Long = 5_000,
        store: String = "Fresh Market",
        details: ExpenseDetails,
        category: Long? = groceries,
        scheduled: Boolean = false,
        request: String = "e1",
    ): Long {
        val payee = store.takeIf { it.isNotBlank() }?.let { t.payees.getOrCreate(it) }
        val entry = NewTransaction(request, checking, TransactionType.EXPENSE, Money(cents), day, scheduled, category, payee)
        return receipts.recordExpense(entry, store, details)
    }

    private suspend fun receiptOf(expense: Long) = receipts.observeDetail(receipts.observeReceiptFor(expense).first()!!).first()!!

    private suspend fun scalar(sql: String) = t.db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    private val everything = ExpenseDetails(
        tax = Money(500),
        items = listOf(ExpenseItem("Milk", Money(2_000)), ExpenseItem("Bread", Money(1_000))),
        storeAddress = "1 Main St",
    )

    @Test
    fun theDetailsBecomeAConfirmedReceiptWithNoImage() = runTest {
        val id = expense(details = everything)

        val receipt = receiptOf(id)
        assertEquals(ReceiptStatus.CONFIRMED, receipt.status)
        assertTrue(receipt.pages.isEmpty())
        assertEquals(id, receipt.transactionId)
        assertEquals("Fresh Market", receipt.review.storeName)
        assertEquals("1 Main St", receipt.review.storeAddress)
        assertEquals(Money(500), receipt.review.tax)
        assertEquals(Money(5_000), receipt.review.total)
        assertEquals(listOf("Milk", "Bread"), receipt.review.items.map { it.description })
        assertEquals(listOf(groceries, groceries), receipt.review.items.map { it.categoryId })
        assertEquals(1, receipts.observeSummaries().first().size)
    }

    @Test
    fun itFeedsStoreAndProductAnalysisWhileTheRemainderStaysInTheCategory() = runTest {
        expense(details = everything)

        val rows = analysis.observeCategories(november).first().associateBy { it.categoryId }
        assertEquals("the whole expense stays in its category", Money(5_000), rows[groceries]!!.total)
        val store = analysis.observeBrands(november).first().single()
        assertEquals("Fresh Market", store.name)
        assertEquals(Money(5_000), store.total)
        assertEquals("1 Main St", store.locations.single().address)
        val bought = analysis.observeProducts(november).first().associateBy { it.name }
        assertEquals(setOf("Milk", "Bread"), bought.keys)
        assertEquals(Money(2_000), bought.getValue("Milk").total)
    }

    @Test
    fun taxAndItemsMayEqualTheAmountButNotExceedIt() = runTest {
        val exact = ExpenseDetails(tax = Money(1_000), items = listOf(ExpenseItem("Milk", Money(4_000))))
        assertNotNull(receipts.observeReceiptFor(expense(details = exact)).first())

        val over = ExpenseDetails(tax = Money(1_000), items = listOf(ExpenseItem("Milk", Money(4_001))))
        try {
            expense(details = over, request = "e2")
            fail("Should have been refused")
        } catch (_: LedgerException.InvalidOperation) {
        }
        assertEquals("nothing was recorded", 1, scalar("SELECT COUNT(*) FROM transactions WHERE type = 'EXPENSE'"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM receipts"))
    }

    @Test
    fun anyOneOptionIsEnoughOnItsOwn() = runTest {
        val taxed = receiptOf(expense(details = ExpenseDetails(tax = Money(100)), request = "tax"))
        assertEquals(Money(100), taxed.review.tax)
        assertTrue(taxed.review.items.isEmpty())

        val located = receiptOf(expense(details = ExpenseDetails(storeAddress = "2 Side St"), request = "place"))
        assertEquals("2 Side St", located.review.storeAddress)

        val itemized = receiptOf(expense(details = ExpenseDetails(items = listOf(ExpenseItem("Tea", Money(300)))), request = "item"))
        assertEquals(1, itemized.review.items.size)
    }

    @Test
    fun withoutAPayeeThereIsNoStoreButTheAddressIsKept() = runTest {
        val id = expense(store = "", details = everything)

        val receipt = receiptOf(id)
        assertEquals("", receipt.review.storeName)
        assertEquals("1 Main St", receipt.review.storeAddress)
        assertNull(receipt.storeId)
        assertEquals(2, receipt.review.items.size)
        assertEquals(0, scalar("SELECT COUNT(*) FROM stores"))
        assertEquals("no store means no price history", 0, scalar("SELECT COUNT(*) FROM price_observations"))
        assertTrue(analysis.observeBrands(november).first().isEmpty())
    }

    @Test
    fun halfFilledItemsAreKeptButStayOutOfPriceHistory() = runTest {
        val id = expense(details = ExpenseDetails(items = listOf(ExpenseItem("Named only"), ExpenseItem("", Money(700)), ExpenseItem("Tea", Money(300)))))

        val items = receiptOf(id).review.items
        assertEquals(listOf("Named only", "Item", "Tea"), items.map { it.description })
        assertEquals(listOf(0L, 700L, 300L), items.map { it.net.cents })
        assertEquals(listOf("Tea"), analysis.observeProducts(november).first().map { it.name })
    }

    @Test
    fun withoutACategoryItemsStayUncategorised() = runTest {
        val id = expense(details = everything, category = null)

        assertEquals(listOf(null, null), receiptOf(id).review.items.map { it.categoryId })
        assertEquals(Money(5_000), analysis.observeCategories(november).first().first { it.categoryId == null }.total)
        assertEquals(setOf("Milk", "Bread"), analysis.observeProducts(november).first().map { it.name }.toSet())
    }

    @Test
    fun anUncategorisedExpenseDoesNotClearAProductsDefaultCategory() = runTest {
        expense(details = ExpenseDetails(items = listOf(ExpenseItem("Milk", Money(100)))))
        expense(details = ExpenseDetails(items = listOf(ExpenseItem("Milk", Money(100)))), category = null, request = "e2")

        assertEquals(groceries, products.observeProducts().first().single().product.defaultCategoryId)
    }

    @Test
    fun aScheduledExpenseCountsOnlyOnceSpent() = runTest {
        val id = expense(details = everything, scheduled = true)

        assertTrue(analysis.observeBrands(november).first().isEmpty())
        assertTrue(analysis.observeProducts(november).first().isEmpty())
        val milk = products.observeProducts().first().first { it.product.name == "Milk" }.product
        assertTrue("no price history yet", products.observePrices(milk.id).first().isEmpty())

        assertTrue(t.ledger.confirm(id, null))

        assertEquals(Money(5_000), analysis.observeBrands(november).first().single().total)
        assertEquals(setOf("Milk", "Bread"), analysis.observeProducts(november).first().map { it.name }.toSet())
        assertEquals(1, products.observePrices(milk.id).first().size)
    }

    @Test
    fun theAmountIsFixedOnceAReceiptDescribesIt() = runTest {
        val spent = expense(details = everything)
        val scheduled = expense(details = everything, scheduled = true, request = "e2")

        try {
            t.ledger.edit(spent, TransactionEdit(Money(9_000), day, groceries, null, null))
            fail("Should have been refused")
        } catch (_: LedgerException.InvalidOperation) {
        }
        try {
            t.ledger.confirm(scheduled, Money(9_000))
            fail("Should have been refused")
        } catch (_: LedgerException.InvalidOperation) {
        }
        // Everything but the amount can still change.
        t.ledger.edit(spent, TransactionEdit(Money(5_000), day, groceries, null, "Weekly shop"))
        assertEquals("Weekly shop", t.ledger.observeDetail(spent).first()!!.note)
    }

    @Test
    fun repeatingASaveRecordsItOnce() = runTest {
        val first = expense(details = everything)
        val second = expense(details = everything)

        assertEquals(first, second)
        assertEquals(1, scalar("SELECT COUNT(*) FROM receipts"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM transactions WHERE type = 'EXPENSE'"))
    }

    @Test
    fun deletingTheExpenseLeavesNothingBehind() = runTest {
        val id = expense(details = everything)

        receipts.delete(receipts.observeReceiptFor(id).first()!!, deleteTransaction = true)

        listOf("receipts", "receipt_items", "price_observations", "stores", "products", "transactions").forEach { table ->
            assertEquals("$table was left behind", 0, scalar("SELECT COUNT(*) FROM $table"))
        }
        assertFalse(t.payees.observeAll().first().any { it.name == "Fresh Market" })
    }

    @Test
    fun theWholeExpenseCanStillBeRefunded() = runTest {
        val id = expense(details = ExpenseDetails(items = listOf(ExpenseItem("Named only"), ExpenseItem("Tea", Money(300)))))
        val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        val refunds = RoomRefundRepository({ t.db }, clock)

        refunds.refundTransaction(id, Money(5_000), day, "refund")

        assertEquals(Money.ZERO, refunds.observeSummary(id).first()!!.remaining)
    }

    @Test
    fun anExpenseWithOnlyTaxCanStillBeRefunded() = runTest {
        val id = expense(details = ExpenseDetails(tax = Money(100)))
        val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        val refunds = RoomRefundRepository({ t.db }, clock)

        refunds.refundTransaction(id, Money(1_000), day, "refund")

        assertEquals(Money(4_000), refunds.observeSummary(id).first()!!.remaining)
    }
}
