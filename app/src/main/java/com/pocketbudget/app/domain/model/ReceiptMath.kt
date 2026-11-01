package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import java.math.BigInteger
import java.math.RoundingMode
import java.text.Normalizer

enum class Dimension { COUNT, MASS, VOLUME }

/**
 * Units a receipt line can be sold in. [nanoPerMilli] is the size of one thousandth
 * of the unit in the dimension's base (nanograms, nanolitres, or thousandths of an
 * item), so every conversion is exact integer arithmetic (1 lb = 453.59237 g exactly).
 */
enum class MeasureUnit(val dimension: Dimension, val nanoPerMilli: Long, val symbol: String) {
    EACH(Dimension.COUNT, 1, "ea"),
    G(Dimension.MASS, 1_000_000, "g"),
    KG(Dimension.MASS, 1_000_000_000, "kg"),
    LB(Dimension.MASS, 453_592_370, "lb"),
    ML(Dimension.VOLUME, 1_000, "ml"),
    L(Dimension.VOLUME, 1_000_000, "L"),
    ;

    companion object {
        fun fromSymbol(text: String): MeasureUnit? = when (text.lowercase().trimEnd('.')) {
            "ea", "each", "pc", "pcs" -> EACH
            "g", "gr", "gm" -> G
            "kg", "kgs" -> KG
            "lb", "lbs" -> LB
            "ml" -> ML
            "l", "lt", "ltr", "litre", "liter" -> L
            else -> null
        }
    }
}

/**
 * Price per standard unit (per kg, per litre or per item) in thousandths of a
 * cent, so small items still compare precisely.
 */
data class NormalizedPrice(val dimension: Dimension, val milliCentsPerUnit: Long) {
    val standardUnit: MeasureUnit
        get() = when (dimension) {
            Dimension.COUNT -> MeasureUnit.EACH
            Dimension.MASS -> MeasureUnit.KG
            Dimension.VOLUME -> MeasureUnit.L
        }

    /** Rounded to whole cents for display only. */
    val perUnit: Money get() = Money(divideRounded(BigInteger.valueOf(milliCentsPerUnit), BigInteger.valueOf(1000)))
}

object UnitPricing {

    /** Base quantity of one standard unit: 1 kg in ng, 1 L in nl, 1 item in thousandths. */
    private fun standardBase(dimension: Dimension): BigInteger = when (dimension) {
        Dimension.COUNT -> BigInteger.valueOf(1_000)
        Dimension.MASS -> BigInteger.valueOf(1_000_000_000_000)
        Dimension.VOLUME -> BigInteger.valueOf(1_000_000_000)
    }

    /**
     * [paid] for [quantityMilli] of [unit]. Items sold individually are measured by their
     * pack size when known (2 x 1 L milk compares per litre), otherwise per item.
     * Returns null when the quantity is zero or the paid amount is not positive.
     */
    fun normalize(
        paid: Money,
        quantityMilli: Long,
        unit: MeasureUnit,
        packQuantityMilli: Long? = null,
        packUnit: MeasureUnit? = null,
    ): NormalizedPrice? {
        if (quantityMilli <= 0 || !paid.isPositive) return null
        val (dimension, base) =
            if (unit == MeasureUnit.EACH && packUnit != null && packUnit != MeasureUnit.EACH &&
                packQuantityMilli != null && packQuantityMilli > 0
            ) {
                // quantity is in thousandths of an item, the pack size in thousandths of its unit.
                packUnit.dimension to BigInteger.valueOf(quantityMilli)
                    .multiply(BigInteger.valueOf(packQuantityMilli))
                    .multiply(BigInteger.valueOf(packUnit.nanoPerMilli))
                    .divide(BigInteger.valueOf(1000))
            } else {
                unit.dimension to BigInteger.valueOf(quantityMilli).multiply(BigInteger.valueOf(unit.nanoPerMilli))
            }
        if (base.signum() <= 0) return null
        val numerator = BigInteger.valueOf(paid.cents).multiply(BigInteger.valueOf(1000)).multiply(standardBase(dimension))
        return NormalizedPrice(dimension, divideRounded(numerator, base))
    }

    /** Percentage change from [previous] to [current] (same dimension), rounded down; null otherwise. */
    fun changePercent(previous: NormalizedPrice, current: NormalizedPrice): Long? {
        if (previous.dimension != current.dimension || previous.milliCentsPerUnit <= 0) return null
        return Math.multiplyExact(current.milliCentsPerUnit - previous.milliCentsPerUnit, 100L) /
            previous.milliCentsPerUnit
    }
}

/** Half-up rounding of a positive ratio; result must fit in a Long. */
private fun divideRounded(numerator: BigInteger, denominator: BigInteger): Long =
    numerator.toBigDecimal().divide(denominator.toBigDecimal(), 0, RoundingMode.HALF_UP).longValueExact()

sealed interface ReconcileIssue {
    /** Items (after their discounts) differ from the printed subtotal. */
    data class SubtotalMismatch(val difference: Money) : ReconcileIssue

