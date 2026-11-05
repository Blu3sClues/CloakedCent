package com.cloakedcent.app.domain.model

import com.cloakedcent.app.core.money.Money
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Spending analysis. "Spending" is always completed, live, non-adjustment expenses:
// scheduled, deleted, income, transfer and balance-adjustment entries never count.
// All money arithmetic is in whole cents; averages and percentages round half up.

enum class DatePreset {
    LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS,
    THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR,
    ALL_TIME, CUSTOM,
}

/** The analysed range and, when meaningful, the equally long period just before it. */
data class AnalysisPeriod(val preset: DatePreset, val range: DateRange, val previous: DateRange?) {
    /** Compact form for navigation arguments: "LAST_30_DAYS" or "CUSTOM:<startEpochDay>:<endEpochDay>". */
    fun encode(): String =
        if (preset == DatePreset.CUSTOM) "CUSTOM:${range.start.toEpochDay()}:${range.endInclusive.toEpochDay()}" else preset.name
}

object AnalysisPeriods {
    /** Ledger dates before this are not expected; "All time" starts here. */
    val EARLIEST: LocalDate = LocalDate.of(1970, 1, 1)

    fun resolve(preset: DatePreset, today: LocalDate, custom: DateRange? = null): AnalysisPeriod {
        fun rolling(days: Long): AnalysisPeriod {
            val range = DateRange(today.minusDays(days - 1), today)
            return AnalysisPeriod(preset, range, DateRange(range.start.minusDays(days), range.start.minusDays(1)))
        }
        fun month(first: LocalDate): AnalysisPeriod {
            val previous = first.minusMonths(1)
            return AnalysisPeriod(
                preset,
                DateRange(first, first.with(TemporalAdjusters.lastDayOfMonth())),
                DateRange(previous, previous.with(TemporalAdjusters.lastDayOfMonth())),
            )
        }
        fun year(first: LocalDate) = AnalysisPeriod(
            preset,
            DateRange(first, first.plusYears(1).minusDays(1)),
            DateRange(first.minusYears(1), first.minusDays(1)),
        )
        return when (preset) {
            DatePreset.LAST_7_DAYS -> rolling(7)
            DatePreset.LAST_30_DAYS -> rolling(30)
            DatePreset.LAST_90_DAYS -> rolling(90)
            DatePreset.THIS_MONTH -> month(today.withDayOfMonth(1))
            DatePreset.LAST_MONTH -> month(today.withDayOfMonth(1).minusMonths(1))
            DatePreset.THIS_YEAR -> year(today.withDayOfYear(1))
            DatePreset.LAST_YEAR -> year(today.withDayOfYear(1).minusYears(1))
            DatePreset.ALL_TIME -> AnalysisPeriod(preset, DateRange(EARLIEST, today), null)
            DatePreset.CUSTOM -> {
                val range = requireNotNull(custom) { "Custom periods need a range" }
                require(!range.endInclusive.isBefore(range.start))
                val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
                AnalysisPeriod(preset, range, DateRange(range.start.minusDays(days), range.start.minusDays(1)))
            }
        }
    }

    fun decode(text: String?, today: LocalDate): AnalysisPeriod {
        val parts = text.orEmpty().split(':')
        if (parts.size == 3 && parts[0] == DatePreset.CUSTOM.name) {
            val start = parts[1].toLongOrNull()
            val end = parts[2].toLongOrNull()
            if (start != null && end != null && end >= start) {
                return resolve(DatePreset.CUSTOM, today, DateRange(LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end)))
            }
        }
        val preset = DatePreset.entries.firstOrNull { it.name == parts[0] && it != DatePreset.CUSTOM } ?: DatePreset.LAST_30_DAYS
        return resolve(preset, today)
    }
}

object SpendMath {
    /** [total] / [count], rounded half up; zero when there is nothing to average. */
    fun average(total: Money, count: Long): Money {
        if (count <= 0) return Money.ZERO
        val half = count / 2
        val cents = if (total.cents >= 0) Math.addExact(total.cents, half) / count else -((-total.cents + half) / count)
        return Money(cents)
    }

