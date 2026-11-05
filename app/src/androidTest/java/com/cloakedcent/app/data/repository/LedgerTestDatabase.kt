package com.cloakedcent.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cloakedcent.app.data.db.CloakedCentDatabase
import com.cloakedcent.app.data.db.CloakedCentDatabaseFactory
import org.junit.rules.ExternalResource
import java.security.SecureRandom

/** A fresh encrypted database plus real repositories for each test. */
class LedgerTestDatabase : ExternalResource() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "ledger-test.db"

    lateinit var db: CloakedCentDatabase
    lateinit var accounts: RoomAccountRepository
    lateinit var categories: RoomCategoryRepository
    lateinit var payees: RoomPayeeRepository
    lateinit var ledger: RoomTransactionRepository

    override fun before() {
        context.deleteDatabase(name)
        db = CloakedCentDatabaseFactory.create(context, ByteArray(32).also(SecureRandom()::nextBytes), name = name)
        val provider = { db }
        accounts = RoomAccountRepository(provider)
        categories = RoomCategoryRepository(provider)
        payees = RoomPayeeRepository(provider)
        ledger = RoomTransactionRepository(provider)
    }

    override fun after() {
        db.close()
        context.deleteDatabase(name)
    }

    fun execSql(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, args)

    fun count(sql: String): Long = db.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }
}
