package com.pocketbudget.app.data.repository

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.db.dao.AnalysisDao
import com.pocketbudget.app.data.db.dao.CategorySpendRow
import com.pocketbudget.app.data.db.dao.DailySpendRow
import com.pocketbudget.app.data.db.dao.LocationSpendRow
import com.pocketbudget.app.data.db.dao.PricePointRow
import com.pocketbudget.app.data.db.dao.ProductSpendRow
import com.pocketbudget.app.data.db.entity.ProductEntity
import com.pocketbudget.app.domain.model.AnalysisPeriod
import com.pocketbudget.app.domain.model.BrandAnalysis
import com.pocketbudget.app.domain.model.BrandSpend
import com.pocketbudget.app.domain.model.Category
import com.pocketbudget.app.domain.model.CategoryAnalysis
import com.pocketbudget.app.domain.model.CategoryRollup
import com.pocketbudget.app.domain.model.CategorySpend
import com.pocketbudget.app.domain.model.Comparison
import com.pocketbudget.app.domain.model.DateRange
import com.pocketbudget.app.domain.model.LocationAnalysis
import com.pocketbudget.app.domain.model.LocationSpend
import com.pocketbudget.app.domain.model.NormalizedPrice
import com.pocketbudget.app.domain.model.PriceAnalysis
import com.pocketbudget.app.domain.model.PriceComparisons
import com.pocketbudget.app.domain.model.PricePoint
import com.pocketbudget.app.domain.model.Product
import com.pocketbudget.app.domain.model.ProductAnalysis
import com.pocketbudget.app.domain.model.ProductSpend
import com.pocketbudget.app.domain.model.SpendingOverview
import com.pocketbudget.app.domain.model.TextKeys
import com.pocketbudget.app.domain.model.Trends
import com.pocketbudget.app.domain.model.pathLabels
import com.pocketbudget.app.domain.model.withDescendants
import com.pocketbudget.app.domain.repository.AnalysisRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val TOP = 5

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAnalysisRepository(
    database: () -> PocketBudgetDatabase,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), AnalysisRepository {

    private fun <T> dao(query: AnalysisDao.() -> Flow<T>): Flow<T> = observe { db -> db.analysisDao().query() }

    private val categories: Flow<List<Category>> = observe { db ->
        db.categoryDao().observeAll().map { rows -> rows.map { Category(it.id, it.name, it.kind, it.parentId) } }
    }

    private fun <T> previousOr(period: AnalysisPeriod, query: (DateRange) -> Flow<T>): Flow<T?> =
        period.previous?.let(query) ?: flowOf(null)

    private fun categoryAmounts(range: DateRange): Flow<Map<Long?, Money>> =
        dao { observeCategories(range.start, range.endInclusive) }.map { rows -> rows.associate { it.toPair() } }

    private fun locations(range: DateRange): Flow<List<LocationSpend>> =
        dao { observeLocations(range.start, range.endInclusive) }.map { rows -> rows.map { it.toDomain() } }

    private fun products(range: DateRange, storeIds: List<Long>? = null, categoryIds: List<Long>? = null): Flow<List<ProductSpend>> =
        dao {
            observeProducts(
                range.start, range.endInclusive,
                anyStore = storeIds == null, storeIds = storeIds.orEmpty(),
                anyCategory = categoryIds == null, categoryIds = categoryIds.orEmpty(),
            )
        }.map { rows -> rows.map { it.toDomain() } }

    private fun pricePoints(range: DateRange, productId: Long? = null, storeIds: List<Long>? = null): Flow<List<PricePointRow>> =
        dao {
            observePricePoints(
                range.start, range.endInclusive,
                anyProduct = productId == null, productId = productId ?: 0,
                anyStore = storeIds == null, storeIds = storeIds.orEmpty(),
            )
        }

    private fun trend(range: DateRange, daily: Flow<List<DailySpendRow>>): Flow<List<com.pocketbudget.app.domain.model.TrendPoint>> =
        daily.map { rows -> Trends.build(range, rows.associate { it.day to Money(it.cents) }) }

    override fun observeOverview(period: AnalysisPeriod): Flow<SpendingOverview> {
        val r = period.range
        val totals = combine(
            dao { observeTotals(r.start, r.endInclusive) },
            previousOr(period) { p -> dao { observeTotals(p.start, p.endInclusive) } },
        ) { current, previous -> current to previous }
        return combine(
            totals,
            trend(r, dao { observeDaily(r.start, r.endInclusive) }),
            observeCategories(period),
            locations(r),
            products(r),
        ) { (current, previous), trend, categoryRows, locationRows, productRows ->
            SpendingOverview(
                period = period,
                total = Comparison(Money(current.cents), previous?.let { Money(it.cents) }),
                purchases = current.purchases,
                previousPurchases = previous?.purchases,
                trend = trend,
                topCategories = categoryRows.filter { it.depth == 0 && it.total.isPositive }.sortedByDescending { it.total.cents }.take(TOP),
                topBrands = BrandSpend.group(locationRows).take(TOP),
                topProducts = productRows.take(TOP),
            )
        }
    }

    override fun observeCategories(period: AnalysisPeriod): Flow<List<CategorySpend>> =
        combine(
            categories,
            categoryAmounts(period.range),
            previousOr(period, ::categoryAmounts),
        ) { all, current, previous -> CategoryRollup.rollup(all, current, previous) }

    override fun observeCategory(categoryId: Long, period: AnalysisPeriod): Flow<CategoryAnalysis?> =
        categories.flatMapLatest { all ->
            val category = all.firstOrNull { it.id == categoryId } ?: return@flatMapLatest flowOf(null)
            val subtree = all.withDescendants(categoryId).toList()
            val r = period.range
            combine(
                observeCategories(period),
                trend(r, dao { observeCategoryDaily(r.start, r.endInclusive, subtree) }),
                products(r, categoryIds = subtree),
            ) { rows, trend, productRows ->
                val row = rows.firstOrNull { it.categoryId == categoryId }
                    ?: CategorySpend(categoryId, category.name, category.parentId, 0, Money.ZERO, Money.ZERO, period.previous?.let { Money.ZERO })
                CategoryAnalysis(
                    category = row,
                    path = all.pathLabels().getValue(categoryId),
                    subcategories = rows.filter { it.parentId == categoryId && it.categoryId != null },
                    trend = trend,
                    topProducts = productRows.take(TOP * 2),
                )
            }
        }

    override fun observeBrands(period: AnalysisPeriod): Flow<List<BrandSpend>> = locations(period.range).map(BrandSpend::group)

    override fun observeBrand(brandKey: String, period: AnalysisPeriod): Flow<BrandAnalysis?> =
        combine(locations(period.range), previousOr(period, ::locations)) { current, previous -> current to previous }
            .flatMapLatest { (current, previous) ->
                val brand = BrandSpend.group(current).firstOrNull { it.brandKey == brandKey } ?: return@flatMapLatest flowOf(null)
                val ids = brand.locations.map { it.storeId }
                val r = period.range
                combine(
                    trend(r, dao { observeStoreDaily(r.start, r.endInclusive, ids) }),
                    products(r, storeIds = ids),
                    pricePoints(r, storeIds = ids),
                ) { trend, productRows, points ->
                    BrandAnalysis(
                        brand = brand,
                        previousTotal = previous?.filter { it.brandKey == brandKey }?.fold(Money.ZERO) { sum, it -> sum + it.total },
                        trend = trend,
                        topProducts = productRows.take(TOP * 2),
                        priceComparisons = PriceComparisons.across(brand.locations, points.byProduct()),
                    )
                }
            }

    override fun observeLocation(storeId: Long, period: AnalysisPeriod): Flow<LocationAnalysis?> {
        val store = observe { db -> flowOf(db.receiptDao().getStore(storeId)) }
        return combine(store, locations(period.range), previousOr(period, ::locations)) { s, current, previous -> Triple(s, current, previous) }
            .flatMapLatest { (entity, current, previous) ->
                entity ?: return@flatMapLatest flowOf(null)
                val here = current.firstOrNull { it.storeId == storeId }
                    ?: LocationSpend(storeId, entity.name, entity.normalizedName, entity.address, 0, Money.ZERO, null, null)
                val brand = current.filter { it.brandKey == here.brandKey }.let { list -> if (here in list) list else list + here }
                val brandIds = brand.map { it.storeId }
                val r = period.range
                combine(
                    trend(r, dao { observeStoreDaily(r.start, r.endInclusive, listOf(storeId)) }),
                    products(r, storeIds = listOf(storeId)),
                    pricePoints(r, storeIds = brandIds),
                ) { trend, productRows, points ->
                    val latestHere = points.filter { it.storeId == storeId }
                        .groupBy { it.productId }
                        .mapValues { (_, list) -> list.maxBy { it.day }.toDomain() }
                    LocationAnalysis(
                        location = here,
                        previousTotal = previous?.let { list -> list.firstOrNull { it.storeId == storeId }?.total ?: Money.ZERO },
                        trend = trend,
                        topProducts = productRows.take(TOP * 2),
                        latestPrices = productRows.mapNotNull { p -> latestHere[p.productId]?.let { p to it } },
                        siblings = brand.filter { it.storeId != storeId },
                        priceComparisons = PriceComparisons.across(brand, points.byProduct())
                            .filter { comparison -> comparison.prices.any { it.first.storeId == storeId } },
                    )
                }
            }
    }

    override fun observeProducts(period: AnalysisPeriod): Flow<List<ProductSpend>> = products(period.range)

    override fun observeProduct(productId: Long, period: AnalysisPeriod): Flow<ProductAnalysis?> {
        val product = observe { db -> db.receiptDao().observeProducts().map { list -> list.firstOrNull { it.id == productId } } }
        return combine(product, pricePoints(period.range, productId = productId)) { entity, rows ->
            entity ?: return@combine null
            val history = rows.map { it.toDomain() }
            val paid = history.fold(Money.ZERO) { sum, it -> sum + it.paid }
            // Compare by unit price where known, so different pack sizes compare fairly.
            val priced = history.filter { it.normalized != null }
            val lowest = if (priced.isNotEmpty()) priced.minBy { it.normalized!!.milliCentsPerUnit } else history.minByOrNull { it.paid.cents }
            val highest = if (priced.isNotEmpty()) priced.maxBy { it.normalized!!.milliCentsPerUnit } else history.maxByOrNull { it.paid.cents }
            ProductAnalysis(
                product = entity.toDomain(),
                spend = ProductSpend(productId, entity.name, history.size.toLong(), paid),
                history = history,
                averageDaysBetween = PriceAnalysis.averageDaysBetween(history),
                overall = PriceAnalysis.stats(entity.name, productId.toString(), history),
                lowestPaid = lowest,
                highestPaid = highest,
                byLocation = PriceAnalysis.byLocation(history),
                byBrand = PriceAnalysis.byBrand(history),
            )
        }
    }
}

