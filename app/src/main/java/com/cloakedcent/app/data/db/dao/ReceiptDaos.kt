package com.cloakedcent.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.cloakedcent.app.data.db.entity.PriceObservationEntity
import com.cloakedcent.app.data.db.entity.ProductAliasEntity
import com.cloakedcent.app.data.db.entity.ProductEntity
import com.cloakedcent.app.data.db.entity.ReceiptEntity
import com.cloakedcent.app.data.db.entity.ReceiptImageEntity
import com.cloakedcent.app.data.db.entity.ReceiptItemEntity
import com.cloakedcent.app.data.db.entity.ReceiptOcrLineEntity
import com.cloakedcent.app.data.db.entity.StoreEntity
import com.cloakedcent.app.data.db.entity.TransactionSplitEntity
import com.cloakedcent.app.domain.model.Dimension
import com.cloakedcent.app.domain.model.MeasureUnit
import com.cloakedcent.app.domain.model.TransactionStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * A receipt with everything shown on its review screen, read in one transaction so the
 * screen never sees updated receipt fields alongside items from before an OCR run.
 */
data class ReceiptBundle(
    @Embedded val receipt: ReceiptEntity,
    @Relation(parentColumn = "id", entityColumn = "receipt_id") val items: List<ReceiptItemEntity>,
    @Relation(parentColumn = "id", entityColumn = "receipt_id") val images: List<ReceiptImageEntity>,
    @Relation(parentColumn = "id", entityColumn = "receipt_id") val ocrLines: List<ReceiptOcrLineEntity>,
)

/** An image hash belonging to a receipt that is not discarded. */
data class ImageHashRow(
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    @ColumnInfo(name = "source_sha256") val sourceSha256: String,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: Long,
)

data class ExpenseMatchRow(
    val id: Long,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "occurred_on") val occurredOn: LocalDate,
    val status: TransactionStatus,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "payee_name") val payeeName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "from_recurring") val fromRecurring: Boolean,
)

data class PriceRow(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "store_name") val storeName: String,
    @ColumnInfo(name = "observed_on") val observedOn: LocalDate,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    val unit: MeasureUnit,
    @ColumnInfo(name = "paid_cents") val paidCents: Long,
    @ColumnInfo(name = "normalized_dimension") val normalizedDimension: Dimension?,
    @ColumnInfo(name = "normalized_millicents") val normalizedMillicents: Long?,
)

@Dao
interface ReceiptDao {
    @Insert
    suspend fun insert(receipt: ReceiptEntity): Long

