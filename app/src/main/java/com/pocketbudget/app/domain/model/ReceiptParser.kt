package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/** A word recognised by OCR with its position on the page. */
data class OcrWord(val text: String, val confidence: Int, val box: OcrBox)

object OcrLayout {
    /**
     * Rebuilds printed rows from words. OCR engines often read a receipt's names and
     * prices as separate columns; words whose vertical centres fall within each other's
     * height belong to the same row, read left to right. Row confidence is the average
     * of its words, weighted by length.
     */
    fun rows(page: Int, words: List<OcrWord>): List<OcrLine> {
        val rows = mutableListOf<MutableList<OcrWord>>()
        for (word in words.filter { it.text.isNotBlank() }.sortedBy { it.box.top + it.box.bottom }) {
            val centre = (word.box.top + word.box.bottom) / 2
            val row = rows.lastOrNull()?.takeIf { row ->
                val top = row.minOf { it.box.top }
                val bottom = row.maxOf { it.box.bottom }
                val rowCentre = (top + bottom) / 2
                centre in top..bottom && rowCentre in word.box.top..word.box.bottom
            }
            if (row != null) row += word else rows += mutableListOf(word)
        }
        return rows.mapIndexed { index, row ->
            val ordered = row.sortedBy { it.box.left }
            val weight = ordered.sumOf { it.text.length }
            OcrLine(
                page = page,
                index = index,
                text = ordered.joinToString(" ") { it.text },
                confidence = if (weight == 0) 0 else ordered.sumOf { it.confidence * it.text.length } / weight,
                box = OcrBox(
                    ordered.minOf { it.box.left },
                    ordered.minOf { it.box.top },
                    ordered.maxOf { it.box.right },
                    ordered.maxOf { it.box.bottom },
                ),
            )
        }
    }
}

/** What the parser could read from OCR lines. Every value is a suggestion for the user to verify. */
data class ParsedReceipt(
    val review: ReceiptReview,
    val uncertain: Set<ReceiptField>,
    val sources: Map<ReceiptField, OcrSource>,
)

/**
 * Turns OCR lines into store, date, totals and items. Heuristic by nature: anything
 * read from a low-confidence line, ambiguous, or not adding up is marked uncertain.
 * Amounts are parsed from digits into whole cents; floating point is never used.
 */
object ReceiptParser {

    /** Lines below this Tesseract confidence are highlighted for review. */
    const val CONFIDENT = 75