private fun CategorySpendRow.toPair(): Pair<Long?, Money> = categoryId to Money(cents)

private fun LocationSpendRow.toDomain() = LocationSpend(
    storeId = storeId,
    name = name,
    brandKey = normalizedName.ifBlank { TextKeys.normalize(name) },
    address = address,
    visits = visits,
    total = Money(cents),
    firstVisit = firstVisit,
    lastVisit = lastVisit,
)

private fun ProductSpendRow.toDomain() = ProductSpend(productId, name, purchases, Money(cents))

private fun PricePointRow.toDomain() = PricePoint(
    date = day,
    storeId = storeId,
    storeName = storeName,
    brandKey = normalizedName,
    address = address,
    quantityMilli = quantityMilli,
    unit = unit,
    paid = Money(paidCents),
    normalized = if (normalizedDimension != null && normalizedMillicents != null) NormalizedPrice(normalizedDimension, normalizedMillicents) else null,
)

private fun List<PricePointRow>.byProduct(): Map<Long, Pair<String, List<PricePoint>>> =
    groupBy { it.productId }.mapValues { (_, rows) -> rows.first().productName to rows.map { it.toDomain() } }

private fun ProductEntity.toDomain() = Product(id, name, brand, defaultCategoryId, packQuantityMilli, packUnit)
