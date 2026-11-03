package com.pocketbudget.app.ui.feature.receipt

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.receipt.ReceiptImageUnreadableException
import com.pocketbudget.app.domain.model.CategoryKind
import com.pocketbudget.app.domain.model.ConfirmTarget
import com.pocketbudget.app.domain.model.DuplicateWarning
import com.pocketbudget.app.domain.model.ExpenseMatch
import com.pocketbudget.app.domain.model.GeoPoint
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.OcrSource
import com.pocketbudget.app.domain.model.ReceiptDetail
import com.pocketbudget.app.domain.model.ReceiptException
import com.pocketbudget.app.domain.model.ReceiptField
import com.pocketbudget.app.domain.model.ReceiptItemDraft
import com.pocketbudget.app.domain.model.ReceiptMath
import com.pocketbudget.app.domain.model.ReceiptParser
import com.pocketbudget.app.domain.model.ReceiptReview
import com.pocketbudget.app.domain.model.ReceiptStatus
import com.pocketbudget.app.domain.model.ReconcileIssue
import com.pocketbudget.app.domain.model.pathLabels
import com.pocketbudget.app.domain.repository.AccountRepository
import com.pocketbudget.app.domain.repository.CategoryRepository
import com.pocketbudget.app.domain.model.RefundSummary
import com.pocketbudget.app.domain.model.RefundableItem
import com.pocketbudget.app.domain.repository.ReceiptRepository
import com.pocketbudget.app.domain.repository.RefundRepository
import com.pocketbudget.app.ui.feature.refund.ItemRefundDialog
import com.pocketbudget.app.ui.feature.refund.RefundError
import com.pocketbudget.app.ui.feature.refund.RefundErrorText
import com.pocketbudget.app.ui.feature.refund.refundError
import com.pocketbudget.app.ui.feature.refund.refundStateLabel
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.pocketbudget.app.ui.components.AmountInput
import com.pocketbudget.app.ui.components.BackTopBar
import com.pocketbudget.app.ui.components.ConfirmDialog
import com.pocketbudget.app.ui.components.DateField
import com.pocketbudget.app.ui.components.MoneyField
import com.pocketbudget.app.ui.components.MoneyText
import com.pocketbudget.app.ui.components.PickerField
import com.pocketbudget.app.ui.components.SectionHeader
import com.pocketbudget.app.ui.components.ledgerErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

const val ARG_RECEIPT_ID = "receiptId"

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun receiptErrorMessage(error: Throwable): Int = when (error) {
    is ReceiptException.StoreRequired -> R.string.error_receipt_store
    is ReceiptException.DateRequired -> R.string.error_receipt_date
    is ReceiptException.TotalRequired -> R.string.error_receipt_total
    is ReceiptException.ItemsRequired -> R.string.error_receipt_items
    is ReceiptException.DescriptionRequired -> R.string.error_receipt_item_name
    is ReceiptException.InvalidQuantity -> R.string.error_receipt_quantity
    is ReceiptException.CategoryRequired -> R.string.error_receipt_category
    is ReceiptException.NotReconciled -> R.string.error_receipt_reconcile
    is ReceiptException.ExpenseUnavailable -> R.string.error_receipt_expense
    is ReceiptException.AmountDiffers -> R.string.error_receipt_amount
    is ReceiptException.NotEditable -> R.string.error_receipt_final
    is ReceiptException.NotFound -> R.string.error_not_found
    is ReceiptImageUnreadableException -> R.string.error_receipt_image
    else -> ledgerErrorMessage(error)
}

/** Text forms of quantities: thousandths of a unit as "2", "1.25" or "0.455". */
object QuantityText {
    private val PATTERN = Regex("""^(\d{1,6})(?:[.,](\d{1,3}))?$""")

    fun format(milli: Long): String {
        val whole = milli / 1000
        val fraction = milli % 1000
        return if (fraction == 0L) "$whole" else "$whole." + "%03d".format(fraction).trimEnd('0')
    }

    fun parse(text: String): Long? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        val fraction = match.groupValues[2].padEnd(3, '0').ifEmpty { "0" }.toLong()
        return (match.groupValues[1].toLong() * 1000 + fraction).takeIf { it > 0 }
    }
}

