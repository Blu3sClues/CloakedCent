package com.pocketbudget.app.ui.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.domain.model.AnalysisPeriod
import com.pocketbudget.app.domain.model.AnalysisPeriods
import com.pocketbudget.app.domain.model.BrandSpend
import com.pocketbudget.app.domain.model.CategorySpend
import com.pocketbudget.app.domain.model.DatePreset
import com.pocketbudget.app.domain.model.DateRange
import com.pocketbudget.app.domain.model.ProductSpend
import com.pocketbudget.app.domain.model.SpendMath
import com.pocketbudget.app.domain.model.SpendingOverview
import com.pocketbudget.app.domain.repository.AnalysisRepository
import com.pocketbudget.app.ui.components.AmountInput
import com.pocketbudget.app.ui.components.BackTopBar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate

/** Holds the selected period; analysis Flows restart whenever it changes. */
@OptIn(ExperimentalCoroutinesApi::class)
open class PeriodViewModel(initial: AnalysisPeriod, private val clock: Clock) : ViewModel() {

    private val _period = MutableStateFlow(initial)
    val period: StateFlow<AnalysisPeriod> = _period.asStateFlow()

    private val today: LocalDate get() = LocalDate.now(clock)

    fun selectPreset(preset: DatePreset) {
        _period.value = AnalysisPeriods.resolve(preset, today)
    }

    fun selectCustom(range: DateRange) {
        _period.value = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, range)
    }

    protected fun <T> stateOf(load: (AnalysisPeriod) -> Flow<T>): StateFlow<AnalysisState<T>> =
        _period.flatMapLatest { period ->
            load(period)
                .map<T, AnalysisState<T>> { AnalysisState.Ready(it) }
                .onStart { emit(AnalysisState.Loading) }
                .catch { emit(AnalysisState.Failed) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisState.Loading)
}

class AnalysisViewModel(
    analysis: AnalysisRepository,
    clock: Clock = Clock.systemDefaultZone(),
    initial: AnalysisPeriod = AnalysisPeriods.resolve(DatePreset.LAST_30_DAYS, LocalDate.now(clock)),
) : PeriodViewModel(initial, clock) {

    val overview: StateFlow<AnalysisState<SpendingOverview>> = stateOf(analysis::observeOverview)
    val categories: StateFlow<AnalysisState<List<CategorySpend>>> = stateOf(analysis::observeCategories)
    val brands: StateFlow<AnalysisState<List<BrandSpend>>> = stateOf(analysis::observeBrands)
    val products: StateFlow<AnalysisState<List<ProductSpend>>> = stateOf(analysis::observeProducts)

    companion object {
        val Factory = viewModelFactory {
            initializer { AnalysisViewModel((this[APPLICATION_KEY] as PocketBudgetApp).container.analysisRepository) }
        }
    }
}

/** Navigation callbacks shared by the analysis screens; the period travels with them. */
class AnalysisNavigation(
    val onBack: () -> Unit,
    val onCategory: (Long, AnalysisPeriod) -> Unit,
    val onBrand: (String, AnalysisPeriod) -> Unit,
    val onLocation: (Long, AnalysisPeriod) -> Unit,
    val onProduct: (Long, AnalysisPeriod) -> Unit,
    val onEditProduct: (Long) -> Unit,
)

@Composable
fun AnalysisRoute(nav: AnalysisNavigation, viewModel: AnalysisViewModel = viewModel(factory = AnalysisViewModel.Factory)) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.analysis_tab_overview, R.string.analysis_tab_categories, R.string.analysis_tab_stores, R.string.analysis_tab_products)

    Scaffold(topBar = { BackTopBar(stringResource(R.string.analysis_title), onBack = null) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PeriodFilter(period, viewModel::selectPreset, viewModel::selectCustom) }
            item {
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    tabs.forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
                    }
                }
            }
            when (tab) {
                0 -> stateItems(overview) { overviewItems(it, period, nav) }
                1 -> stateItems(categories) { categoryItems(it, period, nav) }
                2 -> stateItems(brands) { brandItems(it, period, nav) }
                else -> stateItems(products) { productItems(it, period, nav) }
            }
        }
    }
}

/** Loading and failure as single items; loaded content adds its own items. */
fun <T> LazyListScope.stateItems(state: AnalysisState<T>, content: LazyListScope.(T) -> Unit) {
    when (state) {
        is AnalysisState.Ready -> content(state.value)
        else -> item { StateContent(state) {} }
    }
}

