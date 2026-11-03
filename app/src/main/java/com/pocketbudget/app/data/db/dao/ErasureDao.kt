package com.pocketbudget.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/** A receipt item's text and product, for recomputing what aliases it taught. */
data class AliasSourceRow(
    val rawText: String,
    val description: String,
    val productId: Long?,
)

/**
 * Statements that permanently erase deleted data. Deletion is final: nothing a user
 * deletes may remain in the database. Each erasure first marks its rows (a transaction's
 * `is_deleted`, a receipt's DELETED or DISCARDED status) and the database triggers allow
 * removing only marked rows, all within one transaction, so no mark survives a commit.
 * Run through `Erasure`, never directly.
 */
@Dao
interface ErasureDao {

    // Transactions

    @Query("UPDATE transactions SET is_deleted = 1 WHERE id = :id")
    suspend fun markTransaction(id: Long)

    /** A receipt kept when its expense is deleted stays confirmed, without an expense. */
    @Query("UPDATE receipts SET transaction_id = NULL, updated_at = :now WHERE transaction_id = :transactionId")
    suspend fun detachReceipt(transactionId: Long, now: Long)

    @Query("DELETE FROM transaction_splits WHERE transaction_id = :transactionId")
    suspend fun deleteSplits(transactionId: Long)

    @Query("DELETE FROM refund_allocations WHERE refund_transaction_id = :transactionId")
    suspend fun deleteRefundAllocations(transactionId: Long)

    @Query("DELETE FROM refunds WHERE refund_transaction_id = :transactionId")
    suspend fun deleteRefunds(transactionId: Long)

    /** The occurrence keeps only its due date, so the schedule does not generate it again. */
    @Query("UPDATE recurring_occurrences SET transaction_id = NULL WHERE transaction_id = :transactionId")
    suspend fun releaseOccurrences(transactionId: Long)

    @Query("DELETE FROM transaction_revisions WHERE transaction_id = :transactionId")
    suspend fun deleteRevisions(transactionId: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteTransfer(id: Long)

    // Receipts

    /** Splits of a kept expense stay; only their link to the deleted receipt goes. */
    @Query("UPDATE transaction_splits SET receipt_id = NULL WHERE receipt_id = :receiptId")
    suspend fun unlinkSplits(receiptId: Long)

    @Query("UPDATE refunds SET receipt_item_id = NULL WHERE receipt_item_id IN (SELECT id FROM receipt_items WHERE receipt_id = :receiptId)")
    suspend fun unlinkRefunds(receiptId: Long)

    /** Refund allocations keep their category and amount; the item's details go. */
    @Query(
        """
        UPDATE refund_allocations SET receipt_item_id = NULL, item_net_cents = NULL, quantity_milli = NULL
        WHERE receipt_item_id IN (SELECT id FROM receipt_items WHERE receipt_id = :receiptId)
        """,
    )
    suspend fun unlinkRefundAllocations(receiptId: Long)

    @Query("DELETE FROM price_observations WHERE receipt_item_id IN (SELECT id FROM receipt_items WHERE receipt_id = :receiptId)")
    suspend fun deletePrices(receiptId: Long)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceipt(id: Long)

    // Names left without any use

    @Query(
        """
        SELECT i.raw_text AS rawText, i.description, i.product_id AS productId FROM receipt_items i
        JOIN receipts r ON r.id = i.receipt_id
        WHERE r.store_id = :storeId AND r.status = 'CONFIRMED'
        """,
    )
    suspend fun getConfirmedItemsAt(storeId: Long): List<AliasSourceRow>

    @Query("DELETE FROM product_aliases WHERE id = :id")
    suspend fun deleteAlias(id: Long)

    @Query(
        """
        DELETE FROM product_aliases WHERE product_id = :productId
            AND NOT EXISTS (SELECT 1 FROM receipt_items WHERE product_id = :productId)
            AND NOT EXISTS (SELECT 1 FROM price_observations WHERE product_id = :productId)
        """,
    )
    suspend fun deleteAliasesOfUnusedProduct(productId: Long)

    @Query(
        """
        DELETE FROM products WHERE id = :id
            AND NOT EXISTS (SELECT 1 FROM receipt_items WHERE product_id = :id)
            AND NOT EXISTS (SELECT 1 FROM price_observations WHERE product_id = :id)
            AND NOT EXISTS (SELECT 1 FROM product_aliases WHERE product_id = :id)
        """,
    )
    suspend fun deleteProductIfUnused(id: Long)

    @Query(
        """
        DELETE FROM stores WHERE id = :id
            AND NOT EXISTS (SELECT 1 FROM receipts WHERE store_id = :id)
            AND NOT EXISTS (SELECT 1 FROM price_observations WHERE store_id = :id)
            AND NOT EXISTS (SELECT 1 FROM product_aliases WHERE store_id = :id)
        """,
    )
    suspend fun deleteStoreIfUnused(id: Long)

    @Query(
        """
        DELETE FROM payees WHERE id = :id
            AND NOT EXISTS (SELECT 1 FROM transactions WHERE payee_id = :id)
            -- A kept transaction's edit history may still name it.
            AND NOT EXISTS (SELECT 1 FROM transaction_revisions WHERE payee_id = :id)
            AND NOT EXISTS (SELECT 1 FROM stores WHERE payee_id = :id)
            AND NOT EXISTS (SELECT 1 FROM recurring_schedules WHERE payee_id = :id)
        """,
    )
    suspend fun deletePayeeIfUnused(id: Long)
}
