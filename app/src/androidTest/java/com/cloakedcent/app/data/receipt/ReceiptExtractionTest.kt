package com.cloakedcent.app.data.receipt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.repository.LedgerTestDatabase
import com.cloakedcent.app.data.repository.RoomReceiptRepository
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.ConfirmTarget
import com.cloakedcent.app.domain.model.ImportOutcome
import com.cloakedcent.app.domain.model.MeasureUnit
import com.cloakedcent.app.domain.model.PageInput
import com.cloakedcent.app.domain.model.ReceiptDetail
import com.cloakedcent.app.domain.model.ReceiptException
import com.cloakedcent.app.domain.model.ReceiptField
import com.cloakedcent.app.domain.model.ReceiptMath
import com.cloakedcent.app.domain.model.ReceiptStatus
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
import java.io.File
import java.security.SecureRandom
import kotlin.time.Duration.Companion.minutes

/**
 * Automatic item extraction end to end: photo-like receipt images (tilted, shadowed,
 * noisy, JPEG) go through import, the real offline Tesseract OCR and the parser into a
 * draft, exactly as in the app.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptExtractionTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "extraction-test")
    private lateinit var receipts: RoomReceiptRepository
    private var checking = 0L
    private var groceries = 0L
    private var housing = 0L

    @Before
    fun setUp() = runTest {
        dir.deleteRecursively()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        receipts = RoomReceiptRepository({ t.db }, t.ledger, t.payees, ReceiptImageStore(dir, { key }), TesseractOcrEngine(context))
        checking = t.accounts.create("Checking", AccountType.CHECKING, Money(100_000))
        val c = t.categories.observeAll().first().associateBy { it.name }
        groceries = c.getValue("Groceries").id
        housing = c.getValue("Housing").id
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun scan(lines: List<String>, style: ReceiptPhotos.Style, request: String): ReceiptDetail {
        val outcome = receipts.import(listOf(PageInput(ReceiptPhotos.render(lines, style))), request) as ImportOutcome.Created
        receipts.runOcr(outcome.receiptId, dayFirst = false)
        return receipts.observeDetail(outcome.receiptId).first()!!
    }

    private fun transactions() = t.count("SELECT COUNT(*) FROM transactions")

    @Test
    fun itemsAreExtractedFromTiltedShadowedPhotos() = runTest(timeout = 5.minutes) {
        for ((index, skew) in listOf(2.5f, -4f).withIndex()) {
            val detail = scan(ReceiptPhotos.GROCERY, ReceiptPhotos.Style(skewDegrees = skew, seed = index), "grocery-$index")
            val r = detail.review
            assertEquals(ReceiptStatus.NEEDS_REVIEW, detail.status)
            assertEquals("skew $skew", 6, r.items.size)
            assertEquals(listOf(349L, 279L, 218L, 387L, 500L, 429L), r.items.map { it.lineTotal.cents })
            // Weighed by the kilogram, and a multi-buy with its unit price.
            val bananas = r.items[2]
            assertEquals("BANANAS", bananas.description)
            assertTrue(bananas.uncertain || (bananas.quantityMilli == 1_250L && bananas.unit == MeasureUnit.KG && bananas.unitPrice == Money(174)))
            val yogurt = r.items[3]
            assertEquals(3_000L, yogurt.quantityMilli)
            assertEquals(Money(129), yogurt.unitPrice)
            // The coupon is the soap's discount.
            assertEquals(Money(50), r.items[4].discount)
            assertEquals(Money(2_112), r.subtotal)
            assertEquals(Money(36), r.tax)
            assertEquals(Money(2_148), r.total)
            assertTrue(ReceiptMath.reconcile(r).isEmpty())
            // OCR text and positions are preserved alongside the draft.
            assertTrue(detail.ocrLines.any { it.text.contains("BANANAS") && it.box != null })
            assertEquals(r.items.map { it.rawText }, r.items.map { it.description })
            // Nothing is guessed: new products have no category.
            assertTrue(r.items.all { it.categoryId == null && it.productId == null })
            assertNull(r.defaultCategoryId)
        }
        assertEquals("OCR never creates financial entries", 0L, transactions())
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transaction_splits"))
    }

    @Test
    fun codesDiscountsAndTaxesOnAPharmacyReceipt() = runTest(timeout = 5.minutes) {
        val r = scan(ReceiptPhotos.PHARMACY, ReceiptPhotos.Style(skewDegrees = 1.5f), "pharmacy").review
        assertEquals(listOf(899L, 350L, 420L), r.items.map { it.lineTotal.cents })
        assertTrue(r.items[1].description.startsWith("HAND SOAP"))
        assertTrue(r.items.none { item -> item.description.any(Char::isDigit) && item.description.contains("000") })
        assertEquals(Money(100), r.discount)
        assertEquals(Money(204), r.tax)
        assertEquals(Money(1_773), r.total)
    }

    @Test
    fun repeatedProcessingNeverDuplicatesItemsOrExpenses() = runTest(timeout = 5.minutes) {
        val first = scan(ReceiptPhotos.GROCERY, ReceiptPhotos.Style(seed = 3), "repeat")
        receipts.runOcr(first.id, dayFirst = false)
        receipts.runOcr(first.id, dayFirst = false)
        val again = receipts.observeDetail(first.id).first()!!
        assertEquals(6, again.review.items.size)
        assertEquals(6L, t.count("SELECT COUNT(*) FROM receipt_items"))
        assertEquals(first.ocrLines.size, again.ocrLines.size)

        // The user corrects one item and assigns categories; then confirms twice.
        val review = again.review.let { r ->
            r.copy(
                items = r.items.mapIndexed { i, item ->
                    if (i == 0) item.copy(description = "Milk 2% 1L", categoryId = groceries) else item.copy(categoryId = if (i == 4) housing else groceries)
                },
            )
        }
        val expense = receipts.confirm(first.id, review, ConfirmTarget.NewExpense(checking))
        assertEquals(expense, receipts.confirm(first.id, review, ConfirmTarget.NewExpense(checking)))
        assertEquals(1L, transactions())
        assertEquals(-2_148L, t.db.transactionDao().getById(expense)!!.amountCents)
        // Confirmed receipts are final; re-reading them is refused and changes nothing.
        try {
            receipts.runOcr(first.id, dayFirst = false)
            fail("confirmed receipts cannot be re-read")
        } catch (_: ReceiptException.NotEditable) {
        }
        assertEquals(6L, t.count("SELECT COUNT(*) FROM receipt_items"))
    }

    @Test
    fun learnedProductsAndCategoriesAreReusedAndNewOnesStayUnassigned() = runTest(timeout = 5.minutes) {
        val first = scan(ReceiptPhotos.GROCERY, ReceiptPhotos.Style(seed = 4), "learn-1")
        val categorised = first.review.copy(
            items = first.review.items.mapIndexed { i, item ->
                // The user renames the milk; its receipt wording is remembered for this store.
                if (i == 0) item.copy(description = "Milk 2% 1L", categoryId = groceries) else item.copy(categoryId = if (i == 4) housing else groceries)
            },
        )
        receipts.confirm(first.id, categorised, ConfirmTarget.NewExpense(checking))

        val nextVisit = ReceiptPhotos.GROCERY.map { it.replace("11/14/2026", "11/21/2026") }
            .toMutableList().apply { add(13, "CHEESE CHEDDAR     4.00 F") }
            .map { it.replace("21.12", "25.12").replace("21.48", "25.48") }
        val second = scan(nextVisit, ReceiptPhotos.Style(seed = 5, skewDegrees = -2f), "learn-2").review
        val byText = second.items.associateBy { it.rawText }
        val milk = second.items.first()
        assertEquals("Milk 2% 1L", milk.description)
        assertEquals(groceries, milk.categoryId)
        assertTrue(milk.rawText.startsWith("MILK"))
        assertEquals(housing, second.items.single { it.description.startsWith("DISH SOAP") }.categoryId)
        val cheese = byText.entries.single { it.key.startsWith("CHEESE") }.value
        assertNull("never seen before: left unassigned", cheese.categoryId)
        assertNull(cheese.productId)
        assertEquals(1L, transactions())
    }

    @Test
    fun misreadTotalsAreFlaggedAndCannotBeConfirmedUntilCorrected() = runTest(timeout = 5.minutes) {
        // The printed total is wrong for these items: OCR must not accept it silently.
        val lines = ReceiptPhotos.GROCERY.map { if (it.startsWith("TOTAL")) "TOTAL             29.48" else it }
        val detail = scan(lines, ReceiptPhotos.Style(seed = 6), "wrong")
        assertTrue(ReceiptField.TOTAL in detail.uncertain)
        val review = detail.review.copy(items = detail.review.items.map { it.copy(categoryId = groceries) })
        try {
            receipts.confirm(detail.id, review, ConfirmTarget.NewExpense(checking))
            fail("unreconciled receipt must be refused")
        } catch (_: ReceiptException.NotReconciled) {
        }
        assertEquals(0L, transactions())
        // After correcting the total, it confirms.
        receipts.confirm(detail.id, review.copy(total = Money(2_148)), ConfirmTarget.NewExpense(checking))
        assertEquals(1L, transactions())
    }
}