    private const val AMOUNT = """(-)?\s?\$?\s?(\d{1,3}(?:,\d{3})+|\d+)\s?[.,]\s?(\d{2})\s?(-)?"""
    private val TRAILING_AMOUNT = Regex("""$AMOUNT(?:\s+[A-Z*]{1,2})?$""", RegexOption.IGNORE_CASE)
    private val ANY_AMOUNT = Regex(AMOUNT)
    private val COUNT_AT_PRICE = Regex("""(\d{1,3})\s*[@xX]\s*\$?\s?(\d+)[.,](\d{2})""")
    private val WEIGHT_AT_PRICE = Regex(
        """(\d+(?:[.,]\d{1,3})?)\s*(kg|g|lbs?|ml|l)\b\s*@\s*\$?\s?(\d+)[.,](\d{2})\s*/\s*(kg|g|lbs?|ml|l)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ITEM_CODE = Regex("""^\d{5,}\s+""")
    private val ISO_DATE = Regex("""\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
    private val NUMERIC_DATE = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})\b""")
    private val NAMED_DATE = Regex(
        """\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DAY_NAMED_DATE = Regex(
        """\b(\d{1,2})[\s-](JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?[\s-](\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TIME = Regex("""\b(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp]\.?[Mm]\.?)?""")
    private val RECEIPT_NUMBER = Regex(
        """\b(?:RECEIPT|TRANS(?:ACTION)?|INVOICE|TRX|REF|ORDER)\s*(?:NO\.?|NUM(?:BER)?|#)?\s*[:#]?\s*([A-Z0-9-]*\d[A-Z0-9-]{2,})""",
        RegexOption.IGNORE_CASE,
    )
    private val STREET = Regex(
        """^\d+[A-Z]?\s+.*\b(ST|STREET|AVE|AVENUE|RD|ROAD|BLVD|BOULEVARD|DR|DRIVE|LN|LANE|WAY|HWY|HIGHWAY|PKWY|CT|COURT|PL|PLACE|CRES|SQ|TER|CIR)\b\.?""",
        RegexOption.IGNORE_CASE,
    )
    private val CITY_LINE = Regex(
        """^[A-Za-z .'-]+,?\s+[A-Z]{2}\s+(\d{5}(-\d{4})?|[A-Z]\d[A-Z]\s?\d[A-Z]\d)\b""",
    )
    // Whole words, so "CASHEWS" stays an item.
    private val PAYMENT = Regex("""\b(CHANGE|CASH|VISA|MASTERCARD|MC|AMEX|DEBIT|CREDIT|TEND(ER|ERED)?|PAYMENT|APPROVED|AUTH)\b""")
    private val PHONE =Regex("""\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4}""")

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    private enum class Kind { SUBTOTAL, TAX, TOTAL, DISCOUNT, SUMMARY, PAYMENT, OTHER }

    private data class Amount(val money: Money, val negative: Boolean)

    /**
     * [dayFirst] resolves dates like 03/04/2026 when both parts could be the month;
     * pass true outside the US. Such dates are marked uncertain either way.
     */
    fun parse(lines: List<OcrLine>, dayFirst: Boolean = false): ParsedReceipt {
        val ordered = lines.sortedWith(compareBy({ it.page }, { it.index })).filter { it.text.isNotBlank() }
        val uncertain = mutableSetOf<ReceiptField>()
        val sources = mutableMapOf<ReceiptField, OcrSource>()
        fun note(field: ReceiptField, line: OcrLine) {
            sources.putIfAbsent(field, OcrSource(line.page, line.index))
            if (line.confidence < CONFIDENT) uncertain += field
        }

        var subtotal: Money? = null
        var tax = Money.ZERO
        var receiptDiscount = Money.ZERO
        var total: Money? = null
        val items = mutableListOf<ReceiptItemDraft>()
        var firstItemLine = Int.MAX_VALUE
        var pendingDescription: Pair<Int, OcrLine>? = null
        var pendingQuantity: Pair<Int, Triple<Long, MeasureUnit, Money>>? = null
        var reachedTotals = false

        ordered.forEachIndexed { position, line ->
            if (total != null) return@forEachIndexed // payment and change lines follow the total
            val text = clean(line.text)
            val lineQuantity = quantityOf(text)
            // "2 @ 1.49" alone is a quantity, not a price: amounts are read from what remains.
            val rest = withoutQuantity(text)
            val amount = trailingAmount(rest)
            val kind = classify(text)
            if (amount == null) {
                if (lineQuantity != null && letters(rest) < 3) {
                    pendingQuantity = position to lineQuantity
                } else if (!reachedTotals && kind == Kind.OTHER && letters(text) >= 3) {
                    pendingDescription = position to line
                }
                return@forEachIndexed
            }
            when (kind) {
                Kind.SUBTOTAL -> {
                    reachedTotals = true
                    subtotal = amount.money
                    note(ReceiptField.SUBTOTAL, line)
                }
                Kind.TAX -> {
                    reachedTotals = true
                    tax += amount.money
                    note(ReceiptField.TAX, line)
                }
                Kind.TOTAL -> {
                    total = amount.money
                    note(ReceiptField.TOTAL, line)
                }
                Kind.SUMMARY, Kind.PAYMENT -> Unit
                Kind.DISCOUNT, Kind.OTHER -> {
                    val isDiscount = kind == Kind.DISCOUNT || amount.negative
                    val last = items.lastOrNull()
                    if (isDiscount) {
                        if (!reachedTotals && last != null && last.discount.isZero && amount.money <= last.lineTotal) {
                            items[items.lastIndex] = last.copy(discount = amount.money)
                        } else {
                            receiptDiscount += amount.money
                            note(ReceiptField.DISCOUNT, line)
                        }
                        return@forEachIndexed
                    }
                    if (reachedTotals) return@forEachIndexed
                    var descriptionLine = line
                    var description = descriptionOf(rest)
                    var quantity = lineQuantity
                    // "BANANAS" then "1.25 kg @ 1.74/kg   2.18": the amount line carries no name.
                    val previous = pendingDescription
                    if (letters(description) < 3 && previous != null && previous.first == position - 1) {
                        descriptionLine = previous.second
                        description = descriptionOf(withoutQuantity(clean(previous.second.text)))
                        if (quantity == null) quantity = quantityOf(clean(previous.second.text))
                    }
                    if (quantity == null) {
                        pendingQuantity?.takeIf { it.first == position - 1 }?.let { quantity = it.second }
                    }
                    if (letters(description) < 2) return@forEachIndexed
                    firstItemLine = minOf(firstItemLine, position)
                    val confidence = minOf(line.confidence, descriptionLine.confidence)
                    items += ReceiptItemDraft(
                        rawText = description,
                        description = description,
                        quantityMilli = quantity?.first ?: 1000,
                        unit = quantity?.second ?: MeasureUnit.EACH,
                        unitPrice = quantity?.third,
                        lineTotal = amount.money,
                        confidence = confidence,
                        uncertain = confidence < CONFIDENT || !quantityConsistent(quantity, amount.money),
                        source = OcrSource(descriptionLine.page, descriptionLine.index),
                    )
                    pendingDescription = null
                    pendingQuantity = null
                }
            }
        }

        val header = ordered.take(minOf(firstItemLine, ordered.size).coerceAtMost(8))
        val store = header.take(4).firstOrNull { isStoreName(clean(it.text)) }
        store?.let { note(ReceiptField.STORE, it) }
        val addressLines = header.filter { line ->
            val text = clean(line.text)
            STREET.containsMatchIn(text) || CITY_LINE.containsMatchIn(text)
        }.take(2)
        addressLines.firstOrNull()?.let { note(ReceiptField.ADDRESS, it) }

        var date: LocalDate? = null
        for (line in ordered) {
            val found = dateOf(clean(line.text), dayFirst) ?: continue
            date = found.first
            note(ReceiptField.DATE, line)
            if (found.second) uncertain += ReceiptField.DATE
            break
        }
        var time: LocalTime? = null
        for (line in ordered) {
            val found = timeOf(clean(line.text)) ?: continue
            time = found
            note(ReceiptField.TIME, line)
            break
        }
        var receiptNumber = ""
        for (line in ordered) {
            val match = RECEIPT_NUMBER.find(clean(line.text)) ?: continue
            receiptNumber = match.groupValues[1]
            note(ReceiptField.RECEIPT_NUMBER, line)
            break
        }

        val review = ReceiptReview(
            storeName = store?.let { titleCase(clean(it.text)) }.orEmpty(),
            storeAddress = addressLines.joinToString(", ") { clean(it.text) },
            purchasedOn = date,
            purchasedTime = time,
            subtotal = subtotal,
            tax = tax,
            discount = receiptDiscount,
            total = total,
            receiptNumber = receiptNumber,
            items = items,
        )
        if (store == null) uncertain += ReceiptField.STORE
        if (date == null) uncertain += ReceiptField.DATE
        ReceiptMath.reconcile(review).forEach { issue ->
            when (issue) {
                is ReconcileIssue.SubtotalMismatch -> uncertain += ReceiptField.SUBTOTAL
                else -> uncertain += ReceiptField.TOTAL
            }
        }
        return ParsedReceipt(review, uncertain, sources)
    }

    /** Whole cents from text like "$1,234.56", "12,34" or "3.00-". Null if there is no amount. */
    fun parseAmount(text: String): Money? = trailingAmount(clean(text))?.money

    private fun clean(text: String): String {
        // Fix common OCR confusions inside the last token only, where an amount usually sits.
        val trimmed = text.trim().replace(Regex("\\s+"), " ")
        val lastSpace = trimmed.lastIndexOf(' ')
        val head = if (lastSpace >= 0) trimmed.substring(0, lastSpace + 1) else ""
        val tail = trimmed.substring(lastSpace + 1)
        val fixedTail = if (Regex("""^-?\$?[\dOoIlS]+[.,][\dOoIlS]{2}-?$""").matches(tail) && tail.any(Char::isDigit)) {
            tail.map { c ->
                when (c) {
                    'O', 'o' -> '0'
                    'I', 'l' -> '1'
                    'S' -> '5'
                    else -> c
                }
            }.joinToString("")
        } else {
            tail
        }
        return head + fixedTail
    }

    private fun trailingAmount(text: String): Amount? {
        val match = TRAILING_AMOUNT.find(text) ?: return null
        val (leadingMinus, whole, fraction, trailingMinus) = match.destructured
        val cents = Math.addExact(Math.multiplyExact(whole.replace(",", "").toLong(), 100L), fraction.toLong())
        val negative = leadingMinus.isNotEmpty() || trailingMinus.isNotEmpty()
        return Amount(Money(cents), negative)
    }

    private fun classify(text: String): Kind {
        val upper = text.uppercase()
        val compact = upper.replace(Regex("[^A-Z]"), "")
        return when {
            compact.contains("SUBTOTAL") -> Kind.SUBTOTAL
            listOf("YOUSAVED", "TOTALSAVINGS", "YOURSAVINGS", "TOTALDISCOUNT", "ITEMSSOLD", "NUMBEROFITEMS")
                .any { compact.contains(it) } -> Kind.SUMMARY
            PAYMENT.containsMatchIn(upper) -> Kind.PAYMENT
            Regex("""\b(TAX|HST|GST|PST|QST|VAT)\b""").containsMatchIn(upper) &&
                !compact.contains("TAXABLE") && !compact.contains("EXEMPT") && !compact.contains("INCL") -> Kind.TAX
            compact.contains("TOTAL") || compact.contains("AMOUNTDUE") || compact.contains("BALANCEDUE") -> Kind.TOTAL
            listOf("DISCOUNT", "SAVINGS", "COUPON", "PROMO", "REBATE", "MARKDOWN")
                .any { compact.contains(it) } -> Kind.DISCOUNT
            else -> Kind.OTHER
        }
    }

    private fun withoutQuantity(text: String): String =
        text.replace(WEIGHT_AT_PRICE, " ").replace(COUNT_AT_PRICE, " ").replace(Regex("\\s+"), " ").trim()

    private fun descriptionOf(text: String): String {
        val withoutAmount = ANY_AMOUNT.findAll(text).lastOrNull()
            ?.let { text.replaceRange(it.range, " ") }
            ?: text
        return withoutAmount.replace(Regex("""\s+[A-Z*]{1,2}$"""), "")
            .replace(ITEM_CODE, "")
            .trim()
            .trimEnd('$', '-', ' ')
    }

    /** (quantity in thousandths, unit, unit price) from "2 @ 1.49" or "1.25 kg @ 1.74/kg". */
    private fun quantityOf(text: String): Triple<Long, MeasureUnit, Money>? {
        WEIGHT_AT_PRICE.find(text)?.let { match ->
            val (amount, unitText, whole, fraction, perUnitText) = match.destructured
            val unit = MeasureUnit.fromSymbol(unitText) ?: return null
            val perUnit = MeasureUnit.fromSymbol(perUnitText) ?: return null
            if (unit != perUnit) return null
            val milli = thousandths(amount) ?: return null
            return Triple(milli, unit, Money(whole.toLong() * 100 + fraction.toLong()))
        }
        COUNT_AT_PRICE.find(text)?.let { match ->
            val (count, whole, fraction) = match.destructured
            val n = count.toLong()
            if (n <= 0) return null
            return Triple(n * 1000, MeasureUnit.EACH, Money(whole.toLong() * 100 + fraction.toLong()))
        }
        return null
    }

    /** "1.25" -> 1250, "0,455" -> 455, "2" -> 2000. */
    private fun thousandths(text: String): Long? {
        val parts = text.replace(',', '.').split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        val fraction = parts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return whole * 1000 + fraction
    }

    /** True when quantity x unit price is within one cent of the line total (weights are rounded). */
    private fun quantityConsistent(quantity: Triple<Long, MeasureUnit, Money>?, lineTotal: Money): Boolean {
        quantity ?: return true
        val expected = (quantity.first * quantity.third.cents + 500) / 1000
        return Math.abs(expected - lineTotal.cents) <= 1
    }

    private fun dateOf(text: String, dayFirst: Boolean): Pair<LocalDate, Boolean>? {
        ISO_DATE.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return safeDate(y.toInt(), mo.toInt(), d.toInt())?.let { it to false }
        }
        NAMED_DATE.find(text)?.let { m ->
            val (mon, d, y) = m.destructured
            return safeDate(y.toInt(), MONTHS.indexOf(mon.uppercase().take(3)) + 1, d.toInt())?.let { it to false }
        }
        DAY_NAMED_DATE.find(text)?.let { m ->
            val (d, mon, y) = m.destructured
            return safeDate(y.toInt(), MONTHS.indexOf(mon.uppercase().take(3)) + 1, d.toInt())?.let { it to false }
        }
        NUMERIC_DATE.find(text)?.let { m ->
            val (a, b, yText) = m.destructured
            val year = yText.toInt().let { if (yText.length == 2) 2000 + it else it }
            val first = a.toInt()
            val second = b.toInt()
            val ambiguous = first <= 12 && second <= 12 && first != second
            val (month, day) = when {
                first > 12 -> second to first
                second > 12 -> first to second
                dayFirst -> second to first
                else -> first to second
            }
            return safeDate(year, month, day)?.let { it to ambiguous }
        }
        return null
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day).takeIf { year in 2000..2099 }
        } catch (_: DateTimeException) {
            null
        }

    private fun timeOf(text: String): LocalTime? {
        val m = TIME.find(text) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val meridiem = m.groupValues[3].uppercase().replace(".", "")
        if (meridiem == "PM" && hour in 1..11) hour += 12
        if (meridiem == "AM" && hour == 12) hour = 0
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    private fun isStoreName(text: String): Boolean {
        if (letters(text) < 3) return false
        if (PHONE.containsMatchIn(text) || STREET.containsMatchIn(text) || CITY_LINE.containsMatchIn(text)) return false
        if (trailingAmount(text) != null || dateOf(text, false) != null) return false
        val compact = text.uppercase().replace(Regex("[^A-Z]"), "")
        return listOf("WELCOME", "RECEIPT", "THANKYOU", "CUSTOMERCOPY").none { compact.startsWith(it) }
    }

    private fun letters(text: String) = text.count(Char::isLetter)

    private fun titleCase(text: String): String =
        if (text.any(Char::isLowerCase)) {
            text
        } else {
            text.lowercase().split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
        }
}
