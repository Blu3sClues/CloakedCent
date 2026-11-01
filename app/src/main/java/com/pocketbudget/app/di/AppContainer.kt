package com.pocketbudget.app.di

import android.content.Context
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.db.PocketBudgetDatabaseFactory
import com.pocketbudget.app.data.repository.LocalStorageRepository
import com.pocketbudget.app.data.repository.RoomAccountRepository
import com.pocketbudget.app.data.repository.RoomCategoryRepository
import com.pocketbudget.app.data.repository.RoomPayeeRepository
import com.pocketbudget.app.data.repository.RoomTransactionRepository
import com.pocketbudget.app.data.security.AndroidKeystoreKeyWrapper
import com.pocketbudget.app.data.security.DatabaseKeyStore
import com.pocketbudget.app.dev.SampleData
import com.pocketbudget.app.domain.repository.AccountRepository
import com.pocketbudget.app.domain.repository.CategoryRepository
import com.pocketbudget.app.domain.repository.PayeeRepository
import com.pocketbudget.app.data.backup.BackupManager
import com.pocketbudget.app.data.backup.RestoreJournal
import com.pocketbudget.app.data.backup.StorageLocations
import com.pocketbudget.app.data.receipt.MediaKeyProvider
import com.pocketbudget.app.data.receipt.ReceiptImageStore
import com.pocketbudget.app.data.receipt.TesseractOcrEngine
import com.pocketbudget.app.data.repository.RoomAnalysisRepository
import com.pocketbudget.app.data.repository.RoomRefundRepository
import com.pocketbudget.app.data.repository.RoomProductRepository
import com.pocketbudget.app.data.repository.RoomReceiptRepository
import com.pocketbudget.app.domain.repository.AnalysisRepository
import com.pocketbudget.app.domain.repository.BackupRepository
import com.pocketbudget.app.domain.repository.RefundRepository
import com.pocketbudget.app.domain.repository.ProductRepository
import com.pocketbudget.app.domain.repository.ReceiptRepository
import com.pocketbudget.app.data.repository.RoomBudgetRepository
import com.pocketbudget.app.data.repository.RoomRecurringRepository
import com.pocketbudget.app.data.settings.SharedPreferencesSettingsRepository
import com.pocketbudget.app.domain.repository.BudgetRepository
import com.pocketbudget.app.domain.repository.RecurringRepository
import com.pocketbudget.app.domain.repository.SettingsRepository
import com.pocketbudget.app.domain.repository.StorageRepository
import com.pocketbudget.app.domain.repository.TransactionRepository
import java.io.File

/**
 * Manual dependency container, created once by PocketBudgetApp.
 * Everything is lazy so nothing touches disk or Keystore on the main thread
 * at startup; repositories trigger creation on their IO dispatcher.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val keyWrapper by lazy { AndroidKeystoreKeyWrapper() }

    /** Live data and the private areas used to stage and apply a restore (noBackupFilesDir is never in platform backups). */
    private val storage by lazy {
        val private = appContext.noBackupFilesDir
        StorageLocations(
            databaseFile = appContext.getDatabasePath(PocketBudgetDatabase.NAME),
            keyFile = File(private, DATABASE_KEY_FILE),
            receiptsDir = File(private, RECEIPT_DIR),
            stagingDir = File(private, "restore-staging"),
            previousDir = File(private, "restore-previous"),
            journalFile = File(private, "restore.journal"),
        )
    }

    private val databaseKeyStore by lazy { DatabaseKeyStore(keyFile = storage.keyFile, wrapper = keyWrapper) }

    // A failed initialisation is not cached by lazy, so "Try again" re-attempts it.
    private val database: PocketBudgetDatabase by lazy {
        // A confirmed restore is applied here, before anything opens the database.
        RestoreJournal.recover(storage)
        val databaseExists = appContext.getDatabasePath(PocketBudgetDatabase.NAME).exists()
        val passphrase = databaseKeyStore.getOrCreatePassphrase(databaseExists)
        PocketBudgetDatabaseFactory.create(appContext, passphrase)
    }

    val storageRepository: StorageRepository by lazy {
        LocalStorageRepository(database = { database })
    }

    val accountRepository: AccountRepository by lazy { RoomAccountRepository({ database }) }
    val categoryRepository: CategoryRepository by lazy { RoomCategoryRepository({ database }) }
    val payeeRepository: PayeeRepository by lazy { RoomPayeeRepository({ database }) }
    val transactionRepository: TransactionRepository by lazy { RoomTransactionRepository({ database }) }

    val settingsRepository: SettingsRepository by lazy { SharedPreferencesSettingsRepository(appContext) }
    val budgetRepository: BudgetRepository by lazy { RoomBudgetRepository({ database }) }
    val recurringRepository: RecurringRepository by lazy { RoomRecurringRepository({ database }, transactionRepository) }

    private val mediaKeys by lazy { MediaKeyProvider { database } }

    // noBackupFilesDir: receipt images never leave the device through platform backup.
    private val receiptImages by lazy { ReceiptImageStore(File(appContext.noBackupFilesDir, RECEIPT_DIR), mediaKeys::get) }

    val receiptRepository: ReceiptRepository by lazy {
        RoomReceiptRepository(
            database = { database },
            transactions = transactionRepository,
            payees = payeeRepository,
            images = receiptImages,
            ocr = TesseractOcrEngine(appContext),
        )
    }
    val productRepository: ProductRepository by lazy { RoomProductRepository({ database }) }
    val analysisRepository: AnalysisRepository by lazy { RoomAnalysisRepository({ database }) }
    val refundRepository: RefundRepository by lazy { RoomRefundRepository({ database }) }

    val backupRepository: BackupRepository by lazy {
        BackupManager(
            context = appContext,
            locations = storage,
            database = { database },
            passphrase = { databaseKeyStore.getOrCreatePassphrase(databaseExists = true) },
            keyWrapper = keyWrapper,
            appVersion = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull().orEmpty(),
        )
    }

    /** Debug builds only (see src/debug); null in release. */
    val sampleSeeder: (suspend () -> Unit)? = SampleData.seeder(this)

    private companion object {
        const val DATABASE_KEY_FILE = "database.key"
        const val RECEIPT_DIR = "receipts"
    }
}
