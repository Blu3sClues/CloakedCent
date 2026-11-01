package com.pocketbudget.app.ui.components

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AmountInputTest {

    private val us = Locale.US
    private val de = Locale.GERMANY

    private fun parse(text: String, locale: Locale = us) = AmountInput.parse(text, locale)

    @Test
    fun `whole and partial cents are completed on display`() {
        assertEquals("$38.00", AmountInput.format(parse("38")!!, us))
        assertEquals("$38.50", AmountInput.format(parse("38.5")!!, us))
        assertEquals("$38.56", AmountInput.format(parse("38.56")!!, us))
    }

    @Test
    fun `thousands separators are accepted and shown`() {
        assertEquals(Money(123_456), parse("1,234.56"))
        assertEquals(Money(123_400), parse("1,234"))
        assertEquals(Money(123_456_789), parse("1,234,567.89"))
        assertEquals("$1,234,567.89", AmountInput.format(Money(123_456_789), us))
    }

    @Test
    fun `formatted text parses back to the same cents`() {
        listOf(0L, 5L, 3_800L, -25_000L, 123_456_789L).forEach {
            assertEquals(Money(it), parse(AmountInput.format(Money(it), us)))
        }
    }

    @Test
    fun `currency symbol is removed for editing`() {
        assertEquals("1,234.50", AmountInput.stripForEditing("$1,234.50", us))
        assertEquals("-250.00", AmountInput.stripForEditing("-$250.00", us))
        assertEquals("38", AmountInput.stripForEditing("38", us))
    }

    @Test
    fun `alternative decimal mark is understood`() {
        assertEquals(Money(1_250), parse("12,50"))
        assertEquals(Money(1_250), parse("12,5"))
        assertEquals(Money(-25_000), parse("-250"))
    }

    @Test
    fun `locales with a decimal comma work`() {
        assertEquals(Money(123_456), parse("1.234,56", de))
        assertEquals(Money(1_250), parse("12,50", de))
        assertEquals(Money(1_250), parse("12.5", de))
        assertEquals(Money(123_456), parse(AmountInput.format(Money(123_456), de), de))
    }

    @Test
    fun `rejects input that is not an amount`() {
        listOf("", "abc", "1.234.5", "12..5", "38.567", "€5").forEach { assertNull(it, parse(it)) }
    }
}