    /** Change in hundredths of a percent (1250 = +12.50%); null when there is no basis to compare. */
    fun changeBasisPoints(current: Money, previous: Money?): Long? {
        if (previous == null || previous.cents <= 0) return null
        val numerator = Math.multiplyExact(current.cents - previous.cents, 10_000L)
        val half = previous.cents / 2
        return if (numerator >= 0) (numerator + half) / previous.cents else -((-numerator + half) / previous.cents)
    }

    /** Share of [part] in [whole] in tenths of a percent (0..1000), rounded half up. */
    fun sharePerMille(part: Money, whole: Money): Int {
        if (whole.cents <= 0 || part.cents <= 0) return 0
        return ((Math.multiplyExact(part.cents, 1000L) + whole.cents / 2) / whole.cents).toInt()
    }
}

data class Comparison(val current: Money, val previous: Money?) {
    val changeBasisPoints: Long? get() = SpendMath.changeBasisPoints(current, previous)
}

enum class Granularity { DAY, WEEK, MONTH }

data class TrendPoint(val start: LocalDate, val amount: Money)

object Trends {
    fun granularity(range: DateRange): Granularity {
        val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
        return when {
            days <= 31 -> Granularity.DAY
            days <= 183 -> Granularity.WEEK
            else -> Granularity.MONTH
        }
    }

    /**
     * The days a bar starting on [start] covers, cut to [range]: a week or month at either
     * end of the range only counts the days inside it.
     */
    fun bucketRange(start: LocalDate, granularity: Granularity, range: DateRange): DateRange {
        val end = when (granularity) {
            Granularity.DAY -> start
            Granularity.WEEK -> start.plusDays(6)
            Granularity.MONTH -> start.plusMonths(1).minusDays(1)
        }
        return DateRange(maxOf(start, range.start), minOf(end, range.endInclusive))
    }

    /**
     * Buckets daily totals over [range], including empty buckets so gaps show. Ranges
     * that start before any data (All time) begin at the first day with spending.
     */
    fun build(range: DateRange, daily: Map<LocalDate, Money>): List<TrendPoint> {
        val first = daily.keys.filter { it in range }.minOrNull()
        val start = if (range.start == AnalysisPeriods.EARLIEST) first ?: range.endInclusive else range.start
        val effective = DateRange(start, range.endInclusive)
        val granularity = granularity(effective)
        fun bucket(date: LocalDate): LocalDate = when (granularity) {
            Granularity.DAY -> date
            Granularity.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Granularity.MONTH -> date.withDayOfMonth(1)
        }
        val sums = linkedMapOf<LocalDate, Money>()
        var cursor = bucket(effective.start)
        while (!cursor.isAfter(effective.endInclusive)) {
            sums[cursor] = Money.ZERO
            cursor = when (granularity) {
                Granularity.DAY -> cursor.plusDays(1)
                Granularity.WEEK -> cursor.plusWeeks(1)
                Granularity.MONTH -> cursor.plusMonths(1)
            }
        }
        daily.forEach { (day, amount) ->
            if (day in effective) {
                val key = bucket(day)
                sums[key] = (sums[key] ?: Money.ZERO) + amount
            }
        }
        return sums.map { (start, amount) -> TrendPoint(start, amount) }
    }
}

/** Spending in one category: [own] is booked directly to it, [total] includes every descendant once. */
data class CategorySpend(
    val categoryId: Long?,
    val name: String,
    val parentId: Long?,
    val depth: Int,
    val own: Money,
    val total: Money,
    val previousTotal: Money?,
) {
    val comparison: Comparison get() = Comparison(total, previousTotal)
}

