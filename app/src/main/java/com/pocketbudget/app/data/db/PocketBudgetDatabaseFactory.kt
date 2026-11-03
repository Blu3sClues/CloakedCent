package com.pocketbudget.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.time.Clock

/**
 * Builds the encrypted Room database. This is the only place Room is configured,
 * so encryption, migrations and integrity settings cannot be skipped by accident.
 */
object PocketBudgetDatabaseFactory {

    /**
     * @param passphrase raw SQLCipher passphrase from DatabaseKeyStore. SQLCipher
     *   keeps a reference for reopening connections, so callers must not zero it.
     */
    fun create(
        context: Context,
        passphrase: ByteArray,
        name: String = PocketBudgetDatabase.NAME,
        clock: Clock = Clock.systemUTC(),
    ): PocketBudgetDatabase {
        loadSqlCipher()
        return Room.databaseBuilder(context, PocketBudgetDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*Migrations.ALL)
            .addCallback(IntegrityCallback(clock))
            .build()
    }

    /** SQLCipher's native library must be loaded before any database is opened. */
    fun loadSqlCipher() {
        System.loadLibrary("sqlcipher")
    }

    private class IntegrityCallback(private val clock: Clock) : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO app_metadata (meta_key, meta_value) VALUES (?, ?)",
                arrayOf(AppMetadataKeys.CREATED_AT, clock.millis().toString()),
            )
            LedgerSchema.seedDefaultCategories(db, clock.millis())
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            // SQLite leaves foreign key enforcement off by default; financial
            // records rely on it, so force it on for every open.
            db.execSQL("PRAGMA foreign_keys = ON")
            // Deleted data is overwritten, never left in free pages.
            SecureErase.enableSecureDelete(db)
            LedgerSchema.createTriggers(db)
            SecureErase.vacuumIfRequested(db)
        }
    }
}