    /** Items - discounts + tax differ from the total. */
    data class TotalMismatch(val difference: Money) : ReconcileIssue
    data object MissingTotal : ReconcileIssue
    data object NegativeAmount : ReconcileIssue
}

object ReceiptMath {

    /** Empty when the receipt adds up exactly. Differences are expected minus printed. */
    fun reconcile(review: ReceiptReview): List<ReconcileIssue> {
        val issues = mutableListOf<ReconcileIssue>()
        val total = review.total
        if (review.items.any { it.lineTotal.isNegative || it.discount.isNegative || it.net.isNegative } ||
            review.tax.isNegative || review.discount.isNegative || review.subtotal?.isNegative == true
        ) {
            issues += ReconcileIssue.NegativeAmount
        }
        val itemsNet = review.items.fold(Money.ZERO) { sum, item -> sum + item.net }
        // Receipts print the subtotal either before or after receipt-level coupons; both are accepted.
        review.subtotal?.let { subtotal ->
            if (subtotal != itemsNet && subtotal != itemsNet - review.discount) {
                issues += ReconcileIssue.SubtotalMismatch(itemsNet - subtotal)
            }
        }
        if (total == null || !total.isPositive) {
            issues += ReconcileIssue.MissingTotal
        } else {
            val expected = itemsNet - review.discount + review.tax
            if (expected != total) issues += ReconcileIssue.TotalMismatch(expected - total)
        }
        return issues
    }

    /**
     * Splits [total] across categories in proportion to each item's net amount, so tax
     * and receipt-level discounts are shared fairly. Uses whole cents with the
     * largest-remainder method: the result always sums exactly to [total].
     * [items] are (categoryId, net) with non-negative nets summing to more than zero.
     */
    fun allocate(total: Money, items: List<Pair<Long, Money>>): Map<Long, Money> {
        val parts = allocateItems(total, items.map { it.second })
        val result = linkedMapOf<Long, Money>()
        items.forEachIndexed { index, (categoryId, _) ->
            result[categoryId] = (result[categoryId] ?: Money.ZERO) + parts[index]
        }
        return result.filterValues { it.isPositive }
    }

    /**
     * Each item's share of [total], in proportion to its net amount (largest remainder,
     * ties to the earlier item). The shares sum exactly to [total]; this is what each
     * item actually cost once tax and receipt-level discounts are included.
     */
    fun allocateItems(total: Money, nets: List<Money>): List<Money> {
        require(total.isPositive)
        require(nets.isNotEmpty() && nets.all { !it.isNegative })
        val netSum = nets.fold(0L) { sum, net -> Math.addExact(sum, net.cents) }
        require(netSum > 0)
        val shares = nets.map { Math.multiplyExact(it.cents, total.cents) }
        val floors = shares.map { it / netSum }.toMutableList()
        var leftover = total.cents - floors.sum()
        shares.indices.sortedWith(compareByDescending<Int> { shares[it] % netSum }.thenBy { it })
            .forEach { index ->
                if (leftover > 0) {
                    floors[index]++
                    leftover--
                }
            }
        return floors.map(::Money)
    }
}

/**
 * Keys used to recognise stores, products and receipt descriptions. They ignore case,
 * accents, spacing and punctuation, but keep every word, number and size, so different
 * brands, variants and package sizes never share a key. Display names are untouched.
 */
object TextKeys {
    private val SPACES = Regex("\\s+")
    private val DECIMAL = Regex("""(?<=\d)[.,](?=\d)""")
    private val NUMBER_UNIT = Regex("""^(\d+(?:\.\d+)?)([A-Z]+)$""")

    /** Spellings of size units on receipts and labels, mapped to one form. */
    private val UNITS = mapOf(
        "L" to "L", "LT" to "L", "LTR" to "L", "LTRS" to "L", "LITRE" to "L", "LITER" to "L", "LITRES" to "L", "LITERS" to "L",
        "ML" to "ML", "MLS" to "ML",
        "G" to "G", "GR" to "G", "GRS" to "G", "GM" to "G", "GRAM" to "G", "GRAMS" to "G",
        "KG" to "KG", "KGS" to "KG", "KILO" to "KG", "KILOS" to "KG",
        "LB" to "LB", "LBS" to "LB", "OZ" to "OZ",
    )

    /** Retailer-format and legal-form words that do not change which business it is. */
    private val STORE_SUFFIXES = setOf(
        "SUPERCENTER", "SUPERCENTRE", "SUPERSTORE", "STORE", "STORES",
        "INC", "LLC", "LTD", "CO", "CORP", "CORPORATION",
    )

