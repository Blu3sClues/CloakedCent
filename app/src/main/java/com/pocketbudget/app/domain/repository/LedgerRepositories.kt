package com.pocketbudget.app.domain.repository

import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.Account
import com.pocketbudget.app.domain.model.AccountBalance
import com.pocketbudget.app.domain.model.Payee
import com.pocketbudget.app.domain.model.TransactionDetail
import com.pocketbudget.app.domain.model.TransactionFilter
import com.pocketbudget.app.domain.model.AccountType
import com.pocketbudget.app.domain.model.Category
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.NewAdjustment
import com.pocketbudget.app.domain.model.NewTransaction
import com.pocketbudget.app.domain.model.NewTransfer
import com.pocketbudget.app.domain.model.PayeeType
import com.pocketbudget.app.domain.model.PeriodSummary
import com.pocketbudget.app.domain.model.TransactionEdit
import com.pocketbudget.app.domain.model.TransactionListItem
import com.pocketbudget.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

// All write methods throw LedgerException for rule violations and are atomic:
// either every affected row is written or none is.

interface AccountRepository {
    suspend fun create(
        name: String,
        type: AccountType,
        openingBalance: Money = Money.ZERO,
        includeInTotals: Boolean = true,
    ): Long

    /** Active account, or null if missing or archived. */
    suspend fun get(id: Long): Account?

    /** Renames/retypes an active account. The opening balance is fixed at creation. */
    suspend fun update(id: Long, name: String, type: AccountType, includeInTotals: Boolean)

    /** Hides an account. Only allowed when its projected balance is zero, so totals stay truthful. */
    suspend fun archive(id: Long)

    /** Active accounts with balances, updated whenever the ledger changes. */
    fun observeBalances(): Flow<List<AccountBalance>>
}

interface CategoryRepository {
    /** [parentId] nests the category; the parent must have the same [kind]. */
    suspend fun create(name: String, kind: CategoryKind, parentId: Long? = null): Long

    /** Re-parents a category, rejecting cycles and kind mismatches. */
    suspend fun setParent(id: Long, parentId: Long?)

    fun observeAll(): Flow<List<Category>>
}

interface SettingsRepository {
    val themeMode: StateFlow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)

    /** When false, a receipt's page images are deleted once it is confirmed. */
    val keepReceiptImages: StateFlow<Boolean>
    fun setKeepReceiptImages(keep: Boolean)
}

interface PayeeRepository {
    /** Returns the existing payee with this name (case-insensitive) or creates it. */
    suspend fun getOrCreate(name: String, type: PayeeType = PayeeType.MERCHANT): Long

    fun observeAll(): Flow<List<Payee>>
}

interface TransactionRepository {
    /** Records income or expense; idempotent on [NewTransaction.requestId]. Returns the entry id. */
    suspend fun record(transaction: NewTransaction): Long

    /** Records both legs of a transfer atomically; idempotent on requestId. Returns the transfer id. */
    suspend fun recordTransfer(transfer: NewTransfer): Long

    /** Edits an entry (both legs for transfers), keeping the previous values as a revision. */
    suspend fun edit(id: Long, edit: TransactionEdit)

    /** Soft-deletes an entry (both legs for transfers), keeping it in history. */
    suspend fun delete(id: Long)

    /**
     * SCHEDULED -> the type's completed status (Received / Spent / Completed), both
     * legs atomically for transfers. Returns false if not scheduled, so repeats are harmless.
     * [actualAmount] (positive) replaces the projected amount in the same atomic step,
     * for variable bills; the original value stays in the audit history.
     */
    suspend fun confirm(id: Long, actualAmount: Money? = null): Boolean

    /** Records a balance correction as a new ledger entry; idempotent on requestId. Returns the entry id. */
    suspend fun adjustBalance(adjustment: NewAdjustment): Long

    /** Most recent entries; transfers appear once (outgoing leg). */
    fun observeRecent(limit: Int): Flow<List<TransactionListItem>>

    fun observePeriodSummary(start: LocalDate, endInclusive: LocalDate): Flow<PeriodSummary>

    /** Live entries matching [filter], newest first. Deleted entries are excluded. */
    fun search(filter: TransactionFilter): Flow<List<TransactionListItem>>

    /** Entry with its audit history; null if it does not exist. Deleted entries are still returned. */
    fun observeDetail(id: Long): Flow<TransactionDetail?>
}