    @Update
    suspend fun update(receipt: ReceiptEntity)

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun getById(id: Long): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE request_id = :requestId")
    suspend fun getByRequestId(requestId: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observe(id: Long): Flow<ReceiptEntity?>

    @Transaction
    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observeBundle(id: Long): Flow<ReceiptBundle?>

    @Query("SELECT * FROM receipts WHERE status NOT IN ('DISCARDED', 'DELETED') ORDER BY created_at DESC")
    fun observeVisible(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE transaction_id = :transactionId")
    suspend fun getByTransaction(transactionId: Long): ReceiptEntity?

    @Query("SELECT id FROM receipts WHERE transaction_id = :transactionId")
    fun observeIdForTransaction(transactionId: Long): Flow<Long?>

    /** Other live receipts with the same store, date and total, or the same receipt number at that store. */
    @Query(
        """
        SELECT id FROM receipts
        WHERE id <> :id AND status NOT IN ('DISCARDED', 'DELETED') AND store_name <> '' AND store_name = :storeName COLLATE NOCASE
            AND ((purchased_on = :purchasedOn AND total_cents = :totalCents)
                OR (receipt_number <> '' AND receipt_number = :receiptNumber))
        """,
    )
    suspend fun findSameContent(
        id: Long,
        storeName: String,
        purchasedOn: LocalDate?,
        totalCents: Long?,
        receiptNumber: String,
    ): List<Long>

    // Images

    @Insert
    suspend fun insertImage(image: ReceiptImageEntity): Long

    @Query("SELECT * FROM receipt_images WHERE receipt_id = :receiptId ORDER BY page")
    suspend fun getImages(receiptId: Long): List<ReceiptImageEntity>

    @Query("SELECT * FROM receipt_images WHERE receipt_id = :receiptId ORDER BY page")
    fun observeImages(receiptId: Long): Flow<List<ReceiptImageEntity>>

    @Query("SELECT * FROM receipt_images WHERE id = :id")
    suspend fun getImage(id: Long): ReceiptImageEntity?

    @Query("DELETE FROM receipt_images WHERE receipt_id = :receiptId")
    suspend fun deleteImages(receiptId: Long)

    @Query("SELECT file_name FROM receipt_images")
    suspend fun getAllImageFileNames(): List<String>

    @Query(
        """
        SELECT i.receipt_id, i.source_sha256, i.perceptual_hash FROM receipt_images i
        JOIN receipts r ON r.id = i.receipt_id WHERE r.status NOT IN ('DISCARDED', 'DELETED')
        """,
    )
    suspend fun getLiveImageHashes(): List<ImageHashRow>

    // OCR

    @Insert
    suspend fun insertOcrLines(lines: List<ReceiptOcrLineEntity>)

    @Query("SELECT * FROM receipt_ocr_lines WHERE receipt_id = :receiptId ORDER BY page, line_index")
    fun observeOcrLines(receiptId: Long): Flow<List<ReceiptOcrLineEntity>>

    @Query("SELECT * FROM receipt_ocr_lines WHERE receipt_id = :receiptId ORDER BY page, line_index")
    suspend fun getOcrLines(receiptId: Long): List<ReceiptOcrLineEntity>

    @Query("DELETE FROM receipt_ocr_lines WHERE receipt_id = :receiptId")
    suspend fun deleteOcrLines(receiptId: Long)

    // Items

    @Insert
    suspend fun insertItems(items: List<ReceiptItemEntity>)

    @Insert
    suspend fun insertItem(item: ReceiptItemEntity): Long


    @Update
    suspend fun updateItem(item: ReceiptItemEntity)

    @Query("SELECT * FROM receipt_items WHERE receipt_id = :receiptId ORDER BY position")
    suspend fun getItems(receiptId: Long): List<ReceiptItemEntity>

    @Query("SELECT * FROM receipt_items WHERE receipt_id = :receiptId ORDER BY position")
    fun observeItems(receiptId: Long): Flow<List<ReceiptItemEntity>>

    @Query("DELETE FROM receipt_items WHERE receipt_id = :receiptId")
    suspend fun deleteItems(receiptId: Long)

    // Stores

    @Insert
    suspend fun insertStore(store: StoreEntity): Long

    @Update
    suspend fun updateStore(store: StoreEntity)

    @Query("SELECT * FROM stores WHERE id = :id")
    suspend fun getStore(id: Long): StoreEntity?

    @Query("SELECT * FROM stores WHERE normalized_name = :normalizedName ORDER BY id")
    suspend fun findStores(normalizedName: String): List<StoreEntity>

    @Query("SELECT * FROM stores ORDER BY name COLLATE NOCASE")
    fun observeStores(): Flow<List<StoreEntity>>

    // Products and aliases

    @Insert
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProduct(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE normalized_name = :normalizedName")
    suspend fun getProductByName(normalizedName: String): ProductEntity?

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    suspend fun getProducts(): List<ProductEntity>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM product_aliases WHERE store_id = :storeId AND normalized_alias = :alias")
    suspend fun getAlias(storeId: Long, alias: String): ProductAliasEntity?

    @Query("SELECT * FROM product_aliases WHERE store_id = :storeId")
    suspend fun getAliases(storeId: Long): List<ProductAliasEntity>

    @Insert
    suspend fun insertAlias(alias: ProductAliasEntity): Long

    @Update
    suspend fun updateAlias(alias: ProductAliasEntity)

    // Price history

    /** Returns -1 when the item already has an observation, so confirming twice is harmless. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservation(observation: PriceObservationEntity): Long

    @Query(
        """
        SELECT p.product_id, p.store_id, s.name AS store_name, p.observed_on, p.quantity_milli, p.unit,
            p.paid_cents, p.normalized_dimension, p.normalized_millicents
        FROM price_observations p JOIN stores s ON s.id = p.store_id
        WHERE p.product_id = :productId ORDER BY p.observed_on DESC, p.id DESC
        """,
    )
    fun observePrices(productId: Long): Flow<List<PriceRow>>

    @Query(
        """
        SELECT p.product_id, p.store_id, s.name AS store_name, p.observed_on, p.quantity_milli, p.unit,
            p.paid_cents, p.normalized_dimension, p.normalized_millicents
        FROM price_observations p JOIN stores s ON s.id = p.store_id
        ORDER BY p.observed_on DESC, p.id DESC
        """,
    )
    fun observeAllPrices(): Flow<List<PriceRow>>

    // Splits

    @Insert
    suspend fun insertSplit(split: TransactionSplitEntity): Long

    @Query("SELECT * FROM transaction_splits WHERE transaction_id = :transactionId ORDER BY amount_cents DESC")
    suspend fun getSplits(transactionId: Long): List<TransactionSplitEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM transaction_splits WHERE transaction_id = :transactionId)")
    suspend fun hasSplits(transactionId: Long): Boolean

    /**
     * Expenses a receipt could belong to: live, without a receipt or splits, within
     * the date window. Scheduled entries (often recurring bills) are included.
     */
    @Query(
        """
        SELECT t.id, -t.amount_cents AS amount_cents, t.occurred_on, t.status, a.name AS account_name,
            p.name AS payee_name, c.name AS category_name,
            EXISTS(SELECT 1 FROM recurring_occurrences o WHERE o.transaction_id = t.id) AS from_recurring
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        LEFT JOIN payees p ON p.id = t.payee_id
        LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.type = 'EXPENSE' AND t.is_deleted = 0
            AND t.occurred_on BETWEEN :from AND :to
            AND NOT EXISTS (SELECT 1 FROM receipts r WHERE r.transaction_id = t.id)
            AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transaction_id = t.id)
        ORDER BY ABS(-t.amount_cents - :amountCents), t.occurred_on DESC
        LIMIT 20
        """,
    )
    suspend fun findExpenseCandidates(from: LocalDate, to: LocalDate, amountCents: Long): List<ExpenseMatchRow>
}
