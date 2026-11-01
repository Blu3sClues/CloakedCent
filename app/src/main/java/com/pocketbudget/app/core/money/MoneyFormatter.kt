package com.pocketbudget.app.core.money

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Locale-aware display formatting. Converts cents through BigDecimal (exact),
 * never through Float/Double. All accounts currently share one display currency.
 */
class MoneyFormatter(locale: Locale = Locale.getDefault(), currency: Currency = defaultCurrency(locale)) {

    private val format: NumberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    fun format(money: Money): String = synchronized(format) {
        format.format(BigDecimal.valueOf(money.cents, 2))
    }

    /** Explicit sign for amounts where direction matters, e.g. "+$5.00" / "-$5.00". */
    fun formatSigned(money: Money): String =
        if (money.isPositive) "+${format(money)}" else format(money)

    companion object {
        fun defaultCurrency(locale: Locale): Currency =
            runCatching { Currency.getInstance(locale) }.getOrNull() ?: Currency.getInstance("USD")
    }
}