private fun LazyListScope.overviewItems(overview: SpendingOverview, period: AnalysisPeriod, nav: AnalysisNavigation) {
    if (overview.isEmpty) {
        item { NoSpending() }
        return
    }
    item {
        TotalCard(stringResource(R.string.analysis_total_spent), overview.total) {
            StatRow(stringResource(R.string.analysis_purchases), overview.purchases.toString())
            StatRow(stringResource(R.string.analysis_average_purchase), AmountInput.format(overview.averagePurchase))
            if (overview.refunds.isPositive) StatRow(stringResource(R.string.analysis_refunds), "−" + AmountInput.format(overview.refunds))
        }
    }
    item { SectionCard(stringResource(R.string.analysis_trend)) { TrendChart(overview.trend, period.range) } }
    if (overview.topCategories.isNotEmpty()) {
        item {
            SectionCard(stringResource(R.string.analysis_top_categories)) {
                overview.topCategories.forEach { row ->
                    AmountBarRow(
                        title = row.name.ifEmpty { stringResource(R.string.analysis_uncategorised) },
                        subtitle = stringResource(R.string.analysis_share, formatPerMille(SpendMath.sharePerMille(row.total, overview.total.current))),
                        amount = row.total,
                        share = SpendMath.sharePerMille(row.total, overview.total.current),
                        onClick = row.categoryId?.let { id -> { nav.onCategory(id, period) } },
                    )
                }
            }
        }
    }
    if (overview.topBrands.isNotEmpty()) {
        item {
            SectionCard(stringResource(R.string.analysis_top_stores)) {
                val max = overview.topBrands.first().total
                overview.topBrands.forEach { brand ->
                    AmountBarRow(
                        title = brand.name,
                        subtitle = pluralStringResource(R.plurals.analysis_visits_avg, brand.visits.toInt(), brand.visits.toInt(), AmountInput.format(brand.averageReceipt)),
                        amount = brand.total,
                        share = SpendMath.sharePerMille(brand.total, max),
                        onClick = { nav.onBrand(brand.brandKey, period) },
                    )
                }
            }
        }
    }
    if (overview.topProducts.isNotEmpty()) {
        item {
            SectionCard(stringResource(R.string.analysis_top_products)) {
                val max = overview.topProducts.first().total
                overview.topProducts.forEach { product ->
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
}

private fun LazyListScope.categoryItems(rows: List<CategorySpend>, period: AnalysisPeriod, nav: AnalysisNavigation) {
    if (rows.none { it.total.isPositive }) {
        item { NoSpending() }
        return
    }
    // Top-level totals already include their subcategories, so the whole is their sum.
    val whole = rows.filter { it.depth == 0 }.fold(com.pocketbudget.app.core.money.Money.ZERO) { sum, it -> sum + it.total }
    items(rows, key = { "${it.categoryId}" }) { row ->
        val share = SpendMath.sharePerMille(row.total, whole)
        val change = row.comparison.changeBasisPoints
        AmountBarRow(
            title = row.name.ifEmpty { stringResource(R.string.analysis_uncategorised) },
            subtitle = listOfNotNull(
                stringResource(R.string.analysis_share, formatPerMille(share)),
                change?.let(::formatBasisPoints),
            ).joinToString(" · "),
            amount = row.total,
            share = share,
            indent = row.depth,
            onClick = row.categoryId?.let { id -> { nav.onCategory(id, period) } },
        )
    }
}

private fun LazyListScope.brandItems(brands: List<BrandSpend>, period: AnalysisPeriod, nav: AnalysisNavigation) {
    if (brands.isEmpty()) {
        item { NoSpending(stringResource(R.string.analysis_receipts_empty)) }
        return
    }
    val max = brands.first().total
    items(brands, key = { it.brandKey }) { brand ->
        AmountBarRow(
            title = brand.name,
            subtitle = listOfNotNull(
                pluralStringResource(R.plurals.analysis_visits_avg, brand.visits.toInt(), brand.visits.toInt(), AmountInput.format(brand.averageReceipt)),
                if (brand.locations.size > 1) pluralStringResource(R.plurals.analysis_location_count, brand.locations.size, brand.locations.size) else null,
            ).joinToString(" · "),
            amount = brand.total,
            share = SpendMath.sharePerMille(brand.total, max),
            onClick = { nav.onBrand(brand.brandKey, period) },
        )
    }
}

private fun LazyListScope.productItems(products: List<ProductSpend>, period: AnalysisPeriod, nav: AnalysisNavigation) {
    if (products.isEmpty()) {
        item { NoSpending(stringResource(R.string.analysis_receipts_empty)) }
        return
    }
    val max = products.first().total
    items(products, key = { it.productId }) { product ->
        AmountBarRow(
            title = product.name,
            subtitle = pluralStringResource(R.plurals.analysis_purchase_count, product.purchases.toInt(), product.purchases.toInt(), AmountInput.format(product.averagePrice)),
            amount = product.total,
            share = SpendMath.sharePerMille(product.total, max),
            onClick = { nav.onProduct(product.productId, period) },
        )
    }
    item { Text(stringResource(R.string.analysis_items_note), style = MaterialTheme.typography.bodySmall) }
}
