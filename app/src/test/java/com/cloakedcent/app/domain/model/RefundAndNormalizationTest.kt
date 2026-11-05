package com.cloakedcent.app.domain.model

import com.cloakedcent.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefundAndNormalizationTest {

    private fun item(paid: Long, quantityMilli: Long = 3000, refunded: Long = 0, refundedQty: Long = 0) =
        RefundableItem(1, "Yogurt", quantityMilli, MeasureUnit.EACH, Money(paid), Money(refunded), refundedQty)

    @Test
    fun quantityRefundsArePricedFromThePurchaseAndNeverOverRefund() {
        // 3 for $10.00: one is $3.33, two are $6.67, and the last always takes exactly what is left.
        val bought = item(1000)
        assertEquals(Money(333), RefundMath.amountForQuantity(bought, 1000))
        assertEquals(Money(667), RefundMath.amountForQuantity(bought, 2000))
        val afterOne = item(1000, refunded = 333, refundedQty = 1000)
        assertEquals(Money(333), RefundMath.amountForQuantity(afterOne, 1000))
        val afterTwo = item(1000, refunded = 666, refundedQty = 2000)
        assertEquals(Money(334), RefundMath.amountForQuantity(afterTwo, 1000))
        // Weighted items refund by fractions of the unit.
        val apples = RefundableItem(2, "Apples", 1_200, MeasureUnit.KG, Money(420), Money.ZERO, 0)
        assertEquals(Money(175), RefundMath.amountForQuantity(apples, 500))
    }

    @Test
    fun netPartFollowsTheRefundAndSettlesExactly() {
        // Paid $9.14 for an item worth $8.49 before tax.
        assertEquals(Money(425), RefundMath.netPart(Money(457), Money(914), Money(914), Money(849), Money(849)))
        assertEquals(Money(424), RefundMath.netPart(Money(457), Money(914), Money(457), Money(849), Money(424)))
        assertEquals(Money.ZERO, RefundMath.netPart(Money(100), Money.ZERO, Money(100), Money.ZERO, Money.ZERO))
    }

    @Test
    fun spreadingAWholeRefundOverItemsIsExact() {
        val parts = RefundMath.spread(Money(500), listOf(Money(376), Money(538), Money(0)))
        assertEquals(500L, parts.sumOf { it.cents })
        assertEquals(Money.ZERO, parts[2])
        assertEquals(listOf(Money(206), Money(294), Money.ZERO), parts)
        // Refunding everything left returns each item's remainder exactly.
        assertEquals(listOf(Money(376), Money(538)), RefundMath.spread(Money(914), listOf(Money(376), Money(538))))
    }

    @Test
    fun refundStates() {
        assertEquals(RefundState.NONE, RefundMath.state(Money(1000), Money.ZERO))
        assertEquals(RefundState.PARTIAL, RefundMath.state(Money(1000), Money(1)))
        assertEquals(RefundState.FULL, RefundMath.state(Money(1000), Money(1000)))
    }

    @Test
    fun itemSharesOfTheTotalSumExactly() {
        val shares = ReceiptMath.allocateItems(Money(914), listOf(Money(349), Money(500)))
        assertEquals(listOf(Money(376), Money(538)), shares)
        assertEquals(100L, ReceiptMath.allocateItems(Money(100), listOf(Money(1), Money(1), Money(1))).sumOf { it.cents })
    }

    @Test
    fun storeKeysIgnoreCaseSpacingPunctuationAndFormatWords() {
        val walmart = TextKeys.storeKey("Walmart")
        listOf("WALMART", "walmart", "  Walmart  ", "Wal-Mart", "Walmart Supercenter", "WALMART SUPERCENTER #1234", "Walmart Store 5521", "Walmart, Inc.")
            .forEach { assertEquals(it, walmart, TextKeys.storeKey(it)) }
        assertEquals(TextKeys.storeKey("Trader Joe's"), TextKeys.storeKey("TRADER JOES"))
        assertEquals(TextKeys.storeKey("The Home Depot"), TextKeys.storeKey("Home Depot"))
        assertEquals(TextKeys.storeKey("H&M"), TextKeys.storeKey("H AND M"))
        // Different businesses stay different, even with shared words.
        assertNotEquals(TextKeys.storeKey("Walmart"), TextKeys.storeKey("Walgreens"))
        assertNotEquals(TextKeys.storeKey("Whole Foods Market"), TextKeys.storeKey("Whole Foods Bakery"))
        assertNotEquals(TextKeys.storeKey("Fresh Market"), TextKeys.storeKey("Fresh"))
        assertNotEquals(TextKeys.storeKey("Shell"), TextKeys.storeKey("Shell Bakery"))
        // A name made only of a format word is kept as it is.
        assertEquals("STORE", TextKeys.storeKey("Store"))
    }

    @Test
    fun productKeysUnifyFormattingButKeepSizesBrandsAndVariants() {
        val milk = TextKeys.descriptionKey("Whole Milk")
        assertEquals(milk, TextKeys.descriptionKey("WHOLE MILK"))
        assertEquals(milk, TextKeys.descriptionKey("  whole   milk "))
        assertEquals(milk, TextKeys.descriptionKey("Whole-Milk"))
        assertEquals(TextKeys.descriptionKey("Milk 1L"), TextKeys.descriptionKey("MILK 1 LTR"))
        assertEquals(TextKeys.descriptionKey("Milk 1L"), TextKeys.descriptionKey("milk 1.0 l"))
        assertEquals(TextKeys.descriptionKey("Rice 1.5kg"), TextKeys.descriptionKey("RICE 1,5 KGS"))
        assertEquals(TextKeys.descriptionKey("Ben & Jerry's"), TextKeys.descriptionKey("BEN AND JERRYS"))
        assertEquals("MILK 2 1L", TextKeys.descriptionKey("0012345 Milk 2% 1L"))
        // Never the same key for different sizes, variants or brands.
        assertNotEquals(TextKeys.descriptionKey("Milk 1L"), TextKeys.descriptionKey("Milk 2L"))
        assertNotEquals(TextKeys.descriptionKey("Milk 1.5L"), TextKeys.descriptionKey("Milk 15L"))
        assertNotEquals(TextKeys.descriptionKey("Milk 2% 1L"), TextKeys.descriptionKey("Milk 1% 1L"))
        assertNotEquals(TextKeys.descriptionKey("Whole Milk"), TextKeys.descriptionKey("Skim Milk"))
        assertNotEquals(TextKeys.descriptionKey("Heinz Ketchup"), TextKeys.descriptionKey("Hunts Ketchup"))
    }

    @Test
    fun ocrKeysFoldCommonMisreadsButKeepRealDifferences() {
        assertEquals(TextKeys.ocrKey("MILK 2% 1L"), TextKeys.ocrKey("MILK 2% IL"))
        assertEquals(TextKeys.ocrKey("SOAP 500G"), TextKeys.ocrKey("S0AP 5OOG"))
        assertNotEquals(TextKeys.ocrKey("MILK 2% 1L"), TextKeys.ocrKey("MILK 2% 2L"))
        assertNotEquals(TextKeys.ocrKey("WHOLE MILK"), TextKeys.ocrKey("SKIM MILK"))
    }

    @Test
    fun fuzzyProductSuggestionsNeverCrossSizesOrVariants() {
        val products = listOf(ProductMatcher.Candidate(1, "Organic Whole Milk 2L"), ProductMatcher.Candidate(2, "Greek Yogurt Plain"))
        assertEquals(1L, ProductMatcher.match("WHOLE MILK ORGANIC 2 L", products)!!.first.productId)
        assertNull("different size", ProductMatcher.match("Organic Whole Milk 1L", products))
        assertNull("size only on one side", ProductMatcher.match("Greek Yogurt Plain 500g", products))
    }
}
