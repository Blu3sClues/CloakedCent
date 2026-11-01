package com.pocketbudget.app.core.money

/**
 * An exact monetary amount stored as whole cents (minor units).
 *
 * Rules:
 * - Never construct or calculate money with Float or Double.
 * - All arithmetic is overflow-checked and throws [ArithmeticException]
 *   instead of silently wrapping around.
 * - Amounts assume two minor units (cents). Currencies with a different
 *   number of minor units will need their own handling when multi-currency
 *   support is added.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(cents, other.cents))

    operator fun minus(other: Money): Money = Money(Math.subtractExact(cents, other.cents))

    operator fun unaryMinus(): Money = Money(Math.negateExact(cents))

    operator fun times(multiplier: Long): Money = Money(Math.multiplyExact(cents, multiplier))

    operator fun times(multiplier: Int): Money = times(multiplier.toLong())

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    val isZero: Boolean get() = cents == 0L
    val isNegative: Boolean get() = cents < 0L
    val isPositive: Boolean get() = cents > 0L

    fun abs(): Money = if (isNegative) -this else this

    /**
     * Locale-independent representation such as `-1234.05`. Intended for
     * storage, export and debugging; user-facing formatting belongs in the UI.
     */
    fun toPlainString(): String {
        val sign = if (cents < 0) "-" else ""
        // Long.MIN_VALUE has no positive counterpart, so split before abs().
        val whole = (cents / CENTS_PER_UNIT).let { if (it < 0) -it else it }
        val fraction = (cents % CENTS_PER_UNIT).let { if (it < 0) -it else it }
        return "$sign$whole.${fraction.toString().padStart(2, '0')}"
    }

    override fun toString(): String = toPlainString()

    companion object {
        const val CENTS_PER_UNIT = 100L

        val ZERO = Money(0L)

        fun ofCents(cents: Long): Money = Money(cents)

        /**
         * Parses a plain decimal string such as `12`, `12.5`, `-0.05` or `+3.10`.
         *
         * Accepts an optional sign, digits, and at most two fraction digits.
         * Grouping separators, currency symbols, exponents and locale-specific
         * decimal marks are rejected; the UI layer normalises user input first.
         *
         * @throws IllegalArgumentException if the text is not a valid amount.
         * @throws ArithmeticException if the amount does not fit in a Long of cents.
         */
        fun parse(text: String): Money {
            require(text.isNotEmpty()) { "Amount is empty" }

            var index = 0
            val negative = when (text[0]) {
                '-' -> { index++; true }
                '+' -> { index++; false }
                else -> false
            }

            val dot = text.indexOf('.', startIndex = index)
            val wholePart = if (dot == -1) text.substring(index) else text.substring(index, dot)
            val fractionPart = if (dot == -1) "" else text.substring(dot + 1)

            require(wholePart.isNotEmpty() && wholePart.all { it in '0'..'9' }) {
                "Invalid amount format"
            }
            require(dot == -1 || fractionPart.isNotEmpty()) { "Invalid amount format" }
            require(fractionPart.length <= 2 && fractionPart.all { it in '0'..'9' }) {
                "Amounts support at most two decimal places"
            }

            var units = 0L
            for (digit in wholePart) {
                units = Math.addExact(Math.multiplyExact(units, 10L), (digit - '0').toLong())
            }
            val fractionCents = fractionPart.padEnd(2, '0').toLong()
            val magnitude = Math.addExact(Math.multiplyExact(units, CENTS_PER_UNIT), fractionCents)

            return Money(if (negative) -magnitude else magnitude)
        }

        /** Like [parse] but returns null instead of throwing for invalid or out-of-range input. */
        fun parseOrNull(text: String): Money? =
            try {
                parse(text)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
    }
}

/** Overflow-checked sum of a collection of amounts. */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { total, amount -> total + amount }
