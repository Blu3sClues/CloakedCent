package com.cloakedcent.app.data.repository

import androidx.room.withTransaction
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.data.db.CloakedCentDatabase
import com.cloakedcent.app.data.db.SecureErase
import com.cloakedcent.app.data.db.dao.AccountBalanceRow
import com.cloakedcent.app.data.db.dao.TransactionListRow
import com.cloakedcent.app.data.db.entity.AccountEntity
import com.cloakedcent.app.data.db.entity.CategoryEntity
import com.cloakedcent.app.data.db.entity.PayeeEntity
import com.cloakedcent.app.data.db.entity.TransactionEntity
import com.cloakedcent.app.data.db.entity.TransactionRevisionEntity
import com.cloakedcent.app.data.db.entity.TransactionSplitEntity
import com.cloakedcent.app.data.db.entity.TransferEntity
import com.cloakedcent.app.domain.model.Account
import com.cloakedcent.app.domain.model.AccountBalance
import com.cloakedcent.app.domain.model.AccountType
import com.cloakedcent.app.domain.model.Category
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.CategoryUsage
import com.cloakedcent.app.domain.model.MAX_CATEGORY_DEPTH
import com.cloakedcent.app.domain.model.canSendTransfers
import com.cloakedcent.app.domain.model.depthOf
import com.cloakedcent.app.domain.model.heightOf
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.AdjustmentKind
import com.cloakedcent.app.domain.model.NewAdjustment
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.NewTransfer
import com.cloakedcent.app.domain.model.PayeeType
import com.cloakedcent.app.domain.model.Payee
import com.cloakedcent.app.domain.model.PeriodSummary
import com.cloakedcent.app.domain.model.RevisionAction
import com.cloakedcent.app.domain.model.TransactionDetail
import com.cloakedcent.app.domain.model.TransactionEdit
import com.cloakedcent.app.domain.model.TransactionFilter
import com.cloakedcent.app.domain.model.TransactionRevision
import com.cloakedcent.app.domain.model.TransactionListItem
import com.cloakedcent.app.domain.model.TransactionStatus
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.domain.model.withDescendants
import com.cloakedcent.app.domain.repository.AccountRepository
import com.cloakedcent.app.domain.repository.CategoryRepository
import com.cloakedcent.app.domain.repository.PayeeRepository
import com.cloakedcent.app.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Shared plumbing: lazy database access off the main thread and atomic writes. */
abstract class RoomRepository(
    private val database: () -> CloakedCentDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    protected suspend fun <T> write(block: suspend (CloakedCentDatabase) -> T): T =
        withContext(ioDispatcher) {
            val db = database()
            db.withTransaction { block(db) }
        }

    /**
     * A write that permanently erases data (see Erasure): deleted content is zeroed as it
     * is removed, and after the commit the write-ahead log is truncated so it keeps no
     * old copies of the erased pages.
     */
    protected suspend fun <T> erase(block: suspend (CloakedCentDatabase) -> T): T {
        val result = write { db ->
            SecureErase.enableSecureDelete(db.openHelper.writableDatabase)
            block(db)
        }
        withContext(ioDispatcher) { SecureErase.truncateWal(database().openHelper.writableDatabase) }
        return result
    }

    protected suspend fun <T> withContextRead(block: suspend (CloakedCentDatabase) -> T): T =
        withContext(ioDispatcher) { block(database()) }

    protected fun <T> observe(query: suspend (CloakedCentDatabase) -> Flow<T>): Flow<T> =
        flow { emitAll(query(database())) }.flowOn(ioDispatcher)
}

