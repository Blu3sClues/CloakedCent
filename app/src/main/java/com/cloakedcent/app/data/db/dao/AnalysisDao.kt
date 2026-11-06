package com.cloakedcent.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.cloakedcent.app.domain.model.Dimension
import com.cloakedcent.app.domain.model.MeasureUnit
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

/** Live refund entries, aliased `t`, dated in [:from, :to]. They reduce spending on their own date. */
private const val REFUNDING = """
    t.type = 'REFUND' AND t.is_deleted = 0 AND t.occurred_on BETWEEN :from AND :to
"""

/**
 * Spending by category: split expenses through their splits, the rest (and any part
 * not covered by splits) under the expense's own category, NULL when uncategorised.
 * Each cent of an expense appears in exactly one row; refunds appear as negative rows
 * in the categories they were allocated to.
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
    UNION ALL
    SELECT t.occurred_on AS day, a.category_id AS category_id, -a.amount_cents AS cents
    FROM refund_allocations a JOIN transactions t ON t.id = a.refund_transaction_id
    WHERE $REFUNDING
"""

/** Confirmed receipts whose expense counts as spending; one row per expense. */
private const val RECEIPT_SPENDING = """
    FROM receipts r
    JOIN transactions t ON t.id = r.transaction_id
    JOIN stores st ON st.id = r.store_id
    WHERE r.status = 'CONFIRMED' AND $SPENDING
"""

/** Refunds of confirmed receipts' expenses, with the receipt's store aliased `st`. */
private const val STORE_REFUNDS = """
    FROM refunds rf
    JOIN transactions t ON t.id = rf.refund_transaction_id
    JOIN receipts r ON r.transaction_id = rf.original_transaction_id AND r.status = 'CONFIRMED'
    JOIN stores st ON st.id = r.store_id
    WHERE $REFUNDING
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

/** [cents] is net of refunds; [refundedCents] is the refunds alone. Refunds are never purchases. */
data class SpendTotalRow(val purchases: Long, val cents: Long, @ColumnInfo(name = "refunded_cents") val refundedCents: Long)

data class StoreRefundRow(
    @ColumnInfo(name = "store_id") val storeId: Long,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val address: String?,
    val cents: Long,
)

data class ProductRefundRow(@ColumnInfo(name = "product_id") val productId: Long, val name: String, val cents: Long)

data class DailySpendRow(val day: LocalDate, val cents: Long)

/** One expense's spending within some categories; [name] is null when it has neither payee nor category. */
data class CategoryTransactionRow(val id: Long, val name: String?, val day: LocalDate, val cents: Long)

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

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM transactions t WHERE $SPENDING) AS purchases,
            (SELECT COALESCE(SUM(-t.amount_cents), 0) FROM transactions t WHERE $SPENDING)
                - (SELECT COALESCE(SUM(t.amount_cents), 0) FROM transactions t WHERE $REFUNDING) AS cents,
            (SELECT COALESCE(SUM(t.amount_cents), 0) FROM transactions t WHERE $REFUNDING) AS refunded_cents
        """,
    )
    fun observeTotals(from: LocalDate, to: LocalDate): Flow<SpendTotalRow>

    @Query(
        """
        SELECT day, SUM(cents) AS cents FROM (
            SELECT t.occurred_on AS day, -t.amount_cents AS cents FROM transactions t WHERE $SPENDING
            UNION ALL
            SELECT t.occurred_on AS day, -t.amount_cents AS cents FROM transactions t WHERE $REFUNDING
        ) GROUP BY day
        """,
    )
    fun observeDaily(from: LocalDate, to: LocalDate): Flow<List<DailySpendRow>>

    @Query("SELECT category_id, SUM(cents) AS cents FROM ($CATEGORY_PARTS) GROUP BY category_id")
    fun observeCategories(from: LocalDate, to: LocalDate): Flow<List<CategorySpendRow>>

    @Query("SELECT day, SUM(cents) AS cents FROM ($CATEGORY_PARTS) WHERE category_id IN (:categoryIds) GROUP BY day")
    fun observeCategoryDaily(from: LocalDate, to: LocalDate, categoryIds: List<Long>): Flow<List<DailySpendRow>>

    /**
     * The largest expenses in some categories. A split expense counts only its parts in these
     * categories, as the totals do, so it appears once with those parts added together.
     */
    @Query(
        """
        SELECT t.id AS id, COALESCE(p.name, c.name) AS name, t.occurred_on AS day, SUM(parts.cents) AS cents
        FROM (
            SELECT transaction_id AS id, amount_cents AS cents FROM transaction_splits WHERE category_id IN (:categoryIds)
            UNION ALL
            SELECT id, cents FROM (
                SELECT e.id AS id, -e.amount_cents - (SELECT COALESCE(SUM(amount_cents), 0) FROM transaction_splits
                    WHERE transaction_id = e.id) AS cents
                FROM transactions e WHERE e.category_id IN (:categoryIds)
            ) WHERE cents > 0
        ) parts
        JOIN transactions t ON t.id = parts.id
        LEFT JOIN payees p ON p.id = t.payee_id
        LEFT JOIN categories c ON c.id = t.category_id
        WHERE $SPENDING
        GROUP BY t.id
        ORDER BY cents DESC, day DESC, t.id DESC
        LIMIT :limit
        """,
    )
    fun observeCategoryTransactions(from: LocalDate, to: LocalDate, categoryIds: List<Long>, limit: Int): Flow<List<CategoryTransactionRow>>

    @Query(
        """
        SELECT st.id AS store_id, st.name, st.normalized_name, st.address, COUNT(*) AS visits,
            SUM(-t.amount_cents) AS cents, MIN(t.occurred_on) AS first_visit, MAX(t.occurred_on) AS last_visit
        $RECEIPT_SPENDING
        GROUP BY st.id
        """,
    )
    fun observeLocations(from: LocalDate, to: LocalDate): Flow<List<LocationSpendRow>>

    @Query(
        """
        SELECT day, SUM(cents) AS cents FROM (
            SELECT t.occurred_on AS day, -t.amount_cents AS cents $RECEIPT_SPENDING AND st.id IN (:storeIds)
            UNION ALL
            SELECT t.occurred_on AS day, -t.amount_cents AS cents $STORE_REFUNDS AND st.id IN (:storeIds)
        ) GROUP BY day
        """,
    )
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

    @Query("SELECT st.id AS store_id, st.name, st.normalized_name, st.address, SUM(t.amount_cents) AS cents $STORE_REFUNDS GROUP BY st.id")
    fun observeStoreRefunds(from: LocalDate, to: LocalDate): Flow<List<StoreRefundRow>>

    /** Pre-tax item value refunded per product, with the same optional filters as [observeProducts]. */
    @Query(
        """
        SELECT p.product_id, pr.name, SUM(a.item_net_cents) AS cents
        FROM refund_allocations a
        JOIN transactions t ON t.id = a.refund_transaction_id
        JOIN price_observations p ON p.receipt_item_id = a.receipt_item_id
        JOIN receipt_items i ON i.id = a.receipt_item_id
        JOIN products pr ON pr.id = p.product_id
        WHERE $REFUNDING AND a.item_net_cents IS NOT NULL
            AND (:anyStore OR p.store_id IN (:storeIds))
            AND (:anyCategory OR i.category_id IN (:categoryIds))
        GROUP BY p.product_id
        """,
    )
    fun observeProductRefunds(
        from: LocalDate,
        to: LocalDate,
        anyStore: Boolean,
        storeIds: List<Long>,
        anyCategory: Boolean,
        categoryIds: List<Long>,
    ): Flow<List<ProductRefundRow>>

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
