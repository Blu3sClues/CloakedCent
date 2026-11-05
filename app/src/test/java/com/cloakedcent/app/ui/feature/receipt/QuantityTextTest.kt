package com.cloakedcent.app.ui.feature.receipt

import com.cloakedcent.app.domain.model.MeasureUnit
import com.cloakedcent.app.domain.model.PackSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityTextTest {

    @Test
    fun quantitiesRoundTripAsThousandths() {
        assertEquals("2", QuantityText.format(2000))
        assertEquals("1.25", QuantityText.format(1250))
        assertEquals("0.455", QuantityText.format(455))
        assertEquals(1250L, QuantityText.parse("1.25"))
        assertEquals(455L, QuantityText.parse("0,455"))
        assertEquals(3000L, QuantityText.parse(" 3 "))
        assertNull(QuantityText.parse("0"))
        assertNull(QuantityText.parse("1.2345"))
        assertNull(QuantityText.parse("-1"))
        assertNull(QuantityText.parse("abc"))
    }

    @Test
    fun correctionsRecalculateSubtotalAndTotalExactly() {
        val form = ReviewForm(
            date = java.time.LocalDate.of(2026, 11, 14),
            tax = "0.36",
            discount = "",
            subtotal = "99.99",
            total = "99.99",
            items = listOf(
                ItemForm(key = 1, lineTotal = "3.49"),
                ItemForm(key = 2, lineTotal = "5.00", discount = "0.50"),
            ),
            printedTotal = com.cloakedcent.app.core.money.Money(848),
        )
        val fixed = recalculated(form)
        assertEquals(com.cloakedcent.app.core.money.Money(799), com.cloakedcent.app.ui.components.AmountInput.parse(fixed.subtotal))
        assertEquals(com.cloakedcent.app.core.money.Money(835), com.cloakedcent.app.ui.components.AmountInput.parse(fixed.total))
        // The printed total is kept for comparison, never overwritten.
        assertEquals(com.cloakedcent.app.core.money.Money(848), fixed.printedTotal)
        // A receipt discount and tax both apply.
        val withCoupon = recalculated(fixed.copy(discount = "1.00"))
        assertEquals(com.cloakedcent.app.core.money.Money(735), com.cloakedcent.app.ui.components.AmountInput.parse(withCoupon.total))
        // While an amount is half-typed nothing is recalculated.
        val typing = form.copy(items = form.items + ItemForm(key = 3, lineTotal = "1."))
        assertEquals(typing, recalculated(typing))
    }

    @Test
    fun packSizesAreReadFromProductNames() {
        assertEquals(1000L to MeasureUnit.L, PackSizes.parse("Milk 2% 1L"))
        assertEquals(500_000L to MeasureUnit.G, PackSizes.parse("Coffee 500 g"))
        assertEquals(1_500L to MeasureUnit.KG, PackSizes.parse("Rice 1.5kg"))
        assertEquals(355_000L to MeasureUnit.ML, PackSizes.parse("Cola 355ml"))
        assertNull(PackSizes.parse("Bread"))
        assertNull(PackSizes.parse("Milk 2%"))
    }
}
