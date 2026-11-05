package com.cloakedcent.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.cloakedcent.app.data.db.entity.AccountEntity
import com.cloakedcent.app.data.db.entity.CategoryEntity
import com.cloakedcent.app.data.db.entity.PayeeEntity
import com.cloakedcent.app.data.db.entity.TransactionEntity
import com.cloakedcent.app.data.db.entity.TransactionRevisionEntity
import com.cloakedcent.app.data.db.entity.TransactionSplitEntity
import com.cloakedcent.app.data.db.entity.TransferEntity
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.RevisionAction
import com.cloakedcent.app.domain.model.TransactionStatus
import com.cloakedcent.app.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Ledger activity sums for an account (excluding opening balance). */
data class AccountBalanceRow(
    @Embedded val account: AccountEntity,
    @ColumnInfo(name = "current_cents") val currentCents: Long,
    @ColumnInfo(name = "projected_cents") val projectedCents: Long,
)

data class PeriodSummaryRow(
    @ColumnInfo(name = "income_cents") val incomeCents: Long,
    @ColumnInfo(name = "expense_cents") val expenseCents: Long,
)

data class TransactionListRow(
    val id: Long,
    val type: TransactionType,
    val status: TransactionStatus,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "payee_name") val payeeName: String?,
    @ColumnInfo(name = "counterpart_account_name") val counterpartAccountName: String?,
    @ColumnInfo(name = "is_adjustment") val isAdjustment: Boolean,
    @ColumnInfo(name = "refunded_cents") val refundedCents: Long,
)

