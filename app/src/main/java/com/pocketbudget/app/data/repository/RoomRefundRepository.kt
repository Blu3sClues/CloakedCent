package com.pocketbudget.app.data.repository

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.db.PocketBudgetDatabase
import com.pocketbudget.app.data.db.dao.ItemRefundRow
import com.pocketbudget.app.data.db.entity.ReceiptItemEntity
import com.pocketbudget.app.data.db.entity.RefundAllocationEntity
import com.pocketbudget.app.data.db.entity.RefundEntity
import com.pocketbudget.app.data.db.entity.TransactionEntity
import com.pocketbudget.app.domain.model.LedgerException
import com.pocketbudget.app.domain.model.ReceiptMath
import com.pocketbudget.app.domain.model.ReceiptStatus
import com.pocketbudget.app.domain.model.RefundEntry
import com.pocketbudget.app.domain.model.RefundException
import com.pocketbudget.app.domain.model.RefundMath
import com.pocketbudget.app.domain.model.RefundSummary
import com.pocketbudget.app.domain.model.RefundableItem
import com.pocketbudget.app.domain.model.TransactionStatus
import com.pocketbudget.app.domain.model.TransactionType
import com.pocketbudget.app.domain.repository.RefundRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class RoomRefundRepository(
    database: () -> PocketBudgetDatabase,
    private val clock: Clock = Clock.systemUTC(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository(database, ioDispatcher), RefundRepository {

    /** An item with what it cost and its database row (for category and pre-tax value). */
    private class ItemPosition(val item: RefundableItem, val entity: ReceiptItemEntity, val refundedNet: Money) {
        val net: Money get() = Money(entity.lineTotalCents - entity.discountCents)
    }

    override fun observeSummary(transactionId: Long): Flow<RefundSummary?> = observe { db ->
        val items = db.receiptDao().observeIdForTransaction(transactionId).flatMapLatest { receiptId ->
            if (receiptId == null) {
                flowOf(null)
            } else {
                combine(db.receiptDao().observe(receiptId), db.receiptDao().observeItems(receiptId), db.refundDao().observeItemRefunds(receiptId)) { receipt, rows, refunds ->
                    Triple(receipt, rows, refunds)
                }
            }
        }
        combine(db.transactionDao().observeDetail(transactionId), db.refundDao().observeRefunds(transactionId), items) { row, refunds, receipt ->
            if (row == null || row.type != TransactionType.EXPENSE || row.status != TransactionStatus.SPENT || row.isDeleted || row.isAdjustment) {
                return@combine null
            }
            val original = Money(-row.amountCents)
            val positions = receipt?.takeIf { it.first?.status == ReceiptStatus.CONFIRMED }
                ?.let { (_, rows, itemRefunds) -> positions(original, rows, itemRefunds) }
                .orEmpty()
            RefundSummary(
                transactionId = transactionId,
                original = original,
                refunded = Money(refunds.sumOf { it.amountCents }),
                refunds = refunds.map { RefundEntry(it.transactionId, Money(it.amountCents), it.occurredOn, it.itemDescription) },
                items = positions.map { it.item },
            )
        }
    }

    override fun observeOriginal(refundTransactionId: Long): Flow<Long?> =
        observe { db -> db.refundDao().observeOriginal(refundTransactionId) }

    override suspend fun refundTransaction(
        transactionId: Long,
        amount: Money,
        occurredOn: LocalDate,
        requestId: String,
        note: String?,
    ): Long = write { db ->
        db.transactionDao().getByRequestId(requestId)?.let { return@write it.id }
        val original = requireRefundable(db, transactionId, occurredOn)
        if (!amount.isPositive) throw RefundException.InvalidAmount()
        val remaining = Money(-original.amountCents - db.refundDao().refundedTotal(transactionId))
        if (amount > remaining) throw RefundException.ExceedsRemaining(remaining)

        val refundId = insertRefund(db, original, amount, occurredOn, requestId, note, original.categoryId, null)
        val items = receiptPositions(db, original)
        if (items.isEmpty()) {
            allocate(db, refundId, original.categoryId, null, amount, null, null)
        } else {
            // A receipt's expense is refunded item by item, in proportion to what each item still has.
            val parts = RefundMath.spread(amount, items.map { it.item.remaining })
            items.zip(parts).filter { (_, part) -> part.isPositive }.forEach { (position, part) ->
                val net = RefundMath.netPart(part, position.item.paid, position.item.remaining, position.net, position.net - position.refundedNet)
                allocate(db, refundId, position.entity.categoryId, position.entity.id, part, net, null)
            }
        }
        refundId
    }

    override suspend fun refundItem(
        receiptItemId: Long,
        quantityMilli: Long?,
        amount: Money?,
        occurredOn: LocalDate,
        requestId: String,
    ): Long = write { db ->
        require((quantityMilli == null) != (amount == null)) { "Refund by quantity or by amount" }
        db.transactionDao().getByRequestId(requestId)?.let { return@write it.id }
        val entity = db.refundDao().getItem(receiptItemId) ?: throw LedgerException.NotFound("Item $receiptItemId")
        val receipt = db.receiptDao().getById(entity.receiptId)
        val transactionId = receipt?.takeIf { it.status == ReceiptStatus.CONFIRMED }?.transactionId
            ?: throw RefundException.NotRefundable()
        val original = requireRefundable(db, transactionId, occurredOn)
        val position = receiptPositions(db, original).first { it.entity.id == receiptItemId }
        val item = position.item
        val refund = if (quantityMilli != null) {
            if (quantityMilli <= 0 || quantityMilli > item.remainingQuantityMilli) throw RefundException.InvalidQuantity()
            RefundMath.amountForQuantity(item, quantityMilli)
        } else {
            amount!!
        }
        if (!refund.isPositive) {
            if (item.remaining.isPositive) throw RefundException.InvalidAmount() else throw RefundException.ExceedsRemaining(item.remaining)
        }
        if (refund > item.remaining) throw RefundException.ExceedsRemaining(item.remaining)

        val refundId = insertRefund(db, original, refund, occurredOn, requestId, null, entity.categoryId, receiptItemId)
        val net = RefundMath.netPart(refund, item.paid, item.remaining, position.net, position.net - position.refundedNet)
        allocate(db, refundId, entity.categoryId, receiptItemId, refund, net, quantityMilli)
        refundId
    }

    private suspend fun requireRefundable(db: PocketBudgetDatabase, id: Long, occurredOn: LocalDate): TransactionEntity {
        val original = db.transactionDao().getById(id)
        if (original == null || original.type != TransactionType.EXPENSE || original.status != TransactionStatus.SPENT ||
            original.isDeleted || original.isAdjustment
        ) {
            throw RefundException.NotRefundable()
        }
        val account = db.accountDao().getById(original.accountId)
        if (account == null || account.isArchived) throw LedgerException.AccountUnavailable(original.accountId)
        if (occurredOn.isBefore(original.occurredOn)) throw LedgerException.InvalidOperation("A refund cannot be dated before its purchase")
        return original
    }

    private suspend fun insertRefund(
        db: PocketBudgetDatabase,
        original: TransactionEntity,
        amount: Money,
        occurredOn: LocalDate,
        requestId: String,
        note: String?,
        categoryId: Long?,
        receiptItemId: Long?,
    ): Long {
        val now = clock.millis()
        val id = db.transactionDao().insert(
            TransactionEntity(
                requestId = requestId,
                accountId = original.accountId,
                type = TransactionType.REFUND,
                amountCents = amount.cents,
                status = TransactionType.REFUND.completedStatus,
                occurredOn = occurredOn,
                categoryId = categoryId,
                payeeId = original.payeeId,
                note = note,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.refundDao().insert(RefundEntity(refundTransactionId = id, originalTransactionId = original.id, receiptItemId = receiptItemId, createdAt = now))
        return id
    }

    private suspend fun allocate(
        db: PocketBudgetDatabase,
        refundId: Long,
        categoryId: Long?,
        itemId: Long?,
        amount: Money,
        net: Money?,
        quantityMilli: Long?,
    ) {
        db.refundDao().insertAllocation(
            RefundAllocationEntity(
                refundTransactionId = refundId,
                categoryId = categoryId,
                receiptItemId = itemId,
                amountCents = amount.cents,
                itemNetCents = net?.cents,
                quantityMilli = quantityMilli,
                createdAt = clock.millis(),
            ),
        )
    }

    private suspend fun receiptPositions(db: PocketBudgetDatabase, original: TransactionEntity): List<ItemPosition> {
        val receipt = db.receiptDao().getByTransaction(original.id)?.takeIf { it.status == ReceiptStatus.CONFIRMED } ?: return emptyList()
        return positions(Money(-original.amountCents), db.receiptDao().getItems(receipt.id), db.refundDao().getItemRefunds(receipt.id))
    }

    /** What each item cost (its share of the expense) and how much of it is already refunded. */
    private fun positions(original: Money, rows: List<ReceiptItemEntity>, refunds: List<ItemRefundRow>): List<ItemPosition> {
        val nets = rows.map { Money(it.lineTotalCents - it.discountCents) }
        if (rows.isEmpty() || nets.sumOf { it.cents } <= 0 || !original.isPositive) return emptyList()
        val paid = ReceiptMath.allocateItems(original, nets)
        val byItem = refunds.associateBy { it.receiptItemId }
        return rows.mapIndexed { i, row ->
            val refunded = byItem[row.id]
            ItemPosition(
                item = RefundableItem(
                    itemId = row.id,
                    description = row.description,
                    quantityMilli = row.quantityMilli,
                    unit = row.unit,
                    paid = paid[i],
                    refunded = Money(refunded?.amountCents ?: 0),
                    refundedQuantityMilli = refunded?.quantityMilli ?: 0,
                ),
                entity = row,
                refundedNet = Money(refunded?.netCents ?: 0),
            )
        }
    }
}