class RoomAccountRepository(
    database: () -> CloakedCentDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), AccountRepository {

    override suspend fun create(
        name: String,
        type: AccountType,
        openingBalance: Money,
        includeInTotals: Boolean,
    ): Long = write { db ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LedgerException.NameRequired()
        val now = clock.millis()
        db.accountDao().insert(
            AccountEntity(
                name = trimmed,
                type = type,
                openingBalanceCents = openingBalance.cents,
                includeInTotals = includeInTotals,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun get(id: Long): Account? = withContextRead { db ->
        db.accountDao().getById(id)?.takeUnless { it.isArchived }?.let {
            Account(it.id, it.name, it.type, Money(it.openingBalanceCents), it.includeInTotals)
        }
    }

    override suspend fun update(id: Long, name: String, type: AccountType, includeInTotals: Boolean) = write { db ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LedgerException.NameRequired()
        if (db.accountDao().update(id, trimmed, type, includeInTotals, clock.millis()) == 0) {
            throw LedgerException.AccountUnavailable(id)
        }
    }

    override suspend fun archive(id: Long) = write { db ->
        val row = db.accountDao().getBalance(id)
        if (row == null || row.account.isArchived) throw LedgerException.AccountUnavailable(id)
        if (!row.toDomain().projected.isZero) throw LedgerException.AccountHasBalance()
        db.accountDao().archive(id, clock.millis())
    }

    override fun observeBalances(): Flow<List<AccountBalance>> =
        observe { it.accountDao().observeBalances() }.map { rows -> rows.map { it.toDomain() } }
}

internal fun AccountBalanceRow.toDomain(): AccountBalance {
    val opening = Money(account.openingBalanceCents)
    return AccountBalance(
        account = Account(
            id = account.id,
            name = account.name,
            type = account.type,
            openingBalance = opening,
            includeInTotals = account.includeInTotals,
        ),
        current = opening + Money(currentCents),
        projected = opening + Money(projectedCents),
    )
}

/** Saves [new] and keeps the values it replaces as a revision in the entry's edit history. */
internal suspend fun recordChange(
    db: CloakedCentDatabase,
    old: TransactionEntity,
    new: TransactionEntity,
    action: RevisionAction,
    now: Long,
) {
    val dao = db.transactionDao()
    dao.insertRevision(
        TransactionRevisionEntity(
            transactionId = old.id,
            revision = old.revision,
            action = action,
            accountId = old.accountId,
            type = old.type,
            amountCents = old.amountCents,
            status = old.status,
            occurredOn = old.occurredOn,
            categoryId = old.categoryId,
            payeeId = old.payeeId,
            note = old.note,
            recordedAt = now,
        ),
    )
    dao.update(new.copy(revision = old.revision + 1, updatedAt = now))
}

class RoomCategoryRepository(
    database: () -> CloakedCentDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), CategoryRepository {

    override suspend fun create(name: String, kind: CategoryKind, parentId: Long?): Long = write { db ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LedgerException.NameRequired()
        val all = categories(db)
        if (parentId != null) {
            requireParent(db, parentId, kind)
            if (all.depthOf(parentId) >= MAX_CATEGORY_DEPTH) throw LedgerException.CategoryTooDeep()
        }
        if (all.any { it.parentId == parentId && it.kind == kind && it.name.equals(trimmed, ignoreCase = true) }) {
            throw LedgerException.CategoryNameTaken()
        }
        db.categoryDao().insert(
            CategoryEntity(name = trimmed, kind = kind, parentId = parentId, createdAt = clock.millis()),
        )
    }

    override suspend fun setParent(id: Long, parentId: Long?) = write { db ->
        val dao = db.categoryDao()
        val category = dao.getById(id) ?: throw LedgerException.NotFound("Category $id")
        if (parentId != null) {
            requireParent(db, parentId, category.kind)
            // Walk up from the new parent; reaching this category would create a cycle.
            var ancestor: Long? = parentId
            while (ancestor != null) {
                if (ancestor == id) throw LedgerException.InvalidOperation("Category cannot be nested inside itself")
                ancestor = dao.getById(ancestor)?.parentId
            }
            val all = categories(db)
            if (all.depthOf(parentId) + all.heightOf(id) > MAX_CATEGORY_DEPTH) throw LedgerException.CategoryTooDeep()
        }
        dao.setParent(id, parentId)
    }

    override fun observeAll(): Flow<List<Category>> =
        observe { it.categoryDao().observeAll() }.map { rows ->
            rows.map { Category(it.id, it.name, it.kind, it.parentId) }
        }

    override suspend fun usage(id: Long): CategoryUsage = withContextRead { db ->
        val ids = categories(db).withDescendants(id).toList()
        usageOf(db, ids)
    }

    override suspend fun delete(id: Long, moveTo: Long?) = erase { db ->
        val dao = db.categoryDao()
        val all = categories(db)
        // Already deleted: nothing left to do.
        val category = all.firstOrNull { it.id == id } ?: return@erase
        val ids = all.withDescendants(id).toList()
        if (moveTo != null) {
            if (moveTo in ids) throw LedgerException.InvalidOperation("Move to a category outside the one being deleted")
            val target = dao.getById(moveTo)
            if (target == null || target.isArchived || target.kind != category.kind) throw LedgerException.CategoryMismatch()
        }
        if (usageOf(db, ids).inUse && moveTo == null) throw LedgerException.CategoryInUse()

        dao.markForDeletion(ids)
        if (moveTo != null) {
            val now = clock.millis()
            dao.getTransactionsIn(ids).forEach { recordChange(db, it, it.copy(categoryId = moveTo), RevisionAction.UPDATE, now) }
            // Splits are unique per category: rebuild each expense's, merging those that now share one.
            dao.getSplitTransactionsIn(ids).forEach { transactionId ->
                val merged = dao.getSplits(transactionId).groupBy { if (it.categoryId in ids) moveTo else it.categoryId }
                dao.deleteUnmarkedSplits(transactionId, ids)
                dao.deleteSplits(transactionId)
                merged.forEach { (categoryId, splits) ->
                    dao.insertSplit(
                        TransactionSplitEntity(
                            transactionId = transactionId,
                            categoryId = categoryId,
                            amountCents = splits.sumOf { it.amountCents },
                            receiptId = splits.firstNotNullOfOrNull { it.receiptId },
                            createdAt = splits.minOf { it.createdAt },
                        ),
                    )
                }
            }
            dao.moveRefundAllocations(ids, moveTo)
            dao.moveBudgets(ids, moveTo, now)
            dao.moveRecurring(ids, moveTo, now)
        }
        // Receipt items and defaults follow the move, or are cleared when nothing needed moving.
        dao.moveReceiptItems(ids, moveTo)
        dao.moveReceiptDefaults(ids, moveTo)
        dao.moveProductDefaults(ids, moveTo)
        dao.movePayeeDefaults(ids, moveTo)
        // Deepest first: a category is referenced by its sub-categories.
        ids.sortedByDescending { all.depthOf(it) }.forEach { dao.delete(it) }
    }

    private suspend fun categories(db: CloakedCentDatabase): List<Category> =
        db.categoryDao().getAll().map { Category(it.id, it.name, it.kind, it.parentId) }

    private suspend fun usageOf(db: CloakedCentDatabase, ids: List<Long>): CategoryUsage {
        val dao = db.categoryDao()
        return CategoryUsage(
            transactions = dao.countTransactions(ids),
            budgets = dao.countBudgets(ids),
            recurring = dao.countRecurring(ids),
            subcategories = ids.size - 1,
        )
    }

    private suspend fun requireParent(db: CloakedCentDatabase, parentId: Long, kind: CategoryKind) {
        val parent = db.categoryDao().getById(parentId)
        if (parent == null || parent.isArchived || parent.kind != kind) throw LedgerException.CategoryMismatch()
    }
}

class RoomPayeeRepository(
    database: () -> CloakedCentDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), PayeeRepository {

    override suspend fun getOrCreate(name: String, type: PayeeType): Long = write { db ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LedgerException.NameRequired()
        db.payeeDao().findByName(trimmed)?.id
            ?: db.payeeDao().insert(PayeeEntity(name = trimmed, type = type, createdAt = clock.millis()))
    }

    override fun observeAll(): Flow<List<Payee>> =
        observe { it.payeeDao().observeAll() }.map { rows -> rows.map { Payee(it.id, it.name, it.type) } }
}

class RoomTransactionRepository(
    database: () -> CloakedCentDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), TransactionRepository {

    override suspend fun record(transaction: NewTransaction): Long = write { db ->
        val dao = db.transactionDao()
        // Idempotency: a repeated request returns the original entry untouched.
        dao.getByRequestId(transaction.requestId)?.let { return@write it.id }

        if (transaction.type != TransactionType.INCOME && transaction.type != TransactionType.EXPENSE) {
            throw LedgerException.InvalidOperation("Use recordTransfer or adjustBalance for this type")
        }
        requirePositive(transaction.amount)
        requireActiveAccount(db, transaction.accountId)
        requireCategory(db, transaction.categoryId, transaction.type)

        val now = clock.millis()
        dao.insert(
            TransactionEntity(
                requestId = transaction.requestId,
                accountId = transaction.accountId,
                type = transaction.type,
                amountCents = signed(transaction.type, transaction.amount).cents,
                status = statusFor(transaction.type, transaction.scheduled),
                occurredOn = transaction.occurredOn,
                categoryId = transaction.categoryId,
                payeeId = transaction.payeeId,
                note = transaction.note,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun recordTransfer(transfer: NewTransfer): Long = write { db ->
        val dao = db.transactionDao()
        dao.getTransferByRequestId(transfer.requestId)?.let { return@write it.id }

        requirePositive(transfer.amount)
        if (transfer.fromAccountId == transfer.toAccountId) throw LedgerException.SameAccountTransfer()
        requireActiveAccount(db, transfer.fromAccountId)
        requireActiveAccount(db, transfer.toAccountId)
        if (db.accountDao().getById(transfer.fromAccountId)?.type?.canSendTransfers == false) throw LedgerException.CreditCardTransferSource()

        val now = clock.millis()
        val transferId = dao.insertTransfer(
            TransferEntity(
                requestId = transfer.requestId,
                fromAccountId = transfer.fromAccountId,
                toAccountId = transfer.toAccountId,
                createdAt = now,
            ),
        )
        fun leg(accountId: Long, amount: Money) = TransactionEntity(
            requestId = null,
            accountId = accountId,
            type = TransactionType.TRANSFER,
            amountCents = amount.cents,
            status = statusFor(TransactionType.TRANSFER, transfer.scheduled),
            occurredOn = transfer.occurredOn,
            transferId = transferId,
            note = transfer.note,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(leg(transfer.fromAccountId, -transfer.amount))
        dao.insert(leg(transfer.toAccountId, transfer.amount))
        transferId
    }

    override suspend fun edit(id: Long, edit: TransactionEdit) = write { db ->
        requirePositive(edit.amount)
        val current = requireLive(db, id)
        if (current.type == TransactionType.REFUND) {
            // A refund's amount is fixed by its allocations; delete it and record a new one instead.
            throw LedgerException.InvalidOperation("Refunds cannot be edited")
        }
        if (current.type == TransactionType.TRANSFER) {
            if (edit.categoryId != null || edit.payeeId != null) {
                throw LedgerException.InvalidOperation("Transfers have no category or payee")
            }
            for (leg in legsOf(db, current)) {
                val amount = if (leg.amountCents < 0) -edit.amount else edit.amount
                applyChange(
                    db,
                    leg,
                    leg.copy(amountCents = amount.cents, occurredOn = edit.occurredOn, note = edit.note),
                    RevisionAction.UPDATE,
                )
            }
        } else if (current.type == TransactionType.ADJUSTMENT) {
            if (edit.categoryId != null || edit.payeeId != null) {
                throw LedgerException.InvalidOperation("Balance adjustments have no category or payee")
            }
            // The direction of a balance-only adjustment is fixed when it is recorded.
            val amount = if (current.amountCents < 0) -edit.amount else edit.amount
            applyChange(
                db,
                current,
                current.copy(amountCents = amount.cents, occurredOn = edit.occurredOn, note = edit.note),
                RevisionAction.UPDATE,
            )
        } else {
            requireCategory(db, edit.categoryId, current.type)
            if (signed(current.type, edit.amount).cents != current.amountCents && db.receiptDao().hasSplits(id)) {
                // Its receipt divides this amount across categories; changing it would unbalance them.
                throw LedgerException.InvalidOperation("This expense is split by its receipt, so its amount cannot change")
            }
            applyChange(
                db,
                current,
                current.copy(
                    amountCents = signed(current.type, edit.amount).cents,
                    occurredOn = edit.occurredOn,
                    categoryId = edit.categoryId,
                    payeeId = edit.payeeId,
                    note = edit.note,
                ),
                RevisionAction.UPDATE,
            )
        }
    }

    override suspend fun delete(id: Long) {
        erase { db -> Erasure.transaction(db, id, clock.millis()) }
    }

    override suspend fun confirm(id: Long, actualAmount: Money?): Boolean = write { db ->
        if (actualAmount != null) requirePositive(actualAmount)
        val current = db.transactionDao().getById(id) ?: throw LedgerException.NotFound("Transaction $id")
        val entries = legsOf(db, current)
        if (entries.any { it.isDeleted || !it.status.isScheduled }) return@write false
        for (entry in entries) {
            var latest = entry
            if (actualAmount != null && actualAmount.cents != Math.abs(entry.amountCents)) {
                val signedAmount = if (entry.amountCents < 0) -actualAmount else actualAmount
                applyChange(db, entry, entry.copy(amountCents = signedAmount.cents), RevisionAction.UPDATE)
                latest = db.transactionDao().getById(entry.id)!!
            }
            applyChange(db, latest, latest.copy(status = latest.type.completedStatus), RevisionAction.STATUS_CHANGE)
        }
        true
    }

    override suspend fun adjustBalance(adjustment: NewAdjustment): Long = write { db ->
        val dao = db.transactionDao()
        dao.getByRequestId(adjustment.requestId)?.let { return@write it.id }

        if (adjustment.amount.isZero) throw LedgerException.InvalidAmount()
        val type = when (adjustment.kind) {
            AdjustmentKind.BALANCE_ONLY -> TransactionType.ADJUSTMENT
            AdjustmentKind.INCOME -> TransactionType.INCOME
            AdjustmentKind.EXPENSE -> TransactionType.EXPENSE
        }
        if ((type == TransactionType.INCOME && adjustment.amount.isNegative) ||
            (type == TransactionType.EXPENSE && adjustment.amount.isPositive)
        ) {
            throw LedgerException.AdjustmentKindMismatch()
        }
        requireActiveAccount(db, adjustment.accountId)

        val now = clock.millis()
        dao.insert(
            TransactionEntity(
                requestId = adjustment.requestId,
                accountId = adjustment.accountId,
                type = type,
                amountCents = adjustment.amount.cents,
                status = type.completedStatus,
                occurredOn = adjustment.occurredOn,
                note = adjustment.note,
                isAdjustment = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override fun observeRecent(limit: Int): Flow<List<TransactionListItem>> =
        observe { it.transactionDao().observeRecent(limit) }.map { rows -> rows.map { it.toItem() } }

    override fun search(filter: TransactionFilter): Flow<List<TransactionListItem>> =
        observe { db ->
            val categoryIds = filter.categoryId?.let { root ->
                db.categoryDao().getAll()
                    .map { Category(it.id, it.name, it.kind, it.parentId) }
                    .withDescendants(root)
                    .toList()
            }
            db.transactionDao().search(
                query = filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%${escapeLike(it)}%" },
                start = filter.start,
                endInclusive = filter.endInclusive,
                accountId = filter.accountId,
                filterCategory = categoryIds != null,
                categoryIds = categoryIds.orEmpty(),
                payeeId = filter.payeeId,
                scheduled = filter.scheduled,
            )
        }.map { rows -> rows.map { it.toItem() } }

    override fun observeDetail(id: Long): Flow<TransactionDetail?> =
        observe { db ->
            combine(db.transactionDao().observeDetail(id), db.transactionDao().observeRevisions(id)) { row, revisions ->
                row?.let {
                    TransactionDetail(
                        id = it.id,
                        type = it.type,
                        status = it.status,
                        amount = Money(it.amountCents),
                        occurredOn = it.occurredOn,
                        accountId = it.accountId,
                        accountName = it.accountName,
                        categoryId = it.categoryId,
                        categoryName = it.categoryName,
                        payeeId = it.payeeId,
                        payeeName = it.payeeName,
                        counterpartAccountId = it.counterpartAccountId,
                        counterpartAccountName = it.counterpartAccountName,
                        note = it.note,
                        revision = it.revision,
                        isDeleted = it.isDeleted,
                        isAdjustment = it.isAdjustment,
                        createdAt = Instant.ofEpochMilli(it.createdAt),
                        updatedAt = Instant.ofEpochMilli(it.updatedAt),
                        history = revisions.map { r ->
                            TransactionRevision(
                                revision = r.revision,
                                action = r.action,
                                amount = Money(r.amountCents),
                                status = r.status,
                                occurredOn = r.occurredOn,
                                categoryName = r.categoryName,
                                payeeName = r.payeeName,
                                note = r.note,
                                recordedAt = Instant.ofEpochMilli(r.recordedAt),
                            )
                        },
                    )
                }
            }
        }

    private fun TransactionListRow.toItem() = TransactionListItem(
        id = id,
        type = type,
        status = status,
        amount = Money(amountCents),
        occurredOn = occurredOn,
        accountName = accountName,
        categoryName = categoryName,
        payeeName = payeeName,
        counterpartAccountName = counterpartAccountName,
        isAdjustment = isAdjustment,
        refunded = Money(refundedCents),
    )

    private fun escapeLike(text: String) =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    override fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate): Flow<PeriodSummary> =
        observe { it.transactionDao().observePeriodSummary(start, endInclusive) }.map {
            PeriodSummary(income = Money(it.incomeCents), expense = Money(it.expenseCents))
        }

    private fun statusFor(type: TransactionType, scheduled: Boolean): TransactionStatus =
        if (scheduled) TransactionStatus.SCHEDULED else type.completedStatus

    /** Snapshots [old] into history, then stores [new] as the next revision. */
    private suspend fun applyChange(
        db: CloakedCentDatabase,
        old: TransactionEntity,
        new: TransactionEntity,
        action: RevisionAction,
    ) = recordChange(db, old, new, action, clock.millis())

    private suspend fun legsOf(db: CloakedCentDatabase, entry: TransactionEntity): List<TransactionEntity> =
        entry.transferId?.let { db.transactionDao().getTransferLegs(it) } ?: listOf(entry)

    private suspend fun requireLive(db: CloakedCentDatabase, id: Long): TransactionEntity {
        val entry = db.transactionDao().getById(id) ?: throw LedgerException.NotFound("Transaction $id")
        if (entry.isDeleted) throw LedgerException.InvalidOperation("Deleted transactions cannot be edited")
        return entry
    }

    private suspend fun requireActiveAccount(db: CloakedCentDatabase, accountId: Long) {
        val account = db.accountDao().getById(accountId)
        if (account == null || account.isArchived) throw LedgerException.AccountUnavailable(accountId)
    }

    private suspend fun requireCategory(db: CloakedCentDatabase, categoryId: Long?, type: TransactionType) {
        if (categoryId == null) return
        val expectedKind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val category = db.categoryDao().getById(categoryId)
        if (category == null || category.isArchived || category.kind != expectedKind) {
            throw LedgerException.CategoryMismatch()
        }
    }

    private fun requirePositive(amount: Money) {
        if (!amount.isPositive) throw LedgerException.InvalidAmount()
    }

    private fun signed(type: TransactionType, magnitude: Money): Money =
        if (type == TransactionType.EXPENSE) -magnitude else magnitude
}
