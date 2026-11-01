package com.pocketbudget.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pocketbudget.app.data.db.dao.AccountDao
import com.pocketbudget.app.data.db.dao.AppMetadataDao
import com.pocketbudget.app.data.db.dao.BudgetDao
import com.pocketbudget.app.data.db.dao.ReceiptDao
import com.pocketbudget.app.data.db.dao.RecurringDao
import com.pocketbudget.app.data.db.dao.CategoryDao
import com.pocketbudget.app.data.db.dao.PayeeDao
import com.pocketbudget.app.data.db.dao.TransactionDao
import com.pocketbudget.app.data.db.entity.AccountEntity
import com.pocketbudget.app.data.db.entity.AppMetadataEntity
import com.pocketbudget.app.data.db.entity.BudgetAlertEntity
import com.pocketbudget.app.data.db.entity.BudgetEntity
import com.pocketbudget.app.data.db.entity.BudgetLimitEntity
import com.pocketbudget.app.data.db.entity.PriceObservationEntity
import com.pocketbudget.app.data.db.entity.ProductAliasEntity
import com.pocketbudget.app.data.db.entity.ProductEntity
import com.pocketbudget.app.data.db.entity.ReceiptEntity
import com.pocketbudget.app.data.db.entity.ReceiptImageEntity
import com.pocketbudget.app.data.db.entity.ReceiptItemEntity
import com.pocketbudget.app.data.db.entity.ReceiptOcrLineEntity
import com.pocketbudget.app.data.db.entity.StoreEntity
import com.pocketbudget.app.data.db.entity.TransactionSplitEntity
import com.pocketbudget.app.data.db.entity.RecurringOccurrenceEntity
import com.pocketbudget.app.data.db.entity.RecurringScheduleEntity
import com.pocketbudget.app.data.db.entity.CategoryEntity
import com.pocketbudget.app.data.db.entity.PayeeEntity
import com.pocketbudget.app.data.db.entity.TransactionEntity
import com.pocketbudget.app.data.db.entity.TransactionRevisionEntity
import com.pocketbudget.app.data.db.entity.TransferEntity

/**
 * The single, SQLCipher-encrypted application database.
 * Build instances through [PocketBudgetDatabaseFactory] only.
 */
@Database(
    entities = [
        AppMetadataEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        PayeeEntity::class,
        TransferEntity::class,
        TransactionEntity::class,
        TransactionRevisionEntity::class,
        BudgetEntity::class,
        BudgetLimitEntity::class,
        BudgetAlertEntity::class,
        RecurringScheduleEntity::class,
        RecurringOccurrenceEntity::class,
        StoreEntity::class,
        ReceiptEntity::class,
        ReceiptImageEntity::class,
        ReceiptOcrLineEntity::class,
        ProductEntity::class,
        ReceiptItemEntity::class,
        ProductAliasEntity::class,
        PriceObservationEntity::class,
        TransactionSplitEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PocketBudgetDatabase : RoomDatabase() {

    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun receiptDao(): ReceiptDao

    companion object {
        const val NAME = "pocket_budget.db"
    }
}
