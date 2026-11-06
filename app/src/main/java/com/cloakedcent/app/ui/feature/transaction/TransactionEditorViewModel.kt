package com.cloakedcent.app.ui.feature.transaction

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cloakedcent.app.CloakedCentApp
import com.cloakedcent.app.R
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.AccountBalance
import com.cloakedcent.app.domain.model.Category
import com.cloakedcent.app.domain.model.CategoryKind
import com.cloakedcent.app.domain.model.ExpenseDetails
import com.cloakedcent.app.domain.model.ExpenseItem
import com.cloakedcent.app.domain.model.NewTransaction
import com.cloakedcent.app.domain.model.NewTransfer
import com.cloakedcent.app.domain.model.TransactionEdit
import com.cloakedcent.app.domain.model.TransactionStatus
import com.cloakedcent.app.domain.model.TransactionType
import com.cloakedcent.app.domain.model.canSendTransfers
import com.cloakedcent.app.domain.model.displayBalance
import com.cloakedcent.app.domain.model.pathLabels
import com.cloakedcent.app.domain.repository.AccountRepository
import com.cloakedcent.app.domain.repository.CategoryRepository
import com.cloakedcent.app.domain.repository.PayeeRepository
import com.cloakedcent.app.domain.repository.TransactionRepository
import com.cloakedcent.app.ui.components.AmountInput
import com.cloakedcent.app.ui.components.ledgerErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A transfer's destination account and its balance afterwards, as balances are shown elsewhere. */
data class DestinationBalance(val accountName: String, val balance: Money)

/** One optional line in an expense's Additional Options. [key] tells rows apart while editing. */
data class ExpenseItemInput(val key: Int, val name: String = "", val price: String = "")

data class TransactionEditorState(
    val isNew: Boolean,
    val loading: Boolean,
    val type: TransactionType,
    val date: LocalDate,
    val accounts: List<Pair<Long, String>> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val categories: List<Pair<Long, String>> = emptyList(),
    val payeeNames: List<String> = emptyList(),
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val amount: String = "",
    val categoryId: Long? = null,
    val payee: String = "",
    val note: String = "",
    /** False = completed (Spent/Received/Completed by type); true = Scheduled. */
    val scheduled: Boolean = false,
    val optionsExpanded: Boolean = false,
    val tax: String = "",
    val items: List<ExpenseItemInput> = emptyList(),
    val storeLocation: String = "",
    val amountInvalid: Boolean = false,
    val accountMissing: Boolean = false,
    val toAccountMissing: Boolean = false,
    @StringRes val error: Int? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
) {
    val isTransfer: Boolean get() = type == TransactionType.TRANSFER

    /** Who a new entry can come from: credit cards cannot send a new transfer. */
    val sourceAccounts: List<Pair<Long, String>>
        get() {
            if (!isNew || !isTransfer) return accounts
            val cannotSend = balances.filterNot { it.account.type.canSendTransfers }.map { it.account.id }
            return accounts.filterNot { it.first in cannotSend }
        }

    /**
     * What a new transfer would leave in the destination account, once both accounts and a valid
     * amount are chosen. A scheduled transfer only changes the projected balance.
     */
    val destinationAfterTransfer: DestinationBalance?
        get() {
            if (!isNew || !isTransfer || accountId == null || accountId == toAccountId) return null
            val moved = AmountInput.parse(amount)?.takeIf { it.isPositive } ?: return null
            val destination = balances.firstOrNull { it.account.id == toAccountId } ?: return null
            val before = if (scheduled) destination.projected else destination.current
            return DestinationBalance(destination.account.name, destination.account.type.displayBalance(before + moved))
        }

    /** Only income and expenses carry a category and payee. */
    val hasCategory: Boolean get() = type == TransactionType.INCOME || type == TransactionType.EXPENSE

    /** Additional Options belong to a new expense only. */
    val hasAdditionalOptions: Boolean get() = isNew && type == TransactionType.EXPENSE

    /** The only problem Additional Options can have. Blank fields count as nothing. */
    val optionsExceedAmount: Boolean
        get() {
            if (!hasAdditionalOptions) return false
            val limit = AmountInput.parse(amount) ?: return false
            val entered = (items.map { it.price } + tax).map(::optionalPrice)
            return entered.sumOf { it.cents.toBigInteger() } > limit.cents.toBigInteger()
        }

    /** What was entered in Additional Options; null if nothing was. */
    val expenseDetails: ExpenseDetails?
        get() {
            if (!hasAdditionalOptions) return null
            val entered = items
                .map { ExpenseItem(it.name.trim(), optionalPrice(it.price)) }
                .filter { it.name.isNotEmpty() || it.price.isPositive }
            return ExpenseDetails(optionalPrice(tax), entered, storeLocation.trim()).takeUnless { it.isEmpty }
        }

    private fun optionalPrice(text: String): Money = AmountInput.parse(text)?.takeIf { it.isPositive } ?: Money.ZERO
}

