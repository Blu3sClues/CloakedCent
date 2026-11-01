package com.pocketbudget.app.ui.components

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountInputTest {

    @Test
    fun `accepts common ways of typing amounts`() {
        assertEquals(Money(1_250), AmountInput.parse("12.50"))
        assertEquals(Money(1_250), AmountInput.parse(" 12.5 "))
        assertEquals(Money(1_250), AmountInput.parse("12,50"))
        assertEquals(Money(123_450), AmountInput.parse("1,234.50"))
        assertEquals(Money(123_400), AmountInput.parse("1,234"))
        assertEquals(Money(-25_000), AmountInput.parse("-250"))
    }

    @Test
    fun `rejects input that is not an amount`() {
        listOf("", "abc", "1.234", "12..5", "$5").forEach { assertNull(it, AmountInput.parse(it)) }
    }

    @Test
    fun `format round-trips through parse`() {
        listOf(0L, 5L, -1_999L, 123_456L).forEach {
            assertEquals(Money(it), AmountInput.parse(AmountInput.format(Money(it))))
        }
    }
}
