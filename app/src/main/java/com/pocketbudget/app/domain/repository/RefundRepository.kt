package com.pocketbudget.app.domain.repository

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.RefundSummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Refunds of completed expenses. Each refund is a new REFUND ledger entry linked to
 * the original, recorded atomically and idempotently on its request id; the original
 * is never changed. Writes throw RefundException or LedgerException.
 */
interface RefundRepository {
    /** Refund position of an expense; null unless it is a completed, live expense. */
    fun observeSummary(transactionId: Long): Flow<RefundSummary?>

    /** The expense a refund entry refunds; null for any other entry. */
    fun observeOriginal(refundTransactionId: Long): Flow<Long?>

    /** Refunds [amount] of the expense (up to what remains). Returns the refund entry id. */
    suspend fun refundTransaction(
        transactionId: Long,
        amount: Money,
        occurredOn: LocalDate,
        requestId: String,
        note: String? = null,
    ): Long

    /**
     * Refunds one receipt item, either [quantityMilli] of it (priced from what the item
     * cost) or a given [amount]; exactly one must be set. Returns the refund entry id.
     */
    suspend fun refundItem(
        receiptItemId: Long,
        quantityMilli: Long?,
        amount: Money?,
        occurredOn: LocalDate,
        requestId: String,
    ): Long
}
