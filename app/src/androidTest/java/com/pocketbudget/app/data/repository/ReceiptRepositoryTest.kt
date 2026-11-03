package com.pocketbudget.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.receipt.OcrEngine
import com.pocketbudget.app.data.receipt.ReceiptImageStore
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.BudgetDraft
import com.pocketbudget.app.domain.model.BudgetPeriodType
import com.pocketbudget.app.domain.model.ConfirmTarget
import com.pocketbudget.app.domain.model.Dimension
import com.pocketbudget.app.domain.model.DuplicateWarning
import com.pocketbudget.app.domain.model.ImportOutcome
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.OcrLine
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.model.ReceiptException
import com.pocketbudget.app.domain.model.ReceiptReview
import com.pocketbudget.app.domain.model.ReceiptStatus
import com.pocketbudget.app.domain.model.RevisionAction
import com.pocketbudget.app.domain.model.TextKeys
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ReceiptRepositoryTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 11, 15)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val dir = File(context.noBackupFilesDir, "receipt-test")
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val store = ReceiptImageStore(dir, { key })
    private val ocr = FakeOcr()
    private lateinit var receipts: RoomReceiptRepository
    private var checking = 0L
    private var food = 0L
    private var groceries = 0L
    private var housing = 0L

    /** Returns prepared text per page instead of running Tesseract (covered by TesseractOcrTest). */
    class FakeOcr : OcrEngine {
        override val name = "fake"
        var pages: Map<Int, List<String>> = emptyMap()
        override suspend fun recognize(page: Int, jpeg: ByteArray): List<OcrLine> =
            pages[page].orEmpty().mapIndexed { i, text -> OcrLine(page, i, text, 95) }
    }

    @Before
    fun setUp() = runTest {
        dir.deleteRecursively()
        receipts = repository(t.ledger)
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(100_000))
        val categories = t.categories.observeAll().first().associateBy { it.name }
        food = categories.getValue("Food").id
        groceries = categories.getValue("Groceries").id
        housing = categories.getValue("Housing").id
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repository(ledger: TransactionRepository) =
        RoomReceiptRepository({ t.db }, ledger, t.payees, store, ocr, clock = clock)

    /** A distinct photo: random blocks give each seed its own perceptual hash. */
    private fun photo(seed: Int): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until 9) for (x in 0 until 6) {
            paint.color = android.graphics.Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawRect(x * 100f, y * 100f, x * 100f + 100, y * 100f + 100, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val groceryText = listOf(
        "FRESH MART",
        "123 Main St",
        "2026-11-14 10:15",
        "GV MLK 2% 1L        3.49",
        "DISH SOAP           5.00",
        "SUBTOTAL            8.49",
        "TAX                 0.65",
        "TOTAL               9.14",
    )

    private suspend fun scanned(seed: Int, text: List<String> = groceryText): Long {
        ocr.pages = mapOf(0 to text)
        val outcome = receipts.import(listOf(PageInput(photo(seed))), "scan-$seed") as ImportOutcome.Created
        receipts.runOcr(outcome.receiptId, dayFirst = false)
        return outcome.receiptId
    }

    private suspend fun review(id: Long) = receipts.observeDetail(id).first()!!.review

    /** The user's verification: names corrected and categories chosen. */
    private suspend fun verified(id: Long): ReceiptReview {
        val r = review(id)
        return r.copy(
            items = listOf(
                r.items[0].copy(description = "Milk 2% 1L", categoryId = groceries),
                r.items[1].copy(categoryId = housing),
            ),
        )
    }

    private fun transactionCount() = t.count("SELECT COUNT(*) FROM transactions")
    private suspend fun balance() = t.accounts.observeBalances().first().single { it.account.id == checking }.current

    @Test
    fun nothingFinancialChangesBeforeConfirmation() = runTest {
        val budgets = RoomBudgetRepository({ t.db }, clock)
        budgets.create(BudgetDraft("Food", food, Money(50_000), BudgetPeriodType.MONTHLY, LocalDate.of(2026, 11, 1)))
        val before = Triple(transactionCount(), balance(), budgets.observeProgress(today).first().single().current!!.spent)

        val id = scanned(1)
        assertEquals(ReceiptStatus.NEEDS_REVIEW, receipts.observeDetail(id).first()!!.status)
        receipts.saveReview(id, verified(id))
        receipts.findMatches(id)
        receipts.duplicates(id)

        val after = Triple(transactionCount(), balance(), budgets.observeProgress(today).first().single().current!!.spent)
        assertEquals(before, after)
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_splits"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM price_observations"))

        val expense = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        assertEquals(before.first + 1, transactionCount())
        assertEquals(before.second - Money(914), balance())
        val entry = t.db.transactionDao().getById(expense)!!
        assertEquals(TransactionType.EXPENSE, entry.type)
        assertEquals(TransactionStatus.SPENT, entry.status)
        assertEquals(LocalDate.of(2026, 11, 14), entry.occurredOn)
    }

    @Test
    fun ocrReadsMultiplePagesAndStoresRawTextWithConfidence() = runTest {
        ocr.pages = mapOf(0 to groceryText.take(5), 1 to groceryText.drop(5))
        val outcome = receipts.import(listOf(PageInput(photo(2)), PageInput(photo(3), rotation = 90)), "multi") as ImportOutcome.Created
        receipts.runOcr(outcome.receiptId, dayFirst = false)
        val detail = receipts.observeDetail(outcome.receiptId).first()!!
        assertEquals(listOf(0, 1), detail.pages.map { it.page })
        // Rotated by the user: stored landscape.
        assertTrue(detail.pages[1].width > detail.pages[1].height)
        assertEquals(groceryText, detail.ocrLines.map { it.text })
        assertTrue(detail.ocrLines.all { it.confidence == 95 })
        assertEquals(Money(914), detail.review.total)
        assertEquals(2, detail.review.items.size)
        assertEquals("fake", t.db.receiptDao().getById(outcome.receiptId)!!.ocrEngine)
        // Pages decrypt back to valid JPEGs in memory.
        val jpeg = receipts.loadPage(detail.pages[0].imageId)
        assertTrue(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
    }

    @Test
    fun confirmIsIdempotentAndSplitsAddUpExactly() = runTest {
        val id = scanned(4)
        val first = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        val second = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        assertEquals(first, second)
        assertEquals(1L, t.count("SELECT COUNT(*) FROM transactions WHERE type = 'EXPENSE'"))
        val splits = t.db.receiptDao().getSplits(first).associate { it.categoryId to it.amountCents }
        // $3.49 groceries and $5.00 housing share $0.65 tax proportionally: 376 + 538 = 914.
        assertEquals(mapOf(groceries to 376L, housing to 538L), splits)
        val receipt = t.db.receiptDao().getById(id)!!
        assertEquals(ReceiptStatus.CONFIRMED, receipt.status)
        assertEquals(first, receipt.transactionId)
        assertTrue(t.db.receiptDao().getItems(id).all { it.userVerified })
    }

    @Test
    fun failedConfirmationLeavesNoExpense() = runTest {
        val id = scanned(5)
        // A ledger that records the expense and then fails: the whole confirmation must roll back.
        val failing = object : TransactionRepository by t.ledger {
            override suspend fun record(transaction: NewTransaction): Long {
                t.ledger.record(transaction)
                throw IllegalStateException("simulated failure")
            }
        }
        val before = transactionCount()
        runCatching { repository(failing).confirm(id, verified(id), ConfirmTarget.NewExpense(checking)) }
            .onSuccess { fail("confirmation should fail") }
        assertEquals(before, transactionCount())
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_splits"))
        assertEquals(ReceiptStatus.NEEDS_REVIEW, t.db.receiptDao().getById(id)!!.status)

        // Totals that do not add up are refused before anything is written.
        val wrong = verified(id).copy(total = Money(1000))
        try {
            receipts.confirm(id, wrong, ConfirmTarget.NewExpense(checking))
            fail("unreconciled receipt must be refused")
        } catch (_: ReceiptException.NotReconciled) {
        }
        val uncategorised = verified(id).let { r -> r.copy(items = r.items.map { it.copy(categoryId = null) }) }
        try {
            receipts.confirm(id, uncategorised, ConfirmTarget.NewExpense(checking))
            fail("items need categories")
        } catch (_: ReceiptException.CategoryRequired) {
        }
        assertEquals(before, transactionCount())
    }

    @Test
    fun attachingUsesTheExistingExpenseAndAuditsAmountChanges() = runTest {
        val manual = t.ledger.record(NewTransaction("manual", checking, TransactionType.EXPENSE, Money(900), LocalDate.of(2026, 11, 14), categoryId = food))
        val id = scanned(6)
        val match = receipts.findMatches(id)
        assertEquals("only exact amounts or projections are offered", emptyList<Long>(), match.map { it.transactionId })

        try {
            receipts.confirm(id, verified(id), ConfirmTarget.Existing(manual))
            fail("different amount needs explicit replacement")
        } catch (_: ReceiptException.AmountDiffers) {
        }
        val before = transactionCount()
        val attached = receipts.confirm(id, verified(id), ConfirmTarget.Existing(manual, replaceAmount = true))
        assertEquals(manual, attached)
        assertEquals("no second expense", before, transactionCount())
        assertEquals(-914L, t.db.transactionDao().getById(manual)!!.amountCents)
        val history = t.db.transactionDao().getRevisions(manual)
        assertEquals(RevisionAction.UPDATE, history.single().action)
        assertEquals(-900L, history.single().amountCents)

        // The split expense's amount is now fixed; another receipt cannot take it.
        try {
            t.ledger.edit(manual, TransactionEdit(Money(1000), LocalDate.of(2026, 11, 14), food))
            fail("split amount is fixed")
        } catch (_: LedgerException.InvalidOperation) {
        }
        val other = scanned(7, groceryText.map { it.replace("FRESH MART", "OTHER SHOP") })
        try {
            receipts.confirm(other, verified(other), ConfirmTarget.Existing(manual))
            fail("one receipt per expense")
        } catch (_: ReceiptException.ExpenseUnavailable) {
        }
    }

    @Test
    fun scheduledRecurringMatchIsOfferedButConfirmedOnlyByTheUser() = runTest {
        val bill = t.ledger.record(
            NewTransaction("bill", checking, TransactionType.EXPENSE, Money(1000), LocalDate.of(2026, 11, 16), scheduled = true, categoryId = housing),
        )
        val id = scanned(8)
        val matches = receipts.findMatches(id)
        assertEquals(listOf(bill), matches.map { it.transactionId })
        assertFalse(matches.single().exactAmount)
        // Looking for matches never confirms anything.
        assertEquals(TransactionStatus.SCHEDULED, t.db.transactionDao().getById(bill)!!.status)

        receipts.confirm(id, verified(id), ConfirmTarget.Existing(bill))
        val entry = t.db.transactionDao().getById(bill)!!
        assertEquals(TransactionStatus.SPENT, entry.status)
        assertEquals(-914L, entry.amountCents)
        assertEquals(1L, t.count("SELECT COUNT(*) FROM transactions WHERE type = 'EXPENSE'"))
    }

    @Test
    fun budgetsUseSplitsWithoutDoubleCounting() = runTest {
        val budgets = RoomBudgetRepository({ t.db }, clock)
        val start = LocalDate.of(2026, 11, 1)
        val foodBudget = budgets.create(BudgetDraft("Food", food, Money(50_000), BudgetPeriodType.MONTHLY, start))
        val groceryBudget = budgets.create(BudgetDraft("Groceries", groceries, Money(30_000), BudgetPeriodType.MONTHLY, start))
        val housingBudget = budgets.create(BudgetDraft("Housing", housing, Money(30_000), BudgetPeriodType.MONTHLY, start))
        // An ordinary expense keeps working exactly as before.
        t.ledger.record(NewTransaction("plain", checking, TransactionType.EXPENSE, Money(1_000), today, categoryId = groceries))

        val id = scanned(9)
        receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))

        val spent = budgets.observeProgress(today).first().associate { it.budget.id to it.current!!.spent.cents }
        assertEquals(1_000L + 376, spent[groceryBudget])
        assertEquals("parent includes the child once", 1_000L + 376, spent[foodBudget])
        assertEquals(538L, spent[housingBudget])
        // The split expense counts once in total.
        val all = t.db.budgetDao().getExpenses(start).sumOf { it.spentCents }
        assertEquals(1_000L + 914, all)
    }

    @Test
    fun duplicateImagesAndReceiptsAreDetected() = runTest {
        val first = scanned(10)
        val again = receipts.import(listOf(PageInput(photo(10))), "again")
        assertEquals(ImportOutcome.Duplicate(first), again)
        assertEquals(1L, t.count("SELECT COUNT(*) FROM receipts"))

        val forced = receipts.import(listOf(PageInput(photo(10))), "forced", allowDuplicate = true) as ImportOutcome.Created
        assertTrue(forced.warnings.contains(DuplicateWarning.SameImage(first)))

        // A different photo of the same purchase: same store, date and total.
        val reshot = scanned(11)
        assertTrue(receipts.duplicates(reshot).contains(DuplicateWarning.SameContent(first)))
        // Importing is idempotent on the request id.
        assertEquals(ImportOutcome.Created(forced.receiptId, emptyList()), receipts.import(listOf(PageInput(photo(12))), "forced"))
    }

    @Test
    fun productsAliasesAndPricesAreLearnedOnlyFromConfirmedReceipts() = runTest {
        val id = scanned(13)
        receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        val milk = t.db.receiptDao().getProductByName("MILK 2 1L")!!
        assertEquals(groceries, milk.defaultCategoryId)
        assertEquals(1000L to MeasureUnit.L, milk.packQuantityMilli to milk.packUnit)

        // Next visit: the store's wording is recognised from the earlier correction.
        val next = scanned(14, groceryText.map { it.replace("2026-11-14", "2026-11-21").replace("3.49", "3.99").replace("8.49", "8.99").replace("9.14", "9.64") })
        val suggested = review(next).items[0]
        assertEquals("Milk 2% 1L", suggested.description)
        assertEquals(milk.id, suggested.productId)
        assertEquals(groceries, suggested.categoryId)
        assertEquals("unconfirmed receipts add no prices", 2L, t.count("SELECT COUNT(*) FROM price_observations"))

        receipts.confirm(next, review(next).let { r -> r.copy(items = listOf(r.items[0], r.items[1].copy(categoryId = housing))) }, ConfirmTarget.NewExpense(checking))
        val prices = RoomProductRepository({ t.db }, clock).observePrices(milk.id).first()
        assertEquals(listOf(Money(399), Money(349)), prices.map { it.paid })
        assertEquals(Dimension.VOLUME, prices.first().normalized!!.dimension)
        assertEquals(Money(399), prices.first().normalized!!.perUnit)
        assertEquals(2, t.db.receiptDao().getAlias(t.db.receiptDao().findStores(com.pocketbudget.app.domain.model.TextKeys.storeKey("Fresh Mart")).single().id, "GV MLK 2 1L")!!.timesConfirmed)
    }

    /** Rows in every table a receipt writes to, or that learns from one. */
    private fun receiptTraces(): Map<String, Long> =
        listOf("receipts", "receipt_items", "receipt_ocr_lines", "receipt_images", "price_observations", "products", "product_aliases", "stores")
            .associateWith { t.count("SELECT COUNT(*) FROM $it") }

    private val noReceiptTraces = receiptTracesOf(0)

    private fun receiptTracesOf(n: Long) =
        listOf("receipts", "receipt_items", "receipt_ocr_lines", "receipt_images", "price_observations", "products", "product_aliases", "stores")
            .associateWith { n }

    @Test
    fun discardErasesTheDraftWithoutFinancialEffect() = runTest {
        val id = scanned(15)
        val verified = verified(id)
        val before = transactionCount()
        assertEquals(1, dir.listFiles()!!.size)
        receipts.discard(id)
        receipts.discard(id) // repeat is harmless
        assertEquals(noReceiptTraces, receiptTraces())
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(before, transactionCount())
        try {
            receipts.confirm(id, verified, ConfirmTarget.NewExpense(checking))
            fail("discarded receipts are gone")
        } catch (_: ReceiptException.NotFound) {
        }
        assertNull(receipts.observeSummaries().first().firstOrNull { it.id == id })
    }

    @Test
    fun confirmingWithoutKeepingImagesDeletesThemButKeepsTheReceipt() = runTest {
        val id = scanned(16)
        assertEquals(1, dir.listFiles()!!.size)
        val expense = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking), keepImages = false)
        val detail = receipts.observeDetail(id).first()!!
        assertEquals(ReceiptStatus.CONFIRMED, detail.status)
        assertEquals(expense, detail.transactionId)
        assertTrue(detail.review.items.isNotEmpty())
        assertTrue(detail.pages.isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
        // Repeating the confirmation still returns the same expense.
        assertEquals(expense, receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking), keepImages = false))
    }

    @Test
    fun deletingOnlyTheReceiptErasesItButKeepsTheExpenseAndItsSplits() = runTest {
        val id = scanned(18)
        val expense = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        val splits = t.db.receiptDao().getSplits(expense).map { it.categoryId to it.amountCents }
        val balance = balance()
        val payee = t.db.transactionDao().getById(expense)!!.payeeId!!
        assertEquals(receiptTracesOf(1) + mapOf("receipt_items" to 2L, "price_observations" to 2L, "products" to 2L, "product_aliases" to 2L, "receipt_ocr_lines" to groceryText.size.toLong()), receiptTraces())

        receipts.delete(id, deleteTransaction = false)
        receipts.delete(id, deleteTransaction = false) // repeat is harmless

        // The receipt and everything learned only from it are gone.
        assertEquals(noReceiptTraces, receiptTraces())
        assertEquals(0, dir.listFiles()!!.size)
        assertNull(receipts.observeDetail(id).first())
        assertNull(receipts.observeReceiptFor(expense).first())
        // The expense, its payee, balance effect and category splits are untouched.
        assertFalse(t.db.transactionDao().getById(expense)!!.isDeleted)
        assertEquals(1L, t.count("SELECT COUNT(*) FROM payees WHERE id = $payee"))
        assertEquals(balance, balance())
        assertEquals(splits, t.db.receiptDao().getSplits(expense).map { it.categoryId to it.amountCents })
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_splits WHERE receipt_id IS NOT NULL"))
        // A kept expense is never offered to another receipt (it is already split).
        val next = scanned(19)
        assertTrue(receipts.findMatches(next).none { it.transactionId == expense })
    }

    @Test
    fun deletingTheReceiptWithItsExpenseErasesBothAndTheirNames() = runTest {
        val before = balance()
        val id = scanned(20)
        val expense = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        receipts.delete(id, deleteTransaction = true)
        assertEquals(noReceiptTraces, receiptTraces())
        assertNull(t.db.transactionDao().getById(expense))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_splits"))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_revisions"))
        assertEquals("the store's payee went with them", 0L, t.count("SELECT COUNT(*) FROM payees"))
        assertEquals(before, balance())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun namesSharedWithAnotherReceiptStay() = runTest {
        val first = scanned(24)
        receipts.confirm(first, verified(first), ConfirmTarget.NewExpense(checking))
        val second = scanned(25)
        receipts.confirm(second, verified(second), ConfirmTarget.NewExpense(checking))
        val store = t.db.receiptDao().getById(second)!!.storeId!!
        val milk = t.db.receiptDao().getItems(second).first()
        val alias = t.db.receiptDao().getAlias(store, TextKeys.descriptionKey(milk.rawText.ifBlank { milk.description }))!!
        assertEquals(2, alias.timesConfirmed)

        receipts.delete(first, deleteTransaction = true)

        // The other receipt still uses the store, products and alias; only the erased confirmation is forgotten.
        assertEquals(1L, t.count("SELECT COUNT(*) FROM stores"))
        assertEquals(2L, t.count("SELECT COUNT(*) FROM products"))
        assertEquals(1, t.db.receiptDao().getAlias(store, alias.normalizedAlias)!!.timesConfirmed)
        assertEquals(2L, t.count("SELECT COUNT(*) FROM price_observations"))
        assertEquals(1L, t.count("SELECT COUNT(*) FROM payees"))
    }

    @Test
    fun aRefundedExpenseIsNotDeletedAndTheReceiptIsLeftAsItWas() = runTest {
        val id = scanned(21)
        val expense = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        val refunds = RoomRefundRepository({ t.db }, clock)
        refunds.refundItem(t.db.receiptDao().getItems(id).first().id, 1_000, null, today, "refund-milk")
        val traces = receiptTraces()

        assertTrue(runCatching { receipts.delete(id, deleteTransaction = true) }.isFailure)
        assertEquals(ReceiptStatus.CONFIRMED, t.db.receiptDao().getById(id)!!.status)
        assertFalse(t.db.transactionDao().getById(expense)!!.isDeleted)
        assertEquals(traces, receiptTraces())
        assertEquals(1, dir.listFiles()!!.size)

        // Deleting only the receipt is allowed; the refund stays recorded against the expense,
        // by category and amount, without the erased item's details.
        receipts.delete(id, deleteTransaction = false)
        assertEquals(noReceiptTraces, receiptTraces())
        assertTrue(refunds.observeSummary(expense).first()!!.refunded.isPositive)
        assertEquals(0L, t.count("SELECT COUNT(*) FROM refunds WHERE receipt_item_id IS NOT NULL"))
        assertEquals(
            0L,
            t.count("SELECT COUNT(*) FROM refund_allocations WHERE receipt_item_id IS NOT NULL OR item_net_cents IS NOT NULL OR quantity_milli IS NOT NULL"),
        )
    }

    @Test
    fun deletingOnlyTheExpenseKeepsTheReceipt() = runTest {
        val id = scanned(22)
        val expense = receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        val traces = receiptTraces()
        t.ledger.delete(expense)
        assertNull(t.db.transactionDao().getById(expense))
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_splits"))
        // The receipt is untouched apart from its link to the erased expense.
        assertEquals(traces, receiptTraces())
        val detail = receipts.observeDetail(id).first()!!
        assertEquals(ReceiptStatus.CONFIRMED, detail.status)
        assertNull(detail.transactionId)
        assertTrue(detail.expenseDeleted)
        assertEquals(1, detail.pages.size)
        assertTrue(receipts.observeSummaries().first().any { it.id == id })
        // It cannot be confirmed onto another expense, but can still be deleted on its own.
        assertTrue(runCatching { receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking)) }.isFailure)
        receipts.delete(id, deleteTransaction = true)
        assertEquals(noReceiptTraces, receiptTraces())
    }

    @Test
    fun draftsAreDiscardedNotDeleted() = runTest {
        val id = scanned(23)
        try {
            receipts.delete(id, deleteTransaction = false)
            fail("drafts are discarded instead")
        } catch (_: ReceiptException.NotConfirmed) {
        }
        assertEquals(ReceiptStatus.NEEDS_REVIEW, t.db.receiptDao().getById(id)!!.status)
    }

    @Test
    fun confirmingKeepsImagesByDefault() = runTest {
        val id = scanned(17)
        receipts.confirm(id, verified(id), ConfirmTarget.NewExpense(checking))
        assertEquals(1, receipts.observeDetail(id).first()!!.pages.size)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun interruptedImportsLeaveOnlyOrphansThatAreCleanedUp() = runTest {
        val kept = scanned(16)
        // Files written but the database rows never committed (process killed in between).
        val orphan = store.write(photo(17))
        File(dir, orphan.fileName).setLastModified(0)
        File(dir, "half-written.pbr.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(0) }
        // A brand-new unreferenced file may belong to an import in progress, so it stays.
        val fresh = store.write(photo(18))

        assertEquals(2, receipts.removeOrphanImages())
        assertFalse(File(dir, orphan.fileName).exists())
        assertTrue(File(dir, fresh.fileName).exists())
        val page = receipts.observeDetail(kept).first()!!.pages.single()
        assertTrue(receipts.loadPage(page.imageId).isNotEmpty())
    }
}
