package com.pocketbudget.app.data.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.model.ReceiptMath
import com.pocketbudget.app.domain.model.ReceiptParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/** Runs the bundled Tesseract model on rendered sample receipts, fully offline. */
@RunWith(AndroidJUnit4::class)
class TesseractOcrTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = TesseractOcrEngine(context)

    /** Renders receipt text like a thermal printer would: dark monospace on a light, slightly grey page. */
    private fun render(lines: List<String>, background: Int = Color.rgb(242, 240, 236)): ByteArray {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 34f
            color = Color.rgb(40, 40, 40)
        }
        val bitmap = Bitmap.createBitmap(900, 120 + lines.size * 52, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(background)
            lines.forEachIndexed { i, line -> drawText(line, 60f, 90f + i * 52, paint) }
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        // Stored pages are what OCR reads, so go through the same processing as an import.
        return ReceiptImageProcessor.process(PageInput(jpeg)).jpeg
    }

    @Test
    fun groceryReceiptIsReadOffline() = runTest(timeout = 3.minutes) {
        val page = render(
            listOf(
                "FRESH MART",
                "123 Main St",
                "2026-11-14 10:15",
                "MILK 2% 1L               3.49",
                "BREAD                    2.79",
                "2 @ 1.49",
                "YOGURT                   2.98",
                "SUBTOTAL                 9.26",
                "TAX                      0.37",
                "TOTAL                    9.63",
                "VISA                     9.63",
            ),
        )
        val lines = engine.recognize(0, page)
        assertTrue("lines have positions", lines.all { it.box != null })
        assertTrue("confidence is recorded", lines.all { it.confidence in 0..100 })
        val receipt = ReceiptParser.parse(lines).review
        assertEquals("Fresh Mart", receipt.storeName)
        assertEquals(LocalDate.of(2026, 11, 14), receipt.purchasedOn)
        assertEquals(Money(963), receipt.total)
        assertEquals(Money(37), receipt.tax)
        assertEquals(3, receipt.items.size)
        assertEquals(2000L, receipt.items[2].quantityMilli)
        assertTrue(ReceiptMath.reconcile(receipt).isEmpty())
    }

    @Test
    fun restaurantReceiptWithDiscountIsReadOffline() = runTest(timeout = 3.minutes) {
        val page = render(
            listOf(
                "CORNER BISTRO",
                "45 King Street West",
                "Nov 2, 2026  7:05 PM",
                "Burger                  16.00",
                "Iced Tea                 3.25",
                "Subtotal                19.25",
                "Promo                   -2.00",
                "GST                      0.86",
                "Total                   18.11",
            ),
            background = Color.WHITE,
        )
        val lines = engine.recognize(0, page)
        val receipt = ReceiptParser.parse(lines).review
        assertEquals(LocalDate.of(2026, 11, 2), receipt.purchasedOn)
        assertEquals(Money(1811), receipt.total)
        assertEquals(Money(200), receipt.discount)
        assertEquals(listOf(Money(1600), Money(325)), receipt.items.map { it.lineTotal })
        assertTrue(ReceiptMath.reconcile(receipt).isEmpty())
    }
}