data class ItemForm(
    val key: Long,
    val id: Long = 0,
    val rawText: String = "",
    val description: String = "",
    val quantity: String = "1",
    val unit: MeasureUnit = MeasureUnit.EACH,
    val unitPrice: String = "",
    val lineTotal: String = "",
    val discount: String = "",
    val categoryId: Long? = null,
    val productId: Long? = null,
    val confidence: Int = 100,
    val uncertain: Boolean = false,
    val source: OcrSource? = null,
)

data class ReviewForm(
    val store: String = "",
    val address: String = "",
    val date: LocalDate,
    val time: String = "",
    val receiptNumber: String = "",
    val subtotal: String = "",
    val tax: String = "",
    val discount: String = "",
    val total: String = "",
    val defaultCategoryId: Long? = null,
    val items: List<ItemForm> = emptyList(),
    val location: GeoPoint? = null,
    /** The total as read from the receipt, kept for comparison when items are corrected. */
    val printedTotal: Money? = null,
)

sealed interface ExpenseChoice {
    data object New : ExpenseChoice
    data class Match(val transactionId: Long) : ExpenseChoice
}

data class ReviewUiState(
    val detail: ReceiptDetail? = null,
    val form: ReviewForm? = null,
    val pages: List<ImageBitmap> = emptyList(),
    val categories: List<Pair<Long, String>> = emptyList(),
    val accounts: List<Pair<Long, String>> = emptyList(),
    val accountId: Long? = null,
    val matches: List<ExpenseMatch> = emptyList(),
    val choice: ExpenseChoice? = null,
    val replaceAmount: Boolean = false,
    val duplicates: List<DuplicateWarning> = emptyList(),
    val reading: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null,
    val done: Boolean = false,
    val highlight: OcrSource? = null,
    /** Refund position of the confirmed receipt's expense, per item. */
    val refunds: RefundSummary? = null,
    val refundError: RefundError? = null,
)