/**
 * Collects form input and hands it to the repositories, which own every ledger
 * rule. The request id survives process death so a repeated save is recorded once.
 */
class TransactionEditorViewModel(
    private val transactionId: Long?,
    initialType: TransactionType,
    private val savedState: SavedStateHandle,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val payees: PayeeRepository,
    private val transactions: TransactionRepository,
    private val recordWithDetails: suspend (NewTransaction, String, ExpenseDetails) -> Long,
    clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val requestId: String =
        savedState.get<String>(KEY_REQUEST_ID) ?: UUID.randomUUID().toString().also { savedState[KEY_REQUEST_ID] = it }

    private val _state = MutableStateFlow(
        TransactionEditorState(
            isNew = transactionId == null,
            loading = transactionId != null,
            type = initialType,
            date = LocalDate.now(clock),
        ),
    )
    val state: StateFlow<TransactionEditorState> = _state.asStateFlow()

    private var allCategories: List<Category> = emptyList()

    init {
        viewModelScope.launch {
            combine(accounts.observeBalances(), categories.observeAll(), payees.observeAll()) { a, c, p ->
                Triple(a, c, p.map { it.name })
            }.collect { (balances, categoryList, payeeNames) ->
                allCategories = categoryList
                _state.update {
                    it.copy(
                        accounts = balances.map { b -> b.account.id to b.account.name },
                        balances = balances,
                        categories = categoryOptions(it.type),
                        payeeNames = payeeNames,
                    )
                }
            }
        }
        if (transactionId != null) loadExisting(transactionId)
    }

    private fun loadExisting(id: Long) = viewModelScope.launch {
        val detail = transactions.observeDetail(id).first()
        if (detail == null || detail.isDeleted) {
            _state.update { it.copy(loading = false, error = R.string.error_not_found) }
            return@launch
        }
        val outgoing = detail.amount.isNegative
        _state.update {
            it.copy(
                loading = false,
                type = detail.type,
                categories = categoryOptions(detail.type),
                accountId = if (detail.type == TransactionType.TRANSFER && !outgoing) detail.counterpartAccountId else detail.accountId,
                toAccountId = if (detail.type == TransactionType.TRANSFER) {
                    if (outgoing) detail.counterpartAccountId else detail.accountId
                } else {
                    null
                },
                amount = AmountInput.format(detail.amount.abs()),
                date = detail.occurredOn,
                categoryId = detail.categoryId,
                payee = detail.payeeName.orEmpty(),
                note = detail.note.orEmpty(),
                scheduled = detail.status.isScheduled,
            )
        }
    }

    private fun categoryOptions(type: TransactionType): List<Pair<Long, String>> {
        val kind = when (type) {
            TransactionType.INCOME -> CategoryKind.INCOME
            TransactionType.EXPENSE -> CategoryKind.EXPENSE
            TransactionType.TRANSFER, TransactionType.ADJUSTMENT, TransactionType.REFUND -> return emptyList()
        }
        val labels = allCategories.pathLabels()
        return allCategories.filter { it.kind == kind }.map { it.id to labels.getValue(it.id) }.sortedBy { it.second }
    }

    fun onTypeChange(type: TransactionType) {
        if (!_state.value.isNew) return
        _state.update {
            val changed = it.copy(type = type, categoryId = null, categories = categoryOptions(type), error = null)
            changed.copy(accountId = it.accountId?.takeIf { id -> changed.sourceAccounts.any { option -> option.first == id } })
        }
    }

    fun onAccountChange(id: Long) = _state.update { it.copy(accountId = id, accountMissing = false, error = null) }
    fun onToAccountChange(id: Long) = _state.update { it.copy(toAccountId = id, toAccountMissing = false, error = null) }
    fun onAmountChange(text: String) = _state.update { it.copy(amount = text, amountInvalid = false, error = null) }
    fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }
    fun onCategoryChange(id: Long?) = _state.update { it.copy(categoryId = id, error = null) }
    fun onPayeeChange(text: String) = _state.update { it.copy(payee = text) }
    fun onNoteChange(text: String) = _state.update { it.copy(note = text) }
    fun onScheduledChange(scheduled: Boolean) = _state.update { it.copy(scheduled = scheduled) }
    fun onOptionsToggle() = _state.update { it.copy(optionsExpanded = !it.optionsExpanded) }
    fun onTaxChange(text: String) = _state.update { it.copy(tax = text) }
    fun onStoreLocationChange(text: String) = _state.update { it.copy(storeLocation = text) }
    fun onAddItem() = _state.update { s -> s.copy(items = s.items + ExpenseItemInput((s.items.maxOfOrNull { it.key } ?: 0) + 1)) }
    fun onRemoveItem(key: Int) = _state.update { s -> s.copy(items = s.items.filterNot { it.key == key }) }
    fun onItemNameChange(key: Int, text: String) = updateItem(key) { it.copy(name = text) }
    fun onItemPriceChange(key: Int, text: String) = updateItem(key) { it.copy(price = text) }

    private fun updateItem(key: Int, change: (ExpenseItemInput) -> ExpenseItemInput) =
        _state.update { s -> s.copy(items = s.items.map { if (it.key == key) change(it) else it }) }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val amount = AmountInput.parse(s.amount)
        val invalid = s.copy(
            amountInvalid = amount == null,
            accountMissing = s.accountId == null,
            toAccountMissing = s.isTransfer && s.toAccountId == null,
        )
        if (invalid.amountInvalid || invalid.accountMissing || invalid.toAccountMissing || invalid.optionsExceedAmount) {
            // Opened so the problem is visible even if the menu was rolled up.
            _state.value = invalid.copy(optionsExpanded = invalid.optionsExpanded || invalid.optionsExceedAmount)
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val note = s.note.trim().ifEmpty { null }
                val payeeId = if (!s.hasCategory) null else s.payee.trim().takeIf { it.isNotEmpty() }?.let { payees.getOrCreate(it) }
                when {
                    transactionId != null -> transactions.edit(
                        transactionId,
                        TransactionEdit(amount!!, s.date, s.categoryId.takeIf { s.hasCategory }, payeeId, note),
                    )
                    s.isTransfer -> transactions.recordTransfer(
                        NewTransfer(requestId, s.accountId!!, s.toAccountId!!, amount!!, s.date, s.scheduled, note),
                    )
                    else -> {
                        val entry = NewTransaction(requestId, s.accountId!!, s.type, amount!!, s.date, s.scheduled, s.categoryId, payeeId, note)
                        val details = s.expenseDetails
                        if (details != null) recordWithDetails(entry, s.payee.trim(), details) else transactions.record(entry)
                    }
                }
                _state.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = ledgerErrorMessage(e)) }
            }
        }
    }

    companion object {
        const val ARG_TRANSACTION_ID = "transactionId"
        const val ARG_TYPE = "type"
        private const val KEY_REQUEST_ID = "requestId"

        val Factory = viewModelFactory {
            initializer {
                val handle = createSavedStateHandle()
                val container = (this[APPLICATION_KEY] as CloakedCentApp).container
                TransactionEditorViewModel(
                    transactionId = handle.get<Long>(ARG_TRANSACTION_ID)?.takeIf { it > 0 },
                    initialType = handle.get<String>(ARG_TYPE)?.let(TransactionType::valueOf) ?: TransactionType.EXPENSE,
                    savedState = handle,
                    accounts = container.accountRepository,
                    categories = container.categoryRepository,
                    payees = container.payeeRepository,
                    transactions = container.transactionRepository,
                    recordWithDetails = container.receiptRepository::recordExpense,
                )
            }
        }
    }
}
