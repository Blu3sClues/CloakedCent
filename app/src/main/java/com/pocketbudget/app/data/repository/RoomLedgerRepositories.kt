package com.pocketbudget.app.data.repository

import androidx.room.withTransaction
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.db.dao.AccountBalanceRow
import com.pocketbudget.app.data.db.entity.AccountEntity
import com.pocketbudget.app.data.db.entity.CategoryEntity
import com.pocketbudget.app.data.db.entity.PayeeEntity
import com.pocketbudget.app.data.db.entity.RevisionAction
import com.pocketbudget.app.data.db.entity.TransactionEntity
import com.pocketbudget.app.data.db.entity.TransactionRevisionEntity
import com.pocketbudget.app.data.db.entity.TransferEntity
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.Category
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.PayeeType
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.AccountRepository
import com.pocketbudget.app.domain.repository.CategoryRepository
import com.pocketbudget.app.domain.repository.PayeeRepository
import com.pocketbudget.app.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

/** Shared plumbing: lazy database access off the main thread and atomic writes. */
abstract class RoomRepository(
    private val database: () -> PocketBudgetDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    protected suspend fun <T> write(block: suspend (PocketBudgetDatabase) -> T): T =
        withContext(ioDispatcher) {
            val db = database()
            db.withTransaction { block(db) }
        }

    protected fun <T> observe(query: (PocketBudgetDatabase) -> Flow<T>): Flow<T> =
        flow { emitAll(query(database())) }.flowOn(ioDispatcher)
}

class RoomAccountRepository(
    database: () -> PocketBudgetDatabase,
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
        if (trimmed.isEmpty()) throw LedgerException.InvalidOperation("Account name is required")
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
        cleared = opening + Money(clearedCents),
        projected = opening + Money(projectedCents),
    )
}

class RoomCategoryRepository(
    database: () -> PocketBudgetDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), CategoryRepository {

    override suspend fun create(name: String, kind: CategoryKind, parentId: Long?): Long = write { db ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LedgerException.InvalidOperation("Category name is required")
        if (parentId != null) requireParent(db, parentId, kind)
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
        }
        dao.setParent(id, parentId)
    }

    override fun observeAll(): Flow<List<Category>> =
        observe { it.categoryDao().observeAll() }.map { rows ->
            rows.map { Category(it.id, it.name, it.kind, it.parentId) }
        }

    private suspend fun requireParent(db: PocketBudgetDatabase, parentId: Long, kind: CategoryKind) {
        val parent = db.categoryDao().getById(parentId)
        if (parent == null || parent.isArchived || parent.kind != kind) throw LedgerException.CategoryMismatch()
    }
}

class RoomPayeeRepository(
    database: () -> PocketBudgetDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), PayeeRepository {

    override suspend fun getOrCreate(name: String, type: PayeeType): Long = write { db ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LedgerException.InvalidOperation("Payee name is required")
        db.payeeDao().findByName(trimmed)?.id
            ?: db.payeeDao().insert(PayeeEntity(name = trimmed, type = type, createdAt = clock.millis()))
    }
}

class RoomTransactionRepository(
    database: () -> PocketBudgetDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), TransactionRepository {

    override suspend fun record(transaction: NewTransaction): Long = write { db ->
        val dao = db.transactionDao()
        // Idempotency: a repeated request returns the original entry untouched.
        dao.getByRequestId(transaction.requestId)?.let { return@write it.id }

        if (transaction.type == TransactionType.TRANSFER) {
            throw LedgerException.InvalidOperation("Use recordTransfer for transfers")
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
                status = transaction.status,
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
            status = transfer.status,
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
        } else {
            requireCategory(db, edit.categoryId, current.type)
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

    override suspend fun delete(id: Long) = write { db ->
        val current = db.transactionDao().getById(id) ?: throw LedgerException.NotFound("Transaction $id")
        for (entry in legsOf(db, current)) {
            if (!entry.isDeleted) applyChange(db, entry, entry.copy(isDeleted = true), RevisionAction.DELETE)
        }
    }

    override suspend fun confirm(id: Long): Boolean =
        transition(id, from = TransactionStatus.SCHEDULED, to = TransactionStatus.PENDING)

    override suspend fun markCleared(id: Long): Boolean =
        transition(id, from = TransactionStatus.PENDING, to = TransactionStatus.CLEARED)

    override fun observeRecent(limit: Int): Flow<List<TransactionListItem>> =
        observe { it.transactionDao().observeRecent(limit) }.map { rows ->
            rows.map {
                TransactionListItem(
                    id = it.id,
                    type = it.type,
                    status = it.status,
                    amount = Money(it.amountCents),
                    occurredOn = it.occurredOn,
                    accountName = it.accountName,
                    categoryName = it.categoryName,
                    payeeName = it.payeeName,
                    counterpartAccountName = it.counterpartAccountName,
                )
            }
        }

    override fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate): Flow<PeriodSummary> =
        observe { it.transactionDao().observePeriodSummary(start, endInclusive) }.map {
            PeriodSummary(income = Money(it.incomeCents), expense = Money(it.expenseCents))
        }

    /** Applies a status change to an entry (both legs for transfers) exactly once. */
    private suspend fun transition(id: Long, from: TransactionStatus, to: TransactionStatus): Boolean =
        write { db ->
            val current = db.transactionDao().getById(id) ?: throw LedgerException.NotFound("Transaction $id")
            val entries = legsOf(db, current)
            if (entries.any { it.isDeleted || it.status != from }) return@write false
            entries.forEach { applyChange(db, it, it.copy(status = to), RevisionAction.STATUS_CHANGE) }
            true
        }

    /** Snapshots [old] into history, then stores [new] as the next revision. */
    private suspend fun applyChange(
        db: PocketBudgetDatabase,
        old: TransactionEntity,
        new: TransactionEntity,
        action: RevisionAction,
    ) {
        val now = clock.millis()
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

    private suspend fun legsOf(db: PocketBudgetDatabase, entry: TransactionEntity): List<TransactionEntity> =
        entry.transferId?.let { db.transactionDao().getTransferLegs(it) } ?: listOf(entry)

    private suspend fun requireLive(db: PocketBudgetDatabase, id: Long): TransactionEntity {
        val entry = db.transactionDao().getById(id) ?: throw LedgerException.NotFound("Transaction $id")
        if (entry.isDeleted) throw LedgerException.InvalidOperation("Deleted transactions cannot be edited")
        return entry
    }

    private suspend fun requireActiveAccount(db: PocketBudgetDatabase, accountId: Long) {
        val account = db.accountDao().getById(accountId)
        if (account == null || account.isArchived) throw LedgerException.AccountUnavailable(accountId)
    }

    private suspend fun requireCategory(db: PocketBudgetDatabase, categoryId: Long?, type: TransactionType) {
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
