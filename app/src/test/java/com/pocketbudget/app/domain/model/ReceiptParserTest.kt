package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReceiptParserTest {

    private fun lines(text: String, confidence: Int = 92) =
        text.trimIndent().lines().mapIndexed { i, t -> OcrLine(page = 0, index = i, text = t, confidence = confidence) }

    private fun money(text: String) = Money.parse(text)

    @Test
    fun groceryReceiptWithWeightsQuantitiesAndCoupons() {
        val parsed = ReceiptParser.parse(
            lines(
                """
                FRESH MART
                123 Main St
                Springfield, IL 62701
                (217) 555-0100
                11/03/2026 5:42 PM
                MILK 2% 1L         3.49 F
                BANANAS
                1.250 kg @ $1.74/kg     2.18 F
                2 @ 1.49
                YOGURT              2.98
                BREAD               2.79
                  COUPON           -0.50
                SUBTOTAL           10.94
                TAX                 0.36
                TOTAL              11.30
                VISA               11.30
                CHANGE              0.00
                YOU SAVED           0.50
                """,
            ),
        )
        val r = parsed.review
        assertEquals("Fresh Mart", r.storeName)
        assertEquals("123 Main St, Springfield, IL 62701", r.storeAddress)
        assertEquals(LocalDate.of(2026, 11, 3), r.purchasedOn)
        assertEquals(LocalTime.of(17, 42), r.purchasedTime)
        assertEquals(listOf("MILK 2% 1L", "BANANAS", "YOGURT", "BREAD"), r.items.map { it.description })
        val bananas = r.items[1]
        assertEquals(1250L, bananas.quantityMilli)
        assertEquals(MeasureUnit.KG, bananas.unit)
        assertEquals(money("1.74"), bananas.unitPrice)
        assertEquals(money("2.18"), bananas.lineTotal)
        assertEquals(2000L, r.items[2].quantityMilli)
        assertEquals(money("1.49"), r.items[2].unitPrice)
        assertEquals("coupon after an item is that item's discount", money("0.50"), r.items[3].discount)
        assertEquals(money("10.94"), r.subtotal)
        assertEquals(money("0.36"), r.tax)
        assertEquals(money("11.30"), r.total)
        assertTrue("payment and savings lines are ignored", ReceiptMath.reconcile(r).isEmpty())
        assertFalse(ReceiptField.TOTAL in parsed.uncertain)
        // 11/03 could be 3 November or 11 March.
        assertTrue(ReceiptField.DATE in parsed.uncertain)
    }

    @Test
    fun restaurantWithMultipleTaxesAndReceiptDiscount() {
        val r = ReceiptParser.parse(
            lines(
                """
                The Corner Bistro
                45 King Street West
                Toronto, ON M5H 1J9
                Receipt #: A-10234
                2026-10-28 19:05
                Burger            16.00
                Fries 2 x 4.50     9.00
                Iced Tea           3.25
                Sub-Total         28.25
                Promo 10%         -2.83
                GST 5%             1.27
                PST 8%             2.03
                Total             28.72
                MASTERCARD        28.72
                """,
            ),
        ).review
        assertEquals("The Corner Bistro", r.storeName)
        assertEquals("A-10234", r.receiptNumber)
        assertEquals(LocalDate.of(2026, 10, 28), r.purchasedOn)
        assertEquals(3, r.items.size)
        assertEquals("Fries", r.items[1].description)
        assertEquals(2000L, r.items[1].quantityMilli)
        assertEquals(money("2.83"), r.discount)
        assertEquals(money("3.30"), r.tax)
        assertEquals(money("28.72"), r.total)
        assertTrue(ReceiptMath.reconcile(r).isEmpty())
    }

    @Test
    fun ocrConfusionsInAmountsAreRepairedAndLowConfidenceIsFlagged() {
        val input = lines(
            """
            QUICK STOP
            Nov 2, 2026
            COFFEE    2.5O
            MUFFIN    3.l9
            TOTAL     5.69
            """,
        ).map { if (it.index == 3) it.copy(confidence = 40) else it }
        val parsed = ReceiptParser.parse(input)
        assertEquals(listOf(money("2.50"), money("3.19")), parsed.review.items.map { it.lineTotal })
        assertEquals(LocalDate.of(2026, 11, 2), parsed.review.purchasedOn)
        assertFalse(ReceiptField.DATE in parsed.uncertain)
        assertTrue(parsed.review.items[1].uncertain)
        assertFalse(parsed.review.items[0].uncertain)
        assertEquals(OcrSource(0, 4), parsed.sources[ReceiptField.TOTAL])
    }

    @Test
    fun totalThatDoesNotAddUpIsUncertain() {
        val parsed = ReceiptParser.parse(lines("SHOP\nAPPLES 1.00\nPEARS 2.00\nTOTAL 4.00"))
        assertTrue(ReceiptField.TOTAL in parsed.uncertain)
        assertEquals(
            listOf<ReconcileIssue>(ReconcileIssue.TotalMismatch(Money(-100))),
            ReceiptMath.reconcile(parsed.review),
        )
    }

    @Test
    fun dayFirstDatesAndMissingFields() {
        val r = ReceiptParser.parse(lines("MARKET\n04/11/2026\nTEA 2.00"), dayFirst = true).review
        assertEquals(LocalDate.of(2026, 11, 4), r.purchasedOn)
        assertNull(r.total)
        val empty = ReceiptParser.parse(emptyList())
        assertTrue(ReceiptField.STORE in empty.uncertain && ReceiptField.DATE in empty.uncertain)
    }

    @Test
    fun wordsReadAsSeparateColumnsAreRejoinedIntoRows() {
        fun word(text: String, left: Int, top: Int, confidence: Int = 90) =
            OcrWord(text, confidence, OcrBox(left, top, left + text.length * 20, top + 34))
        // Names first, then the price column, as Tesseract often returns them.
        val words = listOf(
            word("MILK", 60, 100), word("2%", 160, 102),
            word("BREAD", 60, 152),
            word("3.49", 700, 98, confidence = 60),
            word("2.79", 700, 154),
        )
        val rows = OcrLayout.rows(0, words)
        assertEquals(listOf("MILK 2% 3.49", "BREAD 2.79"), rows.map { it.text })
        assertEquals(listOf(0, 1), rows.map { it.index })
        // Length-weighted: (4*90 + 2*90 + 4*60) / 10
        assertEquals(78, rows[0].confidence)
        assertEquals(OcrBox(60, 98, 780, 136), rows[0].box)
    }

    @Test
    fun descriptionsKeepShortWordsAndDropCodesAndMisreadFlags() {
        val r = ReceiptParser.parse(
            lines(
                """
                SUPER STORE
                0007654321 TISSUES 2 PK      4.20
                GV MILK 2% 078742351865 F    3.48 N
                VITAMIN C                    8.99 &
                PAPER TOWEL XL               6.50 T*
                TOTAL                       23.17
                """,
            ),
        ).review
        assertEquals(listOf("TISSUES 2 PK", "GV MILK 2%", "VITAMIN C", "PAPER TOWEL XL"), r.items.map { it.description })
        // The OCR text is kept for history and alias learning.
        assertEquals(r.items.map { it.description }, r.items.map { it.rawText })
        assertTrue(ReceiptMath.reconcile(r).isEmpty())
    }

    @Test
    fun garbledQuantityLinesStayWithTheirItemAndAreFlagged() {
        val parsed = ReceiptParser.parse(
            lines(
                """
                MARKET
                BANANAS
                4.25 kg @ 1.74/k8     2.18
                BREAD                 2.79
                TOTAL                 4.97
                """,
            ),
        )
        val bananas = parsed.review.items.first()
        assertEquals("BANANAS", bananas.description)
        assertEquals(Money(218), bananas.lineTotal)
        assertTrue("unreadable quantity is flagged", bananas.uncertain)
        assertFalse(parsed.review.items[1].uncertain)
    }

    @Test
    fun tiltedPhotosStillGroupNamesWithTheirPrices() {
        // Two printed rows photographed at about 3 degrees: the price column sits ~30 px
        // lower than its name, more than half a line height, yet each stays on its row.
        fun word(text: String, left: Int, top: Int) = OcrWord(text, 90, OcrBox(left, top, left + text.length * 20, top + 34))
        fun tilt(x: Int) = x / 20
        val words = listOf(
            word("MILK", 60, 100 + tilt(60)), word("2%", 160, 100 + tilt(160)), word("1L", 220, 100 + tilt(220)),
            word("3.49", 660, 100 + tilt(660)),
            word("BREAD", 60, 150 + tilt(60)), word("WHOLE", 180, 150 + tilt(180)), word("WHEAT", 300, 150 + tilt(300)),
            word("2.79", 660, 150 + tilt(660)),
            word("TAX", 60, 200 + tilt(60)), word("0.36", 660, 200 + tilt(660)),
        )
        assertEquals(
            listOf("MILK 2% 1L 3.49", "BREAD WHOLE WHEAT 2.79", "TAX 0.36"),
            OcrLayout.rows(0, words).map { it.text },
        )
        // Short low-confidence specks from the paper edge are dropped.
        val noisy = words + OcrWord("ae", 20, OcrBox(5, 100, 25, 130))
        assertEquals("MILK 2% 1L 3.49", OcrLayout.rows(0, noisy).first().text)
    }

    @Test
    fun amountParsingIsExactWholeCents() {
        assertEquals(Money(123456), ReceiptParser.parseAmount("Total $1,234.56"))
        assertEquals(Money(1234), ReceiptParser.parseAmount("12,34"))
        assertEquals(Money(300), ReceiptParser.parseAmount("DISCOUNT 3.00-"))
        assertEquals(Money(999_999_99), ReceiptParser.parseAmount("999999.99"))
        assertNull(ReceiptParser.parseAmount("no amount here"))
        assertNull(ReceiptParser.parseAmount("12.5"))
    }
}
