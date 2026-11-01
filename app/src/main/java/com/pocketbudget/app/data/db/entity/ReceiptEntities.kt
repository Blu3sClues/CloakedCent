package com.pocketbudget.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pocketbudget.app.domain.model.Dimension
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.ReceiptStatus
import java.time.LocalDate

// Schema v6: receipts, purchased items, products and price history. Receipts never
// carry money into the ledger themselves; a confirmed receipt points at exactly one
// expense, and category splits divide that expense. All foreign keys are RESTRICT.

@Entity(
    tableName = "stores",
    foreignKeys = [
        ForeignKey(entity = PayeeEntity::class, parentColumns = ["id"], childColumns = ["payee_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("normalized_name"), Index("payee_id")],
)
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val address: String? = null,
    /** Degrees x 10^7; set only when the user explicitly captures a location. */
    @ColumnInfo(name = "latitude_e7") val latitudeE7: Long? = null,
    @ColumnInfo(name = "longitude_e7") val longitudeE7: Long? = null,
    @ColumnInfo(name = "payee_id") val payeeId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(entity = StoreEntity::class, parentColumns = ["id"], childColumns = ["store_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transaction_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["default_category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["request_id"], unique = true),
        // One receipt per expense: attaching never creates a second expense, and an expense never takes two receipts.
        Index(value = ["transaction_id"], unique = true),
        Index("store_id"),
        Index("default_category_id"),
        Index("purchased_on"),
    ],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "request_id") val requestId: String,
    val status: ReceiptStatus,
    @ColumnInfo(name = "store_id") val storeId: Long? = null,
    @ColumnInfo(name = "store_name") val storeName: String = "",
    @ColumnInfo(name = "store_address") val storeAddress: String = "",
    @ColumnInfo(name = "purchased_on") val purchasedOn: LocalDate? = null,
    /** Minutes after midnight. */
    @ColumnInfo(name = "purchased_minute") val purchasedMinute: Int? = null,
    @ColumnInfo(name = "subtotal_cents") val subtotalCents: Long? = null,
    @ColumnInfo(name = "tax_cents") val taxCents: Long = 0,
    @ColumnInfo(name = "discount_cents") val discountCents: Long = 0,
    @ColumnInfo(name = "total_cents") val totalCents: Long? = null,
    @ColumnInfo(name = "receipt_number") val receiptNumber: String = "",
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long? = null,
    @ColumnInfo(name = "transaction_id") val transactionId: Long? = null,
    @ColumnInfo(name = "latitude_e7") val latitudeE7: Long? = null,
    @ColumnInfo(name = "longitude_e7") val longitudeE7: Long? = null,
    /** Comma-separated ReceiptField names the parser was unsure about. */
    @ColumnInfo(name = "uncertain_fields") val uncertainFields: String = "",
    /** FIELD=page:line pairs pointing at the OCR line each value came from. */
    @ColumnInfo(name = "field_sources") val fieldSources: String = "",
    @ColumnInfo(name = "ocr_engine") val ocrEngine: String? = null,
    @ColumnInfo(name = "ocr_error") val ocrError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "confirmed_at") val confirmedAt: Long? = null,
)

/** One encrypted page image. The file lives in app-private storage under [fileName]. */
@Entity(
    tableName = "receipt_images",
    foreignKeys = [
        ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receipt_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["receipt_id", "page"], unique = true),
        Index(value = ["file_name"], unique = true),
        Index("source_sha256"),
    ],
)
data class ReceiptImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    val page: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    /** SHA-256 of the imported bytes, for exact duplicate detection. */
    @ColumnInfo(name = "source_sha256") val sourceSha256: String,
    /** SHA-256 of the stored (decrypted) JPEG, checked on every read. */
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    /** 64-bit difference hash for spotting the same receipt photographed twice. */
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "format_version") val formatVersion: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "receipt_ocr_lines",
    foreignKeys = [
        ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receipt_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["receipt_id", "page", "line_index"], unique = true)],
)
data class ReceiptOcrLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    val page: Int,
    @ColumnInfo(name = "line_index") val lineIndex: Int,
    val text: String,
    val confidence: Int,
    @ColumnInfo(name = "box_left") val boxLeft: Int?,
    @ColumnInfo(name = "box_top") val boxTop: Int?,
    @ColumnInfo(name = "box_right") val boxRight: Int?,
    @ColumnInfo(name = "box_bottom") val boxBottom: Int?,
)

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["default_category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["normalized_name"], unique = true), Index("default_category_id")],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val brand: String? = null,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long? = null,
    @ColumnInfo(name = "pack_quantity_milli") val packQuantityMilli: Long? = null,
    @ColumnInfo(name = "pack_unit") val packUnit: MeasureUnit? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "receipt_items",
    foreignKeys = [
        ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receipt_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["product_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["receipt_id", "position"], unique = true), Index("product_id"), Index("category_id")],
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    val position: Int,
    @ColumnInfo(name = "raw_text") val rawText: String,
    val description: String,
    @ColumnInfo(name = "product_id") val productId: Long? = null,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    val unit: MeasureUnit,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long? = null,
    @ColumnInfo(name = "line_total_cents") val lineTotalCents: Long,
    @ColumnInfo(name = "discount_cents") val discountCents: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    val confidence: Int,
    val uncertain: Boolean,
    @ColumnInfo(name = "source_page") val sourcePage: Int? = null,
    @ColumnInfo(name = "source_line") val sourceLine: Int? = null,
    @ColumnInfo(name = "user_verified") val userVerified: Boolean = false,
)

/** Receipt wording learned from user corrections: at [storeId], [normalizedAlias] means [productId]. */
@Entity(
    tableName = "product_aliases",
    foreignKeys = [
        ForeignKey(entity = StoreEntity::class, parentColumns = ["id"], childColumns = ["store_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["product_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["store_id", "normalized_alias"], unique = true), Index("product_id")],
)
data class ProductAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "times_confirmed") val timesConfirmed: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Immutable price history, written only from confirmed receipt items. */
@Entity(
    tableName = "price_observations",
    foreignKeys = [
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["product_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = StoreEntity::class, parentColumns = ["id"], childColumns = ["store_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReceiptItemEntity::class, parentColumns = ["id"], childColumns = ["receipt_item_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["receipt_item_id"], unique = true),
        Index(value = ["product_id", "observed_on"]),
        Index("store_id"),
    ],
)
data class PriceObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "receipt_item_id") val receiptItemId: Long,
    @ColumnInfo(name = "observed_on") val observedOn: LocalDate,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    val unit: MeasureUnit,
    /** Paid for the line after item discounts, before receipt-level tax and coupons. */
    @ColumnInfo(name = "paid_cents") val paidCents: Long,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long?,
    @ColumnInfo(name = "normalized_dimension") val normalizedDimension: Dimension?,
    @ColumnInfo(name = "normalized_millicents") val normalizedMillicents: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Divides one expense across categories. Budgets count the splits instead of the
 * expense's own category, so nothing is counted twice. Triggers keep the parts from
 * exceeding the expense, and the repository writes them so they add up exactly.
 */
@Entity(
    tableName = "transaction_splits",
    foreignKeys = [
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transaction_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receipt_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["transaction_id", "category_id"], unique = true),
        Index("category_id"),
        Index("receipt_id"),
    ],
)
data class TransactionSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    /** Positive share of the expense. */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "receipt_id") val receiptId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
