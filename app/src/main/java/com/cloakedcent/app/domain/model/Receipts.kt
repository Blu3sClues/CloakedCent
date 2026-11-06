package com.cloakedcent.app.domain.model

import com.cloakedcent.app.core.money.Money
import java.time.LocalDate
import java.time.LocalTime

// Receipts are documents attached to expenses. Nothing here affects balances or
// budgets until a receipt is confirmed, and then only through the ledger.

enum class ReceiptStatus {
    /** Images stored; OCR not finished (or failed). */
    DRAFT,

    /** OCR finished; waiting for the user to verify. */
    NEEDS_REVIEW,

    /** Verified and linked to exactly one expense. Final. */
    CONFIRMED,

    /** A draft the user rejected. Only a transient mark: the receipt is erased in the same transaction. */
    DISCARDED,

    /** A confirmed receipt the user deleted. Only a transient mark: it is erased in the same transaction. */
    DELETED,
}

enum class ReceiptField { STORE, ADDRESS, DATE, TIME, SUBTOTAL, TAX, DISCOUNT, TOTAL, RECEIPT_NUMBER }

/** Text recognised on one line of one page, with its position in the stored image. */
data class OcrLine(
    val page: Int,
    val index: Int,
    val text: String,
    /** Tesseract confidence, 0-100. */
    val confidence: Int,
    val box: OcrBox? = null,
)

data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Where a parsed value came from, for highlighting on the image. */
data class OcrSource(val page: Int, val line: Int)

data class ReceiptItemDraft(
    /** 0 for an item the user added. */
    val id: Long = 0,
    val rawText: String,
    val description: String,
    /** Thousandths of [unit]: 1 item = 1000, 0.455 kg = 455. */
    val quantityMilli: Long = 1000,
    val unit: MeasureUnit = MeasureUnit.EACH,
    val unitPrice: Money? = null,
    /** Price of the line before any item discount. */
    val lineTotal: Money,
    /** Item-level discount (positive). */
    val discount: Money = Money.ZERO,
    val categoryId: Long? = null,
    val productId: Long? = null,
    val confidence: Int = 100,
    val uncertain: Boolean = false,
    val source: OcrSource? = null,
) {
    val net: Money get() = lineTotal - discount
}

/** Everything the user can verify on the review screen. */
data class ReceiptReview(
    val storeName: String = "",
    val storeAddress: String = "",
    val purchasedOn: LocalDate? = null,
    val purchasedTime: LocalTime? = null,
    val subtotal: Money? = null,
    val tax: Money = Money.ZERO,
    /** Receipt-level discounts (positive), e.g. coupons after the subtotal. */
    val discount: Money = Money.ZERO,
    val total: Money? = null,
    val receiptNumber: String = "",
    /** Used for items without their own category. */
    val defaultCategoryId: Long? = null,
    val items: List<ReceiptItemDraft> = emptyList(),
)

/** An item entered with a new expense. A blank name or a zero price means it was left out. */
data class ExpenseItem(val name: String, val price: Money = Money.ZERO)

/** The optional details of a new expense, kept as a confirmed receipt with no image. */
data class ExpenseDetails(val tax: Money = Money.ZERO, val items: List<ExpenseItem> = emptyList(), val storeAddress: String = "") {
    val isEmpty: Boolean get() = !tax.isPositive && items.isEmpty() && storeAddress.isBlank()
}

data class ReceiptPage(val imageId: Long, val page: Int, val width: Int, val height: Int)

data class ReceiptDetail(
    val id: Long,
    val status: ReceiptStatus,
    val review: ReceiptReview,
    val uncertain: Set<ReceiptField>,
    val fieldSources: Map<ReceiptField, OcrSource>,
    val pages: List<ReceiptPage>,
    val ocrLines: List<OcrLine>,
    val transactionId: Long?,
    val storeId: Long?,
    val ocrError: String?,
    /** The linked expense was deleted; the receipt itself was kept. */
    val expenseDeleted: Boolean = false,
)