private class FormError(val messageId: Int) : Exception()

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptReviewViewModel(
    private val receipts: ReceiptRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    val receiptId: Long,
    private val refunds: RefundRepository? = null,
    private val dayFirst: Boolean = Locale.getDefault().country != "US",
    /** Read at confirmation, so a change in Settings applies to receipts already open. */
    private val keepImages: () -> Boolean = { true },
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var nextKey = 0L
    private var rebuildForm = true

    /** Set once the user changes anything, so later data never overwrites their edits. */
    private var edited = false

    init {
        viewModelScope.launch {
            val list = accounts.observeBalances().first().map { it.account.id to it.account.name }
            _state.update { it.copy(accounts = list, accountId = it.accountId ?: list.firstOrNull()?.first) }
        }
        viewModelScope.launch {
            categories.observeAll().collect { all ->
                val labels = all.pathLabels()
                val options = all.filter { it.kind == CategoryKind.EXPENSE }.map { it.id to labels.getValue(it.id) }.sortedBy { it.second }
                _state.update { it.copy(categories = options) }
            }
        }
        viewModelScope.launch {
            receipts.observeDetail(receiptId).collect { detail ->
                _state.update { it.copy(detail = detail) }
                detail ?: return@collect
                if (_state.value.pages.isEmpty() && detail.pages.isNotEmpty()) loadPages(detail)
                val ready = detail.status != ReceiptStatus.DRAFT || detail.ocrError != null
                // Also rebuild an untouched form that was built before the items arrived.
                val itemsArrived = !edited && _state.value.form?.items?.isEmpty() == true && detail.review.items.isNotEmpty()
                if ((rebuildForm || itemsArrived) && ready) {
                    rebuildForm = false
                    _state.update { it.copy(form = formOf(detail)) }
                    if (detail.status == ReceiptStatus.NEEDS_REVIEW || detail.status == ReceiptStatus.DRAFT) refreshChecks()
                }
                if (detail.status == ReceiptStatus.DRAFT && detail.ocrError == null && !_state.value.reading) read()
            }
        }
        val repository = refunds
        if (repository != null) {
            viewModelScope.launch {
                receipts.observeDetail(receiptId)
                    .map { it?.transactionId }
                    .distinctUntilChanged()
                    .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeSummary(id) }
                    .collect { summary -> _state.update { it.copy(refunds = summary) } }
            }
        }
    }

    fun refundItem(itemId: Long, quantityMilli: Long?, amount: Money?, date: LocalDate, requestId: String) {
        val repository = refunds ?: return
        _state.update { it.copy(refundError = null) }
        viewModelScope.launch {
            try {
                repository.refundItem(itemId, quantityMilli, amount, date, requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(refundError = refundError(e)) }
            }
        }
    }

    private fun loadPages(detail: ReceiptDetail) = viewModelScope.launch {
        try {
            val images = detail.pages.map { page ->
                val bytes = receipts.loadPage(page.imageId)
                withContext(Dispatchers.Default) {
                    val options = BitmapFactory.Options().apply { inSampleSize = if (maxOf(page.width, page.height) > 2400) 2 else 1 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options).asImageBitmap()
                }
            }
            _state.update { it.copy(pages = images) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = receiptErrorMessage(e)) }
        }
    }

    /** Offline OCR on a background dispatcher; the screen stays usable meanwhile. */
    private fun read() {
        _state.update { it.copy(reading = true) }
        viewModelScope.launch {
            try {
                receipts.runOcr(receiptId, dayFirst)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = receiptErrorMessage(e)) }
            } finally {
                _state.update { it.copy(reading = false) }
            }
        }
    }

    fun readAgain() {
        rebuildForm = true
        edited = false
        _state.update { it.copy(form = null) }
        read()
    }

    private fun formOf(detail: ReceiptDetail): ReviewForm {
        val r = detail.review
        return ReviewForm(
            store = r.storeName,
            address = r.storeAddress,
            date = r.purchasedOn ?: LocalDate.now(),
            time = r.purchasedTime?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty(),
            receiptNumber = r.receiptNumber,
            subtotal = r.subtotal?.let(AmountInput::format).orEmpty(),
            tax = r.tax.takeIf { it.isPositive }?.let(AmountInput::format).orEmpty(),
            discount = r.discount.takeIf { it.isPositive }?.let(AmountInput::format).orEmpty(),
            total = r.total?.let(AmountInput::format).orEmpty(),
            defaultCategoryId = r.defaultCategoryId,
            items = r.items.map { item ->
                ItemForm(
                    key = nextKey++,
                    id = item.id,
                    rawText = item.rawText,
                    description = item.description,
                    quantity = QuantityText.format(item.quantityMilli),
                    unit = item.unit,
                    unitPrice = item.unitPrice?.let(AmountInput::format).orEmpty(),
                    lineTotal = AmountInput.format(item.lineTotal),
                    discount = item.discount.takeIf { it.isPositive }?.let(AmountInput::format).orEmpty(),
                    categoryId = item.categoryId,
                    productId = item.productId,
                    confidence = item.confidence,
                    uncertain = item.uncertain,
                    source = item.source,
                )
            },
            location = r.location,
            printedTotal = r.total,
        )
    }

    fun edit(change: (ReviewForm) -> ReviewForm) {
        edited = true
        _state.update { s -> s.copy(form = s.form?.let(change), message = null) }
    }

    fun editItem(key: Long, change: (ItemForm) -> ItemForm) =
        edit { f -> recalculated(f.copy(items = f.items.map { if (it.key == key) change(it) else it })) }

    /** Tax and receipt-level discounts change the total. */
    fun editAdjustments(change: (ReviewForm) -> ReviewForm) = edit { recalculated(change(it)) }

    fun addItem() = edit { it.copy(items = it.items + ItemForm(key = nextKey++)) }

    fun removeItem(key: Long) = edit { f -> recalculated(f.copy(items = f.items.filterNot { it.key == key })) }

    fun highlight(source: OcrSource?) = _state.update { it.copy(highlight = source) }

    fun selectAccount(id: Long) = _state.update { it.copy(accountId = id) }

    fun choose(choice: ExpenseChoice) = _state.update { it.copy(choice = choice, replaceAmount = false, message = null) }

    fun setReplaceAmount(on: Boolean) = _state.update { it.copy(replaceAmount = on) }

    fun setLocation(point: GeoPoint?) = edit { it.copy(location = point) }

    fun showMessage(id: Int) = _state.update { it.copy(message = id) }

    fun save() = run { review ->
        receipts.saveReview(receiptId, review)
        refreshChecks()
        _state.update { it.copy(message = R.string.review_saved) }
    }

    fun confirm() = run { review ->
        receipts.saveReview(receiptId, review)
        val fresh = receipts.findMatches(receiptId)
        val s = _state.value
        // New candidates appeared since the user chose: show them and let the user decide.
        if (fresh.map { it.transactionId } != s.matches.map { it.transactionId }) {
            _state.update { it.copy(matches = fresh, choice = if (fresh.isEmpty()) ExpenseChoice.New else null, message = R.string.review_choose_target) }
            return@run
        }
        val target = when (val choice = s.choice) {
            null -> {
                _state.update { it.copy(message = R.string.review_choose_target) }
                return@run
            }
            ExpenseChoice.New -> ConfirmTarget.NewExpense(s.accountId ?: throw FormError(R.string.error_account_required))
            is ExpenseChoice.Match -> ConfirmTarget.Existing(choice.transactionId, s.replaceAmount)
        }
        receipts.confirm(receiptId, review, target, keepImages())
        _state.update { it.copy(done = true) }
    }

    fun discard() {
        viewModelScope.launch {
            try {
                receipts.discard(receiptId)
                _state.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = receiptErrorMessage(e)) }
            }
        }
    }

    private suspend fun refreshChecks() {
        val matches = receipts.findMatches(receiptId)
        val duplicates = receipts.duplicates(receiptId)
        _state.update { s ->
            val keep = when (val c = s.choice) {
                is ExpenseChoice.Match -> c.takeIf { choice -> matches.any { it.transactionId == choice.transactionId } }
                else -> c
            }
            s.copy(matches = matches, duplicates = duplicates, choice = keep ?: if (matches.isEmpty()) ExpenseChoice.New else null)
        }
    }

    private fun run(action: suspend (ReceiptReview) -> Unit) {
        val form = _state.value.form ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                action(form.toReview())
            } catch (e: CancellationException) {
                throw e
            } catch (e: FormError) {
                _state.update { it.copy(message = e.messageId) }
            } catch (e: Exception) {
                _state.update { it.copy(message = receiptErrorMessage(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PocketBudgetApp).container
                ReceiptReviewViewModel(
                    container.receiptRepository,
                    container.accountRepository,
                    container.categoryRepository,
                    requireNotNull(createSavedStateHandle().get<Long>(ARG_RECEIPT_ID)),
                    container.refundRepository,
                    keepImages = { container.settingsRepository.keepReceiptImages.value },
                )
            }
        }
    }
}

