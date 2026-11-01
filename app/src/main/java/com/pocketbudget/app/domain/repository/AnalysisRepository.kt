package com.pocketbudget.app.domain.repository

import com.pocketbudget.app.domain.model.AnalysisPeriod
import com.pocketbudget.app.domain.model.BrandAnalysis
import com.pocketbudget.app.domain.model.BrandSpend
import com.pocketbudget.app.domain.model.CategoryAnalysis
import com.pocketbudget.app.domain.model.CategorySpend
import com.pocketbudget.app.domain.model.LocationAnalysis
import com.pocketbudget.app.domain.model.ProductAnalysis
import com.pocketbudget.app.domain.model.ProductSpend
import com.pocketbudget.app.domain.model.SpendingOverview
import kotlinx.coroutines.flow.Flow

/**
 * Read-only spending analysis over the ledger, receipts and price history. Every
 * Flow re-emits when the underlying data changes. Spending excludes scheduled,
 * deleted, income, transfer and balance-adjustment entries.
 */
interface AnalysisRepository {
    fun observeOverview(period: AnalysisPeriod): Flow<SpendingOverview>

    /** Category tree with totals that include descendants exactly once; uncategorised has a null id. */
    fun observeCategories(period: AnalysisPeriod): Flow<List<CategorySpend>>

    /** Null when the category does not exist. */
    fun observeCategory(categoryId: Long, period: AnalysisPeriod): Flow<CategoryAnalysis?>

    /** Retailers (locations grouped by normalized store name), from confirmed receipts. */
    fun observeBrands(period: AnalysisPeriod): Flow<List<BrandSpend>>

    /** Null when the retailer has no spending in the period. */
    fun observeBrand(brandKey: String, period: AnalysisPeriod): Flow<BrandAnalysis?>

    /** Null when the store does not exist. */
    fun observeLocation(storeId: Long, period: AnalysisPeriod): Flow<LocationAnalysis?>

    fun observeProducts(period: AnalysisPeriod): Flow<List<ProductSpend>>

    /** Null when the product does not exist. */
    fun observeProduct(productId: Long, period: AnalysisPeriod): Flow<ProductAnalysis?>
}
