package com.pocketbudget.app.ui.feature.receipt

import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.PackSizes
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
    fun packSizesAreReadFromProductNames() {
        assertEquals(1000L to MeasureUnit.L, PackSizes.parse("Milk 2% 1L"))
        assertEquals(500_000L to MeasureUnit.G, PackSizes.parse("Coffee 500 g"))
        assertEquals(1_500L to MeasureUnit.KG, PackSizes.parse("Rice 1.5kg"))
        assertEquals(355_000L to MeasureUnit.ML, PackSizes.parse("Cola 355ml"))
        assertNull(PackSizes.parse("Bread"))
        assertNull(PackSizes.parse("Milk 2%"))
    }
}