/**
 * After a correction, the subtotal becomes the items after their discounts and the total
 * subtotal - receipt discounts + tax, in exact cents. Left alone while any amount is not
 * yet a valid number, so typing is never interrupted.
 */
internal fun recalculated(form: ReviewForm): ReviewForm {
    val nets = form.items.map { item ->
        val line = AmountInput.parse(item.lineTotal) ?: return form
        val discount = if (item.discount.isBlank()) Money.ZERO else AmountInput.parse(item.discount) ?: return form
        line - discount
    }
    val tax = if (form.tax.isBlank()) Money.ZERO else AmountInput.parse(form.tax) ?: return form
    val discount = if (form.discount.isBlank()) Money.ZERO else AmountInput.parse(form.discount) ?: return form
    val subtotal = nets.fold(Money.ZERO) { sum, it -> sum + it }
    return form.copy(subtotal = AmountInput.format(subtotal), total = AmountInput.format(subtotal - discount + tax))
}

private fun money(text: String, required: Boolean): Money? {
    if (text.isBlank()) return if (required) throw FormError(R.string.error_amount_invalid) else null
    return AmountInput.parse(text)?.takeIf { !it.isNegative } ?: throw FormError(R.string.error_amount_invalid)
}

private fun ReviewForm.toReview(): ReceiptReview {
    val parsedTime = time.trim().takeIf { it.isNotEmpty() }?.let { text ->
        val match = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(text) ?: throw FormError(R.string.error_receipt_time)
        val (h, m) = match.destructured
        if (h.toInt() > 23 || m.toInt() > 59) throw FormError(R.string.error_receipt_time)
        LocalTime.of(h.toInt(), m.toInt())
    }
    return ReceiptReview(
        storeName = store.trim(),
        storeAddress = address.trim(),
        purchasedOn = date,
        purchasedTime = parsedTime,
        subtotal = money(subtotal, required = false),
        tax = money(tax, required = false) ?: Money.ZERO,
        discount = money(discount, required = false) ?: Money.ZERO,
        total = money(total, required = false),
        receiptNumber = receiptNumber.trim(),
        defaultCategoryId = defaultCategoryId,
        items = items.map { item ->
            ReceiptItemDraft(
                id = item.id,
                rawText = item.rawText.ifBlank { item.description },
                description = item.description.trim(),
                quantityMilli = QuantityText.parse(item.quantity) ?: throw FormError(R.string.error_receipt_quantity),
                unit = item.unit,
                unitPrice = money(item.unitPrice, required = false),
                lineTotal = money(item.lineTotal, required = true)!!,
                discount = money(item.discount, required = false) ?: Money.ZERO,
                categoryId = item.categoryId,
                productId = item.productId,
                confidence = item.confidence,
                uncertain = item.uncertain,
                source = item.source,
            )
        },
        location = location,
    )
}

