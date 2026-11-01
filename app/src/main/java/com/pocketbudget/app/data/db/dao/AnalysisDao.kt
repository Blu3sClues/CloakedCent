package com.pocketbudget.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.pocketbudget.app.domain.model.Dimension
import com.pocketbudget.app.domain.model.MeasureUnit
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Spending entries, aliased `t`: completed, live expenses that are not balance
 * adjustments, in [:from, :to]. Scheduled, deleted, income, transfer and adjustment
 * entries never count. Every analysis query starts from this, so totals agree.
 */
private const val SPENDING = """
    t.type = 'EXPENSE' AND t.status <> 'SCHEDULED' AND t.is_deleted = 0 AND t.is_adjustment = 0
    AND t.occurred_on BETWEEN :from AND :to
"""

/**
 * Spending by category: split expenses through their splits, the rest (and any part
 * not covered by splits) under the expense's own category, NULL when uncategorised.
 * Each cent of an expense appears in exactly one row.
 */
private const val CATEGORY_PARTS = """
    SELECT t.occurred_on AS day, s.category_id AS category_id, s.amount_cents AS cents
    FROM transaction_splits s JOIN transactions t ON t.id = s.transaction_id
    WHERE $SPENDING
    UNION ALL
    SELECT day, category_id, cents FROM (
        SELECT t.occurred_on AS day, t.category_id AS category_id,
            -t.amount_cents - (SELECT COALESCE(SUM(amount_cents), 0) FROM transaction_splits
                WHERE transaction_id = t.id) AS cents
        FROM transactions t WHERE $SPENDING
    ) WHERE cents > 0
"""

/** Confirmed receipts whose expense counts as spending; one row per expense. */
private const val RECEIPT_SPENDING = """
    FROM receipts r
    JOIN transactions t ON t.id = r.transaction_id
    JOIN stores st ON st.id = r.store_id
    WHERE r.status = 'CONFIRMED' AND $SPENDING
"""

/** Confirmed purchased items whose receipt's expense counts as spending; one row per item. */
private const val ITEM_SPENDING = """
    FROM price_observations p
    JOIN receipt_items i ON i.id = p.receipt_item_id
    JOIN receipts r ON r.id = i.receipt_id
    JOIN transactions t ON t.id = r.transaction_id
    JOIN products pr ON pr.id = p.product_id
    JOIN stores st ON st.id = p.store_id
    WHERE r.status = 'CONFIRMED' AND $SPENDING
"""

data class SpendTotalRow(val purchases: Long, val cents: Long)

data class DailySpendRow(val day: LocalDate, val cents: Long)

data class CategorySpendRow(@ColumnInfo(name = "category_id") val categoryId: Long?, val cents: Long)

data class LocationSpendRow(
    @ColumnInfo(name = "store_id") val storeId: Long,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val address: String?,
    val visits: Long,
    val cents: Long,
    @ColumnInfo(name = "first_visit") val firstVisit: LocalDate,
    @ColumnInfo(name = "last_visit") val lastVisit: LocalDate,
)

data class ProductSpendRow(
    @ColumnInfo(name = "product_id") val productId: Long,
    val name: String,
    val purchases: Long,
    val cents: Long,
)

data class PricePointRow(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "product_name") val productName: String,
    val day: LocalDate,
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "store_name") val storeName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val address: String?,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    val unit: MeasureUnit,
    @ColumnInfo(name = "paid_cents") val paidCents: Long,
    @ColumnInfo(name = "normalized_dimension") val normalizedDimension: Dimension?,
    @ColumnInfo(name = "normalized_millicents") val normalizedMillicents: Long?,
)

/** Aggregations for spending analysis. All grouping happens in SQLite; Flows re-emit on any ledger or receipt change. */
@Dao
interface AnalysisDao {

    @Query("SELECT COUNT(*) AS purchases, COALESCE(SUM(-t.amount_cents), 0) AS cents FROM transactions t WHERE $SPENDING")
    fun observeTotals(from: LocalDate, to: LocalDate): Flow<SpendTotalRow>

    @Query("SELECT t.occurred_on AS day, SUM(-t.amount_cents) AS cents FROM transactions t WHERE $SPENDING GROUP BY t.occurred_on")
    fun observeDaily(from: LocalDate, to: LocalDate): Flow<List<DailySpendRow>>

    @Query("SELECT category_id, SUM(cents) AS cents FROM ($CATEGORY_PARTS) GROUP BY category_id")
    fun observeCategories(from: LocalDate, to: LocalDate): Flow<List<CategorySpendRow>>

    @Query("SELECT day, SUM(cents) AS cents FROM ($CATEGORY_PARTS) WHERE category_id IN (:categoryIds) GROUP BY day")
    fun observeCategoryDaily(from: LocalDate, to: LocalDate, categoryIds: List<Long>): Flow<List<DailySpendRow>>

    @Query(
        """
        SELECT st.id AS store_id, st.name, st.normalized_name, st.address, COUNT(*) AS visits,
            SUM(-t.amount_cents) AS cents, MIN(t.occurred_on) AS first_visit, MAX(t.occurred_on) AS last_visit
        $RECEIPT_SPENDING
        GROUP BY st.id
        """,
    )
    fun observeLocations(from: LocalDate, to: LocalDate): Flow<List<LocationSpendRow>>

    @Query("SELECT t.occurred_on AS day, SUM(-t.amount_cents) AS cents $RECEIPT_SPENDING AND st.id IN (:storeIds) GROUP BY t.occurred_on")
    fun observeStoreDaily(from: LocalDate, to: LocalDate, storeIds: List<Long>): Flow<List<DailySpendRow>>

    /**
     * Item spending per product (after item discounts, before receipt-level tax and
     * coupons). Optional filters: [anyStore] or one of [storeIds]; [anyCategory] or one of
     * [categoryIds] (the item's category).
     */
    @Query(
        """
        SELECT p.product_id, pr.name, COUNT(*) AS purchases, SUM(p.paid_cents) AS cents
        $ITEM_SPENDING
            AND (:anyStore OR p.store_id IN (:storeIds))
            AND (:anyCategory OR i.category_id IN (:categoryIds))
        GROUP BY p.product_id
        ORDER BY cents DESC, pr.name
        """,
    )
    fun observeProducts(
        from: LocalDate,
        to: LocalDate,
        anyStore: Boolean,
        storeIds: List<Long>,
        anyCategory: Boolean,
        categoryIds: List<Long>,
    ): Flow<List<ProductSpendRow>>

    /** Purchases with prices: one product ([anyProduct] false) and/or a set of stores ([anyStore] false). */
    @Query(
        """
        SELECT p.product_id, pr.name AS product_name, t.occurred_on AS day, st.id AS store_id, st.name AS store_name,
            st.normalized_name, st.address, p.quantity_milli, p.unit, p.paid_cents,
            p.normalized_dimension, p.normalized_millicents
        $ITEM_SPENDING
            AND (:anyProduct OR p.product_id = :productId)
            AND (:anyStore OR p.store_id IN (:storeIds))
        ORDER BY t.occurred_on DESC, p.id DESC
        """,
    )
    fun observePricePoints(
        from: LocalDate,
        to: LocalDate,
        anyProduct: Boolean,
        productId: Long,
        anyStore: Boolean,
        storeIds: List<Long>,
    ): Flow<List<PricePointRow>>
}
