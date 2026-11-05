package com.cloakedcent.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cloakedcent.app.data.db.dao.AccountDao
import com.cloakedcent.app.data.db.dao.AnalysisDao
import com.cloakedcent.app.data.db.dao.AppMetadataDao
import com.cloakedcent.app.data.db.dao.ErasureDao
import com.cloakedcent.app.data.db.dao.BudgetDao
import com.cloakedcent.app.data.db.dao.ReceiptDao
import com.cloakedcent.app.data.db.dao.RefundDao
import com.cloakedcent.app.data.db.dao.RecurringDao
import com.cloakedcent.app.data.db.dao.CategoryDao
import com.cloakedcent.app.data.db.dao.PayeeDao
import com.cloakedcent.app.data.db.dao.TransactionDao
import com.cloakedcent.app.data.db.entity.AccountEntity
import com.cloakedcent.app.data.db.entity.AppMetadataEntity
import com.cloakedcent.app.data.db.entity.BudgetAlertEntity
import com.cloakedcent.app.data.db.entity.BudgetEntity
import com.cloakedcent.app.data.db.entity.BudgetLimitEntity
import com.cloakedcent.app.data.db.entity.PriceObservationEntity
import com.cloakedcent.app.data.db.entity.ProductAliasEntity
import com.cloakedcent.app.data.db.entity.ProductEntity
import com.cloakedcent.app.data.db.entity.ReceiptEntity
import com.cloakedcent.app.data.db.entity.ReceiptImageEntity
import com.cloakedcent.app.data.db.entity.ReceiptItemEntity
import com.cloakedcent.app.data.db.entity.ReceiptOcrLineEntity
import com.cloakedcent.app.data.db.entity.StoreEntity
import com.cloakedcent.app.data.db.entity.TransactionSplitEntity
import com.cloakedcent.app.data.db.entity.RefundAllocationEntity
import com.cloakedcent.app.data.db.entity.RefundEntity
import com.cloakedcent.app.data.db.entity.RecurringOccurrenceEntity
import com.cloakedcent.app.data.db.entity.RecurringScheduleEntity
import com.cloakedcent.app.data.db.entity.CategoryEntity
import com.cloakedcent.app.data.db.entity.PayeeEntity
import com.cloakedcent.app.data.db.entity.TransactionEntity
import com.cloakedcent.app.data.db.entity.TransactionRevisionEntity
import com.cloakedcent.app.data.db.entity.TransferEntity

/**
 * The single, SQLCipher-encrypted application database.
 * Build instances through [CloakedCentDatabaseFactory] only.
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
        RefundEntity::class,
        RefundAllocationEntity::class,
    ],
    version = CloakedCentDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CloakedCentDatabase : RoomDatabase() {

    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun refundDao(): RefundDao
    abstract fun erasureDao(): ErasureDao

    companion object {
        const val NAME = "cloaked_cent.db"

        /** Current schema; backups from newer schemas are refused. */
        const val SCHEMA_VERSION = 11
    }
}