data class ReceiptSummary(
    val id: Long,
    val status: ReceiptStatus,
    val storeName: String?,
    val purchasedOn: LocalDate?,
    val total: Money?,
    val createdAt: Long,
)

sealed interface DuplicateWarning {
    val receiptId: Long

    /** The same image file was imported before. */
    data class SameImage(override val receiptId: Long) : DuplicateWarning

    /** A visually near-identical photo was imported before. */
    data class SimilarImage(override val receiptId: Long) : DuplicateWarning

    /** Another receipt has the same store, date and total, or the same receipt number. */
    data class SameContent(override val receiptId: Long) : DuplicateWarning
}

/** An existing expense the receipt might belong to. Never chosen automatically. */
data class ExpenseMatch(
    val transactionId: Long,
    /** Positive magnitude. */
    val amount: Money,
    val occurredOn: LocalDate,
    val status: TransactionStatus,
    val accountName: String,
    val payeeName: String?,
    val categoryName: String?,
    val fromRecurring: Boolean,
    val exactAmount: Boolean,
)

sealed interface ConfirmTarget {
    data class NewExpense(val accountId: Long) : ConfirmTarget

    /**
     * Attach to an existing expense. A scheduled one is confirmed with the receipt total.
     * A completed one with a different amount is changed (audited) only if [replaceAmount].
     */
    data class Existing(val transactionId: Long, val replaceAmount: Boolean = false) : ConfirmTarget
}

/** A page waiting to be stored: raw image bytes plus the user's adjustments. */
class PageInput(
    val bytes: ByteArray,
    /** Extra clockwise rotation applied after the photo's own orientation: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** Crop as fractions of the rotated image; null keeps the whole image. */
    val crop: CropRect? = null,
    /** Grayscale and contrast stretch for faded thermal paper. */
    val enhance: Boolean = false,
    /**
     * Text printed in a digital PDF, positioned on this image; used instead of OCR when every
     * page of a receipt has it. Must be null once the image is rotated or cropped.
     */
    val embeddedText: List<OcrLine>? = null,
)

data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

sealed interface ImportOutcome {
    data class Created(val receiptId: Long, val warnings: List<DuplicateWarning>) : ImportOutcome

    /** Exact image already belongs to a live receipt; nothing was stored. */
    data class Duplicate(val existingReceiptId: Long) : ImportOutcome
}

data class Store(
    val id: Long,
    val name: String,
    val address: String?,
)

data class Product(
    val id: Long,
    val name: String,
    val brand: String?,
    val defaultCategoryId: Long?,
    val packQuantityMilli: Long?,
    val packUnit: MeasureUnit?,
)

data class PriceObservation(
    val productId: Long,
    val storeId: Long,
    val storeName: String,
    val observedOn: LocalDate,
    val quantityMilli: Long,
    val unit: MeasureUnit,
    /** Paid for the line after item discounts. */
    val paid: Money,
    val normalized: NormalizedPrice?,
)

data class ProductSummary(
    val product: Product,
    val observations: Int,
    val lastPrice: PriceObservation?,
)

sealed class ReceiptException(message: String) : Exception(message) {
    class NotFound : ReceiptException("Receipt not found")
    class NotEditable : ReceiptException("This receipt is already confirmed or discarded")
    class NotConfirmed : ReceiptException("Only confirmed receipts can be deleted; discard a draft instead")
    class StoreRequired : ReceiptException("Enter the store name")
    class DateRequired : ReceiptException("Enter the purchase date")
    class TotalRequired : ReceiptException("Enter a total greater than zero")
    class ItemsRequired : ReceiptException("Add at least one item")
    class DescriptionRequired : ReceiptException("Every item needs a name")
    class InvalidQuantity : ReceiptException("Quantities must be greater than zero")
    class CategoryRequired : ReceiptException("Choose a category for every item, or a default category")
    class NotReconciled(val issues: List<ReconcileIssue>) :
        ReceiptException("Items, tax and discounts do not add up to the total")
    class ExpenseUnavailable : ReceiptException("That expense cannot take a receipt")
    class AmountDiffers : ReceiptException("The expense amount differs from the receipt total")
}
