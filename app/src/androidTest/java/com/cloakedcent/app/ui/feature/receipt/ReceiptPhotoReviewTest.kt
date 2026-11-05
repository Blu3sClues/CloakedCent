package com.cloakedcent.app.ui.feature.receipt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.receipt.ReceiptImageStore
import com.cloakedcent.app.data.receipt.TesseractOcrEngine
import com.cloakedcent.app.data.repository.LedgerTestDatabase
import com.cloakedcent.app.data.repository.RoomReceiptRepository
import com.cloakedcent.app.data.repository.RoomRefundRepository
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.ImportOutcome
import com.cloakedcent.app.domain.model.MeasureUnit
import com.cloakedcent.app.domain.model.PageInput
import com.cloakedcent.app.ui.components.AmountInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * The review screen's ViewModel with real storage and real offline OCR, fed receipt
 * photos stored as test assets: the items appear without the user adding anything.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptPhotoReviewTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "photo-review-test")
    private lateinit var receipts: RoomReceiptRepository
    private var groceries = 0L
    private var housing = 0L

    @Before
    fun setUp() = runBlocking {
        dir.deleteRecursively()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        receipts = RoomReceiptRepository({ t.db }, t.ledger, t.payees, ReceiptImageStore(dir, { key }), TesseractOcrEngine(context))
        t.accounts.create("Checking", AccountType.CHECKING, Money(100_000))
        val c = t.categories.observeAll().first().associateBy { it.name }
        groceries = c.getValue("Groceries").id
        housing = c.getValue("Housing").id
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open("receipts/$name").use { it.readBytes() }

    /** Imports like the capture screen, then opens the review screen's ViewModel and waits for OCR. */
    private suspend fun review(asset: String): ReceiptReviewViewModel {
        val id = (receipts.import(listOf(PageInput(asset(asset))), asset) as ImportOutcome.Created).receiptId
        val vm = withContext(Dispatchers.Main) {
            ReceiptReviewViewModel(receipts, t.accounts, t.categories, id, RoomRefundRepository({ t.db }), dayFirst = false)
        }
        withTimeout(120_000) {
            while (vm.state.value.form == null || vm.state.value.reading) delay(100)
        }
        return vm
    }

    private fun money(text: String) = AmountInput.parse(text)

    @Test
    fun tiltedPhotoFillsTheReviewScreenAndConfirmsAfterCorrection() = runBlocking {
        val vm = review("grocery-tilted-photo.jpg")
        val form = vm.state.value.form!!
        assertEquals(6, form.items.size)
        assertEquals(listOf(349L, 279L, 218L, 387L, 500L, 429L), form.items.map { money(it.lineTotal)!!.cents })
        val bananas = form.items[2]
        assertEquals("BANANAS", bananas.description)
        assertEquals(MeasureUnit.KG, bananas.unit)
        assertEquals("1.25", bananas.quantity)
        val yogurt = form.items[3]
        assertEquals("3", yogurt.quantity)
        assertEquals(Money(129), money(yogurt.unitPrice))
        assertEquals(Money(2_148), money(form.total))
        assertEquals(Money(2_148), form.printedTotal)
        // Nothing financial yet, and nothing guessed.
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transactions WHERE type = 'EXPENSE'"))
        assertTrue(form.items.all { it.categoryId == null })

        // The photo's coupon line reads -8.50 instead of -0.50: the user fixes it on the soap,
        // assigns categories, and the totals are recalculated to match the printed total.
        withContext(Dispatchers.Main) {
            vm.editAdjustments { it.copy(discount = "") }
            form.items.forEachIndexed { index, item ->
                vm.editItem(item.key) { it.copy(categoryId = if (index == 4) housing else groceries, discount = if (index == 4) "0.50" else it.discount) }
            }
        }
        val fixed = vm.state.value.form!!
        assertEquals(Money(2_112), money(fixed.subtotal))
        assertEquals(Money(2_148), money(fixed.total))
        withContext(Dispatchers.Main) {
            vm.choose(ExpenseChoice.New)
            vm.confirm()
        }
        withTimeout(30_000) { while (!vm.state.value.done && vm.state.value.message == null) delay(100) }
        assertTrue("confirmed: ${vm.state.value.message}", vm.state.value.done)
        assertEquals(1L, t.count("SELECT COUNT(*) FROM transactions WHERE type = 'EXPENSE'"))
        assertEquals(2_148L, -t.db.transactionDao().getByRequestId("receipt-${vm.receiptId}")!!.amountCents)
    }

    @Test
    fun levelScanWithAWeighedItem() = runBlocking {
        val form = review("grocery-level-scan.jpg").state.value.form!!
        assertEquals(listOf("APPLES", "MILK 2% 1L", "DISH SOAP"), form.items.map { it.description })
        assertEquals("1.2", form.items[0].quantity)
        assertEquals(MeasureUnit.KG, form.items[0].unit)
        assertEquals(Money(350), money(form.items[0].unitPrice))
        assertEquals(Money(1_294), money(form.subtotal))
    }
}
