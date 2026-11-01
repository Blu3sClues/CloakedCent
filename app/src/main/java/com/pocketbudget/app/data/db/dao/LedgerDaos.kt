package com.pocketbudget.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.pocketbudget.app.data.db.entity.AccountEntity
import com.pocketbudget.app.data.db.entity.CategoryEntity
import com.pocketbudget.app.data.db.entity.PayeeEntity
import com.pocketbudget.app.data.db.entity.TransactionEntity
import com.pocketbudget.app.data.db.entity.TransactionRevisionEntity
import com.pocketbudget.app.data.db.entity.TransferEntity
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Ledger activity sums for an account (excluding opening balance). */
data class AccountBalanceRow(
    @Embedded val account: AccountEntity,
    @ColumnInfo(name = "current_cents") val currentCents: Long,
    @ColumnInfo(name = "cleared_cents") val clearedCents: Long,
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
)

private const val BALANCE_SELECT = """
    SELECT a.*,
        COALESCE(SUM(CASE WHEN t.status IN ('PENDING', 'CLEARED') THEN t.amount_cents END), 0) AS current_cents,
        COALESCE(SUM(CASE WHEN t.status = 'CLEARED' THEN t.amount_cents END), 0) AS cleared_cents,
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
}

@Dao
interface PayeeDao {
    @Insert
    suspend fun insert(payee: PayeeEntity): Long

    @Query("SELECT * FROM payees WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): PayeeEntity?
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
            COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN -t.amount_cents END), 0) AS expense_cents
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        WHERE t.is_deleted = 0 AND t.status <> 'SCHEDULED' AND a.include_in_totals = 1
            AND t.occurred_on BETWEEN :start AND :endInclusive
        """,
    )
    fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate): Flow<PeriodSummaryRow>

    @Query(
        """
        SELECT t.id, t.type, t.status, t.amount_cents, t.occurred_on,
            a.name AS account_name, c.name AS category_name, p.name AS payee_name,
            (SELECT a2.name FROM transactions t2 JOIN accounts a2 ON a2.id = t2.account_id
                WHERE t2.transfer_id = t.transfer_id AND t2.id <> t.id) AS counterpart_account_name
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN payees p ON p.id = t.payee_id
        WHERE t.is_deleted = 0 AND NOT (t.type = 'TRANSFER' AND t.amount_cents > 0)
        ORDER BY t.occurred_on DESC, t.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<TransactionListRow>>
}
