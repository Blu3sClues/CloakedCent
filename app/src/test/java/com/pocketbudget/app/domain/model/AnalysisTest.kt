package com.pocketbudget.app.domain.model

import com.pocketbudget.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalysisTest {

    private val today = LocalDate.of(2026, 3, 15)
    private fun d(text: String) = LocalDate.parse(text)
    private fun range(a: String, b: String) = DateRange(d(a), d(b))

    @Test
    fun presetsResolveToRangesAndPreviousPeriods() {
        fun check(preset: DatePreset, current: DateRange, previous: DateRange?) {
            val p = AnalysisPeriods.resolve(preset, today)
            assertEquals(preset.name, current, p.range)
            assertEquals(preset.name, previous, p.previous)
        }
        check(DatePreset.LAST_7_DAYS, range("2026-03-09", "2026-03-15"), range("2026-03-02", "2026-03-08"))
        check(DatePreset.LAST_30_DAYS, range("2026-02-14", "2026-03-15"), range("2026-01-15", "2026-02-13"))
        check(DatePreset.LAST_90_DAYS, range("2025-12-16", "2026-03-15"), range("2025-09-17", "2025-12-15"))
        check(DatePreset.THIS_MONTH, range("2026-03-01", "2026-03-31"), range("2026-02-01", "2026-02-28"))
        check(DatePreset.LAST_MONTH, range("2026-02-01", "2026-02-28"), range("2026-01-01", "2026-01-31"))
        check(DatePreset.THIS_YEAR, range("2026-01-01", "2026-12-31"), range("2025-01-01", "2025-12-31"))
        check(DatePreset.LAST_YEAR, range("2025-01-01", "2025-12-31"), range("2024-01-01", "2024-12-31"))
        check(DatePreset.ALL_TIME, DateRange(AnalysisPeriods.EARLIEST, today), null)
        // Leap year: the previous month of March 2024 is 29 days.
        assertEquals(range("2024-02-01", "2024-02-29"), AnalysisPeriods.resolve(DatePreset.THIS_MONTH, d("2024-03-10")).previous)

        val custom = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, range("2026-01-10", "2026-01-19"))
        assertEquals(range("2025-12-31", "2026-01-09"), custom.previous)
    }

    @Test
    fun periodsSurviveNavigationEncoding() {
        val custom = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, range("2026-01-10", "2026-01-19"))
        assertEquals(custom, AnalysisPeriods.decode(custom.encode(), today))
        assertEquals(DatePreset.THIS_YEAR, AnalysisPeriods.decode("THIS_YEAR", today).preset)
        assertEquals(DatePreset.LAST_30_DAYS, AnalysisPeriods.decode(null, today).preset)
        assertEquals(DatePreset.LAST_30_DAYS, AnalysisPeriods.decode("CUSTOM:9:1", today).preset)
    }

    @Test
    fun averagesAndPercentagesUseExactRounding() {
        assertEquals(Money(334), SpendMath.average(Money(1001), 3))  // 333.67 -> 334
        assertEquals(Money(333), SpendMath.average(Money(1000), 3))  // 333.33 -> 333
        assertEquals(Money(2), SpendMath.average(Money(3), 2))       // 1.5 -> 2 (half up)
        assertEquals(Money.ZERO, SpendMath.average(Money(500), 0))
        assertEquals(Money(-2), SpendMath.average(Money(-3), 2))

        assertEquals(2500L, SpendMath.changeBasisPoints(Money(12_500), Money(10_000)))
        assertEquals(-3333L, SpendMath.changeBasisPoints(Money(200), Money(300)))
        assertEquals(0L, SpendMath.changeBasisPoints(Money(300), Money(300)))
        assertNull(SpendMath.changeBasisPoints(Money(300), Money.ZERO))
        assertNull(SpendMath.changeBasisPoints(Money(300), null))

        assertEquals(333, SpendMath.sharePerMille(Money(1), Money(3)))
        assertEquals(1000, SpendMath.sharePerMille(Money(5), Money(5)))
        assertEquals(0, SpendMath.sharePerMille(Money(5), Money.ZERO))
        // Large amounts stay exact (no overflow, no floating point).
        assertEquals(500, SpendMath.sharePerMille(Money(4_000_000_000_00), Money(8_000_000_000_00)))
    }

    @Test
    fun trendsBucketByDayWeekAndMonthIncludingGaps() {
        val daily = mapOf(d("2026-03-02") to Money(100), d("2026-03-04") to Money(250), d("2026-03-10") to Money(50))
        val days = Trends.build(range("2026-03-01", "2026-03-10"), daily)
        assertEquals(10, days.size)
        assertEquals(Money(250), days[3].amount)
        assertEquals(Money.ZERO, days[0].amount)

        val weeks = Trends.build(range("2026-01-01", "2026-03-31"), daily)
        assertEquals(Granularity.WEEK, Trends.granularity(range("2026-01-01", "2026-03-31")))
        val week = weeks.single { it.start == d("2026-03-02") } // Monday
        assertEquals(Money(350), week.amount)

        val months = Trends.build(range("2025-01-01", "2026-12-31"), daily)
        assertEquals(24, months.size)
        assertEquals(Money(400), months.single { it.start == d("2026-03-01") }.amount)
        // Totals are preserved exactly across bucketing.
        assertEquals(400L, months.sumOf { it.amount.cents })

        val allTime = Trends.build(DateRange(AnalysisPeriods.EARLIEST, d("2026-03-10")), daily)
        assertEquals(d("2026-03-02"), allTime.first().start)
        assertTrue(Trends.build(DateRange(AnalysisPeriods.EARLIEST, today), emptyMap()).size == 1)
    }

    private val categories = listOf(
        Category(1, "Food", CategoryKind.EXPENSE, null),
        Category(2, "Groceries", CategoryKind.EXPENSE, 1),
        Category(3, "Produce", CategoryKind.EXPENSE, 2),
        Category(4, "Dining", CategoryKind.EXPENSE, 1),
        Category(5, "Housing", CategoryKind.EXPENSE, null),
        Category(6, "Salary", CategoryKind.INCOME, null),
    )

    @Test
    fun categoryRollupCountsEachAmountOnceAtEveryLevel() {
        val current = mapOf<Long?, Money>(1L to Money(100), 2L to Money(200), 3L to Money(300), 4L to Money(400), 5L to Money(50), null to Money(7))
        val previous = mapOf<Long?, Money>(3L to Money(600))
        val rows = CategoryRollup.rollup(categories, current, previous).associateBy { it.categoryId }
        assertEquals(Money(1000), rows[1]!!.total)  // 100 + 200 + 300 + 400
        assertEquals(Money(100), rows[1]!!.own)
        assertEquals(Money(500), rows[2]!!.total)
        assertEquals(Money(300), rows[3]!!.total)
        assertEquals(Money(7), rows[null]!!.total)
        assertEquals(listOf(0, 1, 2), listOf(rows[1]!!.depth, rows[2]!!.depth, rows[3]!!.depth))
        // Top-level totals plus uncategorised equal the overall spending exactly.
        val topLevel = rows.values.filter { it.depth == 0 }.sumOf { it.total.cents }
        assertEquals(current.values.sumOf { it.cents }, topLevel)
        assertEquals(Money(600), rows[1]!!.previousTotal)
        assertEquals(-5000L, rows[3]!!.comparison.changeBasisPoints)
        assertEquals(Money.ZERO, rows[5]!!.previousTotal)
        // Categories with nothing in either period are omitted.
        assertTrue(6L !in rows)
        // Parents come before their children.
        val order = CategoryRollup.rollup(categories, current, null).map { it.categoryId }
        assertTrue(order.indexOf(1L) < order.indexOf(2L) && order.indexOf(2L) < order.indexOf(3L))
    }

    private fun point(date: String, store: Long, brand: String, cents: Long, perUnit: Long?, dimension: Dimension = Dimension.MASS) = PricePoint(
        date = d(date), storeId = store, storeName = brand.lowercase(), brandKey = brand, address = "addr $store",
        quantityMilli = 1000, unit = MeasureUnit.KG, paid = Money(cents),
        normalized = perUnit?.let { NormalizedPrice(dimension, it) },
    )

    @Test
    fun priceStatisticsByLocationAndRetailer() {
        val points = listOf(
            point("2026-03-01", 1, "MART", 300, 300_000),
            point("2026-03-08", 1, "MART", 350, 350_000),
            point("2026-03-05", 2, "MART", 280, 280_000),
            point("2026-03-03", 3, "SHOP", 400, 400_001),
            point("2026-03-09", 3, "SHOP", 999, null),
        )
        val all = PriceAnalysis.stats("All", "all", points)!!
        assertEquals(4, all.purchases)
        assertEquals(280_000L, all.lowest.milliCentsPerUnit)
        assertEquals(400_001L, all.highest.milliCentsPerUnit)
        assertEquals(332_500L, all.average.milliCentsPerUnit) // (300000+350000+280000+400001)/4 rounded
        assertEquals(350_000L, all.latest.milliCentsPerUnit)
        assertEquals(listOf("2", "1", "3"), PriceAnalysis.byLocation(points).map { it.key })
        assertEquals(listOf("MART", "SHOP"), PriceAnalysis.byBrand(points).map { it.key })
        assertEquals(310_000L, PriceAnalysis.byBrand(points).first().average.milliCentsPerUnit)
        assertEquals(2L, PriceAnalysis.averageDaysBetween(points)) // 1st..9th over 4 gaps
        assertNull(PriceAnalysis.averageDaysBetween(points.take(1)))
        assertNull(PriceAnalysis.stats("none", "none", listOf(point("2026-03-01", 1, "MART", 100, null))))
    }

    @Test
    fun retailersGroupLocationsAndCrossLocationPricesCompare() {
        val a = LocationSpend(1, "Mart", "MART", "1 Main St", 3, Money(3000), null, null)
        val b = LocationSpend(2, "MART", "MART", "9 Elm St", 1, Money(1001), null, null)
        val c = LocationSpend(3, "Shop", "SHOP", null, 2, Money(500), null, null)
        val brands = BrandSpend.group(listOf(c, b, a))
        assertEquals(listOf("MART", "SHOP"), brands.map { it.brandKey })
        assertEquals("Mart", brands[0].name)
        assertEquals(Money(4001), brands[0].total)
        assertEquals(4L, brands[0].visits)
        assertEquals(Money(1000), brands[0].averageReceipt) // 1000.25 -> 1000

        val points = mapOf(
            10L to ("Milk" to listOf(point("2026-03-01", 1, "MART", 300, 300_000), point("2026-03-07", 1, "MART", 320, 320_000), point("2026-03-02", 2, "MART", 310, 310_000))),
            11L to ("Bread" to listOf(point("2026-03-01", 1, "MART", 200, 200_000))),
        )
        val comparisons = PriceComparisons.across(listOf(a, b), points)
        assertEquals(1, comparisons.size)
        assertEquals(listOf(2L to 310_000L, 1L to 320_000L), comparisons.single().prices.map { it.first.storeId to it.second.milliCentsPerUnit })
    }
}
