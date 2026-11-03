package com.pocketbudget.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.receipt.ReceiptImageStore
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.AnalysisPeriods
import com.pocketbudget.app.domain.model.BudgetDraft
import com.pocketbudget.app.domain.model.BudgetPeriodType
import com.pocketbudget.app.domain.model.ConfirmTarget
import com.pocketbudget.app.domain.model.DatePreset
import com.pocketbudget.app.domain.model.DateRange
import com.pocketbudget.app.domain.model.ImportOutcome
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.model.ReceiptItemDraft
import com.pocketbudget.app.domain.model.ReceiptReview
import com.pocketbudget.app.domain.model.RefundException
import com.pocketbudget.app.domain.model.RefundState
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
class RefundRepositoryTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "refund-test")
    private val today = LocalDate.of(2026, 11, 20)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val november = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
    private lateinit var refunds: RoomRefundRepository
    private lateinit var receipts: RoomReceiptRepository
    private lateinit var analysis: RoomAnalysisRepository
    private var checking = 0L
    private var groceries = 0L
    private var housing = 0L
    private var salary = 0L

    @Before
    fun setUp() = runTest {
        dir.deleteRecursively()
        refunds = RoomRefundRepository({ t.db }, clock)
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        receipts = RoomReceiptRepository({ t.db }, t.ledger, t.payees, ReceiptImageStore(dir, { key }), ReceiptRepositoryTest.FakeOcr())
        analysis = RoomAnalysisRepository({ t.db })
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(100_000))
        val c = t.categories.observeAll().first().associateBy { it.name }
        groceries = c.getValue("Groceries").id
        housing = c.getValue("Housing").id
        salary = c.getValue("Salary").id
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun day(d: Int) = LocalDate.of(2026, 11, d)

    private suspend fun expense(id: String, cents: Long, category: Long? = groceries, scheduled: Boolean = false) =
        t.ledger.record(NewTransaction(id, checking, TransactionType.EXPENSE, Money(cents), day(5), scheduled, category))

    private suspend fun balance() = t.accounts.observeBalances().first().single().current

    private suspend inline fun <reified E : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is E) throw e
        }
    }

    @Test
    fun partialRefundsUntilFullyRefundedWithoutTouchingTheOriginal() = runTest {
        val id = expense("e", 10_000)
        val before = t.db.transactionDao().getById(id)!!
        refunds.refundTransaction(id, Money(3_000), day(8), "r1")
        var summary = refunds.observeSummary(id).first()!!
        assertEquals(Money(3_000), summary.refunded)
        assertEquals(Money(7_000), summary.remaining)
        assertEquals(RefundState.PARTIAL, summary.state)

        expect<RefundException.ExceedsRemaining> { refunds.refundTransaction(id, Money(7_001), day(9), "r2") }
        refunds.refundTransaction(id, Money(7_000), day(9), "r3")
        summary = refunds.observeSummary(id).first()!!
        assertEquals(RefundState.FULL, summary.state)
        assertEquals(Money.ZERO, summary.remaining)
        assertEquals(listOf(Money(7_000), Money(3_000)), summary.refunds.map { it.amount })
        expect<RefundException.ExceedsRemaining> { refunds.refundTransaction(id, Money(1), day(10), "r4") }

        // The original is untouched: same row, no revision; the refunds are separate entries.
        assertEquals(before, t.db.transactionDao().getById(id))
        assertTrue(t.db.transactionDao().getRevisions(id).isEmpty())
        val refund = t.db.transactionDao().getByRequestId("r1")!!
        assertEquals(TransactionType.REFUND, refund.type)
        assertEquals(TransactionStatus.RECEIVED, refund.status)
        assertEquals(3_000L, refund.amountCents)
        assertEquals(Money(100_000), balance())
        // The list marks the expense as refunded.
        assertEquals(Money(10_000), t.ledger.observeRecent(10).first().single { it.id == id }.refunded)
    }

    @Test
    fun duplicateRequestsAndInvalidRefundsAreRejected() = runTest {
        val id = expense("e", 5_000)
        val first = refunds.refundTransaction(id, Money(1_000), day(8), "same")
        assertEquals(first, refunds.refundTransaction(id, Money(1_000), day(8), "same"))
        assertEquals(Money(1_000), refunds.observeSummary(id).first()!!.refunded)

        expect<RefundException.InvalidAmount> { refunds.refundTransaction(id, Money.ZERO, day(8), "zero") }
        expect<LedgerException.InvalidOperation> { refunds.refundTransaction(id, Money(100), day(1), "early") }
        val scheduled = expense("s", 2_000, scheduled = true)
        expect<RefundException.NotRefundable> { refunds.refundTransaction(scheduled, Money(100), day(8), "sched") }
        val income = t.ledger.record(NewTransaction("i", checking, TransactionType.INCOME, Money(9_000), day(5), categoryId = salary))
        expect<RefundException.NotRefundable> { refunds.refundTransaction(income, Money(100), day(8), "inc") }
        assertNull(refunds.observeSummary(income).first())

        // Refunds cannot be edited; refunded expenses cannot be deleted or reduced below the refund.
        expect<LedgerException.InvalidOperation> { t.ledger.edit(first, TransactionEdit(Money(500), day(8))) }
        expect<LedgerException.InvalidOperation> { t.ledger.delete(id) }
        val reduced = runCatching { t.ledger.edit(id, TransactionEdit(Money(900), day(5), groceries)) }
        assertTrue("edit below refunded is refused", reduced.isFailure)
        // The database refuses an over-refund even if code is bypassed.
        val direct = runCatching {
            t.execSql(
                "INSERT INTO transactions (request_id, account_id, type, amount_cents, status, occurred_on, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES ('x', ?, 'REFUND', 4500, 'RECEIVED', 20412, 1, 0, 0, 0, 0)",
                checking,
            )
            t.execSql("INSERT INTO refunds (refund_transaction_id, original_transaction_id, created_at) VALUES ((SELECT id FROM transactions WHERE request_id = 'x'), ?, 0)", id)
        }
        assertTrue(direct.isFailure)

        // Deleting a refund erases it with its links and allocations, and makes its amount refundable again.
        t.ledger.delete(first)
        assertEquals(Money(5_000), refunds.observeSummary(id).first()!!.remaining)
        assertNull(t.db.transactionDao().getById(first))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM refunds WHERE refund_transaction_id = $first"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM refund_allocations WHERE refund_transaction_id = $first"))
        // With no refunds left, the expense itself can be erased.
        t.ledger.delete(id)
        assertNull(t.db.transactionDao().getById(id))
    }

    @Test
    fun refundsReduceSpendingAndBudgetsButAreNeitherIncomeNorPurchases() = runTest {
        val budgets = RoomBudgetRepository({ t.db }, clock)
        val budget = budgets.create(BudgetDraft("Groceries", groceries, Money(50_000), BudgetPeriodType.MONTHLY, day(1)))
        val id = expense("e", 10_000)
        refunds.refundTransaction(id, Money(2_500), day(8), "r")

        val summary = t.ledger.observePeriodSummary(day(1), day(30)).first()
        assertEquals(Money.ZERO, summary.income)
        assertEquals(Money(7_500), summary.expense)
        assertEquals(Money(7_500), budgets.observeProgress(today).first().single { it.budget.id == budget }.current!!.spent)

        val overview = analysis.observeOverview(november).first()
        assertEquals(Money(7_500), overview.total.current)
        assertEquals(Money(2_500), overview.refunds)
        assertEquals(1L, overview.purchases)
        assertEquals(7_500L, overview.trend.sumOf { it.amount.cents })
        assertEquals(Money(7_500), analysis.observeCategories(november).first().single { it.categoryId == groceries }.total)
        // A refund in a later period reduces that period, never rewriting the purchase.
        val december = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)))
        refunds.refundTransaction(id, Money(500), LocalDate.of(2026, 12, 2), "later")
        assertEquals(Money(-500), analysis.observeOverview(december).first().total.current)
        assertEquals(Money(7_500), analysis.observeOverview(november).first().total.current)
    }

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

    /** Yogurt 3 x $2.00 (groceries) and soap $5.00 (housing), $1.10 tax: $12.10. */
    private suspend fun receipt(): Long {
        val id = (receipts.import(listOf(PageInput(photo(1))), "receipt") as ImportOutcome.Created).receiptId
        val review = ReceiptReview(
            storeName = "Fresh Mart",
            purchasedOn = day(5),
            tax = Money(110),
            total = Money(1_210),
            items = listOf(
                ReceiptItemDraft(rawText = "Yogurt", description = "Yogurt", quantityMilli = 3_000, unit = MeasureUnit.EACH, unitPrice = Money(200), lineTotal = Money(600), categoryId = groceries),
                ReceiptItemDraft(rawText = "Soap", description = "Soap", lineTotal = Money(500), categoryId = housing),
            ),
        )
        receipts.saveReview(id, review)
        return receipts.confirm(id, review, ConfirmTarget.NewExpense(checking))
    }

    @Test
    fun itemRefundsByQuantityAndAmountAreTrackedAndCapped() = runTest {
        val expenseId = receipt()
        val items = refunds.observeSummary(expenseId).first()!!.items.associateBy { it.description }
        val yogurt = items.getValue("Yogurt")
        // Each item's share of what was paid, tax included: 660 + 550 = 1210.
        assertEquals(Money(660), yogurt.paid)
        assertEquals(Money(550), items.getValue("Soap").paid)

        refunds.refundItem(yogurt.itemId, 1_000, null, day(8), "y1")
        var now = refunds.observeSummary(expenseId).first()!!.items.single { it.itemId == yogurt.itemId }
        assertEquals(Money(220), now.refunded)
        assertEquals(2_000L, now.remainingQuantityMilli)
        assertEquals(RefundState.PARTIAL, now.state)

        expect<RefundException.InvalidQuantity> { refunds.refundItem(yogurt.itemId, 3_000, null, day(8), "y2") }
        expect<RefundException.ExceedsRemaining> { refunds.refundItem(yogurt.itemId, null, Money(441), day(8), "y3") }
        // Same request again: nothing more is refunded.
        refunds.refundItem(yogurt.itemId, 1_000, null, day(8), "y1")
        refunds.refundItem(yogurt.itemId, 2_000, null, day(9), "y4")
        now = refunds.observeSummary(expenseId).first()!!.items.single { it.itemId == yogurt.itemId }
        assertEquals(Money(660), now.refunded)
        assertEquals(RefundState.FULL, now.state)
        expect<RefundException.InvalidQuantity> { refunds.refundItem(yogurt.itemId, 1_000, null, day(9), "y5") }

        // A whole-expense refund goes to what is still refundable: only the soap.
        refunds.refundTransaction(expenseId, Money(300), day(10), "w")
        val summary = refunds.observeSummary(expenseId).first()!!
        assertEquals(Money(960), summary.refunded)
        assertEquals(Money(300), summary.items.single { it.description == "Soap" }.refunded)
        // Newest first: the whole-expense refund, then the two yogurt refunds.
        assertEquals(listOf(null, "Yogurt", "Yogurt"), summary.refunds.map { it.itemDescription })

        // Analysis: net spending, purchases unchanged, categories and products reduced.
        val overview = analysis.observeOverview(november).first()
        assertEquals(Money(250), overview.total.current)
        assertEquals(1L, overview.purchases)
        val categories = analysis.observeCategories(november).first().associateBy { it.categoryId }
        // Fully refunded: nets to zero, so it is no longer listed.
        assertEquals(Money.ZERO, categories[groceries]?.total ?: Money.ZERO)
        assertEquals(Money(250), categories[housing]!!.total)
        val products = analysis.observeProducts(november).first().associateBy { it.name }
        assertEquals(Money.ZERO, products.getValue("Yogurt").total)  // 600 pre-tax, all returned
        assertEquals(1L, products.getValue("Yogurt").purchases)
        assertEquals(Money(227), products.getValue("Soap").total)    // 500 - 273 (pre-tax part of 300)
        val store = analysis.observeBrands(november).first().single()
        assertEquals(Money(250), store.total)
        assertEquals(1L, store.visits)
        // The receipt and its purchase history are preserved.
        assertEquals(2, t.db.receiptDao().getItems(t.db.receiptDao().getByTransaction(expenseId)!!.id).size)
        assertEquals(2L, t.count("SELECT COUNT(*) FROM price_observations"))
        assertEquals(Money(100_000 - 250), balance())
    }
}
