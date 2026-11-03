package com.pocketbudget.app.domain.repository

import com.pocketbudget.app.domain.model.ConfirmTarget
import com.pocketbudget.app.domain.model.DuplicateWarning
import com.pocketbudget.app.domain.model.ExpenseMatch
import com.pocketbudget.app.domain.model.ImportOutcome
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.model.PriceObservation
import com.pocketbudget.app.domain.model.Product
import com.pocketbudget.app.domain.model.ProductSummary
import com.pocketbudget.app.domain.model.ReceiptDetail
import com.pocketbudget.app.domain.model.ReceiptReview
import com.pocketbudget.app.domain.model.ReceiptSummary
import com.pocketbudget.app.domain.model.Store
import kotlinx.coroutines.flow.Flow

/**
 * Receipt capture, OCR and review. Until [confirm] succeeds nothing here touches the
 * ledger, balances or budgets. Writes throw ReceiptException or LedgerException.
 */
interface ReceiptRepository {
    /**
     * Stores the pages (metadata removed, encrypted) as a new draft. Idempotent on
     * [requestId]. An image already belonging to a live receipt is refused unless
     * [allowDuplicate].
     */
    suspend fun import(pages: List<PageInput>, requestId: String, allowDuplicate: Boolean = false): ImportOutcome

    /**
     * Reads the draft's pages offline and fills in suggested values for review.
     * [dayFirst] resolves dates such as 03/04 (true outside the US). Failures are kept
     * on the draft so the user can retry or enter values by hand.
     */
    suspend fun runOcr(id: Long, dayFirst: Boolean)

    fun observeSummaries(): Flow<List<ReceiptSummary>>

    fun observeDetail(id: Long): Flow<ReceiptDetail?>

    /** Id of the receipt attached to an expense, if any. */
    fun observeReceiptFor(transactionId: Long): Flow<Long?>

    /** Decrypted JPEG of one page, in memory only. */
    suspend fun loadPage(imageId: Long): ByteArray

    /** Saves the user's corrections. No financial effect. */
    suspend fun saveReview(id: Long, review: ReceiptReview)

    suspend fun duplicates(id: Long): List<DuplicateWarning>

    /** Existing expenses this receipt might belong to, best first. Never applied automatically. */
    suspend fun findMatches(id: Long): List<ExpenseMatch>

    /**
     * Verifies [review] adds up, then atomically creates or attaches exactly one expense,
     * splits it by category, and records products, aliases and prices. Repeating it for a
     * confirmed receipt returns the same expense. Returns the expense id. Unless
     * [keepImages], the page images are deleted once the confirmation is committed.
     */
    suspend fun confirm(id: Long, review: ReceiptReview, target: ConfirmTarget, keepImages: Boolean = true): Long

    /** Permanently erases a draft with its images. No financial effect. Repeating it is harmless. */
    suspend fun discard(id: Long)

    /**
     * Permanently erases a confirmed receipt with its images, items, prices and OCR text,
     * and any store, product or alias only it used. Its expense keeps its category splits
     * unless [deleteTransaction], which erases the expense in the same transaction
     * (refused while the expense has refunds). Repeating it is harmless.
     */
    suspend fun delete(id: Long, deleteTransaction: Boolean)

    /** Deletes image files left by interrupted imports or discarded drafts. */
    suspend fun removeOrphanImages(): Int
}

interface ProductRepository {
    fun observeProducts(): Flow<List<ProductSummary>>

    suspend fun get(id: Long): Product?

    /** Confirmed prices for one product, newest first. */
    fun observePrices(productId: Long): Flow<List<PriceObservation>>

    suspend fun update(
        id: Long,
        name: String,
        brand: String?,
        categoryId: Long?,
        packQuantityMilli: Long?,
        packUnit: MeasureUnit?,
    )

    fun observeStores(): Flow<List<Store>>
}
