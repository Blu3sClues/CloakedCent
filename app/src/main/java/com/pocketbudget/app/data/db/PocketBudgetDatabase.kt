package com.pocketbudget.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pocketbudget.app.data.db.dao.AppMetadataDao
import com.pocketbudget.app.data.db.entity.AppMetadataEntity

/**
 * The single, SQLCipher-encrypted application database.
 * Build instances through [PocketBudgetDatabaseFactory] only.
 */
@Database(
    entities = [AppMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PocketBudgetDatabase : RoomDatabase() {

    abstract fun appMetadataDao(): AppMetadataDao

    companion object {
        const val NAME = "pocket_budget.db"
    }
}
