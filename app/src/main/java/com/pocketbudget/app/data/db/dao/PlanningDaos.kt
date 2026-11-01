package com.pocketbudget.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.pocketbudget.app.data.db.entity.BudgetAlertEntity
import com.pocketbudget.app.data.db.entity.BudgetEntity
import com.pocketbudget.app.data.db.entity.BudgetLimitEntity
import com.pocketbudget.app.data.db.entity.RecurringOccurrenceEntity
import com.pocketbudget.app.data.db.entity.RecurringScheduleEntity
import com.pocketbudget.app.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** A completed expense, for budget calculations. */
data class ExpenseRow(
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "spent_cents") val spentCents: Long,
)

/** A generated occurrence whose transaction is still scheduled. */
data class PendingOccurrenceRow(
    val id: Long,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    @ColumnInfo(name = "reminder_sent") val reminderSent: Boolean,
    @ColumnInfo(name = "overdue_sent") val overdueSent: Boolean,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    val name: String,
    val type: TransactionType,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
)

@Dao
interface BudgetDao {
    @Insert
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE state <> 'ARCHIVED' ORDER BY name COLLATE NOCASE")
    fun observeVisible(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE state <> 'ARCHIVED'")
    suspend fun getVisible(): List<BudgetEntity>

    @Upsert
    suspend fun upsertLimit(limit: BudgetLimitEntity)

    @Query("SELECT * FROM budget_limits WHERE budget_id = :budgetId ORDER BY effective_from")
    suspend fun getLimits(budgetId: Long): List<BudgetLimitEntity>

    @Query("SELECT * FROM budget_limits ORDER BY effective_from")
    fun observeAllLimits(): Flow<List<BudgetLimitEntity>>

    @Query("SELECT * FROM budget_limits ORDER BY effective_from")
    suspend fun getAllLimits(): List<BudgetLimitEntity>

    /** Returns -1 if this threshold was already raised for the period. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: BudgetAlertEntity): Long

    /** Completed, categorised expenses on or after [from]; scheduled and deleted entries never count. */
    @Query(
        """
        SELECT occurred_on, category_id, -amount_cents AS spent_cents FROM transactions
        WHERE type = 'EXPENSE' AND status <> 'SCHEDULED' AND is_deleted = 0
            AND category_id IS NOT NULL AND occurred_on >= :from
        """,
    )
    fun observeExpenses(from: LocalDate): Flow<List<ExpenseRow>>

    @Query(
        """
        SELECT occurred_on, category_id, -amount_cents AS spent_cents FROM transactions
        WHERE type = 'EXPENSE' AND status <> 'SCHEDULED' AND is_deleted = 0
            AND category_id IS NOT NULL AND occurred_on >= :from
        """,
    )
    suspend fun getExpenses(from: LocalDate): List<ExpenseRow>
}

@Dao
interface RecurringDao {
    @Insert
    suspend fun insert(schedule: RecurringScheduleEntity): Long

    @Update
    suspend fun update(schedule: RecurringScheduleEntity)

    @Query("SELECT * FROM recurring_schedules WHERE id = :id")
    suspend fun getById(id: Long): RecurringScheduleEntity?

    @Query("SELECT * FROM recurring_schedules ORDER BY state, name COLLATE NOCASE")
    fun observeAll(): Flow<List<RecurringScheduleEntity>>

    @Query("SELECT * FROM recurring_schedules WHERE state = 'ACTIVE'")
    suspend fun getActive(): List<RecurringScheduleEntity>

    /** Returns -1 when the occurrence already exists (duplicate prevention). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(occurrence: RecurringOccurrenceEntity): Long

    @Query("SELECT * FROM recurring_occurrences WHERE schedule_id = :scheduleId AND due_date = :dueDate")
    suspend fun getOccurrence(scheduleId: Long, dueDate: LocalDate): RecurringOccurrenceEntity?

    @Query("UPDATE recurring_occurrences SET skipped = 1 WHERE id = :id")
    suspend fun markSkipped(id: Long)

    @Query("UPDATE recurring_occurrences SET reminder_sent = 1 WHERE id = :id")
    suspend fun markReminderSent(id: Long)

    @Query("UPDATE recurring_occurrences SET overdue_sent = 1 WHERE id = :id")
    suspend fun markOverdueSent(id: Long)

    @Query(
        """
        SELECT o.id, o.schedule_id, o.due_date, o.reminder_sent, o.overdue_sent, o.transaction_id, s.name, s.type, t.amount_cents
        FROM recurring_occurrences o
        JOIN recurring_schedules s ON s.id = o.schedule_id
        JOIN transactions t ON t.id = o.transaction_id
        WHERE o.skipped = 0 AND t.status = 'SCHEDULED' AND t.is_deleted = 0
        """,
    )
    suspend fun getPendingOccurrences(): List<PendingOccurrenceRow>

    @Query("SELECT COUNT(*) FROM recurring_occurrences WHERE schedule_id = :scheduleId")
    suspend fun countOccurrences(scheduleId: Long): Int
}
