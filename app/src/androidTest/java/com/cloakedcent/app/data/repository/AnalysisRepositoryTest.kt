package com.cloakedcent.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.receipt.ReceiptImageStore
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.AdjustmentKind
import com.cloakedcent.app.domain.model.AnalysisPeriods
import com.cloakedcent.app.domain.model.ConfirmTarget
import com.cloakedcent.app.domain.model.DatePreset
import com.cloakedcent.app.domain.model.DateRange
import com.cloakedcent.app.domain.model.Dimension
import com.cloakedcent.app.domain.model.ImportOutcome
import com.cloakedcent.app.domain.model.MAX_CATEGORY_TRANSACTIONS
import com.cloakedcent.app.domain.model.NewAdjustment
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.NewTransfer
import com.cloakedcent.app.domain.model.PageInput
import com.cloakedcent.app.domain.model.ReceiptItemDraft
import com.cloakedcent.app.domain.model.ReceiptReview
import com.cloakedcent.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class AnalysisRepositoryTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "analysis-test")
    private lateinit var receipts: RoomReceiptRepository
    private lateinit var analysis: RoomAnalysisRepository
    private val today = LocalDate.of(2026, 11, 30)
    private val november = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, DateRange(LocalDate.of(2026, 11, 1), today))
    private var checking = 0L
    private var savings = 0L
    private var food = 0L
    private var groceries = 0L
    private var dining = 0L
    private var housing = 0L
    private var otherIncome = 0L

    @Before
    fun setUp() = runTest {
        dir.deleteRecursively()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        receipts = RoomReceiptRepository({ t.db }, t.ledger, t.payees, ReceiptImageStore(dir, { key }), ReceiptRepositoryTest.FakeOcr())
        analysis = RoomAnalysisRepository({ t.db })
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(1_000_000))
        savings = t.accounts.create("Savings", AccountType.SAVINGS)
        val c = t.categories.observeAll().first().associateBy { it.name }
        food = c.getValue("Food").id
        groceries = c.getValue("Groceries").id
        dining = c.getValue("Dining out").id
        housing = c.getValue("Housing").id
        otherIncome = c.getValue("Other income").id
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun day(d: Int, month: Int = 11) = LocalDate.of(2026, month, d)

    private suspend fun expense(id: String, cents: Long, date: LocalDate, category: Long?, scheduled: Boolean = false) =
        t.ledger.record(NewTransaction(id, checking, TransactionType.EXPENSE, Money(cents), date, scheduled, category))

    private fun photo(seed: Int): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until 4) for (x in 0 until 3) {
            paint.color = android.graphics.Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawRect(x * 100f, y * 100f, x * 100f + 100, y * 100f + 100, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun item(name: String, cents: Long, category: Long) =
        ReceiptItemDraft(rawText = name, description = name, lineTotal = Money(cents), categoryId = category)

    /** A receipt confirmed through the normal review path. Returns the expense id. */
    private suspend fun receipt(
        seed: Int,
        store: String,
        address: String,
        date: LocalDate,
        items: List<ReceiptItemDraft>,
        tax: Long = 0,
        target: ConfirmTarget = ConfirmTarget.NewExpense(checking),
    ): Long {
        val id = (receipts.import(listOf(PageInput(photo(seed))), "r$seed") as ImportOutcome.Created).receiptId
        val review = ReceiptReview(
            storeName = store,
            storeAddress = address,
            purchasedOn = date,
            tax = Money(tax),
            total = Money(items.sumOf { it.lineTotal.cents } + tax),
            items = items,
        )
        receipts.saveReview(id, review)
        return receipts.confirm(id, review, target)
    }

    /** The shared scenario: counted spending, excluded entries and receipts. */
    private suspend fun scenario() {
        expense("g", 1_000, day(3), groceries)
        expense("d", 500, day(5), dining)
        expense("f", 200, day(6), food)
        expense("u", 300, day(7), null)
        expense("prev", 800, day(15, 10), groceries)

        // Never spending: scheduled, deleted, income (incl. refunds), transfers, adjustments.
        expense("scheduled", 9_999, day(10), groceries, scheduled = true)
        t.ledger.delete(expense("deleted", 7_777, day(11), groceries))
        t.ledger.record(NewTransaction("refund", checking, TransactionType.INCOME, Money(5_000), day(12), categoryId = otherIncome, note = "Refund"))
        t.ledger.recordTransfer(NewTransfer("move", checking, savings, Money(4_444), day(12)))
        t.ledger.adjustBalance(NewAdjustment("adj1", checking, Money(-1_111), AdjustmentKind.BALANCE_ONLY, day(12)))
        t.ledger.adjustBalance(NewAdjustment("adj2", checking, Money(-2_222), AdjustmentKind.EXPENSE, day(12)))

        // Receipts: two locations of one retailer, split across categories.
        receipt(1, "Fresh Mart", "1 Main St", day(14), listOf(item("Milk 1L", 349, groceries), item("Dish soap", 500, housing)), tax = 65)
        receipt(2, "Fresh Mart", "9 Elm St", day(20), listOf(item("Milk 1L", 399, groceries)))
        // Attached to an expense that already exists: counted once, now split to Groceries.
        val manual = expense("bread", 600, day(22), dining)
        receipt(3, "Corner Shop", "", day(22), listOf(item("Bread", 600, groceries)), target = ConfirmTarget.Existing(manual))
        // A receipt whose expense was later deleted no longer counts anywhere.
        t.ledger.delete(receipt(4, "Fresh Mart", "1 Main St", day(25), listOf(item("Eggs", 450, groceries))))
    }

    @Test
    fun overviewCountsOnlyCompletedSpendingOnce() = runTest {
        scenario()
        val overview = analysis.observeOverview(november).first()
        // 1000 + 500 + 200 + 300 + 914 (receipt) + 399 (receipt) + 600 (attached, once)
        assertEquals(Money(3_913), overview.total.current)
        assertEquals(7L, overview.purchases)
        assertEquals(Money(559), overview.averagePurchase) // 3913 / 7 = 559.0
        assertEquals(Money(800), overview.total.previous)
        assertEquals(38913L, overview.total.changeBasisPoints) // +389.13% (half up)
        assertEquals(3_913L, overview.trend.sumOf { it.amount.cents })
        assertEquals(listOf("Food", "Housing", ""), overview.topCategories.map { it.name })
        assertEquals("FRESHMART", overview.topBrands.first().brandKey)
        assertEquals(listOf("Milk 1L", "Bread", "Dish soap"), overview.topProducts.map { it.name })
    }

    @Test
    fun categoryTotalsRollUpWithoutDoubleCounting() = runTest {
        scenario()
        val rows = analysis.observeCategories(november).first().associateBy { it.categoryId }
        // Groceries: 1000 + 376 (split of 914) + 399 + 600 (attached receipt re-categorised)
        assertEquals(Money(2_375), rows[groceries]!!.total)
        assertEquals(Money(500), rows[dining]!!.total)
        assertEquals(Money(200), rows[food]!!.own)
        assertEquals(Money(3_075), rows[food]!!.total)
        assertEquals(Money(538), rows[housing]!!.total)
        assertEquals(Money(300), rows[null]!!.total)
        assertEquals(3_913L, rows.values.filter { it.depth == 0 }.sumOf { it.total.cents })
        assertEquals(Money(800), rows[groceries]!!.previousTotal)

        val detail = analysis.observeCategory(food, november).first()!!
        assertEquals(listOf(groceries, dining), detail.subcategories.map { it.categoryId })
        assertEquals(3_075L, detail.trend.sumOf { it.amount.cents })
        assertEquals(listOf("Milk 1L", "Bread"), detail.topProducts.map { it.name })
        assertNull(analysis.observeCategory(999, november).first())
    }

    private suspend fun transactions(category: Long) = analysis.observeCategory(category, november).first()!!.transactions

    @Test
    fun categoryTransactionsAreLargestFirstThenNewest() = runTest {
        val small = expense("small", 100, day(20), groceries)
        val olderTie = expense("older", 500, day(3), groceries)
        val newerTie = expense("newer", 500, day(9), groceries)
        val large = expense("large", 900, day(1), groceries)

        assertEquals(listOf(large, newerTie, olderTie, small), transactions(groceries).map { it.id })
        assertEquals(listOf(900L, 500L, 500L, 100L), transactions(groceries).map { it.amount.cents })
        assertEquals(day(9), transactions(groceries)[1].date)
    }

    @Test
    fun categoryTransactionsCoverOnlyConfirmedSpendingInThePeriod() = runTest {
        val kept = expense("kept", 100, day(5), groceries)
        expense("scheduled", 9_000, day(6), groceries, scheduled = true)
        expense("before", 9_000, day(31, month = 10), groceries)
        expense("other", 9_000, day(6), housing)
        t.ledger.delete(expense("deleted", 9_000, day(7), groceries))
        t.ledger.record(NewTransaction("income", checking, TransactionType.INCOME, Money(9_000), day(8), categoryId = otherIncome))
        t.ledger.recordTransfer(NewTransfer("move", checking, savings, Money(9_000), day(9), false))
        t.ledger.adjustBalance(NewAdjustment("adjust", checking, Money(-9_000), AdjustmentKind.EXPENSE, day(10)))

        assertEquals(listOf(kept), transactions(groceries).map { it.id })
    }

    @Test
    fun categoryTransactionsStopAtThirtyKeepingTheLargest() = runTest {
        repeat(32) { expense("e$it", 100L + it, day(1 + it % 28), groceries) }

        val amounts = transactions(groceries).map { it.amount.cents }
        assertEquals(MAX_CATEGORY_TRANSACTIONS, amounts.size)
        assertEquals(131L, amounts.first())
        assertEquals(102L, amounts.last())
    }

    @Test
    fun aParentCategoryListsItsSubcategories() = runTest {
        val own = expense("own", 300, day(2), food)
        val sub = expense("sub", 200, day(2), groceries)
        val deeper = expense("sub2", 100, day(2), dining)

        assertEquals(listOf(own, sub, deeper), transactions(food).map { it.id })
        assertEquals(listOf(sub), transactions(groceries).map { it.id })
    }

    @Test
    fun aSplitExpenseShowsOnlyThePartInTheCategory() = runTest {
        val id = receipt(1, "Mart", "1 Main St", day(4), listOf(item("Milk", 300, groceries), item("Lunch", 200, dining), item("Rent", 500, housing)))

        assertEquals(listOf(id to 300L), transactions(groceries).map { it.id to it.amount.cents })
        assertEquals(listOf(id to 500L), transactions(housing).map { it.id to it.amount.cents })
        // Two parts under the same parent are one transaction, added together.
        assertEquals(listOf(id to 500L), transactions(food).map { it.id to it.amount.cents })
    }

    @Test
    fun categoryTransactionsAreNamedAfterThePayeeThenTheCategory() = runTest {
        val payee = t.payees.getOrCreate("Fresh Market")
        t.ledger.record(NewTransaction("paid", checking, TransactionType.EXPENSE, Money(200), day(2), categoryId = groceries, payeeId = payee))
        expense("unnamed", 100, day(2), groceries)

        assertEquals(listOf("Fresh Market", "Groceries"), transactions(groceries).map { it.name })
    }

    @Test
    fun refundsAreLeftOutAndTheExpenseKeepsItsAmount() = runTest {
        val id = expense("bought", 1_000, day(2), groceries)
        val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        RoomRefundRepository({ t.db }, clock).refundTransaction(id, Money(400), day(3), "refund")

        assertEquals(listOf(id to 1_000L), transactions(groceries).map { it.id to it.amount.cents })
    }

    @Test
    fun storesLocationsAndProductsUseConfirmedLiveReceipts() = runTest {
        scenario()
        val brands = analysis.observeBrands(november).first()
        val mart = brands.single { it.brandKey == "FRESHMART" }
        assertEquals(Money(1_313), mart.total)
        assertEquals(2L, mart.visits)
        assertEquals(Money(657), mart.averageReceipt) // 656.5 rounds up
        assertEquals(2, mart.locations.size)
        assertEquals(Money(600), brands.single { it.brandKey == "CORNERSHOP" }.total)

        val brand = analysis.observeBrand("FRESHMART", november).first()!!
        assertEquals(1_313L, brand.trend.sumOf { it.amount.cents })
        assertEquals(listOf("Milk 1L", "Dish soap"), brand.topProducts.map { it.name })
        val milkPrices = brand.priceComparisons.single()
        assertEquals("Milk 1L", milkPrices.productName)
        assertEquals(listOf("1 Main St", "9 Elm St"), milkPrices.prices.map { it.first.address })

        val main = mart.locations.single { it.address == "1 Main St" }
        val location = analysis.observeLocation(main.storeId, november).first()!!
        assertEquals(1L, location.location.visits)
        assertEquals(Money(914), location.location.averageReceipt)
        assertEquals(listOf("9 Elm St"), location.siblings.map { it.address })
        assertEquals(listOf("Dish soap", "Milk 1L"), location.latestPrices.map { it.first.name })

        val products = analysis.observeProducts(november).first()
        assertTrue("deleted expense's items are excluded", products.none { it.name == "Eggs" })
        val milk = products.single { it.name == "Milk 1L" }
        assertEquals(2L, milk.purchases)
        assertEquals(Money(748), milk.total)
        assertEquals(Money(374), milk.averagePrice)

        val product = analysis.observeProduct(milk.productId, november).first()!!
        assertEquals(listOf(Money(399), Money(349)), product.history.map { it.paid })
        assertEquals(Dimension.VOLUME, product.overall!!.average.dimension)
        assertEquals(Money(374), product.overall!!.average.perUnit)
        assertEquals(Money(349), product.lowestPaid!!.paid)
        assertEquals(Money(399), product.highestPaid!!.paid)
        assertEquals(6L, product.averageDaysBetween)
        assertEquals(2, product.byLocation.size)
        assertEquals(1, product.byBrand.size)
    }

    @Test
    fun periodsFilterAndCompare() = runTest {
        scenario()
        val october = AnalysisPeriods.resolve(DatePreset.LAST_MONTH, today)
        val overview = analysis.observeOverview(october).first()
        assertEquals(Money(800), overview.total.current)
        assertEquals(1L, overview.purchases)
        assertEquals(Money.ZERO, overview.total.previous)
        assertNull(overview.total.changeBasisPoints)
        assertTrue(analysis.observeBrands(october).first().isEmpty())

        val allTime = analysis.observeOverview(AnalysisPeriods.resolve(DatePreset.ALL_TIME, today)).first()
        assertEquals(Money(4_713), allTime.total.current)
        assertNull(allTime.total.previous)
        assertEquals(4_713L, allTime.trend.sumOf { it.amount.cents })

        val empty = analysis.observeOverview(AnalysisPeriods.resolve(DatePreset.LAST_YEAR, today)).first()
        assertTrue(empty.isEmpty)
    }

    @Test
    fun analysisUpdatesWhenTheLedgerChanges() = runBlocking {
        expense("first", 1_000, day(3), groceries)
        val seen = mutableListOf<Long>()
        val job = launch(Dispatchers.Default) { analysis.observeOverview(november).collect { seen += it.total.current.cents } }
        suspend fun waitFor(cents: Long) = withTimeout(10_000) { while (cents !in seen) delay(20) }
        // Each change re-emits; Room delivers the new aggregate without a refresh.
        waitFor(1_000)
        expense("second", 250, day(4), groceries)
        waitFor(1_250)
        t.ledger.delete(t.db.transactionDao().getByRequestId("first")!!.id)
        waitFor(250)
        job.cancel()
    }
}