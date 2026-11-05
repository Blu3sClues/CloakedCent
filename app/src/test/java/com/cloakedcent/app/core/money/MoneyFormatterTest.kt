package com.cloakedcent.app.core.money

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Currency
import java.util.Locale

class MoneyFormatterTest {

    private val formatter = MoneyFormatter(Locale.US, Currency.getInstance("USD"))

    @Test
    fun `formats cents exactly`() {
        assertEquals("$123.45", formatter.format(Money(12_345)))
        assertEquals("$0.05", formatter.format(Money(5)))
        assertEquals("-$5.00", formatter.format(Money(-500)))
    }

    @Test
    fun `extreme values keep every cent`() {
        // A Double would round this; BigDecimal keeps it exact.
        assertEquals("$92,233,720,368,547,758.07", formatter.format(Money(Long.MAX_VALUE)))
    }

    @Test
    fun `signed format marks direction`() {
        assertEquals("+$1.00", formatter.formatSigned(Money(100)))
        assertEquals("-$1.00", formatter.formatSigned(Money(-100)))
        assertEquals("$0.00", formatter.formatSigned(Money.ZERO))
    }
}
