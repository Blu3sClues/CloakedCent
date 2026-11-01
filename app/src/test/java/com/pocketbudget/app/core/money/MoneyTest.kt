package com.pocketbudget.app.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun `arithmetic is exact in cents`() {
        // The classic 0.1 + 0.2 floating point trap must not apply.
        assertEquals(Money(30), Money(10) + Money(20))
        assertEquals(Money(-5), Money(10) - Money(15))
        assertEquals(Money(-10), -Money(10))
        assertEquals(Money(3_000), Money(1_000) * 3)
        assertEquals(Money(1_000), Money(-1_000).abs())
    }

    @Test
    fun `addition overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) + Money(1) }
    }

    @Test
    fun `subtraction overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE) - Money(1) }
    }

    @Test
    fun `negating the minimum value throws`() {
        assertThrows(ArithmeticException::class.java) { -Money(Long.MIN_VALUE) }
    }

    @Test
    fun `multiplication overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE / 2 + 1) * 2 }
    }

    @Test
    fun `sign predicates`() {
        assertTrue(Money.ZERO.isZero)
        assertTrue(Money(-1).isNegative)
        assertTrue(Money(1).isPositive)
        assertFalse(Money.ZERO.isPositive)
        assertFalse(Money.ZERO.isNegative)
    }

    @Test
    fun `ordering compares cents`() {
        assertTrue(Money(-1) < Money.ZERO)
        assertTrue(Money(100) > Money(99))
        assertEquals(listOf(Money(-5), Money(0), Money(7)), listOf(Money(7), Money(-5), Money(0)).sorted())
    }

    @Test
    fun `sum adds all amounts and is zero for empty input`() {
        assertEquals(Money(600), listOf(Money(100), Money(200), Money(300)).sum())
        assertEquals(Money.ZERO, emptyList<Money>().sum())
    }

    @Test
    fun `sum overflow throws`() {
        assertThrows(ArithmeticException::class.java) {
            listOf(Money(Long.MAX_VALUE), Money(1)).sum()
        }
    }

    @Test
    fun `toPlainString formats sign and two decimals`() {
        assertEquals("0.00", Money.ZERO.toPlainString())
        assertEquals("0.05", Money(5).toPlainString())
        assertEquals("-0.05", Money(-5).toPlainString())
        assertEquals("1234.50", Money(123_450).toPlainString())
        assertEquals("-1234.50", Money(-123_450).toPlainString())
        assertEquals("92233720368547758.07", Money(Long.MAX_VALUE).toPlainString())
        assertEquals("-92233720368547758.08", Money(Long.MIN_VALUE).toPlainString())
    }

    @Test
    fun `parse accepts plain decimal amounts`() {
        assertEquals(Money(1_200), Money.parse("12"))
        assertEquals(Money(1_250), Money.parse("12.5"))
        assertEquals(Money(1_205), Money.parse("12.05"))
        assertEquals(Money(-5), Money.parse("-0.05"))
        assertEquals(Money(310), Money.parse("+3.10"))
        assertEquals(Money.ZERO, Money.parse("0"))
        assertEquals(Money(7), Money.parse("000.07"))
    }

    @Test
    fun `parse round-trips toPlainString`() {
        listOf(0L, 1L, -1L, 99L, 100L, -12_345L, Long.MAX_VALUE).forEach { cents ->
            assertEquals(Money(cents), Money.parse(Money(cents).toPlainString()))
        }
    }

    @Test
    fun `parse rejects malformed input`() {
        listOf(
            "", "-", "+", ".", "1.", ".5", "1.234", "1,00", "1 000", "$5",
            "1e3", "--1", "1.2.3", "abc", " 1", "1 ", "NaN", "Infinity",
        ).forEach { text ->
            assertThrows("Expected rejection of '$text'", IllegalArgumentException::class.java) {
                Money.parse(text)
            }
        }
    }

    @Test
    fun `parse rejects amounts that do not fit in a Long of cents`() {
        assertThrows(ArithmeticException::class.java) { Money.parse("92233720368547758.08") }
        assertThrows(ArithmeticException::class.java) { Money.parse("99999999999999999999") }
    }

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertNull(Money.parseOrNull("1.234"))
        assertNull(Money.parseOrNull("99999999999999999999"))
        assertEquals(Money(150), Money.parseOrNull("1.50"))
    }
}