// ------------------------------------------------------------------ UI

@Composable
fun ReceiptReviewRoute(
    onBack: () -> Unit,
    onOpenReceipt: (Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    viewModel: ReceiptReviewViewModel = viewModel(factory = ReceiptReviewViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onBack() }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReread by remember { mutableStateOf(false) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    val detail = state.detail
    val editable = detail?.status == ReceiptStatus.DRAFT || detail?.status == ReceiptStatus.NEEDS_REVIEW

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.review_title), onBack) {
                if (editable && !state.reading) {
                    TextButton(onClick = { confirmReread = true }) { Text(stringResource(R.string.review_rerun)) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (detail == null) {
                CircularProgressIndicator()
                return@Column
            }
            PageStrip(state, detail, onOpen = { zoomPage = it })
            if (state.reading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.review_reading))
                }
            }
            detail.ocrError?.let { Text(stringResource(R.string.review_ocr_failed, it), color = MaterialTheme.colorScheme.error) }
            when {
                detail.status == ReceiptStatus.CONFIRMED -> ConfirmedSummary(detail, state, viewModel::refundItem, onOpenTransaction)
                state.form != null && editable -> ReviewEditor(state, viewModel, onOpenReceipt)
            }
            state.message?.let {
                Text(
                    stringResource(it),
                    color = if (it == R.string.review_saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (editable && state.form != null) {
                Button(onClick = viewModel::confirm, enabled = !state.busy && !state.reading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_confirm))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::save, enabled = !state.busy && !state.reading) { Text(stringResource(R.string.review_save)) }
                    TextButton(onClick = { confirmDiscard = true }, enabled = !state.busy) {
                        Text(stringResource(R.string.review_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.review_discard_title),
            text = stringResource(R.string.review_discard_body),
            confirmLabel = stringResource(R.string.review_discard),
            onConfirm = { confirmDiscard = false; viewModel.discard() },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmReread) {
        ConfirmDialog(
            title = stringResource(R.string.review_rerun_title),
            text = stringResource(R.string.review_rerun_body),
            confirmLabel = stringResource(R.string.review_rerun),
            onConfirm = { confirmReread = false; viewModel.readAgain() },
            onDismiss = { confirmReread = false },
        )
    }
    zoomPage?.let { index ->
        if (detail != null && index < state.pages.size) {
            ZoomDialog(state.pages[index], detail, index, state.highlight, onDismiss = { zoomPage = null })
        }
    }
}

@Composable
private fun PageStrip(state: ReviewUiState, detail: ReceiptDetail, onOpen: (Int) -> Unit) {
    if (state.pages.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(state.pages) { index, image ->
            val description = stringResource(R.string.review_page_description, index + 1)
            Card(Modifier.clickable(onClickLabel = description, role = Role.Image) { onOpen(index) }) {
                PageWithBoxes(image, detail, index, state.highlight, Modifier.height(320.dp))
            }
        }
    }
}

/** The page with low-confidence lines outlined and the focused field's source filled. */
@Composable
private fun PageWithBoxes(image: ImageBitmap, detail: ReceiptDetail, index: Int, highlight: OcrSource?, modifier: Modifier) {
    val page = detail.pages.getOrNull(index) ?: return
    val uncertainColor = MaterialTheme.colorScheme.tertiary
    val focusColor = MaterialTheme.colorScheme.primary
    Box(modifier.aspectRatio(image.width.toFloat() / image.height)) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / page.width
            detail.ocrLines.filter { it.page == page.page && it.box != null }.forEach { line ->
                val box = line.box!!
                val topLeft = Offset(box.left * scale, box.top * scale)
                val rect = Size((box.right - box.left) * scale, (box.bottom - box.top) * scale)
                if (highlight != null && highlight.page == line.page && highlight.line == line.index) {
                    drawRect(focusColor.copy(alpha = 0.3f), topLeft, rect)
                } else if (line.confidence < ReceiptParser.CONFIDENT) {
                    drawRect(uncertainColor, topLeft, rect, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun ZoomDialog(image: ImageBitmap, detail: ReceiptDetail, index: Int, highlight: OcrSource?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset += pan
        }
        Box(Modifier.fillMaxSize().transformable(transform), contentAlignment = Alignment.Center) {
            PageWithBoxes(
                image,
                detail,
                index,
                highlight,
                Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_back), tint = Color.White)
            }
        }
    }
}

@Composable
private fun ReviewEditor(state: ReviewUiState, vm: ReceiptReviewViewModel, onOpenReceipt: (Long) -> Unit) {
    val form = state.form ?: return
    val detail = state.detail ?: return
    val uncertain = detail.uncertain
    val check = stringResource(R.string.review_check)
    fun focusOn(field: ReceiptField) = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.highlight(detail.fieldSources[field]) }

    if (uncertain.isNotEmpty() || form.items.any { it.uncertain }) {
        Text(stringResource(R.string.review_uncertain_hint), style = MaterialTheme.typography.bodyMedium)
    }
    state.duplicates.forEach { warning ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        when (warning) {
                            is DuplicateWarning.SameImage -> R.string.review_duplicate_same
                            is DuplicateWarning.SameContent -> R.string.review_duplicate_content
                            is DuplicateWarning.SimilarImage -> R.string.review_duplicate_similar
                        },
                    ),
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = { onOpenReceipt(warning.receiptId) }) { Text(stringResource(R.string.review_open_other)) }
            }
        }
    }

    OutlinedTextField(
        value = form.store,
        onValueChange = { v -> vm.edit { it.copy(store = v) } },
        label = { Text(stringResource(R.string.field_store)) },
        isError = ReceiptField.STORE in uncertain,
        supportingText = if (ReceiptField.STORE in uncertain) ({ Text(check) }) else null,
        singleLine = true,
        modifier = focusOn(ReceiptField.STORE),
    )
    OutlinedTextField(
        value = form.address,
        onValueChange = { v -> vm.edit { it.copy(address = v) } },
        label = { Text(stringResource(R.string.field_store_address)) },
        modifier = focusOn(ReceiptField.ADDRESS),
    )
    LocationRow(form.location, onLocation = { vm.setLocation(it) }, onMessage = vm::showMessage)
    DateField(stringResource(R.string.field_date), form.date, { d -> vm.edit { it.copy(date = d) } })
    if (ReceiptField.DATE in uncertain) Text(check, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.time,
            onValueChange = { v -> vm.edit { it.copy(time = v) } },
            label = { Text(stringResource(R.string.field_time)) },
            singleLine = true,
            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) vm.highlight(detail.fieldSources[ReceiptField.TIME]) },
        )
        OutlinedTextField(
            value = form.receiptNumber,
            onValueChange = { v -> vm.edit { it.copy(receiptNumber = v) } },
            label = { Text(stringResource(R.string.field_receipt_number)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }

    SectionHeader(stringResource(R.string.review_items), actionLabel = stringResource(R.string.review_add_item), onAction = vm::addItem)
    form.items.forEach { item -> ItemCard(item, state.categories, vm, check) }
    PickerField(
        label = stringResource(R.string.field_default_category),
        options = state.categories,
        selected = form.defaultCategoryId,
        onSelect = { id -> vm.edit { it.copy(defaultCategoryId = id) } },
    )

    SectionHeader(stringResource(R.string.review_totals))
    MoneyField(
        form.subtotal, { v -> vm.edit { it.copy(subtotal = v) } }, stringResource(R.string.field_subtotal),
        modifier = focusOn(ReceiptField.SUBTOTAL), isError = ReceiptField.SUBTOTAL in uncertain,
        supportingText = if (ReceiptField.SUBTOTAL in uncertain) check else null,
    )
    MoneyField(
        form.discount, { v -> vm.editAdjustments { it.copy(discount = v) } }, stringResource(R.string.field_discount),
        modifier = focusOn(ReceiptField.DISCOUNT), isError = ReceiptField.DISCOUNT in uncertain,
        supportingText = if (ReceiptField.DISCOUNT in uncertain) check else null,
    )
    MoneyField(
        form.tax, { v -> vm.editAdjustments { it.copy(tax = v) } }, stringResource(R.string.field_tax),
        modifier = focusOn(ReceiptField.TAX), isError = ReceiptField.TAX in uncertain,
        supportingText = if (ReceiptField.TAX in uncertain) check else null,
    )
    MoneyField(
        form.total, { v -> vm.edit { it.copy(total = v) } }, stringResource(R.string.field_total),
        modifier = focusOn(ReceiptField.TOTAL), isError = ReceiptField.TOTAL in uncertain,
        supportingText = if (ReceiptField.TOTAL in uncertain) check else null,
    )
    ReconcileSummary(form)
    PrintedTotalCheck(form)

    SectionHeader(stringResource(R.string.review_expense))
    ExpenseTarget(state, vm)
}

@Composable
private fun ItemCard(item: ItemForm, categories: List<Pair<Long, String>>, vm: ReceiptReviewViewModel, check: String) {
    val units = MeasureUnit.entries.map { it to it.symbol }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.uncertain) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.description,
                    onValueChange = { v -> vm.editItem(item.key) { it.copy(description = v) } },
                    label = { Text(stringResource(R.string.field_item_name)) },
                    isError = item.uncertain,
                    supportingText = if (item.uncertain) ({ Text(check) }) else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) vm.highlight(item.source) },
                )
                IconButton(onClick = { vm.removeItem(item.key) }) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.review_remove_item))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = item.quantity,
                    onValueChange = { v -> vm.editItem(item.key) { it.copy(quantity = v) } },
                    label = { Text(stringResource(R.string.field_quantity)) },
                    isError = QuantityText.parse(item.quantity) == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                PickerField(
                    label = stringResource(R.string.field_unit),
                    options = units,
                    selected = item.unit,
                    onSelect = { u -> vm.editItem(item.key) { it.copy(unit = u) } },
                    modifier = Modifier.weight(1f),
                )
                MoneyField(
                    item.unitPrice, { v -> vm.editItem(item.key) { it.copy(unitPrice = v) } }, stringResource(R.string.field_unit_price),
                    modifier = Modifier.weight(1.4f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(
                    item.lineTotal, { v -> vm.editItem(item.key) { it.copy(lineTotal = v) } }, stringResource(R.string.field_line_total),
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) vm.highlight(item.source) },
                    isError = item.uncertain,
                )
                MoneyField(
                    item.discount, { v -> vm.editItem(item.key) { it.copy(discount = v) } }, stringResource(R.string.field_item_discount),
                    modifier = Modifier.weight(1f),
                )
            }
            PickerField(
                label = stringResource(R.string.field_category),
                options = categories,
                selected = item.categoryId,
                onSelect = { id -> vm.editItem(item.key) { it.copy(categoryId = id) } },
            )
        }
    }
}

