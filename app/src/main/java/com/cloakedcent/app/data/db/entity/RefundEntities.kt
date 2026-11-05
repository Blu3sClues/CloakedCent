package com.cloakedcent.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v7: refunds. A refund is a REFUND ledger entry (money back into the account)
// linked here to the expense it refunds; the expense itself is never changed. Both
// tables are append-only (LedgerSchema triggers).

@Entity(
    tableName = "refunds",
    foreignKeys = [
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["refund_transaction_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["original_transaction_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReceiptItemEntity::class, parentColumns = ["id"], childColumns = ["receipt_item_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["refund_transaction_id"], unique = true), Index("original_transaction_id"), Index("receipt_item_id")],
)
data class RefundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "refund_transaction_id") val refundTransactionId: Long,
    @ColumnInfo(name = "original_transaction_id") val originalTransactionId: Long,
    /** Set when a single purchased item was refunded; null for a refund of the whole expense. */
    @ColumnInfo(name = "receipt_item_id") val receiptItemId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Where a refund's money comes off spending: a category (null when the expense was
 * uncategorised) and, for receipts, the purchased item. Allocations of one refund add
 * up exactly to it.
 */
@Entity(
    tableName = "refund_allocations",
    foreignKeys = [
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["refund_transaction_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReceiptItemEntity::class, parentColumns = ["id"], childColumns = ["receipt_item_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("refund_transaction_id"), Index("category_id"), Index("receipt_item_id")],
)
data class RefundAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "refund_transaction_id") val refundTransactionId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "receipt_item_id") val receiptItemId: Long?,
    /** Money returned (positive), tax share included. */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    /** Pre-tax item value returned, for product analysis; null for non-item refunds. */
    @ColumnInfo(name = "item_net_cents") val itemNetCents: Long?,
    /** Quantity returned in thousandths of the item's unit; null when refunded by amount. */
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
