package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptMathTest {

    @Test
    fun unitPricesNormalizeAcrossUnitsExactly() {
        // 1.25 kg for $2.18 -> $1.744/kg
        assertEquals(174_400L,UnitPricing.normalize(Money(218), 1250, MeasureUnit.KG)!!.milliCentsPerUnit)
        // 500 g for $2.00 -> $4.00/kg
        assertEquals(Money(400), UnitPricing.normalize(Money(200), 500_000, MeasureUnit.G)!!.perUnit)
        // 1 lb for $4.5359237 -> $10.00/kg; lb is converted exactly via 453.59237 g.
        val perLb = UnitPricing.normalize(Money(454), 1000, MeasureUnit.LB)!!
        assertEquals(Dimension.MASS, perLb.dimension)
        assertEquals(1_000_899L, perLb.milliCentsPerUnit)
        // 750 ml for $3.00 -> $4.00/L
        assertEquals(Money(400), UnitPricing.normalize(Money(300), 750_000, MeasureUnit.ML)!!.perUnit)
        // 3 items for $3.00 -> $1.00 each
        assertEquals(Money(100), UnitPricing.normalize(Money(300), 3000, MeasureUnit.EACH)!!.perUnit)
        // 2 x 1 L packs for $5.00 -> $2.50/L, comparable with loose litres.
        val packs = UnitPricing.normalize(Money(500), 2000, MeasureUnit.EACH, 1000, MeasureUnit.L)!!
        assertEquals(Dimension.VOLUME, packs.dimension)
        assertEquals(Money(250), packs.perUnit)
        assertNull(UnitPricing.normalize(Money(0), 1000, MeasureUnit.EACH))
        assertNull(UnitPricing.normalize(Money(100), 0, MeasureUnit.KG))
    }

    @Test
    fun priceChangeComparesSameDimensionOnly() {
        val old = NormalizedPrice(Dimension.MASS, 400_000)
        assertEquals(25L, UnitPricing.changePercent(old, NormalizedPrice(Dimension.MASS, 500_000)))
        assertNull(UnitPricing.changePercent(old, NormalizedPrice(Dimension.VOLUME, 500_000)))
    }

    @Test
    fun allocationSumsExactlyAndSharesTaxProportionally() {
        // $10.00 food + $5.00 household, total with tax $16.95
        val split = ReceiptMath.allocate(Money(1695), listOf(1L to Money(1000), 2L to Money(500)))
        assertEquals(Money(1130), split[1])
        assertEquals(Money(565), split[2])
        // Thirds: remainders go to the largest fractions, total still exact.
        val thirds = ReceiptMath.allocate(Money(100), listOf(1L to Money(1), 2L to Money(1), 3L to Money(1)))
        assertEquals(100L, thirds.values.sumOf { it.cents })
        // Same category on several items is merged; zero-net items get nothing.
        val merged = ReceiptMath.allocate(Money(300), listOf(1L to Money(100), 1L to Money(200), 4L to Money(0)))
        assertEquals(mapOf(1L to Money(300)), merged)
    }

    @Test
    fun reconcileAcceptsSubtotalBeforeOrAfterCoupons() {
        val items = listOf(
            ReceiptItemDraft(rawText = "A", description = "A", lineTotal = Money(1000), discount = Money(100)),
            ReceiptItemDraft(rawText = "B", description = "B", lineTotal = Money(500)),
        )
        val base = ReceiptReview(items = items, tax = Money(70), discount = Money(200), total = Money(1270))
        assertTrue(ReceiptMath.reconcile(base.copy(subtotal = Money(1400))).isEmpty())
        assertTrue(ReceiptMath.reconcile(base.copy(subtotal = Money(1200))).isEmpty())
        assertEquals(
            listOf<ReconcileIssue>(ReconcileIssue.SubtotalMismatch(Money(-50))),
            ReceiptMath.reconcile(base.copy(subtotal = Money(1450))),
        )
        assertEquals(listOf<ReconcileIssue>(ReconcileIssue.MissingTotal), ReceiptMath.reconcile(base.copy(total = null)))
    }

    @Test
    fun productMatchingUsesKeysAndSimilarity() {
        val products = listOf(
            ProductMatcher.Candidate(1, "Milk 2% 1L"),
            ProductMatcher.Candidate(2, "Whole Wheat Bread"),
        )
        assertEquals(1L to true, ProductMatcher.match("MILK 2% 1L", products)!!.let { it.first.productId to it.second })
        assertEquals(2L, ProductMatcher.match("WHOLE WHEAT BREAD LOAF", products)!!.first.productId)
        assertNull(ProductMatcher.match("BANANAS", products))
        assertEquals("MILK 2 1L", TextKeys.descriptionKey("0012345 Milk  2%  1L"))
        assertEquals("CAFE", TextKeys.normalize("Café"))
    }

    @Test
    fun imageHashDistance() {
        assertEquals(0, ImageHashes.distance(0x0F0FL, 0x0F0FL))
        assertEquals(4, ImageHashes.distance(0x0FL, 0x00L))
    }
}
