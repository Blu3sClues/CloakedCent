package com.pocketbudget.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pocketbudget.app.data.db.dao.AccountDao
import com.pocketbudget.app.data.db.dao.AppMetadataDao
import com.pocketbudget.app.data.db.dao.CategoryDao
import com.pocketbudget.app.data.db.dao.PayeeDao
import com.pocketbudget.app.data.db.dao.TransactionDao
import com.pocketbudget.app.data.db.entity.AccountEntity
import com.pocketbudget.app.data.db.entity.AppMetadataEntity
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
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PocketBudgetDatabase : RoomDatabase() {

    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val NAME = "pocket_budget.db"
    }
}
