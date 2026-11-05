package com.cloakedcent.app.ui.feature.analysis

import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.AnalysisPeriod
import com.cloakedcent.app.domain.model.AnalysisPeriods
import com.cloakedcent.app.domain.model.BrandAnalysis
import com.cloakedcent.app.domain.model.BrandSpend
import com.cloakedcent.app.domain.model.CategoryAnalysis
import com.cloakedcent.app.domain.model.CategorySpend
import com.cloakedcent.app.domain.model.Comparison
import com.cloakedcent.app.domain.model.DatePreset
import com.cloakedcent.app.domain.model.DateRange
import com.cloakedcent.app.domain.model.LocationAnalysis
import com.cloakedcent.app.domain.model.ProductAnalysis
import com.cloakedcent.app.domain.model.ProductSpend
import com.cloakedcent.app.domain.model.SpendingOverview
import com.cloakedcent.app.domain.repository.AnalysisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)

    /** Spending in the ledger; changing it must reach the screen. */
    private val spent = MutableStateFlow(Money(1_000))
    private val requested = mutableListOf<AnalysisPeriod>()
    private var failProducts = false

    private val repository = object : AnalysisRepository {
        override fun observeOverview(period: AnalysisPeriod): Flow<SpendingOverview> {
            requested += period
            return spent.map { SpendingOverview(period, Comparison(it, null), Money.ZERO, 1, null, emptyList(), emptyList(), emptyList(), emptyList()) }
        }
        override fun observeCategories(period: AnalysisPeriod): Flow<List<CategorySpend>> = flowOf(emptyList())
        override fun observeCategory(categoryId: Long, period: AnalysisPeriod): Flow<CategoryAnalysis?> = flowOf(null)
        override fun observeBrands(period: AnalysisPeriod): Flow<List<BrandSpend>> = flowOf(emptyList())
        override fun observeBrand(brandKey: String, period: AnalysisPeriod): Flow<BrandAnalysis?> = flowOf(null)
        override fun observeLocation(storeId: Long, period: AnalysisPeriod): Flow<LocationAnalysis?> = flowOf(null)
        override fun observeProducts(period: AnalysisPeriod): Flow<List<ProductSpend>> = flow {
            if (failProducts) throw IllegalStateException("database unavailable")
            emit(listOf(ProductSpend(1, "Milk", 2, Money(698))))
        }
        override fun observeProduct(productId: Long, period: AnalysisPeriod): Flow<ProductAnalysis?> = flowOf(null)
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AnalysisViewModel(repository, clock)

    @Test
    fun startsLoadingThenShowsDataAndFollowsChanges() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(AnalysisState.Loading, vm.overview.value)
        backgroundScope.launch { vm.overview.collect {} }
        advanceUntilIdle()
        assertEquals(Money(1_000), (vm.overview.value as AnalysisState.Ready).value.total.current)

        // A new expense in the ledger reaches the screen without any refresh.
        spent.value = Money(1_250)
        advanceUntilIdle()
        assertEquals(Money(1_250), (vm.overview.value as AnalysisState.Ready).value.total.current)
    }

    @Test
    fun changingThePeriodReloadsForTheNewRange() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.overview.collect {} }
        advanceUntilIdle()
        assertEquals(DatePreset.LAST_30_DAYS, requested.last().preset)

        vm.selectPreset(DatePreset.LAST_MONTH)
        advanceUntilIdle()
        assertEquals(DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), requested.last().range)
        assertEquals(DatePreset.LAST_MONTH, (vm.overview.value as AnalysisState.Ready).value.period.preset)

        val custom = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
        vm.selectCustom(custom)
        advanceUntilIdle()
        assertEquals(custom, vm.period.value.range)
        assertEquals(AnalysisPeriods.resolve(DatePreset.CUSTOM, LocalDate.of(2026, 11, 15), custom), requested.last())
    }

    @Test
    fun failuresBecomeAnErrorStateInsteadOfACrash() = runTest(dispatcher) {
        failProducts = true
        val vm = viewModel()
        backgroundScope.launch { vm.products.collect {} }
        advanceUntilIdle()
        assertEquals(AnalysisState.Failed, vm.products.value)

        failProducts = false
        vm.selectPreset(DatePreset.ALL_TIME)
        advanceUntilIdle()
        assertTrue(vm.products.value is AnalysisState.Ready)
    }
}
