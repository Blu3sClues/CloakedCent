package com.cloakedcent.app.data.repository

import com.cloakedcent.app.data.db.CloakedCentDatabase
import com.cloakedcent.app.data.db.entity.ReceiptEntity
import com.cloakedcent.app.domain.model.LedgerException
import com.cloakedcent.app.domain.model.ReceiptStatus
import com.cloakedcent.app.domain.model.TextKeys
import com.cloakedcent.app.domain.model.TransactionType

/**
 * Permanent deletion. CloakedCent is privacy-first: whatever the user deletes is
 * erased, with no record of it left in the database, including names (payees,
 * stores, products, learned aliases) that only it used. Each function must run inside
 * a write transaction started by `RoomRepository.erase`, which also scrubs the file.
 *
 * Rows are marked first and the database triggers allow removing only marked rows,
 * so the ledger's protections against accidental changes still hold.
 */
internal object Erasure {

    /**
     * Erases a transaction (both legs of a transfer) with its edit history, splits and,
     * for a refund, its refund records. A receipt attached to it is kept, without an
     * expense. Returns false if it does not exist (already erased).
     */
    suspend fun transaction(db: CloakedCentDatabase, id: Long, now: Long): Boolean {
        val dao = db.transactionDao()
        val erasure = db.erasureDao()
        val entry = dao.getById(id) ?: return false
        if (entry.type == TransactionType.EXPENSE && db.refundDao().refundedTotal(id) > 0) {
            throw LedgerException.InvalidOperation("Delete this expense's refunds first")
        }
        val legs = entry.transferId?.let { dao.getTransferLegs(it) } ?: listOf(entry)
        // Its current payee and any its edit history named, before that history goes.
        val payees = legs.mapNotNull { it.payeeId } + legs.flatMap { leg -> dao.getRevisions(leg.id).mapNotNull { it.payeeId } }
        legs.forEach { erasure.markTransaction(it.id) }
        for (leg in legs) {
            erasure.detachReceipt(leg.id, now)
            erasure.deleteSplits(leg.id)
            erasure.deleteRefundAllocations(leg.id)
            erasure.deleteRefunds(leg.id)
            erasure.releaseOccurrences(leg.id)
            erasure.deleteRevisions(leg.id)
            erasure.deleteTransaction(leg.id)
        }
        entry.transferId?.let { erasure.deleteTransfer(it) }
        removeUnusedPayees(db, payees)
        return true
    }

    /**
     * Erases a receipt: its items, prices, OCR text and image rows, and the store,
     * products and aliases only it used. Its expense, if any, is kept and keeps its
     * category splits. Returns the image files to delete once the transaction commits.
     */
    suspend fun receipt(db: CloakedCentDatabase, receipt: ReceiptEntity, now: Long): List<String> {
        val dao = db.receiptDao()
        val erasure = db.erasureDao()
        val id = receipt.id
        val marker = if (receipt.status == ReceiptStatus.CONFIRMED) ReceiptStatus.DELETED else ReceiptStatus.DISCARDED
        dao.update(receipt.copy(status = marker, updatedAt = now))
        erasure.unlinkSplits(id)
        erasure.unlinkRefunds(id)
        erasure.unlinkRefundAllocations(id)
        val items = dao.getItems(id)
        val imageFiles = dao.getImages(id).map { it.fileName }
        erasure.deletePrices(id)
        dao.deleteItems(id)
        dao.deleteOcrLines(id)
        dao.deleteImages(id)
        erasure.deleteReceipt(id)

        val store = receipt.storeId?.let { dao.getStore(it) }
        if (store != null) forgetAliases(db, store.id, items.map { it.rawText.ifBlank { it.description } })
        items.mapNotNull { it.productId }.distinct().forEach { product ->
            erasure.deleteAliasesOfUnusedProduct(product)
            erasure.deleteProductIfUnused(product)
        }
        if (store != null) {
            erasure.deleteStoreIfUnused(store.id)
            removeUnusedPayees(db, listOfNotNull(store.payeeId))
        }
        return imageFiles
    }

    /**
     * Aliases are learned from confirmed items (see confirm). One taught only by the erased
     * items goes; one other receipts also taught loses the erased confirmations.
     */
    private suspend fun forgetAliases(db: CloakedCentDatabase, storeId: Long, texts: List<String>) {
        val removed = texts.map(TextKeys::descriptionKey).filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        if (removed.isEmpty()) return
        val remaining = db.erasureDao().getConfirmedItemsAt(storeId)
            .map { TextKeys.descriptionKey(it.rawText.ifBlank { it.description }) }
            .groupingBy { it }.eachCount()
        for ((key, count) in removed) {
            val alias = db.receiptDao().getAlias(storeId, key) ?: continue
            val support = remaining[key] ?: 0
            if (support == 0) {
                db.erasureDao().deleteAlias(alias.id)
            } else {
                db.receiptDao().updateAlias(alias.copy(timesConfirmed = maxOf(support, alias.timesConfirmed - count)))
            }
        }
    }

    private suspend fun removeUnusedPayees(db: CloakedCentDatabase, ids: List<Long>) {
        ids.distinct().forEach { db.erasureDao().deletePayeeIfUnused(it) }
    }
}
