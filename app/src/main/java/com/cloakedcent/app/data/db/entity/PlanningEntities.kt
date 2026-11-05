package com.cloakedcent.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cloakedcent.app.domain.model.BudgetPeriodType
import com.cloakedcent.app.domain.model.BudgetState
import com.cloakedcent.app.domain.model.RecurrenceFrequency
import com.cloakedcent.app.domain.model.RecurringKind
import com.cloakedcent.app.domain.model.ScheduleState
import com.cloakedcent.app.domain.model.TransactionType
import java.time.LocalDate

// Budgets and schedules are never hard-deleted (archived/cancelled instead), so
// every foreign key is RESTRICT, matching the ledger tables.

@Entity(
    tableName = "budgets",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("category_id")],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "period_type") val periodType: BudgetPeriodType,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate?,
    @ColumnInfo(name = "cycle_start") val cycleStart: LocalDate,
    val rollover: Boolean,
    /** Comma-separated percentages, e.g. "50,75,90,100". */
    @ColumnInfo(name = "alert_thresholds") val alertThresholds: String,
    val state: BudgetState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Limit history: a change takes effect from [effectiveFrom]'s period. */
@Entity(
    tableName = "budget_limits",
    foreignKeys = [ForeignKey(BudgetEntity::class, ["id"], ["budget_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["budget_id", "effective_from"], unique = true)],
)
data class BudgetLimitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "budget_id") val budgetId: Long,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
)

/** Alerts already raised, so each threshold notifies once per period. */
@Entity(
    tableName = "budget_alerts",
    primaryKeys = ["budget_id", "period_start", "threshold"],
    foreignKeys = [ForeignKey(BudgetEntity::class, ["id"], ["budget_id"], onDelete = ForeignKey.RESTRICT)],
)
data class BudgetAlertEntity(
    @ColumnInfo(name = "budget_id") val budgetId: Long,
    @ColumnInfo(name = "period_start") val periodStart: LocalDate,
    val threshold: Int,
    @ColumnInfo(name = "raised_at") val raisedAt: Long,
)

@Entity(
    tableName = "recurring_schedules",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(PayeeEntity::class, ["id"], ["payee_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("account_id"), Index("to_account_id"), Index("category_id"), Index("payee_id")],
)
data class RecurringScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: RecurringKind,
    val type: TransactionType,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "to_account_id") val toAccountId: Long?,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "variable_amount") val variableAmount: Boolean,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "payee_id") val payeeId: Long?,
    val note: String?,
    val frequency: RecurrenceFrequency,
    @ColumnInfo(name = "interval_count") val intervalCount: Int,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate?,
    val state: ScheduleState,
    @ColumnInfo(name = "generated_through") val generatedThrough: LocalDate?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One row per occurrence that has been generated or skipped. The unique
 * (schedule_id, due_date) key, together with the generated transaction's request
 * id, makes generation idempotent across restarts.
 */
@Entity(
    tableName = "recurring_occurrences",
    foreignKeys = [
        ForeignKey(RecurringScheduleEntity::class, ["id"], ["schedule_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(TransactionEntity::class, ["id"], ["transaction_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["schedule_id", "due_date"], unique = true), Index("transaction_id")],
)
data class RecurringOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    @ColumnInfo(name = "transaction_id") val transactionId: Long?,
    val skipped: Boolean,
    @ColumnInfo(name = "reminder_sent") val reminderSent: Boolean = false,
    @ColumnInfo(name = "overdue_sent") val overdueSent: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
