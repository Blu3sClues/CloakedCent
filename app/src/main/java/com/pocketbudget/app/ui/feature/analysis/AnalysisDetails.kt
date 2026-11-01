package com.pocketbudget.app.ui.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.domain.model.AnalysisPeriod
import com.pocketbudget.app.domain.model.AnalysisPeriods
import com.pocketbudget.app.domain.model.BrandAnalysis
import com.pocketbudget.app.domain.model.CategoryAnalysis
import com.pocketbudget.app.domain.model.Comparison
import com.pocketbudget.app.domain.model.LocationAnalysis
import com.pocketbudget.app.domain.model.LocationPriceComparison
import com.pocketbudget.app.domain.model.PricePoint
import com.pocketbudget.app.domain.model.PriceStats
import com.pocketbudget.app.domain.model.ProductAnalysis
import com.pocketbudget.app.domain.model.ProductSpend
import com.pocketbudget.app.domain.model.SpendMath
import com.pocketbudget.app.domain.repository.AnalysisRepository
import com.pocketbudget.app.ui.components.AmountInput
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.MoneyText
import com.pocketbudget.app.ui.feature.receipt.QuantityText
import com.pocketbudget.app.ui.feature.receipt.normalizedLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.LocalDate

const val ARG_ANALYSIS_ID = "id"
const val ARG_ANALYSIS_KEY = "key"
const val ARG_ANALYSIS_PERIOD = "period"

/** One analysed subject (category, store, location or product); null content means it no longer exists. */
class AnalysisDetailViewModel<T : Any>(
    initial: AnalysisPeriod,
    clock: Clock,
    load: (AnalysisPeriod) -> Flow<T?>,
) : PeriodViewModel(initial, clock) {
    val state: StateFlow<AnalysisState<T?>> = stateOf(load)

    companion object {
        fun <T : Any> factory(load: AnalysisRepository.(CreationExtras, Long, String) -> (AnalysisPeriod) -> Flow<T?>) = viewModelFactory {
            initializer {
                val handle = createSavedStateHandle()
                val clock = Clock.systemDefaultZone()
                val repository = (this[APPLICATION_KEY] as PocketBudgetApp).container.analysisRepository
                AnalysisDetailViewModel(
                    AnalysisPeriods.decode(handle.get<String>(ARG_ANALYSIS_PERIOD), LocalDate.now(clock)),
                    clock,
                    repository.load(this, handle.get<Long>(ARG_ANALYSIS_ID) ?: 0L, handle.get<String>(ARG_ANALYSIS_KEY).orEmpty()),
                )
            }
        }
    }
}

@Composable
private fun <T : Any> DetailScaffold(
    title: @Composable (T?) -> String,
    viewModel: AnalysisDetailViewModel<T>,
    onBack: () -> Unit,
    content: LazyListScope.(T, AnalysisPeriod) -> Unit,
) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val value = (state as? AnalysisState.Ready)?.value
    Scaffold(topBar = { BackTopBar(title(value), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PeriodFilter(period, viewModel::selectPreset, viewModel::selectCustom) }
            stateItems(state) { loaded ->
                if (loaded == null) item { NoSpending() } else content(loaded, period)
            }
        }
    }
}

// ------------------------------------------------------------------ Category

@Composable
fun CategoryAnalysisRoute(
    nav: AnalysisNavigation,
    viewModel: AnalysisDetailViewModel<CategoryAnalysis> = viewModel(
        factory = AnalysisDetailViewModel.factory { _, id, _ -> { period -> observeCategory(id, period) } },
    ),
) {
    DetailScaffold({ it?.path ?: stringResource(R.string.analysis_tab_categories) }, viewModel, nav.onBack) { analysis, period ->
        val row = analysis.category
        item {
            TotalCard(stringResource(R.string.analysis_total_spent), row.comparison) {
                if (row.own.isPositive && analysis.subcategories.isNotEmpty()) {
                    StatRow(stringResource(R.string.analysis_direct, row.name), AmountInput.format(row.own))
                }
            }
        }
        if (row.total.isPositive) item { SectionCard(stringResource(R.string.analysis_trend)) { TrendChart(analysis.trend, period.range) } }
        if (analysis.subcategories.isNotEmpty()) {
            item {
                SectionCard(stringResource(R.string.analysis_subcategories)) {
                    analysis.subcategories.forEach { sub ->
                        val share = SpendMath.sharePerMille(sub.total, row.total)
                        AmountBarRow(
                            title = sub.name,
                            subtitle = listOfNotNull(formatPerMille(share), sub.comparison.changeBasisPoints?.let(::formatBasisPoints)).joinToString(" · "),
                            amount = sub.total,
                            share = share,
                            onClick = sub.categoryId?.let { id -> { nav.onCategory(id, period) } },
                        )
                    }
                }
            }
        }
        productsSection(analysis.topProducts, period, nav)
    }
}

