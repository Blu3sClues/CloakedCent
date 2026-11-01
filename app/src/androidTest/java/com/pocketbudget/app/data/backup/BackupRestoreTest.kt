package com.pocketbudget.app.data.backup

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.receipt.MediaKeyProvider
import com.pocketbudget.app.data.receipt.PdfReceiptRenderer
import com.pocketbudget.app.data.receipt.ReceiptImageStore
import com.pocketbudget.app.data.receipt.ReceiptPhotos
import com.pocketbudget.app.data.repository.PDF_TEXT_ENGINE
import com.pocketbudget.app.data.repository.ReceiptRepositoryTest
import com.pocketbudget.app.data.repository.RoomAccountRepository
import com.pocketbudget.app.data.repository.RoomAnalysisRepository
import com.pocketbudget.app.data.repository.RoomBudgetRepository
import com.pocketbudget.app.data.repository.RoomCategoryRepository
import com.pocketbudget.app.data.repository.RoomPayeeRepository
import com.pocketbudget.app.data.repository.RoomProductRepository
import com.pocketbudget.app.data.repository.RoomReceiptRepository
import com.pocketbudget.app.data.repository.RoomRecurringRepository
import com.pocketbudget.app.data.repository.RoomRefundRepository
import com.pocketbudget.app.data.repository.RoomTransactionRepository
import com.pocketbudget.app.data.security.DatabaseKeyException
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.AnalysisPeriods
import com.pocketbudget.app.domain.model.BudgetDraft
import com.pocketbudget.app.domain.model.BudgetPeriodType
import com.pocketbudget.app.domain.model.ConfirmTarget
import com.pocketbudget.app.domain.model.DatePreset
import com.pocketbudget.app.domain.model.DateRange
import com.pocketbudget.app.domain.model.ImportOutcome
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.model.ReceiptItemDraft
import com.pocketbudget.app.domain.model.ReceiptReview
import com.pocketbudget.app.domain.model.RecurrenceFrequency
import com.pocketbudget.app.domain.model.RecurringDraft
import com.pocketbudget.app.domain.model.RecurringKind
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.BackupException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Backups and restores between isolated test installations (each with its own directory
 * and its own stand-in Keystore key). The app's real data is never used.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.noBackupFilesDir, "backup-tests").apply { deleteRecursively() }
    private val today = LocalDate.of(2026, 11, 20)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val november = AnalysisPeriods.resolve(DatePreset.CUSTOM, today, DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
    private val password = "correct horse battery"

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PocketBudgetDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(ByteArray(32).also { it.fill(7) }),
    )

    /** No test may hang: a stuck backup or restore fails the test instead. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val installations = mutableListOf<TestInstallation>()

    private fun installation(name: String) = TestInstallation(context, File(root, name)).also { installations += it }

    @After
    fun tearDown() {
        installations.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun pdfReceipt(): File {
        val lines = listOf(
            "CORNER PHARMACY", "45 King Street West", "2026-11-12 18:05",
            "VITAMIN C 500MG      8.99", "HAND SOAP            3.50", "TISSUES 2 PK         4.20",
            "SUBTOTAL            16.69", "TAX                  2.17", "TOTAL               18.86",
        )
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 9f; color = Color.BLACK }
        val page = document.startPage(PdfDocument.PageInfo.Builder(227, lines.size * 12 + 40, 1).create())
        lines.forEachIndexed { i, line -> page.canvas.drawText(line, 12f, 24f + i * 12, paint) }
        document.finishPage(page)
        return File(root, "receipt.pdf").also { root.mkdirs(); it.outputStream().use(document::writeTo); document.close() }
    }

    /** A realistic dataset: accounts, a budget, a recurring bill, a photo and a PDF receipt with splits, products, prices and a refund. */
    private fun populate(install: TestInstallation) = runBlocking {
        val db = install.db
        val ledger = RoomTransactionRepository({ db }, clock)
        val accounts = RoomAccountRepository({ db })
        val categories = RoomCategoryRepository({ db }).observeAll().first().associateBy { it.name }
        val receipts = RoomReceiptRepository(
            { db }, ledger, RoomPayeeRepository({ db }),
            ReceiptImageStore(install.locations.receiptsDir, MediaKeyProvider { db }::get),
            ReceiptRepositoryTest.FakeOcr(),
        )
        val checking = accounts.create("Checking", AccountType.CHECKING, Money(250_000))
        val savings = accounts.create("Savings", AccountType.SAVINGS, Money(1_000_000))
        ledger.record(NewTransaction("salary", checking, TransactionType.INCOME, Money(320_000), LocalDate.of(2026, 11, 1), categoryId = categories.getValue("Salary").id))
        ledger.record(NewTransaction("rent", checking, TransactionType.EXPENSE, Money(110_000), LocalDate.of(2026, 11, 2), categoryId = categories.getValue("Housing").id))
        RoomBudgetRepository({ db }, clock).create(BudgetDraft("Food", categories.getValue("Food").id, Money(50_000), BudgetPeriodType.MONTHLY, LocalDate.of(2026, 11, 1)))
        RoomRecurringRepository({ db }, ledger, clock).create(
            RecurringDraft("Internet", RecurringKind.UTILITY, TransactionType.EXPENSE, checking, amount = Money(6_000), categoryId = categories.getValue("Utilities").id, frequency = RecurrenceFrequency.MONTHLY, startDate = LocalDate.of(2026, 11, 25)),
        )

        // A two-page photo receipt, confirmed with items in two categories (a split expense).
        val photo = (receipts.import(listOf(PageInput(ReceiptPhotos.render(ReceiptPhotos.GROCERY)), PageInput(ReceiptPhotos.render(listOf("THANK YOU"), ReceiptPhotos.Style(seed = 9)))), "photo") as ImportOutcome.Created).receiptId
        val groceries = categories.getValue("Groceries").id
        val photoReview = ReceiptReview(
            storeName = "Fresh Mart", storeAddress = "123 Main St", purchasedOn = LocalDate.of(2026, 11, 14),
            subtotal = Money(2_112), tax = Money(36), total = Money(2_148),
            items = listOf(
                ReceiptItemDraft(rawText = "MILK 2% 1L", description = "Milk 2% 1L", lineTotal = Money(349), categoryId = groceries),
                ReceiptItemDraft(rawText = "BREAD", description = "Bread", lineTotal = Money(279), categoryId = groceries),
                ReceiptItemDraft(rawText = "BANANAS", description = "Bananas", quantityMilli = 1_250, unit = MeasureUnit.KG, unitPrice = Money(174), lineTotal = Money(218), categoryId = groceries),
                ReceiptItemDraft(rawText = "YOGURT GREEK", description = "Greek Yogurt", quantityMilli = 3_000, unitPrice = Money(129), lineTotal = Money(387), categoryId = groceries),
                ReceiptItemDraft(rawText = "DISH SOAP", description = "Dish Soap", lineTotal = Money(500), discount = Money(50), categoryId = categories.getValue("Housing").id),
                ReceiptItemDraft(rawText = "EGGS LARGE 12", description = "Eggs Large 12", lineTotal = Money(429), categoryId = groceries),
            ),
        )
        receipts.saveReview(photo, photoReview)
        val photoExpense = receipts.confirm(photo, photoReview, ConfirmTarget.NewExpense(checking))

        // A PDF receipt, read from its own text and confirmed.
        val pages = ParcelFileDescriptor.open(pdfReceipt(), ParcelFileDescriptor.MODE_READ_ONLY).use(PdfReceiptRenderer::render)
        val pdf = (receipts.import(pages.images.mapIndexed { i, image -> PageInput(image, embeddedText = pages.text[i]) }, "pdf") as ImportOutcome.Created).receiptId
        receipts.runOcr(pdf, dayFirst = false)
        val pdfReview = receipts.observeDetail(pdf).first()!!.review.let { r -> r.copy(items = r.items.map { it.copy(categoryId = categories.getValue("Health").id) }) }
        receipts.confirm(pdf, pdfReview, ConfirmTarget.NewExpense(savings))

        // A refund of one item.
        val yogurt = db.receiptDao().getItems(photo).single { it.description == "Greek Yogurt" }.id
        RoomRefundRepository({ db }, clock).refundItem(yogurt, 1_000, null, LocalDate.of(2026, 11, 16), "refund-yogurt")
        photoExpense
    }

    /** Everything a user would see, as comparable values. */
    private fun snapshot(install: TestInstallation): Map<String, Any?> = runBlocking {
        val db = install.db
        val q = { sql: String -> db.openHelper.readableDatabase.query(sql).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }) } } }
        val images = ReceiptImageStore(install.locations.receiptsDir, MediaKeyProvider { db }::get)
        mapOf(
            "balances" to RoomAccountRepository({ db }).observeBalances().first().map { "${it.account.name}=${it.current.cents}/${it.projected.cents}" },
            "summary" to RoomTransactionRepository({ db }).observePeriodSummary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).first(),
            "analysis" to RoomAnalysisRepository({ db }).observeOverview(november).first().let { listOf(it.total, it.purchases, it.refunds, it.topCategories.map { c -> c.name to c.total }, it.topProducts) },
            "budgets" to RoomBudgetRepository({ db }, clock).observeProgress(today).first().map { it.budget.name to it.current?.spent },
            "recurring" to q("SELECT name, amount_cents, frequency, start_date, state FROM recurring_schedules ORDER BY id"),
            "transactions" to q("SELECT id, type, amount_cents, status, occurred_on, category_id, payee_id, is_deleted FROM transactions ORDER BY id"),
            "revisions" to q("SELECT transaction_id, revision, action, amount_cents FROM transaction_revisions ORDER BY id"),
            "receipts" to q("SELECT id, status, store_name, total_cents, transaction_id, ocr_engine FROM receipts ORDER BY id"),
            "items" to q("SELECT receipt_id, description, raw_text, quantity_milli, line_total_cents, category_id, product_id FROM receipt_items ORDER BY id"),
            "splits" to q("SELECT transaction_id, category_id, amount_cents FROM transaction_splits ORDER BY id"),
            "products" to q("SELECT id, name, normalized_name, default_category_id FROM products ORDER BY id"),
            "prices" to q("SELECT product_id, store_id, observed_on, paid_cents, normalized_millicents FROM price_observations ORDER BY id"),
            "refunds" to q("SELECT refund_transaction_id, original_transaction_id, receipt_item_id FROM refunds ORDER BY id"),
            "ocr" to q("SELECT receipt_id, page, line_index, text FROM receipt_ocr_lines ORDER BY id"),
            "images" to db.receiptDao().getAllImageFileNames().sorted().map { name ->
                val row = q("SELECT content_sha256 FROM receipt_images WHERE file_name = '$name'").single()
                name to TestInstallation.sha(images.read(name, row))
            },
        )
    }

    /** Adds [count] receipt pages of 200 KB each, so backups span many 1 MB chunks. */
    private fun addImages(install: TestInstallation, count: Int) = runBlocking {
        val db = install.db
        val store = ReceiptImageStore(install.locations.receiptsDir, MediaKeyProvider { db }::get)
        val receiptId = db.receiptDao().insert(com.pocketbudget.app.data.db.entity.ReceiptEntity(requestId = "bulk-$count", status = com.pocketbudget.app.domain.model.ReceiptStatus.DRAFT, createdAt = 0, updatedAt = 0))
        repeat(count) { page ->
            val stored = store.write(Random(page).nextBytes(200 * 1024))
            db.receiptDao().insertImage(
                com.pocketbudget.app.data.db.entity.ReceiptImageEntity(
                    receiptId = receiptId, page = page, fileName = stored.fileName, sourceSha256 = "s$page", contentSha256 = stored.contentSha256,
                    perceptualHash = page.toLong(), width = 1, height = 1, byteSize = stored.byteSize, formatVersion = 1, createdAt = 0,
                ),
            )
        }
    }

    private fun backup(install: TestInstallation, kdf: BackupFormat.KdfParams = TestInstallation.FAST_KDF): ByteArray = runBlocking {
        ByteArrayOutputStream().also { install.manager(kdf).createBackup(it, password.toCharArray()) }.toByteArray()
    }

    private fun restore(target: TestInstallation, backup: ByteArray, pass: String = password, usableSpace: (File) -> Long = { it.usableSpace }) = runBlocking {
        val manager = target.manager(usableSpace = usableSpace)
        val preview = manager.prepareRestore(ByteArrayInputStream(backup), backup.size.toLong(), pass.toCharArray())
        manager.commitPreparedRestore()
        // As the app does: restart, which applies the restore before the database opens.
        target.close()
        target.open()
        preview
    }

    private inline fun <reified E : BackupException> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: BackupException) {
            if (e !is E) throw AssertionError("expected ${E::class.simpleName}, got ${e::class.simpleName}", e)
        }
    }

    @Test
    fun endToEndRestoreOnAFreshInstallationWithADifferentKeystoreKey() {
        val source = installation("source")
        populate(source)
        val expected = snapshot(source)
        // Production Argon2id parameters for the end-to-end run.
        val file = backup(source, BackupFormat.KdfParams())

        val fresh = installation("fresh")
        assertFalse(fresh.locations.databaseFile.exists())
        val preview = restore(fresh, file)
        assertEquals(2, preview.accounts)
        assertEquals(2, preview.receipts)
        assertEquals(3, preview.attachments) // two photo pages and one PDF page
        assertEquals(PocketBudgetDatabase.SCHEMA_VERSION, preview.schemaVersion)

        assertEquals(expected, snapshot(fresh))
        // The PDF receipt kept its own text reading.
        assertTrue((expected["receipts"] as List<*>).any { it.toString().endsWith(PDF_TEXT_ENGINE) })
        // The database key now belongs to the new installation's Keystore only.
        assertTrue(fresh.locations.keyFile.isFile)
        try {
            source.wrapper.unwrap(fresh.locations.keyFile.readBytes().copyOfRange(1, fresh.locations.keyFile.length().toInt()))
            fail("the source installation's key must not open the restored key file")
        } catch (_: DatabaseKeyException) {
        }
        // Nothing is left behind.
        assertFalse(fresh.locations.stagingDir.exists() || fresh.locations.previousDir.exists() || fresh.locations.journalFile.exists())
    }

    @Test
    fun restoreReplacesExistingDataCompletely() {
        val source = installation("source")
        populate(source)
        val expected = snapshot(source)
        val file = backup(source)

        val existing = installation("existing")
        runBlocking { RoomAccountRepository({ existing.db }).create("Old account", AccountType.CASH, Money(123)) }
        val oldKey = existing.locations.keyFile.readBytes()
        restore(existing, file)
        assertEquals(expected, snapshot(existing))
        assertFalse(oldKey.contentEquals(existing.locations.keyFile.readBytes()))
    }

    @Test
    fun backupsAreEncryptedWithUniqueSaltsAndNoncesAndCarryMetadata() {
        val source = installation("source")
        populate(source)
        val first = backup(source)
        val second = backup(source)
        val firstHeader = BackupFormat.readPreamble(ByteArrayInputStream(first)).first
        val secondHeader = BackupFormat.readPreamble(ByteArrayInputStream(second)).first
        assertFalse(firstHeader.salt.contentEquals(secondHeader.salt))
        assertFalse(firstHeader.noncePrefix.contentEquals(secondHeader.noncePrefix))
        assertNotEquals(first.toList().takeLast(64), second.toList().takeLast(64))

        // No recognisable plaintext: store names, item names and the SQLite header never appear.
        val text = String(first, Charsets.ISO_8859_1)
        listOf("Fresh Mart", "Greek Yogurt", "SQLite format", "CORNER PHARMACY", "Checking").forEach { assertFalse(it, text.contains(it)) }

        val entries = BackupArchive.read(first, password)
        val metadata = JSONObject(String(entries.first().bytes))
        assertEquals(BackupManager.METADATA, entries.first().name)
        assertEquals(BackupFormat.FORMAT_VERSION, metadata.getInt("formatVersion"))
        assertEquals(PocketBudgetDatabase.SCHEMA_VERSION, metadata.getInt("schemaVersion"))
        assertEquals("test-1.0", metadata.getString("appVersion"))
        assertTrue(metadata.getLong("createdAt") > 0)
        val names = entries.map { it.name }
        assertTrue(BackupManager.KEY in names && "database/${PocketBudgetDatabase.NAME}" in names && names.last() == BackupManager.MANIFEST)
        assertEquals(3, names.count { it.startsWith(BackupManager.RECEIPT_PREFIX) })
        // Receipt images inside the backup are still the encrypted files, byte for byte.
        entries.filter { it.name.startsWith(BackupManager.RECEIPT_PREFIX) }.forEach { entry ->
            assertTrue(File(source.locations.receiptsDir, entry.name.removePrefix(BackupManager.RECEIPT_PREFIX)).readBytes().contentEquals(entry.bytes))
        }
    }

    @Test
    fun largeBackupsSpanManyChunks() {
        val source = installation("source")
        addImages(source, 120)
        val file = backup(source)
        assertTrue("about 24 MB across many chunks: ${file.size}", file.size > 20 * 1024 * 1024)
        val target = installation("target")
        val preview = restore(target, file)
        assertEquals(120, preview.attachments)
        assertEquals(snapshot(source)["images"], snapshot(target)["images"])
    }

    @Test
    fun failedRestoresLeaveExistingDataUntouched() {
        val source = installation("source")
        populate(source)
        val file = backup(source)
        val existing = installation("existing")
        populate(existing)
        val before = snapshot(existing)
        existing.close()
        val files = existing.fingerprint()

        fun attempt(bytes: ByteArray, pass: String = password, space: (File) -> Long = { it.usableSpace }) = runBlocking {
            existing.manager(usableSpace = space).prepareRestore(ByteArrayInputStream(bytes), bytes.size.toLong(), pass.toCharArray())
        }
        expectFailure<BackupException.WrongPasswordOrDamaged> { attempt(file, "wrong password!") }
        expectFailure<BackupException.Incomplete> { attempt(file.copyOf(file.size - 100)) }
        expectFailure<BackupException.Incomplete> { attempt(file.copyOf(file.size / 2)) }
        // One flipped bit anywhere fails authentication: in the header, the first chunk, or later.
        for (position in listOf(20, 300, file.size / 2, file.size - 20)) {
            val tampered = file.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            try {
                attempt(tampered)
                fail("tampered at $position")
            } catch (e: BackupException) {
                assertTrue(e is BackupException.WrongPasswordOrDamaged || e is BackupException.Damaged)
            }
        }
        expectFailure<BackupException.Unsupported> { attempt(file.copyOf().also { it[4] = 9 }) }
        expectFailure<BackupException.Damaged> { attempt(Random(1).nextBytes(5000)) }
        expectFailure<BackupException.Damaged> { attempt(ByteArray(0)) }
        expectFailure<BackupException.InsufficientStorage> { attempt(file, space = { 1024L }) }

        // Nothing changed on disk, and the data reads exactly as before.
        assertEquals(files, existing.fingerprint())
        assertFalse(existing.locations.stagingDir.exists())
        assertEquals(before, snapshot(existing))
    }

    @Test
    fun maliciousAndInconsistentArchivesAreRejected() {
        val source = installation("source")
        populate(source)
        val entries = BackupArchive.read(backup(source), password)
        val target = installation("target")
        fun attempt(list: List<BackupArchive.Entry>, manifest: Boolean = true) = runBlocking {
            val bytes = BackupArchive.write(list, password, manifest)
            target.manager().prepareRestore(ByteArrayInputStream(bytes), null, password.toCharArray())
        }
        val image = entries.first { it.name.startsWith(BackupManager.RECEIPT_PREFIX) }
        // Path traversal and unknown entries.
        expectFailure<BackupException.Damaged> { attempt(entries + BackupArchive.Entry("receipts/../../database.key", ByteArray(4))) }
        expectFailure<BackupException.Damaged> { attempt(entries + BackupArchive.Entry("/data/evil", ByteArray(4))) }
        expectFailure<BackupException.Damaged> { attempt(entries.map { if (it == image) it.copy(name = "receipts/../${image.name.substringAfter('/')}") else it }) }
        // A receipt image the database refers to is missing.
        expectFailure<BackupException.MissingAttachment> { attempt(entries - image) }
        // Without a manifest, or with an entry that does not match it.
        expectFailure<BackupException.Damaged> { attempt(entries, manifest = false) }
        // A database key of the wrong size, or a different key for the database.
        expectFailure<BackupException.Damaged> { attempt(entries.map { if (it.name == BackupManager.KEY) it.copy(bytes = ByteArray(16)) else it }) }
        expectFailure<BackupException.Damaged> { attempt(entries.map { if (it.name == BackupManager.KEY) it.copy(bytes = ByteArray(32)) else it }) }
        // A backup from a newer schema.
        val newer = entries.map {
            if (it.name == BackupManager.METADATA) it.copy(bytes = JSONObject(String(it.bytes)).put("schemaVersion", 99).toString().toByteArray()) else it
        }
        expectFailure<BackupException.NewerVersion> { attempt(newer) }
        assertFalse(target.locations.databaseFile.exists() || target.locations.stagingDir.exists())
    }

    @Test
    fun olderSchemaBackupsAreMigrated() {
        // A version 6 database (before refunds), encrypted like the app's, with its key.
        val key = ByteArray(32).also { it.fill(7) }
        val name = "old-schema.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 6).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance_cents, include_in_totals, is_archived, created_at, updated_at) VALUES (1, 'Checking', 'CHECKING', 10000, 1, 0, 0, 0)")
            execSQL("INSERT INTO categories (id, name, kind, parent_id, is_archived, created_at) VALUES (5, 'Food', 'EXPENSE', NULL, 0, 0)")
            execSQL("INSERT INTO transactions (id, request_id, account_id, type, amount_cents, status, occurred_on, category_id, revision, is_deleted, is_adjustment, created_at, updated_at) VALUES (1, 'r1', 1, 'EXPENSE', -2500, 'SPENT', 20400, 5, 1, 0, 0, 0, 0)")
            execSQL("INSERT INTO stores (id, name, normalized_name, created_at, updated_at) VALUES (1, 'Walmart Supercenter', 'WALMART SUPERCENTER', 0, 0)")
            close()
        }
        val db = context.getDatabasePath(name).readBytes()
        val metadata = JSONObject().put("formatVersion", 1).put("schemaVersion", 6).put("appVersion", "0.6").put("createdAt", 1).put("database", PocketBudgetDatabase.NAME)
        val archive = BackupArchive.write(
            listOf(
                BackupArchive.Entry(BackupManager.METADATA, metadata.toString().toByteArray()),
                BackupArchive.Entry(BackupManager.KEY, key),
                BackupArchive.Entry("database/${PocketBudgetDatabase.NAME}", db),
            ),
            password,
        )
        context.deleteDatabase(name)
        val target = installation("target")
        val preview = restore(target, archive)
        assertEquals(6, preview.schemaVersion)
        runBlocking {
            assertEquals(Money(7_500), RoomAccountRepository({ target.db }).observeBalances().first().single().current)
            assertEquals(PocketBudgetDatabase.SCHEMA_VERSION, target.db.openHelper.readableDatabase.version)
            // The v7 migration ran: refund tables exist and store keys were recomputed.
            target.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM refunds").use { assertTrue(it.moveToFirst()) }
            target.db.openHelper.readableDatabase.query("SELECT normalized_name FROM stores").use { it.moveToFirst(); assertEquals("WALMART", it.getString(0)) }
        }
    }

    @Test
    fun interruptedRestoresAlwaysFinishCompletely() {
        val source = installation("source")
        populate(source)
        val expected = snapshot(source)
        val file = backup(source)
        // Crash after each possible step of the replacement, then start the app again.
        var crashAt = 0
        while (true) {
            val target = installation("crash-$crashAt")
            runBlocking { RoomAccountRepository({ target.db }).create("Before", AccountType.CASH, Money(1)) }
            val manager = target.manager()
            runBlocking { manager.prepareRestore(ByteArrayInputStream(file), null, password.toCharArray()); manager.commitPreparedRestore() }
            target.close()
            var crashed = false
            try {
                RestoreJournal.recover(target.locations) { step -> if (step == crashAt) { crashed = true; throw IllegalStateException("power loss") } }
            } catch (_: IllegalStateException) {
            }
            target.open()
            assertEquals("crash before step $crashAt", expected, snapshot(target))
            target.close()
            if (!crashed) break
            crashAt++
        }
        assertTrue("several steps were interrupted", crashAt >= 4)
    }

    @Test
    fun aRestoreWhoseStagingDisappearedNeverTouchesLiveData() {
        val source = installation("source")
        populate(source)
        val file = backup(source)
        val existing = installation("existing")
        populate(existing)
        val before = snapshot(existing)
        val manager = existing.manager()
        runBlocking { manager.prepareRestore(ByteArrayInputStream(file), null, password.toCharArray()); manager.commitPreparedRestore() }
        existing.close()
        existing.locations.stagingDir.deleteRecursively()
        existing.open()
        assertEquals(before, snapshot(existing))
        assertFalse(existing.locations.journalFile.exists())
    }

    @Test
    fun cancelledOperationsLeaveNothingBehind() {
        val source = installation("source")
        populate(source)
        addImages(source, 30) // about 6 MB, several chunks
        val file = backup(source)

        // Cancel a restore part-way through reading.
        val target = installation("target")
        runBlocking { RoomAccountRepository({ target.db }).create("Keep me", AccountType.CASH, Money(42)) }
        val before = snapshot(target)
        runBlocking {
            val reachedMiddle = CompletableDeferred<Unit>()
            val slow = object : FilterInputStream(ByteArrayInputStream(file)) {
                var read = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    // Small pieces, like a slow storage provider; pause once past the middle.
                    val n = super.read(b, off, minOf(len, 64 * 1024))
                    if (n > 0) read += n
                    if (read > file.size / 2) { reachedMiddle.complete(Unit); Thread.sleep(50) }
                    return n
                }
            }
            val job = async(Dispatchers.IO) { target.manager().prepareRestore(slow, null, password.toCharArray()) }
            cancelMidway(job, reachedMiddle)
        }
        assertFalse(target.locations.stagingDir.exists())
        assertEquals(before, snapshot(target))

        // Cancel a backup part-way: the partial file is never accepted as a backup.
        val partial = ByteArrayOutputStream()
        runBlocking {
            val reachedMiddle = CompletableDeferred<Unit>()
            val output = object : OutputStream() {
                override fun write(b: Int) = partial.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    partial.write(b, off, len)
                    if (partial.size() > file.size / 2) { reachedMiddle.complete(Unit); Thread.sleep(50) }
                }
            }
            val job = async(Dispatchers.IO) { source.manager().createBackup(output, password.toCharArray()) }
            cancelMidway(job, reachedMiddle)
        }
        assertTrue(partial.size() > 0)
        expectFailure<BackupException.Incomplete> {
            runBlocking { target.manager().prepareRestore(ByteArrayInputStream(partial.toByteArray()), null, password.toCharArray()) }
        }
        assertEquals(before, snapshot(target))
    }

    @Test
    fun backupsRefuseMissingImagesAndShortPasswords() {
        val source = installation("source")
        populate(source)
        expectFailure<BackupException.PasswordTooShort> { runBlocking { source.manager().createBackup(ByteArrayOutputStream(), "short".toCharArray()) } }
        source.locations.receiptsDir.listFiles()!!.first().delete()
        expectFailure<BackupException.MissingAttachment> { runBlocking { source.manager().createBackup(ByteArrayOutputStream(), password.toCharArray()) } }
        // The password array is wiped after use.
        val secret = password.toCharArray()
        runCatching { runBlocking { source.manager().createBackup(ByteArrayOutputStream(), secret) } }
        assertTrue(secret.all { it == '\u0000' })
    }

    /**
     * Cancels [job] once it reaches the middle. Bounded: if the job finishes or fails first,
     * the test fails with a message instead of waiting forever.
     */
    private suspend fun cancelMidway(job: Deferred<*>, reachedMiddle: CompletableDeferred<Unit>) {
        withTimeout(60_000) {
            while (!reachedMiddle.isCompleted && job.isActive) delay(10)
        }
        if (!(reachedMiddle.isCompleted && job.isActive)) {
            val outcome = if (job.isCompleted) runCatching { job.await() }.exceptionOrNull()?.let { "failed: ${it::class.simpleName}" } ?: "finished" else "active"
            fail("the operation ended before it could be cancelled midway ($outcome, middle reached: ${reachedMiddle.isCompleted})")
        }
        job.cancelAndJoin()
    }
}