private const val BALANCE_SELECT = """
    SELECT a.*,
        COALESCE(SUM(CASE WHEN t.status <> 'SCHEDULED' THEN t.amount_cents END), 0) AS current_cents,
        COALESCE(SUM(t.amount_cents), 0) AS projected_cents
    FROM accounts a
    LEFT JOIN transactions t ON t.account_id = a.id AND t.is_deleted = 0
"""

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("UPDATE accounts SET is_archived = 1, updated_at = :now WHERE id = :id")
    suspend fun archive(id: Long, now: Long)

    @Query(
        """
        UPDATE accounts SET name = :name, type = :type, include_in_totals = :includeInTotals, updated_at = :now
        WHERE id = :id AND is_archived = 0
        """,
    )
    suspend fun update(id: Long, name: String, type: AccountType, includeInTotals: Boolean, now: Long): Int

    @Query("$BALANCE_SELECT WHERE a.is_archived = 0 GROUP BY a.id ORDER BY a.name COLLATE NOCASE")
    fun observeBalances(): Flow<List<AccountBalanceRow>>

    @Query("$BALANCE_SELECT WHERE a.id = :id GROUP BY a.id")
    suspend fun getBalance(id: Long): AccountBalanceRow?
}

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("UPDATE categories SET parent_id = :parentId WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long?)

    @Query("SELECT * FROM categories WHERE is_archived = 0 ORDER BY kind, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    // Usage and deletion. [ids] is always a category with all its sub-categories.

    /** Transactions using the categories directly, through a receipt split, or through a refund allocation. */
    @Query(
        """
        SELECT COUNT(*) FROM transactions WHERE category_id IN (:ids)
            OR id IN (SELECT transaction_id FROM transaction_splits WHERE category_id IN (:ids))
            OR id IN (SELECT refund_transaction_id FROM refund_allocations WHERE category_id IN (:ids))
        """,
    )
    suspend fun countTransactions(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM budgets WHERE category_id IN (:ids)")
    suspend fun countBudgets(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM recurring_schedules WHERE category_id IN (:ids)")
    suspend fun countRecurring(ids: List<Long>): Int

    /**
     * Marks categories for deletion (reusing is_archived, which nothing else sets). The
     * triggers allow moving locked records — splits, refund allocations, confirmed
     * receipts and their items — away from marked categories only. Marked categories are
     * deleted in the same transaction.
     */
    @Query("UPDATE categories SET is_archived = 1 WHERE id IN (:ids)")
    suspend fun markForDeletion(ids: List<Long>)

    @Query("SELECT * FROM transactions WHERE category_id IN (:ids)")
    suspend fun getTransactionsIn(ids: List<Long>): List<TransactionEntity>

    @Query("SELECT DISTINCT transaction_id FROM transaction_splits WHERE category_id IN (:ids)")
    suspend fun getSplitTransactionsIn(ids: List<Long>): List<Long>

    @Query("SELECT * FROM transaction_splits WHERE transaction_id = :transactionId")
    suspend fun getSplits(transactionId: Long): List<TransactionSplitEntity>

    /** Unmarked splits first: the delete trigger allows them only while a marked split remains. */
    @Query("DELETE FROM transaction_splits WHERE transaction_id = :transactionId AND category_id NOT IN (:ids)")
    suspend fun deleteUnmarkedSplits(transactionId: Long, ids: List<Long>)

    @Query("DELETE FROM transaction_splits WHERE transaction_id = :transactionId")
    suspend fun deleteSplits(transactionId: Long)

    @Insert
    suspend fun insertSplit(split: TransactionSplitEntity): Long

    @Query("UPDATE refund_allocations SET category_id = :to WHERE category_id IN (:ids)")
    suspend fun moveRefundAllocations(ids: List<Long>, to: Long)

    @Query("UPDATE receipt_items SET category_id = :to WHERE category_id IN (:ids)")
    suspend fun moveReceiptItems(ids: List<Long>, to: Long?)

    @Query("UPDATE receipts SET default_category_id = :to WHERE default_category_id IN (:ids)")
    suspend fun moveReceiptDefaults(ids: List<Long>, to: Long?)

    @Query("UPDATE products SET default_category_id = :to WHERE default_category_id IN (:ids)")
    suspend fun moveProductDefaults(ids: List<Long>, to: Long?)

    @Query("UPDATE payees SET default_category_id = :to WHERE default_category_id IN (:ids)")
    suspend fun movePayeeDefaults(ids: List<Long>, to: Long?)

    @Query("UPDATE budgets SET category_id = :to, updated_at = :now WHERE category_id IN (:ids)")
    suspend fun moveBudgets(ids: List<Long>, to: Long, now: Long)

    @Query("UPDATE recurring_schedules SET category_id = :to, updated_at = :now WHERE category_id IN (:ids)")
    suspend fun moveRecurring(ids: List<Long>, to: Long, now: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PayeeDao {
    @Insert
    suspend fun insert(payee: PayeeEntity): Long

    @Query("SELECT * FROM payees WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): PayeeEntity?

    @Query("SELECT * FROM payees ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PayeeEntity>>
}

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Insert
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Insert
    suspend fun insertRevision(revision: TransactionRevisionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE request_id = :requestId")
    suspend fun getByRequestId(requestId: String): TransactionEntity?

    @Query("SELECT * FROM transfers WHERE request_id = :requestId")
    suspend fun getTransferByRequestId(requestId: String): TransferEntity?

    @Query("SELECT * FROM transactions WHERE transfer_id = :transferId ORDER BY amount_cents")
    suspend fun getTransferLegs(transferId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transaction_revisions WHERE transaction_id = :transactionId ORDER BY revision")
    suspend fun getRevisions(transactionId: Long): List<TransactionRevisionEntity>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_cents END), 0) AS income_cents,
            COALESCE(SUM(CASE WHEN t.type IN ('EXPENSE', 'REFUND') THEN -t.amount_cents END), 0) AS expense_cents
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        WHERE t.is_deleted = 0 AND t.status <> 'SCHEDULED' AND a.include_in_totals = 1
            AND t.occurred_on BETWEEN :start AND :endInclusive
        """,
    )
    fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate): Flow<PeriodSummaryRow>

    @Query(
        """
        $LIST_SELECT
        WHERE t.is_deleted = 0 AND t.status <> 'SCHEDULED' AND NOT (t.type = 'TRANSFER' AND t.amount_cents > 0)
        ORDER BY t.occurred_on DESC, t.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<TransactionListRow>>

    /**
     * Null parameters mean "any". Transfers appear once (outgoing leg) unless an
     * account is selected, in which case that account's leg is shown.
     * [query] must be a LIKE pattern escaped with '\'.
     */
    @Query(
        """
        $LIST_SELECT
        WHERE t.is_deleted = 0
            AND ((:accountId IS NULL AND NOT (t.type = 'TRANSFER' AND t.amount_cents > 0))
                OR t.account_id = :accountId)
            AND (:start IS NULL OR t.occurred_on >= :start)
            AND (:endInclusive IS NULL OR t.occurred_on <= :endInclusive)
            AND (:filterCategory = 0 OR t.category_id IN (:categoryIds))
            AND (:payeeId IS NULL OR t.payee_id = :payeeId)
            AND (:scheduled IS NULL OR (t.status = 'SCHEDULED') = :scheduled)
            AND (:query IS NULL
                OR p.name LIKE :query ESCAPE '\' OR c.name LIKE :query ESCAPE '\'
                OR t.note LIKE :query ESCAPE '\' OR a.name LIKE :query ESCAPE '\')
        ORDER BY t.occurred_on DESC, t.id DESC
        LIMIT $SEARCH_LIMIT
        """,
    )
    fun search(
        query: String?,
        start: LocalDate?,
        endInclusive: LocalDate?,
        accountId: Long?,
        filterCategory: Boolean,
        categoryIds: List<Long>,
        payeeId: Long?,
        scheduled: Boolean?,
    ): Flow<List<TransactionListRow>>

    @Query(
        """
        SELECT t.id, t.type, t.status, t.amount_cents, t.occurred_on, t.account_id,
            a.name AS account_name, t.category_id, c.name AS category_name,
            t.payee_id, p.name AS payee_name, t.note, t.revision, t.is_deleted, t.is_adjustment,
            t.created_at, t.updated_at,
            t2.account_id AS counterpart_account_id, a2.name AS counterpart_account_name
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN payees p ON p.id = t.payee_id
        LEFT JOIN transactions t2 ON t2.transfer_id = t.transfer_id AND t2.id <> t.id
        LEFT JOIN accounts a2 ON a2.id = t2.account_id
        WHERE t.id = :id
        """,
    )
    fun observeDetail(id: Long): Flow<TransactionDetailRow?>

    @Query(
        """
        SELECT r.revision, r.action, r.amount_cents, r.status, r.occurred_on, r.note, r.recorded_at,
            c.name AS category_name, p.name AS payee_name
        FROM transaction_revisions r
        LEFT JOIN categories c ON c.id = r.category_id
        LEFT JOIN payees p ON p.id = r.payee_id
        WHERE r.transaction_id = :transactionId
        ORDER BY r.revision
        """,
    )
    fun observeRevisions(transactionId: Long): Flow<List<RevisionRow>>
}

const val SEARCH_LIMIT = 500

private const val LIST_SELECT = """
    SELECT t.id, t.type, t.status, t.amount_cents, t.occurred_on,
        a.name AS account_name, c.name AS category_name, p.name AS payee_name,
        (SELECT a2.name FROM transactions t2 JOIN accounts a2 ON a2.id = t2.account_id
            WHERE t2.transfer_id = t.transfer_id AND t2.id <> t.id) AS counterpart_account_name,
        t.is_adjustment,
        (SELECT COALESCE(SUM(rt.amount_cents), 0) FROM refunds rf JOIN transactions rt ON rt.id = rf.refund_transaction_id
            WHERE rf.original_transaction_id = t.id AND rt.is_deleted = 0) AS refunded_cents
    FROM transactions t
    JOIN accounts a ON a.id = t.account_id
    LEFT JOIN categories c ON c.id = t.category_id
    LEFT JOIN payees p ON p.id = t.payee_id
"""

data class TransactionDetailRow(
    val id: Long,
    val type: TransactionType,
    val status: TransactionStatus,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "payee_id") val payeeId: Long?,
    @ColumnInfo(name = "payee_name") val payeeName: String?,
    val note: String?,
    val revision: Int,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "is_adjustment") val isAdjustment: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "counterpart_account_id") val counterpartAccountId: Long?,
    @ColumnInfo(name = "counterpart_account_name") val counterpartAccountName: String?,
)

data class RevisionRow(
    val revision: Int,
    val action: RevisionAction,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val status: TransactionStatus,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    val note: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "payee_name") val payeeName: String?,
)