object CategoryRollup {
    /**
     * Rolls per-category amounts up the hierarchy. Each amount is booked to exactly one
     * category, so adding a category's own amount to each of its ancestors counts it
     * once at every level and never twice within a level. The null key is uncategorised
     * spending. Result is in tree order (parents before children), zero rows omitted.
     */
    fun rollup(
        categories: List<Category>,
        current: Map<Long?, Money>,
        previous: Map<Long?, Money>?,
        uncategorisedLabel: String = "",
    ): List<CategorySpend> {
        val byId = categories.associateBy { it.id }
        fun totals(amounts: Map<Long?, Money>): Map<Long, Money> {
            val result = mutableMapOf<Long, Money>()
            amounts.forEach { (id, amount) ->
                var node = id?.let(byId::get)
                // Guard against cycles: a category can appear at most once in its own chain.
                val seen = mutableSetOf<Long>()
                while (node != null && seen.add(node.id)) {
                    result[node.id] = (result[node.id] ?: Money.ZERO) + amount
                    node = node.parentId?.let(byId::get)
                }
            }
            return result
        }
        val currentTotals = totals(current)
        val previousTotals = previous?.let(::totals)
        val children = categories.groupBy { it.parentId }
        val rows = mutableListOf<CategorySpend>()
        fun visit(category: Category, depth: Int) {
            val total = currentTotals[category.id] ?: Money.ZERO
            val before = previousTotals?.get(category.id) ?: if (previousTotals != null) Money.ZERO else null
            if (total.isZero && (before == null || before.isZero)) return
            rows += CategorySpend(category.id, category.name, category.parentId, depth, current[category.id] ?: Money.ZERO, total, before)
            children[category.id].orEmpty().sortedByDescending { currentTotals[it.id]?.cents ?: 0 }.forEach { visit(it, depth + 1) }
        }
        categories.filter { it.parentId == null || it.parentId !in byId }
            .sortedByDescending { currentTotals[it.id]?.cents ?: 0 }
            .forEach { visit(it, 0) }
        val unassigned = current[null] ?: Money.ZERO
        val unassignedBefore = previous?.let { it[null] ?: Money.ZERO }
        if (unassigned.isPositive || unassignedBefore?.isPositive == true) {
            rows += CategorySpend(null, uncategorisedLabel, null, 0, unassigned, unassigned, unassignedBefore)
        }
        return rows
    }
}

/** One physical store (a row in `stores`); [brandKey] groups locations of the same retailer. */
data class LocationSpend(
    val storeId: Long,
    val name: String,
    val brandKey: String,
    val address: String?,
    val visits: Long,
    val total: Money,
    val firstVisit: LocalDate?,
    val lastVisit: LocalDate?,
) {
    val averageReceipt: Money get() = SpendMath.average(total, visits)
}

data class BrandSpend(val brandKey: String, val name: String, val locations: List<LocationSpend>) {
    val visits: Long get() = locations.sumOf { it.visits }
    val total: Money get() = locations.fold(Money.ZERO) { sum, it -> sum + it.total }
    val averageReceipt: Money get() = SpendMath.average(total, visits)

    companion object {
        /** Groups locations by retailer; the most visited location's spelling names the brand. */
        fun group(locations: List<LocationSpend>): List<BrandSpend> =
            locations.groupBy { it.brandKey }
                .map { (key, list) -> BrandSpend(key, list.maxBy { it.visits }.name, list.sortedByDescending { it.total.cents }) }
                .sortedByDescending { it.total.cents }
    }
}

data class ProductSpend(val productId: Long, val name: String, val purchases: Long, val total: Money) {
    val averagePrice: Money get() = SpendMath.average(total, purchases)
}

/** One confirmed purchase of a product. */
data class PricePoint(
    val date: LocalDate,
    val storeId: Long,
    val storeName: String,
    val brandKey: String,
    val address: String?,
    val quantityMilli: Long,
    val unit: MeasureUnit,
    val paid: Money,
    val normalized: NormalizedPrice?,
)

/** Unit-price statistics for a group of purchases, all in one dimension. */
data class PriceStats(
    val label: String,
    val key: String,
    val purchases: Int,
    val lowest: NormalizedPrice,
    val highest: NormalizedPrice,
    val average: NormalizedPrice,
    val latest: NormalizedPrice,
)

object PriceAnalysis {
    /** Statistics over [points] that have a unit price in the most common dimension; null if none. */
    fun stats(label: String, key: String, points: List<PricePoint>): PriceStats? {
        val priced = points.mapNotNull { p -> p.normalized?.let { p to it } }
        if (priced.isEmpty()) return null
        val dimension = priced.groupingBy { it.second.dimension }.eachCount().maxBy { it.value }.key
        val same = priced.filter { it.second.dimension == dimension }
        val values = same.map { it.second.milliCentsPerUnit }
        val half = values.size / 2L
        val average = (values.fold(0L) { sum, v -> Math.addExact(sum, v) } + half) / values.size
        return PriceStats(
            label = label,
            key = key,
            purchases = same.size,
            lowest = same.minBy { it.second.milliCentsPerUnit }.second,
            highest = same.maxBy { it.second.milliCentsPerUnit }.second,
            average = NormalizedPrice(dimension, average),
            latest = same.maxBy { it.first.date }.second,
        )
    }

