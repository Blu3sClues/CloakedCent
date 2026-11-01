package com.pocketbudget.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pocketbudget.app.data.db.entity.ReceiptItemEntity
import com.pocketbudget.app.data.db.entity.RefundAllocationEntity
import com.pocketbudget.app.data.db.entity.RefundEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class RefundRow(
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    @ColumnInfo(name = "item_description") val itemDescription: String?,
)

/** What has been refunded of one receipt item, counting live refunds only. */
data class ItemRefundRow(
    @ColumnInfo(name = "receipt_item_id") val receiptItemId: Long,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "net_cents") val netCents: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
)

private const val LIVE_REFUNDS_OF = """
    FROM refunds rf JOIN transactions rt ON rt.id = rf.refund_transaction_id
    WHERE rf.original_transaction_id = :originalId AND rt.is_deleted = 0
"""

private const val ITEM_REFUNDS = """
    SELECT a.receipt_item_id, SUM(a.amount_cents) AS amount_cents,
        SUM(COALESCE(a.item_net_cents, 0)) AS net_cents, SUM(COALESCE(a.quantity_milli, 0)) AS quantity_milli
    FROM refund_allocations a JOIN transactions rt ON rt.id = a.refund_transaction_id
    WHERE rt.is_deleted = 0 AND a.receipt_item_id IN (SELECT id FROM receipt_items WHERE receipt_id = :receiptId)
    GROUP BY a.receipt_item_id
"""

@Dao
interface RefundDao {
    @Insert
    suspend fun insert(refund: RefundEntity): Long

    @Insert
    suspend fun insertAllocation(allocation: RefundAllocationEntity): Long

    @Query("SELECT COALESCE(SUM(rt.amount_cents), 0) $LIVE_REFUNDS_OF")
    suspend fun refundedTotal(originalId: Long): Long

    @Query(
        """
        SELECT rt.id AS transaction_id, rt.amount_cents, rt.occurred_on, i.description AS item_description
        FROM refunds rf JOIN transactions rt ON rt.id = rf.refund_transaction_id
        LEFT JOIN receipt_items i ON i.id = rf.receipt_item_id
        WHERE rf.original_transaction_id = :originalId AND rt.is_deleted = 0
        ORDER BY rt.occurred_on DESC, rt.id DESC
        """,
    )
    fun observeRefunds(originalId: Long): Flow<List<RefundRow>>

    @Query(ITEM_REFUNDS)
    suspend fun getItemRefunds(receiptId: Long): List<ItemRefundRow>

    @Query(ITEM_REFUNDS)
    fun observeItemRefunds(receiptId: Long): Flow<List<ItemRefundRow>>

    @Query("SELECT * FROM receipt_items WHERE id = :id")
    suspend fun getItem(id: Long): ReceiptItemEntity?

    /** The expense a refund entry belongs to, if it is a refund. */
    @Query("SELECT original_transaction_id FROM refunds WHERE refund_transaction_id = :refundTransactionId")
    fun observeOriginal(refundTransactionId: Long): Flow<Long?>
}