    /**
     * Upper-case letters, digits and single spaces. Accents are removed, "&" reads as
     * AND, apostrophes join ("Joe's" -> JOES), other punctuation separates words, and
     * decimal points inside numbers are kept ("1.5").
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val marked = decomposed.uppercase()
            .replace("&", " AND ")
            .replace(Regex("['’`´]"), "")
            .replace(DECIMAL, "\u0001")
        val cleaned = buildString {
            marked.forEach { ch ->
                append(
                    when {
                        ch == '\u0001' -> '.'
                        ch in 'A'..'Z' || ch in '0'..'9' -> ch
                        else -> ' '
                    },
                )
            }
        }
        return cleaned.replace(SPACES, " ").trim()
    }

    /**
     * Product and receipt-line key: [normalize], without long digit runs (item codes,
     * UPCs), with sizes written one way ("1 LTR", "1L", "1.0 l" -> "1L"). Sizes stay in the
     * key, so 1L and 2L milk remain different products.
     */
    fun descriptionKey(text: String): String {
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() && !(it.length >= 5 && it.all(Char::isDigit)) }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val attached = NUMBER_UNIT.matchEntire(token)
            when {
                attached != null && UNITS[attached.groupValues[2]] != null ->
                    out += number(attached.groupValues[1]) + UNITS.getValue(attached.groupValues[2])
                isNumber(token) && i + 1 < tokens.size && UNITS[tokens[i + 1]] != null -> {
                    out += number(token) + UNITS.getValue(tokens[i + 1])
                    i++
                }
                isNumber(token) -> out += number(token)
                else -> out += token
            }
            i++
        }
        return out.joinToString(" ")
    }

    /**
     * Store (retailer) key: [normalize] without a leading THE, store numbers, and
     * retailer-format or legal-form suffixes, with spaces removed ("Wal-Mart",
     * "WALMART SUPERCENTER #1234" -> WALMART). Different words always stay different.
     */
    fun storeKey(name: String): String {
        val tokens = normalize(name).split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size > 1 && tokens.first() == "THE") tokens.removeAt(0)
        // Store numbers: "#1234", "STORE 1234", "NO 12" after the name.
        val kept = tokens.filterIndexed { index, token ->
            index == 0 || !(token.all(Char::isDigit) || (token in setOf("NO", "NUM", "NUMBER") && tokens.getOrNull(index + 1)?.all(Char::isDigit) == true))
        }.toMutableList()
        while (kept.size > 1 && kept.last() in STORE_SUFFIXES) kept.removeAt(kept.lastIndex)
        return kept.joinToString("")
    }

    private fun isNumber(token: String) = token.isNotEmpty() && token.all { it.isDigit() || it == '.' } && token.first().isDigit()

    /** "1.0" -> "1", "1.50" -> "1.5". */
    private fun number(token: String): String =
        if ('.' in token) token.trimEnd('0').trimEnd('.') else token.trimStart('0').ifEmpty { "0" }
}
object ProductMatcher {

    data class Candidate(val productId: Long, val name: String)

    /**
     * Best product for [description] when no store alias exists: an exact key match, or
     * the most similar name if at least [threshold] percent of words are shared (Jaccard).
     * Returns the product and whether the match was exact.
     */
    fun match(description: String, products: List<Candidate>, threshold: Int = 60): Pair<Candidate, Boolean>? {
        val key = TextKeys.descriptionKey(description)
        if (key.isEmpty()) return null
        products.firstOrNull { TextKeys.descriptionKey(it.name) == key }?.let { return it to true }
        val words = key.split(' ').toSet()
        val numbers = words.filter { word -> word.any(Char::isDigit) }.toSet()
        return products
            .map { it to TextKeys.descriptionKey(it.name).split(' ').toSet() }
            // Sizes, percentages and other numbers must agree: 1L and 2L milk are different products.
            .filter { (_, other) -> other.filter { word -> word.any(Char::isDigit) }.toSet() == numbers }
            .map { (candidate, other) -> candidate to similarity(words, other) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.let { it.first to false }
    }

    private fun similarity(a: Set<String>, b: Set<String>): Int {
        val union = (a + b).size
        return if (union == 0) 0 else a.intersect(b).size * 100 / union
    }
}

object PackSizes {
    private val SIZE = Regex("""(?<![\d.,])(\d{1,4}(?:[.,]\d{1,3})?)\s?(kg|g|lbs?|ml|l|ltr)\b""", RegexOption.IGNORE_CASE)

    /** Pack size printed in a product name, e.g. "Milk 2% 1L" -> (1000, L); thousandths of the unit. */
    fun parse(text: String): Pair<Long, MeasureUnit>? {
        val match = SIZE.findAll(text).lastOrNull() ?: return null
        val unit = MeasureUnit.fromSymbol(match.groupValues[2]) ?: return null
        val parts = match.groupValues[1].replace(',', '.').split('.')
        val milli = parts[0].toLong() * 1000 + (parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0L)
        return if (milli > 0) milli to unit else null
    }
}

object ImageHashes {
    /** Bits that differ between two 64-bit difference hashes. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Photos this close are treated as the same receipt photographed again. */
    const val SIMILAR_DISTANCE = 6
}