private fun LazyListScope.productsSection(products: List<ProductSpend>, period: AnalysisPeriod, nav: AnalysisNavigation) {
    if (products.isEmpty()) return
    item {
        SectionCard(stringResource(R.string.analysis_top_products)) {
            val max = products.first().total
            products.forEach { product ->
                AmountBarRow(
                    title = product.name,
                    subtitle = pluralStringResource(R.plurals.analysis_purchase_count, product.purchases.toInt(), product.purchases.toInt(), AmountInput.format(product.averagePrice)),
                    amount = product.total,
                    share = SpendMath.sharePerMille(product.total, max),
                    onClick = { nav.onProduct(product.productId, period) },
                )
            }
            Text(stringResource(R.string.analysis_items_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun LazyListScope.comparisonsSection(comparisons: List<LocationPriceComparison>, highlightStoreId: Long? = null) {
    if (comparisons.isEmpty()) return
    item {
        SectionCard(stringResource(R.string.analysis_price_comparison)) {
            comparisons.forEach { comparison ->
                Text(comparison.productName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                comparison.prices.forEach { (location, price) ->
                    val label = listOfNotNull(location.name, location.address).joinToString(" · ")
                    StatRow(if (location.storeId == highlightStoreId) "▸ $label" else label, normalizedLabel(price))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Store (retailer) and location

@Composable
fun BrandAnalysisRoute(
    nav: AnalysisNavigation,
    viewModel: AnalysisDetailViewModel<BrandAnalysis> = viewModel(
        factory = AnalysisDetailViewModel.factory { _, _, key -> { period -> observeBrand(key, period) } },
    ),
) {
    DetailScaffold({ it?.brand?.name ?: stringResource(R.string.analysis_tab_stores) }, viewModel, nav.onBack) { analysis, period ->
        val brand = analysis.brand
        item {
            TotalCard(stringResource(R.string.analysis_total_spent), Comparison(brand.total, analysis.previousTotal)) {
                StatRow(stringResource(R.string.analysis_visits), brand.visits.toString())
                StatRow(stringResource(R.string.analysis_average_receipt), AmountInput.format(brand.averageReceipt))
            }
        }
        item { SectionCard(stringResource(R.string.analysis_trend)) { TrendChart(analysis.trend, period.range) } }
        item {
            SectionCard(stringResource(R.string.analysis_locations)) {
                brand.locations.forEach { location ->
                    AmountBarRow(
                        title = location.address ?: stringResource(R.string.analysis_location_unknown),
                        subtitle = pluralStringResource(R.plurals.analysis_visits_avg, location.visits.toInt(), location.visits.toInt(), AmountInput.format(location.averageReceipt)),
                        amount = location.total,
                        share = SpendMath.sharePerMille(location.total, brand.total),
                        onClick = { nav.onLocation(location.storeId, period) },
                    )
                }
            }
        }
        productsSection(analysis.topProducts, period, nav)
        comparisonsSection(analysis.priceComparisons)
    }
}

@Composable
fun LocationAnalysisRoute(
    nav: AnalysisNavigation,
    viewModel: AnalysisDetailViewModel<LocationAnalysis> = viewModel(
        factory = AnalysisDetailViewModel.factory { _, id, _ -> { period -> observeLocation(id, period) } },
    ),
) {
    DetailScaffold({ it?.location?.name ?: stringResource(R.string.analysis_locations) }, viewModel, nav.onBack) { analysis, period ->
        val location = analysis.location
        item {
            TotalCard(location.address ?: stringResource(R.string.analysis_location_unknown), Comparison(location.total, analysis.previousTotal)) {
                StatRow(stringResource(R.string.analysis_visits), location.visits.toString())
                StatRow(stringResource(R.string.analysis_average_receipt), AmountInput.format(location.averageReceipt))
                if (location.firstVisit != null && location.lastVisit != null) {
                    StatRow(
                        stringResource(R.string.analysis_history),
                        stringResource(R.string.analysis_unit_range, analysisDate.format(location.firstVisit), analysisDate.format(location.lastVisit)),
                    )
                }
            }
        }
        if (location.total.isPositive) item { SectionCard(stringResource(R.string.analysis_trend)) { TrendChart(analysis.trend, period.range) } }
        productsSection(analysis.topProducts, period, nav)
        if (analysis.latestPrices.isNotEmpty()) {
            item {
                SectionCard(stringResource(R.string.analysis_prices_here)) {
                    analysis.latestPrices.forEach { (product, point) ->
                        StatRow(
                            "${product.name} · ${analysisDate.format(point.date)}",
                            point.normalized?.let { normalizedLabel(it) } ?: AmountInput.format(point.paid),
                        )
                    }
                }
            }
        }
        if (analysis.siblings.isNotEmpty()) {
            item {
                SectionCard(stringResource(R.string.analysis_other_locations)) {
                    analysis.siblings.forEach { other ->
                        AmountBarRow(
                            title = other.address ?: stringResource(R.string.analysis_location_unknown),
                            subtitle = pluralStringResource(R.plurals.analysis_visits_avg, other.visits.toInt(), other.visits.toInt(), AmountInput.format(other.averageReceipt)),
                            amount = other.total,
                            share = SpendMath.sharePerMille(other.total, maxOf(other.total, location.total, compareBy { it.cents })),
                            onClick = { nav.onLocation(other.storeId, period) },
                        )
                    }
                }
            }
        }
        comparisonsSection(analysis.priceComparisons, highlightStoreId = location.storeId)
    }
}

// ------------------------------------------------------------------ Product

@Composable
fun ProductAnalysisRoute(
    nav: AnalysisNavigation,
    viewModel: AnalysisDetailViewModel<ProductAnalysis> = viewModel(
        factory = AnalysisDetailViewModel.factory { _, id, _ -> { period -> observeProduct(id, period) } },
    ),
) {
    DetailScaffold({ it?.product?.name ?: stringResource(R.string.analysis_tab_products) }, viewModel, nav.onBack) { analysis, _ ->
        val spend = analysis.spend
        item {
            SectionCard(stringResource(R.string.analysis_total_product)) {
                MoneyText(spend.total, style = MaterialTheme.typography.headlineMedium)
                StatRow(stringResource(R.string.analysis_purchases), spend.purchases.toString())
                StatRow(stringResource(R.string.analysis_average_price), AmountInput.format(spend.averagePrice))
                Text(
                    analysis.averageDaysBetween?.let { pluralStringResource(R.plurals.analysis_frequency, it.toInt(), it.toInt()) }
                        ?: stringResource(R.string.analysis_frequency_single),
                    style = MaterialTheme.typography.bodySmall,
                )
                analysis.overall?.let { StatRow(stringResource(R.string.analysis_unit_average), normalizedLabel(it.average)) }
                analysis.lowestPaid?.let { StatRow(stringResource(R.string.analysis_lowest), pointLabel(it)) }
                analysis.highestPaid?.let { StatRow(stringResource(R.string.analysis_highest), pointLabel(it)) }
                OutlinedButton(onClick = { nav.onEditProduct(analysis.product.id) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.analysis_edit_product))
                }
            }
        }
        if (spend.purchases == 0L) {
            item { NoSpending() }
            return@DetailScaffold
        }
        if (analysis.history.count { it.normalized != null } >= 2) {
            item { SectionCard(stringResource(R.string.analysis_price_chart)) { PriceChart(analysis.history) } }
        }
        statsSection(R.string.analysis_by_location, analysis.byLocation)
        statsSection(R.string.analysis_by_store, analysis.byBrand)
        item {
            SectionCard(stringResource(R.string.analysis_history)) {
                analysis.history.forEach { point ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${analysisDate.format(point.date)} · ${point.storeName}")
                            Text(
                                listOfNotNull(
                                    "${QuantityText.format(point.quantityMilli)} ${point.unit.symbol}",
                                    point.normalized?.let { normalizedLabel(it) },
                                    point.address,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        MoneyText(point.paid)
                    }
                }
            }
        }
    }
}

@Composable
private fun pointLabel(point: PricePoint): String =
    listOfNotNull(
        point.normalized?.let { normalizedLabel(it) } ?: AmountInput.format(point.paid),
        point.storeName,
        analysisDate.format(point.date),
    ).joinToString(" · ")

private fun LazyListScope.statsSection(title: Int, stats: List<PriceStats>) {
    if (stats.size < 2) return
    item {
        SectionCard(stringResource(title)) {
            stats.forEach { s ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    StatRow(s.label, normalizedLabel(s.average))
                    Text(
                        stringResource(R.string.analysis_unit_range, normalizedLabel(s.lowest), normalizedLabel(s.highest)) +
                            " · " + pluralStringResource(R.plurals.analysis_purchase_count, s.purchases, s.purchases, normalizedLabel(s.latest)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
