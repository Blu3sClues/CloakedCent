package com.cloakedcent.app.data.repository

import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.db.CloakedCentDatabase
import com.cloakedcent.app.data.db.dao.PriceRow
import com.cloakedcent.app.data.db.entity.PriceObservationEntity
import com.cloakedcent.app.data.db.entity.ProductAliasEntity
import com.cloakedcent.app.data.db.entity.ProductEntity
import com.cloakedcent.app.data.db.entity.ReceiptEntity
import com.cloakedcent.app.data.db.entity.ReceiptImageEntity
import com.cloakedcent.app.data.db.entity.ReceiptItemEntity
import com.cloakedcent.app.data.db.entity.ReceiptOcrLineEntity
import com.cloakedcent.app.data.db.entity.StoreEntity
import com.cloakedcent.app.data.db.entity.TransactionSplitEntity
import com.cloakedcent.app.data.receipt.OcrEngine
import com.cloakedcent.app.data.receipt.ProcessedPage
import com.cloakedcent.app.data.receipt.ReceiptImageProcessor
import com.cloakedcent.app.data.receipt.ReceiptImageStore
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.ConfirmTarget
import com.cloakedcent.app.domain.model.DuplicateWarning
import com.cloakedcent.app.domain.model.ExpenseMatch
import com.cloakedcent.app.domain.model.ImageHashes
import com.cloakedcent.app.domain.model.ImportOutcome
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.MeasureUnit
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.NormalizedPrice
import com.cloakedcent.app.domain.model.OcrBox
import com.cloakedcent.app.domain.model.OcrLine
import com.cloakedcent.app.domain.model.OcrSource
import com.cloakedcent.app.domain.model.PackSizes
import com.cloakedcent.app.domain.model.PageInput
import com.cloakedcent.app.domain.model.PriceObservation
import com.cloakedcent.app.domain.model.Product
import com.cloakedcent.app.domain.model.ProductSummary
import com.cloakedcent.app.domain.model.ReceiptDetail
import com.cloakedcent.app.domain.model.ReceiptException
import com.cloakedcent.app.domain.model.ReceiptField
import com.cloakedcent.app.domain.model.ReceiptItemDraft
import com.cloakedcent.app.domain.model.ReceiptMath
import com.cloakedcent.app.domain.model.ReceiptPage
import com.cloakedcent.app.domain.model.ReceiptParser
import com.cloakedcent.app.domain.model.ReceiptReview
import com.cloakedcent.app.domain.model.ReceiptStatus
import com.cloakedcent.app.domain.model.ReceiptSummary
import com.cloakedcent.app.domain.model.ReconcileIssue
import com.cloakedcent.app.domain.model.Store
import com.cloakedcent.app.domain.model.TextKeys
import com.cloakedcent.app.domain.model.TransactionEdit
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.domain.model.UnitPricing
import com.cloakedcent.app.domain.repository.PayeeRepository
import com.cloakedcent.app.domain.repository.ProductRepository
import com.cloakedcent.app.domain.repository.ReceiptRepository
import com.cloakedcent.app.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalTime

private const val MATCH_WINDOW_DAYS = 7L

