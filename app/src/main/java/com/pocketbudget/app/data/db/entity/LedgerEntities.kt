package com.pocketbudget.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.PayeeType
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import java.time.LocalDate

// Monetary columns are INTEGER cents. Foreign keys use RESTRICT so referenced
// records (accounts, categories, payees, transfers) can never be hard-deleted
// out from under ledger history. Extra rules live in LedgerSchema triggers.

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    @ColumnInfo(name = "opening_balance_cents") val openingBalanceCents: Long,
    @ColumnInfo(name = "include_in_totals") val includeInTotals: Boolean,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parent_id")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "payees",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["default_category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["name"], unique = true), Index("default_category_id")],
)
data class PayeeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PayeeType,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Groups the two ledger legs of a transfer. */
@Entity(
    tableName = "transfers",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["from_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["to_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["request_id"], unique = true),
        Index("from_account_id"),
        Index("to_account_id"),
    ],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "from_account_id") val fromAccountId: Long,
    @ColumnInfo(name = "to_account_id") val toAccountId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One ledger entry against one account. [amountCents] is signed: inflow positive,
 * outflow negative. Transfers are two entries sharing [transferId]. Rows are never
 * hard-deleted; edits and deletes write a [TransactionRevisionEntity] first.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PayeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["payee_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TransferEntity::class,
            parentColumns = ["id"],
            childColumns = ["transfer_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["request_id"], unique = true),
        Index("account_id"),
        Index("category_id"),
        Index("payee_id"),
        Index("transfer_id"),
        Index("occurred_on"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val type: TransactionType,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val status: TransactionStatus,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "payee_id") val payeeId: Long? = null,
    @ColumnInfo(name = "transfer_id") val transferId: Long? = null,
    val note: String? = null,
    val revision: Int = 1,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

enum class RevisionAction { UPDATE, STATUS_CHANGE, DELETE }

/** Immutable snapshot of a transaction's values before a change. */
@Entity(
    tableName = "transaction_revisions",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["transaction_id", "revision"], unique = true)],
)
data class TransactionRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    val revision: Int,
    val action: RevisionAction,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val type: TransactionType,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val status: TransactionStatus,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "payee_id") val payeeId: Long?,
    val note: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)