@Composable
private fun ReconcileSummary(form: ReviewForm) {
    // Live check while editing; only fully parseable values are compared.
    val review = runCatching { form.toReview() }.getOrNull()
    val issues = review?.let(ReceiptMath::reconcile)
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (review != null) {
                val itemsNet = review.items.fold(Money.ZERO) { sum, item -> sum + item.net }
                SummaryRow(stringResource(R.string.review_items_sum), itemsNet)
                SummaryRow(stringResource(R.string.review_expected_total), itemsNet - review.discount + review.tax)
            }
            when {
                issues == null -> Unit
                issues.isEmpty() -> Text(stringResource(R.string.review_balanced), color = MaterialTheme.colorScheme.primary)
                else -> issues.forEach { issue ->
                    val text = when (issue) {
                        is ReconcileIssue.TotalMismatch -> stringResource(R.string.review_difference, AmountInput.format(issue.difference.abs()))
                        is ReconcileIssue.SubtotalMismatch -> stringResource(R.string.review_subtotal_difference, AmountInput.format(issue.difference.abs()))
                        ReconcileIssue.MissingTotal -> stringResource(R.string.error_receipt_total)
                        ReconcileIssue.NegativeAmount -> stringResource(R.string.error_amount_invalid)
                    }
                    Text(text, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Warns when the corrected items no longer add up to what the receipt printed. */
@Composable
private fun PrintedTotalCheck(form: ReviewForm) {
    val printed = form.printedTotal ?: return
    val total = AmountInput.parse(form.total) ?: return
    if (total == printed) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            stringResource(R.string.review_printed_total, AmountInput.format(printed), AmountInput.format(total)),
            Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun SummaryRow(label: String, amount: Money) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        MoneyText(amount)
    }
}

@Composable
private fun ExpenseTarget(state: ReviewUiState, vm: ReceiptReviewViewModel) {
    if (state.matches.isNotEmpty()) {
        Text(stringResource(R.string.review_matches_hint), style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(stringResource(R.string.review_new_expense), state.choice == ExpenseChoice.New) { vm.choose(ExpenseChoice.New) }
        state.matches.forEach { match ->
            val selected = state.choice == ExpenseChoice.Match(match.transactionId)
            Column {
                ChoiceRow(
                    stringResource(
                        R.string.review_match_row,
                        AmountInput.format(match.amount),
                        dateFormat.format(match.occurredOn),
                        listOfNotNull(match.payeeName, match.categoryName, match.accountName).joinToString(" · "),
                    ) + if (match.fromRecurring) " · " + stringResource(R.string.review_match_recurring) else "",
                    selected,
                ) { vm.choose(ExpenseChoice.Match(match.transactionId)) }
                if (selected && match.status.isScheduled) {
                    Text(stringResource(R.string.review_match_scheduled), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp))
                }
                if (selected && !match.status.isScheduled && !match.exactAmount) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 36.dp)) {
                        Checkbox(checked = state.replaceAmount, onCheckedChange = vm::setReplaceAmount)
                        Text(stringResource(R.string.review_replace_amount), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (state.choice == ExpenseChoice.New || state.matches.isEmpty()) {
        PickerField(
            label = stringResource(R.string.field_account),
            options = state.accounts,
            selected = state.accountId,
            onSelect = vm::selectAccount,
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LocationRow(location: GeoPoint?, onLocation: (GeoPoint?) -> Unit, onMessage: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    fun capture() {
        finding = true
        scope.launch {
            val point = StoreLocation.capture(context)
            finding = false
            if (point != null) onLocation(point) else onMessage(R.string.review_location_unavailable)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) capture() else onMessage(R.string.review_location_denied)
    }
    Column {
        Text(stringResource(R.string.review_location), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.review_location_hint), style = MaterialTheme.typography.bodySmall)
        when {
            finding -> Text(stringResource(R.string.review_location_finding))
            location != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.review_location_set, "%.5f".format(location.latitudeE7 / 1e7), "%.5f".format(location.longitudeE7 / 1e7)),
                    Modifier.weight(1f),
                )
                TextButton(onClick = { onLocation(null) }) { Text(stringResource(R.string.review_location_remove)) }
            }
            else -> TextButton(onClick = {
                if (StoreLocation.hasPermission(context)) capture() else permission.launch(StoreLocation.PERMISSIONS)
            }) {
                Icon(painterResource(R.drawable.ic_location), null)
                Text(stringResource(R.string.review_location_capture), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ConfirmedSummary(
    detail: ReceiptDetail,
    state: ReviewUiState,
    onRefundItem: (Long, Long?, Money?, LocalDate, String) -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val r = detail.review
    val names = state.categories.toMap()
    val refundable = state.refunds?.items.orEmpty().associateBy { it.itemId }
    var refunding by remember { mutableStateOf<RefundableItem?>(null) }
    Text(stringResource(R.string.review_confirmed_info), color = MaterialTheme.colorScheme.primary)
    Text(r.storeName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    if (r.storeAddress.isNotBlank()) Text(r.storeAddress, style = MaterialTheme.typography.bodyMedium)
    r.purchasedOn?.let { Text(dateFormat.format(it)) }
    r.items.forEach { item ->
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(item.description)
                Text(
                    listOfNotNull(
                        if (item.quantityMilli != 1000L || item.unit != MeasureUnit.EACH) "${QuantityText.format(item.quantityMilli)} ${item.unit.symbol}" else null,
                        item.categoryId?.let(names::get),
                        refundable[item.id]?.let { refundStateLabel(it.state) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                refundable[item.id]?.takeIf { it.remaining.isPositive }?.let { position ->
                    TextButton(onClick = { refunding = position }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.refund_action))
                    }
                }
            }
            MoneyText(item.net)
        }
    }
    RefundErrorText(state.refundError)
    refunding?.let { item ->
        ItemRefundDialog(
            item = item,
            onConfirm = { quantity, amount, date, requestId -> refunding = null; onRefundItem(item.itemId, quantity, amount, date, requestId) },
            onDismiss = { refunding = null },
        )
    }
    if (r.discount.isPositive) SummaryRow(stringResource(R.string.field_discount), -r.discount)
    SummaryRow(stringResource(R.string.field_tax), r.tax)
    r.total?.let { SummaryRow(stringResource(R.string.field_total), it) }
    detail.transactionId?.let { id ->
        OutlinedButton(onClick = { onOpenTransaction(id) }) { Text(stringResource(R.string.review_open_expense)) }
    }
}