class RoomReceiptRepository(
    database: () -> CloakedCentDatabase,
    private val transactions: TransactionRepository,
    private val payees: PayeeRepository,
    private val images: ReceiptImageStore,
    private val ocr: OcrEngine,
    private val process: (PageInput) -> ProcessedPage = ReceiptImageProcessor::process,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), ReceiptRepository {

    override suspend fun import(pages: List<PageInput>, requestId: String, allowDuplicate: Boolean): ImportOutcome {
        require(pages.isNotEmpty())
        withContextRead { db -> db.receiptDao().getByRequestId(requestId) }
            ?.let { return ImportOutcome.Created(it.id, emptyList()) }

        val processed = withContext(Dispatchers.Default) { pages.map(process) }
        val known = withContextRead { db -> db.receiptDao().getLiveImageHashes() }
        val exact = processed.firstNotNullOfOrNull { page -> known.firstOrNull { it.sourceSha256 == page.sourceSha256 } }
        if (exact != null && !allowDuplicate) return ImportOutcome.Duplicate(exact.receiptId)

        // Embedded PDF text is used only when every page has it; otherwise all pages are read by OCR.
        val embedded = pages.takeIf { list -> list.all { it.embeddedText != null } }
            ?.flatMapIndexed { page, input -> input.embeddedText!!.map { it.copy(page = page) } }
            ?.mapIndexed { index, line -> line.copy(index = index) }

        // Files first, then rows: a crash in between leaves only orphan files, which are cleaned up later.
        val stored = withContext(ioDispatcher) { processed.map { images.write(it.jpeg) } }
        val id = try {
            write { db ->
                val now = clock.millis()
                val receiptId = db.receiptDao().insert(
                    ReceiptEntity(
                        requestId = requestId,
                        status = ReceiptStatus.DRAFT,
                        ocrEngine = if (embedded != null) PDF_TEXT_ENGINE else null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                // A digital PDF's own text is stored as its reading; runOcr then parses it exactly.
                embedded?.let { db.receiptDao().insertOcrLines(it.map { line -> line.toEntity(receiptId) }) }
                processed.forEachIndexed { page, image ->
                    db.receiptDao().insertImage(
                        ReceiptImageEntity(
                            receiptId = receiptId,
                            page = page,
                            fileName = stored[page].fileName,
                            sourceSha256 = image.sourceSha256,
                            contentSha256 = stored[page].contentSha256,
                            perceptualHash = image.perceptualHash,
                            width = image.width,
                            height = image.height,
                            byteSize = stored[page].byteSize,
                            formatVersion = ReceiptImageStore.FORMAT_VERSION,
                            createdAt = now,
                        ),
                    )
                }
                receiptId
            }
        } catch (e: Exception) {
            stored.forEach { images.delete(it.fileName) }
            throw e
        }
        val warnings = processed.flatMap { page ->
            known.filter { it.sourceSha256 == page.sourceSha256 }.map { DuplicateWarning.SameImage(it.receiptId) } +
                known.filter { ImageHashes.distance(it.perceptualHash, page.perceptualHash) <= ImageHashes.SIMILAR_DISTANCE }
                    .map { DuplicateWarning.SimilarImage(it.receiptId) }
        }.distinctBy { it.receiptId }
        return ImportOutcome.Created(id, warnings)
    }

    override suspend fun runOcr(id: Long, dayFirst: Boolean) {
        val (pages, stored) = withContextRead { db ->
            val receipt = db.receiptDao().getById(id) ?: throw ReceiptException.NotFound()
            if (!receipt.isEditable) throw ReceiptException.NotEditable()
            val text = if (receipt.ocrEngine == PDF_TEXT_ENGINE) db.receiptDao().getOcrLines(id).map { it.toDomain() } else null
            db.receiptDao().getImages(id) to text
        }
        val engine = if (stored != null) PDF_TEXT_ENGINE else ocr.name
        val lines = try {
            stored ?: pages.flatMap { image -> ocr.recognize(image.page, images.read(image.fileName, image.contentSha256)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            write { db ->
                db.receiptDao().getById(id)?.takeIf { it.isEditable }?.let {
                    db.receiptDao().update(it.copy(ocrError = e.message ?: "Text recognition failed", updatedAt = clock.millis()))
                }
            }
            return
        }
        val parsed = ReceiptParser.parse(lines, dayFirst)
        write { db ->
            val dao = db.receiptDao()
            val receipt = dao.getById(id)?.takeIf { it.isEditable } ?: return@write
            val store = dao.findStores(TextKeys.storeKey(parsed.review.storeName)).firstOrNull()
            // No default category is guessed: unrecognised items stay unassigned for the user.
            val review = parsed.review.copy(items = suggestProducts(db, store?.id, parsed.review.items))
            dao.deleteOcrLines(id)
            dao.insertOcrLines(lines.map { it.toEntity(id) })
            dao.deleteItems(id)
            dao.insertItems(review.items.mapIndexed { position, item -> item.toEntity(id, position) })
            dao.update(
                receipt.withReview(review).copy(
                    status = ReceiptStatus.NEEDS_REVIEW,
                    uncertainFields = parsed.uncertain.joinToString(",") { it.name },
                    fieldSources = parsed.sources.entries.joinToString(";") { (field, source) ->
                        "${field.name}=${source.page}:${source.line}"
                    },
                    ocrEngine = engine,
                    ocrError = null,
                    updatedAt = clock.millis(),
                ),
            )
        }
    }

    /**
     * Recognises products: the store's wording learned from earlier corrections first,
     * otherwise a product whose name has exactly the same key. A recognised product's
     * learned category is reused; anything else keeps its OCR text and stays
     * uncategorised. Similar-looking names are never applied automatically, because a
     * guessed product would bring a guessed category with it.
     */
    private suspend fun suggestProducts(db: CloakedCentDatabase, storeId: Long?, items: List<ReceiptItemDraft>): List<ReceiptItemDraft> {
        val dao = db.receiptDao()
        val storeAliases = storeId?.let { dao.getAliases(it) }.orEmpty()
        return items.map { item ->
            val key = TextKeys.descriptionKey(item.rawText)
            var misread = false
            val alias = storeId?.let { dao.getAlias(it, key) }
                ?: storeAliases.filter { TextKeys.ocrKey(it.normalizedAlias) == TextKeys.ocrKey(key) }
                    // Only when it points at one product; the user is asked to check it.
                    .takeIf { matches -> matches.map { it.productId }.distinct().size == 1 }
                    ?.first()?.also { misread = true }
            val product = alias?.let { dao.getProduct(it.productId) }
                ?: key.takeIf { it.isNotEmpty() }?.let { dao.getProductByName(it) }
                ?: return@map item
            item.copy(
                description = product.name,
                productId = product.id,
                categoryId = product.defaultCategoryId,
                uncertain = item.uncertain || misread,
            )
        }
    }
    override fun observeSummaries(): Flow<List<ReceiptSummary>> = observe { db ->
        db.receiptDao().observeVisible().map { list ->
            list.map {
                ReceiptSummary(
                    id = it.id,
                    status = it.status,
                    storeName = it.storeName.ifBlank { null },
                    purchasedOn = it.purchasedOn,
                    total = it.totalCents?.let(::Money),
                    createdAt = it.createdAt,
                )
            }
        }
    }

    override fun observeDetail(id: Long): Flow<ReceiptDetail?> = observe { db ->
        db.receiptDao().observeBundle(id).map { bundle ->
            bundle ?: return@map null
            val receipt = bundle.receipt
            val items = bundle.items.sortedBy { it.position }
            val pages = bundle.images.sortedBy { it.page }
            val lines = bundle.ocrLines.sortedWith(compareBy({ it.page }, { it.lineIndex }))
            ReceiptDetail(
                id = receipt.id,
                status = receipt.status,
                review = receipt.toReview(items),
                uncertain = receipt.uncertainFields.split(',').mapNotNull { name ->
                    ReceiptField.entries.firstOrNull { it.name == name }
                }.toSet(),
                fieldSources = parseSources(receipt.fieldSources),
                pages = pages.map { ReceiptPage(it.id, it.page, it.width, it.height) },
                ocrLines = lines.map {
                    OcrLine(
                        page = it.page,
                        index = it.lineIndex,
                        text = it.text,
                        confidence = it.confidence,
                        box = if (it.boxLeft != null && it.boxTop != null && it.boxRight != null && it.boxBottom != null) {
                            OcrBox(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom)
                        } else {
                            null
                        },
                    )
                },
                transactionId = receipt.transactionId,
                storeId = receipt.storeId,
                ocrError = receipt.ocrError,
                // A confirmed receipt always had an expense; none now means it was deleted.
                expenseDeleted = receipt.status == ReceiptStatus.CONFIRMED && receipt.transactionId == null,
            )
        }
    }

    override fun observeReceiptFor(transactionId: Long): Flow<Long?> =
        observe { db -> db.receiptDao().observeIdForTransaction(transactionId) }

    override suspend fun loadPage(imageId: Long): ByteArray {
        val image = withContextRead { db -> db.receiptDao().getImage(imageId) } ?: throw ReceiptException.NotFound()
        return withContext(ioDispatcher) { images.read(image.fileName, image.contentSha256) }
    }

    override suspend fun saveReview(id: Long, review: ReceiptReview) = write { db ->
        val dao = db.receiptDao()
        val receipt = dao.getById(id) ?: throw ReceiptException.NotFound()
        if (!receipt.isEditable) throw ReceiptException.NotEditable()
        requireValidItems(review)
        dao.deleteItems(id)
        dao.insertItems(review.items.mapIndexed { position, item -> item.toEntity(id, position) })
        dao.update(receipt.withReview(review).copy(status = ReceiptStatus.NEEDS_REVIEW, updatedAt = clock.millis()))
    }

    override suspend fun duplicates(id: Long): List<DuplicateWarning> = withContextRead { db ->
        val dao = db.receiptDao()
        val receipt = dao.getById(id) ?: return@withContextRead emptyList()
        val own = dao.getImages(id)
        val others = dao.getLiveImageHashes().filter { it.receiptId != id }
        val sameImage = others.filter { other -> own.any { it.sourceSha256 == other.sourceSha256 } }
            .map { DuplicateWarning.SameImage(it.receiptId) }
        val sameContent = dao.findSameContent(id, receipt.storeName, receipt.purchasedOn, receipt.totalCents, receipt.receiptNumber)
            .map { DuplicateWarning.SameContent(it) }
        val similar = others.filter { other ->
            own.any { ImageHashes.distance(it.perceptualHash, other.perceptualHash) <= ImageHashes.SIMILAR_DISTANCE }
        }.map { DuplicateWarning.SimilarImage(it.receiptId) }
        (sameImage + sameContent + similar).distinctBy { it.receiptId }
    }

    override suspend fun findMatches(id: Long): List<ExpenseMatch> = withContextRead { db ->
        val receipt = db.receiptDao().getById(id) ?: return@withContextRead emptyList()
        val date = receipt.purchasedOn ?: return@withContextRead emptyList()
        val total = receipt.totalCents ?: return@withContextRead emptyList()
        db.receiptDao()
            .findExpenseCandidates(date.minusDays(MATCH_WINDOW_DAYS), date.plusDays(MATCH_WINDOW_DAYS), total)
            .filter { row ->
                // Exact amounts, or projections (variable bills) within half the receipt total.
                row.amountCents == total || ((row.status.isScheduled || row.fromRecurring) && Math.abs(row.amountCents - total) * 2 <= total)
            }
            .map {
                ExpenseMatch(
                    transactionId = it.id,
                    amount = Money(it.amountCents),
                    occurredOn = it.occurredOn,
                    status = it.status,
                    accountName = it.accountName,
                    payeeName = it.payeeName,
                    categoryName = it.categoryName,
                    fromRecurring = it.fromRecurring,
                    exactAmount = it.amountCents == total,
                )
            }
    }

    override suspend fun confirm(id: Long, review: ReceiptReview, target: ConfirmTarget, keepImages: Boolean): Long {
        // Confirming erases the reading (OCR text and boxes), and the images if not kept, so it scrubs like any deletion.
        val (transactionId, files) = erase { db -> confirmInTransaction(db, id, review, target, keepImages) }
        // After the commit; if this is interrupted, orphan cleanup removes them.
        withContext(ioDispatcher) { files.forEach(images::delete) }
        return transactionId
    }

    /** Returns the expense id and the image files to delete once committed. */
    private suspend fun confirmInTransaction(
        db: CloakedCentDatabase,
        id: Long,
        review: ReceiptReview,
        target: ConfirmTarget,
        keepImages: Boolean,
    ): Pair<Long, List<String>> {
        val dao = db.receiptDao()
        val receipt = dao.getById(id) ?: throw ReceiptException.NotFound()
        if (receipt.status == ReceiptStatus.CONFIRMED) {
            // Its expense was deleted; the receipt is final and cannot take another.
            val expense = receipt.transactionId ?: throw ReceiptException.NotEditable()
            return expense to emptyList()
        }
        if (!receipt.isEditable) throw ReceiptException.NotEditable()
        validateForConfirm(db, review)
        val total = review.total!!
        val date = review.purchasedOn!!
        val now = clock.millis()
        val store = resolveStore(db, review, now)
        fun categoryOf(item: ReceiptItemDraft) = item.categoryId ?: review.defaultCategoryId!!
        val shares = ReceiptMath.allocate(total, review.items.map { categoryOf(it) to it.net })

        val transactionId = when (target) {
            is ConfirmTarget.NewExpense -> transactions.record(
                NewTransaction(
                    requestId = "receipt-$id",
                    accountId = target.accountId,
                    type = TransactionType.EXPENSE,
                    amount = total,
                    occurredOn = date,
                    categoryId = shares.maxBy { it.value.cents }.key,
                    payeeId = store.payeeId,
                    note = review.receiptNumber.ifBlank { null }?.let { "Receipt $it" },
                ),
            )
            is ConfirmTarget.Existing -> attach(db, target, total)
        }
        shares.forEach { (categoryId, amount) ->
            dao.insertSplit(
                TransactionSplitEntity(
                    transactionId = transactionId,
                    categoryId = categoryId,
                    amountCents = amount.cents,
                    receiptId = id,
                    createdAt = now,
                ),
            )
        }
        check(dao.getSplits(transactionId).sumOf { it.amountCents } == total.cents) { "Splits must equal the expense" }

        dao.deleteItems(id)
        review.items.forEachIndexed { position, item ->
            val categoryId = categoryOf(item)
            val product = resolveProduct(db, item, categoryId, now)
            val itemId = dao.insertItem(
                item.toEntity(id, position).copy(
                    sourcePage = null,
                    sourceLine = null,
                    productId = product.id,
                    categoryId = categoryId,
                    uncertain = false,
                    userVerified = true,
                ),
            )
            learnAlias(db, store.id, item.rawText.ifBlank { item.description }, product.id, now)
            val normalized = UnitPricing.normalize(item.net, item.quantityMilli, item.unit, product.packQuantityMilli, product.packUnit)
            dao.insertObservation(
                PriceObservationEntity(
                    productId = product.id,
                    storeId = store.id,
                    receiptItemId = itemId,
                    observedOn = date,
                    quantityMilli = item.quantityMilli,
                    unit = item.unit,
                    paidCents = item.net.cents,
                    unitPriceCents = item.unitPrice?.cents,
                    normalizedDimension = normalized?.dimension,
                    normalizedMillicents = normalized?.milliCentsPerUnit,
                    createdAt = now,
                ),
            )
        }
        dao.update(
            receipt.withReview(review).copy(
                status = ReceiptStatus.CONFIRMED,
                storeId = store.id,
                transactionId = transactionId,
                uncertainFields = "",
                fieldSources = "",
                updatedAt = now,
                confirmedAt = now,
            ),
        )
        // The confirmed receipt shows the page alone; the reading behind the review boxes goes.
        dao.deleteOcrLines(id)
        val imageFiles = if (keepImages) emptyList() else dao.getImages(id).map { it.fileName }
        if (!keepImages) dao.deleteImages(id)
        return transactionId to imageFiles
    }

    /** Links to an existing expense without ever creating another one. */
    private suspend fun attach(db: CloakedCentDatabase, target: ConfirmTarget.Existing, total: Money): Long {
        val entry = db.transactionDao().getById(target.transactionId)
        if (entry == null || entry.isDeleted || entry.type != TransactionType.EXPENSE ||
            db.receiptDao().getByTransaction(entry.id) != null || db.receiptDao().hasSplits(entry.id)
        ) {
            throw ReceiptException.ExpenseUnavailable()
        }
        when {
            // A projection (e.g. a recurring bill) becomes Spent with the receipt's actual amount.
            entry.status.isScheduled -> transactions.confirm(entry.id, total)
            -entry.amountCents != total.cents -> {
                if (!target.replaceAmount) throw ReceiptException.AmountDiffers()
                transactions.edit(
                    entry.id,
                    TransactionEdit(total, entry.occurredOn, entry.categoryId, entry.payeeId, entry.note),
                )
            }
        }
        return entry.id
    }

    private suspend fun validateForConfirm(db: CloakedCentDatabase, review: ReceiptReview) {
        if (review.storeName.isBlank()) throw ReceiptException.StoreRequired()
        if (review.purchasedOn == null) throw ReceiptException.DateRequired()
        if (review.total == null || !review.total.isPositive) throw ReceiptException.TotalRequired()
        if (review.items.isEmpty()) throw ReceiptException.ItemsRequired()
        requireValidItems(review)
        val categories = db.categoryDao()
        val needed = review.items.map { it.categoryId ?: review.defaultCategoryId ?: throw ReceiptException.CategoryRequired() }
        (needed + listOfNotNull(review.defaultCategoryId)).distinct().forEach { categoryId ->
            val category = categories.getById(categoryId)
            if (category == null || category.isArchived || category.kind != CategoryKind.EXPENSE) {
                throw LedgerException.CategoryMismatch()
            }
        }
        val issues = ReceiptMath.reconcile(review)
        if (issues.isNotEmpty()) throw ReceiptException.NotReconciled(issues)
        if (review.items.sumOf { it.net.cents } <= 0) throw ReceiptException.NotReconciled(listOf(ReconcileIssue.NegativeAmount))
    }

    private fun requireValidItems(review: ReceiptReview) {
        review.items.forEach { item ->
            if (TextKeys.normalize(item.description).isEmpty()) throw ReceiptException.DescriptionRequired()
            if (item.quantityMilli <= 0) throw ReceiptException.InvalidQuantity()
            if (item.lineTotal.isNegative || item.discount.isNegative || item.net.isNegative) {
                throw ReceiptException.NotReconciled(listOf(ReconcileIssue.NegativeAmount))
            }
        }
    }

    private suspend fun resolveStore(db: CloakedCentDatabase, review: ReceiptReview, now: Long): StoreEntity {
        val dao = db.receiptDao()
        val name = review.storeName.trim()
        val address = review.storeAddress.trim().ifEmpty { null }
        val existing = dao.findStores(TextKeys.storeKey(name)).firstOrNull { store ->
            address == null || store.address == null || TextKeys.normalize(store.address) == TextKeys.normalize(address)
        }
        val payeeId = existing?.payeeId ?: payees.getOrCreate(name)
        if (existing != null) {
            val updated = existing.copy(
                address = existing.address ?: address,
                payeeId = payeeId,
                updatedAt = now,
            )
            if (updated != existing) dao.updateStore(updated)
            return updated
        }
        val store = StoreEntity(
            name = name,
            normalizedName = TextKeys.storeKey(name),
            address = address,
            payeeId = payeeId,
            createdAt = now,
            updatedAt = now,
        )
        return store.copy(id = dao.insertStore(store))
    }

    private suspend fun resolveProduct(db: CloakedCentDatabase, item: ReceiptItemDraft, categoryId: Long, now: Long): ProductEntity {
        val dao = db.receiptDao()
        val key = TextKeys.descriptionKey(item.description).ifEmpty { TextKeys.normalize(item.description) }
        val suggested = item.productId?.let { dao.getProduct(it) }?.takeIf { TextKeys.descriptionKey(it.name) == key }
        val existing = suggested ?: dao.getProductByName(key)
        if (existing != null) {
            // The latest confirmed category becomes the product's default.
            return existing.copy(defaultCategoryId = categoryId, updatedAt = now).also {
                if (it != existing) dao.updateProduct(it)
            }
        }
        val pack = PackSizes.parse(item.description)
        val product = ProductEntity(
            name = item.description.trim(),
            normalizedName = key,
            defaultCategoryId = categoryId,
            packQuantityMilli = pack?.first,
            packUnit = pack?.second,
            createdAt = now,
            updatedAt = now,
        )
        return product.copy(id = dao.insertProduct(product))
    }

    /** Remembers that this store's receipt wording means this product. Kept permanently. */
    private suspend fun learnAlias(db: CloakedCentDatabase, storeId: Long, rawText: String, productId: Long, now: Long) {
        val dao = db.receiptDao()
        val alias = TextKeys.descriptionKey(rawText).ifEmpty { return }
        val existing = dao.getAlias(storeId, alias)
        if (existing == null) {
            dao.insertAlias(
                ProductAliasEntity(
                    storeId = storeId,
                    normalizedAlias = alias,
                    productId = productId,
                    timesConfirmed = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            dao.updateAlias(existing.copy(productId = productId, timesConfirmed = existing.timesConfirmed + 1, updatedAt = now))
        }
    }

    override suspend fun discard(id: Long) {
        val files = erase { db ->
            // Already erased: nothing left to do.
            val receipt = db.receiptDao().getById(id) ?: return@erase emptyList()
            if (!receipt.isEditable) throw ReceiptException.NotEditable()
            Erasure.receipt(db, receipt, clock.millis())
        }
        // After the commit; if this is interrupted, orphan cleanup removes them.
        withContext(ioDispatcher) { files.forEach(images::delete) }
    }

    override suspend fun delete(id: Long, deleteTransaction: Boolean) {
        val files = erase { db ->
            val dao = db.receiptDao()
            val found = dao.getById(id) ?: return@erase emptyList()
            if (found.status != ReceiptStatus.CONFIRMED) throw ReceiptException.NotConfirmed()
            // First, so a refused expense deletion (it has refunds) leaves the receipt untouched.
            val expense = found.transactionId
            if (deleteTransaction && expense != null) Erasure.transaction(db, expense, clock.millis())
            // Re-read: erasing the expense detached it from the receipt.
            Erasure.receipt(db, dao.getById(id)!!, clock.millis())
        }
        // After the commit; if this is interrupted, orphan cleanup removes them.
        withContext(ioDispatcher) { files.forEach(images::delete) }
    }

    override suspend fun removeOrphanImages(): Int {
        val referenced = withContextRead { db -> db.receiptDao().getAllImageFileNames().toSet() }
        return withContext(ioDispatcher) { images.removeOrphans(referenced) }
    }

    private val ReceiptEntity.isEditable get() = status == ReceiptStatus.DRAFT || status == ReceiptStatus.NEEDS_REVIEW

    private fun ReceiptEntity.withReview(review: ReceiptReview) = copy(
        storeName = review.storeName.trim(),
        storeAddress = review.storeAddress.trim(),
        purchasedOn = review.purchasedOn,
        purchasedMinute = review.purchasedTime?.let { it.hour * 60 + it.minute },
        subtotalCents = review.subtotal?.cents,
        taxCents = review.tax.cents,
        discountCents = review.discount.cents,
        totalCents = review.total?.cents,
        receiptNumber = review.receiptNumber.trim(),
        defaultCategoryId = review.defaultCategoryId,
    )

    private fun ReceiptEntity.toReview(items: List<ReceiptItemEntity>) = ReceiptReview(
        storeName = storeName,
        storeAddress = storeAddress,
        purchasedOn = purchasedOn,
        purchasedTime = purchasedMinute?.let { LocalTime.of(it / 60, it % 60) },
        subtotal = subtotalCents?.let(::Money),
        tax = Money(taxCents),
        discount = Money(discountCents),
        total = totalCents?.let(::Money),
        receiptNumber = receiptNumber,
        defaultCategoryId = defaultCategoryId,
        items = items.map { it.toDraft() },
    )

    private fun parseSources(text: String): Map<ReceiptField, OcrSource> =
        text.split(';').mapNotNull { entry ->
            val (name, position) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val field = ReceiptField.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val (page, line) = position.split(':').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return@mapNotNull null
            field to OcrSource(page, line)
        }.toMap()
}

/** Recorded as the reading engine when a receipt's text came from the PDF itself. */
const val PDF_TEXT_ENGINE = "pdf-text"

private fun ReceiptOcrLineEntity.toDomain() = OcrLine(
    page = page,
    index = lineIndex,
    text = text,
    confidence = confidence,
    box = if (boxLeft != null && boxTop != null && boxRight != null && boxBottom != null) OcrBox(boxLeft, boxTop, boxRight, boxBottom) else null,
)

private fun OcrLine.toEntity(receiptId: Long) = ReceiptOcrLineEntity(
    receiptId = receiptId,
    page = page,
    lineIndex = index,
    text = text,
    confidence = confidence,
    boxLeft = box?.left,
    boxTop = box?.top,
    boxRight = box?.right,
    boxBottom = box?.bottom,
)

private fun ReceiptItemDraft.toEntity(receiptId: Long, position: Int) = ReceiptItemEntity(
    receiptId = receiptId,
    position = position,
    rawText = rawText.ifBlank { description },
    description = description.trim(),
    productId = productId,
    quantityMilli = quantityMilli,
    unit = unit,
    unitPriceCents = unitPrice?.cents,
    lineTotalCents = lineTotal.cents,
    discountCents = discount.cents,
    categoryId = categoryId,
    confidence = confidence,
    uncertain = uncertain,
    sourcePage = source?.page,
    sourceLine = source?.line,
)

private fun ReceiptItemEntity.toDraft() = ReceiptItemDraft(
    id = id,
    rawText = rawText,
    description = description,
    quantityMilli = quantityMilli,
    unit = unit,
    unitPrice = unitPriceCents?.let(::Money),
    lineTotal = Money(lineTotalCents),
    discount = Money(discountCents),
    categoryId = categoryId,
    productId = productId,
    confidence = confidence,
    uncertain = uncertain,
    source = if (sourcePage != null && sourceLine != null) OcrSource(sourcePage, sourceLine) else null,
)

class RoomProductRepository(
    database: () -> CloakedCentDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), ProductRepository {

    override fun observeProducts(): Flow<List<ProductSummary>> = observe { db ->
        combine(db.receiptDao().observeProducts(), db.receiptDao().observeAllPrices()) { products, prices ->
            val byProduct = prices.groupBy { it.productId }
            products.map { product ->
                val history = byProduct[product.id].orEmpty()
                ProductSummary(product.toDomain(), history.size, history.firstOrNull()?.toDomain())
            }
        }
    }

    override suspend fun get(id: Long): Product? = withContextRead { db -> db.receiptDao().getProduct(id)?.toDomain() }

    override fun observePrices(productId: Long): Flow<List<PriceObservation>> =
        observe { db -> db.receiptDao().observePrices(productId).map { rows -> rows.map { it.toDomain() } } }

    override suspend fun update(
        id: Long,
        name: String,
        brand: String?,
        categoryId: Long?,
        packQuantityMilli: Long?,
        packUnit: MeasureUnit?,
    ) = write { db ->
        val dao = db.receiptDao()
        val product = dao.getProduct(id) ?: throw LedgerException.NotFound("Product $id")
        val trimmed = name.trim()
        val key = TextKeys.descriptionKey(trimmed).ifEmpty { TextKeys.normalize(trimmed) }
        if (key.isEmpty()) throw LedgerException.NameRequired()
        dao.getProductByName(key)?.takeIf { it.id != id }?.let {
            throw LedgerException.InvalidOperation("Another product already has this name")
        }
        if (categoryId != null) {
            val category = db.categoryDao().getById(categoryId)
            if (category == null || category.kind != CategoryKind.EXPENSE) throw LedgerException.CategoryMismatch()
        }
        if ((packQuantityMilli == null) != (packUnit == null) || (packQuantityMilli != null && packQuantityMilli <= 0)) {
            throw LedgerException.InvalidOperation("Enter both a pack size and its unit")
        }
        dao.updateProduct(
            product.copy(
                name = trimmed,
                normalizedName = key,
                brand = brand?.trim()?.ifEmpty { null },
                defaultCategoryId = categoryId,
                packQuantityMilli = packQuantityMilli,
                packUnit = packUnit,
                updatedAt = clock.millis(),
            ),
        )
    }

    override fun observeStores(): Flow<List<Store>> = observe { db ->
        db.receiptDao().observeStores().map { stores ->
            stores.map {
                Store(
                    id = it.id,
                    name = it.name,
                    address = it.address,
                )
            }
        }
    }
}

private fun ProductEntity.toDomain() = Product(id, name, brand, defaultCategoryId, packQuantityMilli, packUnit)

private fun PriceRow.toDomain() = PriceObservation(
    productId = productId,
    storeId = storeId,
    storeName = storeName,
    observedOn = observedOn,
    quantityMilli = quantityMilli,
    unit = unit,
    paid = Money(paidCents),
    normalized = if (normalizedDimension != null && normalizedMillicents != null) {
        NormalizedPrice(normalizedDimension, normalizedMillicents)
    } else {
        null
    },
)