    fun byLocation(points: List<PricePoint>): List<PriceStats> =
        points.groupBy { it.storeId }
            .mapNotNull { (id, list) ->
                val first = list.first()
                stats(listOfNotNull(first.storeName, first.address).joinToString(" · "), id.toString(), list)
            }
            .sortedBy { it.average.milliCentsPerUnit }

    fun byBrand(points: List<PricePoint>): List<PriceStats> =
        points.groupBy { it.brandKey }
            .mapNotNull { (key, list) -> stats(list.groupingBy { it.storeName }.eachCount().maxBy { it.value }.key, key, list) }
            .sortedBy { it.average.milliCentsPerUnit }

    /** Average whole days between purchases on different days; null with fewer than two. */
    fun averageDaysBetween(points: List<PricePoint>): Long? {
        val days = points.map { it.date }.distinct().sorted()
        if (days.size < 2) return null
        val span = ChronoUnit.DAYS.between(days.first(), days.last())
        val gaps = days.size - 1L
        return (span + gaps / 2) / gaps
    }
}

data class SpendingOverview(
    val period: AnalysisPeriod,
    /** Net of refunds. */
    val total: Comparison,
    /** Refunds in the period (already subtracted from [total]). */
    val refunds: Money,
    val purchases: Long,
    val previousPurchases: Long?,
    val trend: List<TrendPoint>,
    val topCategories: List<CategorySpend>,
    val topBrands: List<BrandSpend>,
    val topProducts: List<ProductSpend>,
) {
    val averagePurchase: Money get() = SpendMath.average(total.current, purchases)
    val isEmpty: Boolean get() = purchases == 0L && refunds.isZero && (previousPurchases ?: 0L) == 0L
}

data class CategoryAnalysis(
    val category: CategorySpend,
    val path: String,
    val subcategories: List<CategorySpend>,
    val trend: List<TrendPoint>,
    val topProducts: List<ProductSpend>,
)

/** A product bought at several locations, with each location's latest unit price. */
data class LocationPriceComparison(val productId: Long, val productName: String, val prices: List<Pair<LocationSpend, NormalizedPrice>>)

data class BrandAnalysis(
    val brand: BrandSpend,
    val previousTotal: Money?,
    val trend: List<TrendPoint>,
    val topProducts: List<ProductSpend>,
    val priceComparisons: List<LocationPriceComparison>,
)

data class LocationAnalysis(
    val location: LocationSpend,
    val previousTotal: Money?,
    val trend: List<TrendPoint>,
    val topProducts: List<ProductSpend>,
    /** Latest unit price here for each product bought here. */
    val latestPrices: List<Pair<ProductSpend, PricePoint>>,
    /** The same retailer's other locations in the period. */
    val siblings: List<LocationSpend>,
    val priceComparisons: List<LocationPriceComparison>,
)

data class ProductAnalysis(
    val product: Product,
    val spend: ProductSpend,
    val history: List<PricePoint>,
    val averageDaysBetween: Long?,
    val overall: PriceStats?,
    val lowestPaid: PricePoint?,
    val highestPaid: PricePoint?,
    val byLocation: List<PriceStats>,
    val byBrand: List<PriceStats>,
)

object PriceComparisons {
    /** Products bought at two or more of [locations], each with its latest unit price per location. */
    fun across(locations: List<LocationSpend>, points: Map<Long, Pair<String, List<PricePoint>>>): List<LocationPriceComparison> {
        val byId = locations.associateBy { it.storeId }
        return points.mapNotNull { (productId, entry) ->
            val (name, list) = entry
            val latest = list.filter { it.normalized != null && it.storeId in byId }
                .groupBy { it.storeId }
                .map { (storeId, at) -> byId.getValue(storeId) to at.maxBy { it.date }.normalized!! }
            val dimension = latest.firstOrNull()?.second?.dimension
            val comparable = latest.filter { it.second.dimension == dimension }
            if (comparable.size < 2) null else LocationPriceComparison(productId, name, comparable.sortedBy { it.second.milliCentsPerUnit })
        }.sortedBy { it.productName.lowercase() }
    }
}
