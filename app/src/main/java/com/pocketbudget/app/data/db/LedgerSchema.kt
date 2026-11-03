package com.pocketbudget.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database-level ledger rules that Room entities cannot express. They guard
 * against bugs in any code path, not just the repositories.
 *
 * Triggers are (re)created with IF NOT EXISTS on every open. To change one,
 * DROP it in a migration and update the SQL here.
 */
object LedgerSchema {

    /** Sign, linkage and the type-specific status for each entry type. */
    private const val VALID_ENTRY = """
        (NEW.type = 'INCOME' AND NEW.amount_cents > 0 AND NEW.transfer_id IS NULL
            AND NEW.status IN ('SCHEDULED', 'RECEIVED'))
        OR (NEW.type = 'EXPENSE' AND NEW.amount_cents < 0 AND NEW.transfer_id IS NULL
            AND NEW.status IN ('SCHEDULED', 'SPENT'))
        OR (NEW.type = 'TRANSFER' AND NEW.amount_cents <> 0 AND NEW.transfer_id IS NOT NULL
            AND NEW.category_id IS NULL AND NEW.is_adjustment = 0
            AND NEW.status IN ('SCHEDULED', 'COMPLETED'))
        OR (NEW.type = 'ADJUSTMENT' AND NEW.amount_cents <> 0 AND NEW.transfer_id IS NULL
            AND NEW.category_id IS NULL AND NEW.is_adjustment = 1 AND NEW.status = 'COMPLETED')
        OR (NEW.type = 'REFUND' AND NEW.amount_cents > 0 AND NEW.transfer_id IS NULL
            AND NEW.is_adjustment = 0 AND NEW.status = 'RECEIVED')
    """

    /** Triggers whose SQL changed in a later schema version; migrations drop them so onOpen recreates them. */
    val STATUS_TRIGGERS = listOf("trg_transactions_validate_insert", "trg_transactions_validate_update")

    /** Receipt triggers that learned the DELETED status in v8; dropped by that migration. */
    val RECEIPT_DELETE_TRIGGERS = listOf(
        "trg_receipts_final",
        "trg_receipt_items_validate_update",
        "trg_receipt_items_confirmed_delete",
    )

    private val TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS trg_transactions_validate_insert
        BEFORE INSERT ON transactions
        WHEN NOT ($VALID_ENTRY)
        BEGIN SELECT RAISE(ABORT, 'transaction violates ledger rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transactions_validate_update
        BEFORE UPDATE ON transactions
        WHEN NOT ($VALID_ENTRY)
            OR NEW.account_id <> OLD.account_id OR NEW.type <> OLD.type
            OR NEW.transfer_id IS NOT OLD.transfer_id OR NEW.is_adjustment <> OLD.is_adjustment
        BEGIN SELECT RAISE(ABORT, 'transaction violates ledger rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transactions_no_delete
        BEFORE DELETE ON transactions
        BEGIN SELECT RAISE(ABORT, 'transactions are soft-deleted only'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transfers_distinct_accounts
        BEFORE INSERT ON transfers
        WHEN NEW.from_account_id = NEW.to_account_id
        BEGIN SELECT RAISE(ABORT, 'transfer accounts must differ'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_revisions_immutable_update
        BEFORE UPDATE ON transaction_revisions
        BEGIN SELECT RAISE(ABORT, 'revisions are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_revisions_immutable_delete
        BEFORE DELETE ON transaction_revisions
        BEGIN SELECT RAISE(ABORT, 'revisions are immutable'); END
        """,
    )

    /**
     * v6 rules. Splits belong to a live expense, use expense categories and never add
     * up to more than the expense; once written, they and the expense amount are fixed.
     * A confirmed receipt is final and always points at an expense; since v8 its only
     * allowed change is to DELETED, keeping that expense link. Price history is
     * append-only.
     */
    private val RECEIPT_TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS trg_splits_validate_insert
        BEFORE INSERT ON transaction_splits
        WHEN NEW.amount_cents <= 0
            OR NOT EXISTS (SELECT 1 FROM transactions t WHERE t.id = NEW.transaction_id
                AND t.type = 'EXPENSE' AND t.is_deleted = 0)
            OR NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = NEW.category_id AND c.kind = 'EXPENSE')
            OR (SELECT COALESCE(SUM(amount_cents), 0) FROM transaction_splits
                WHERE transaction_id = NEW.transaction_id) + NEW.amount_cents
                > (SELECT -amount_cents FROM transactions WHERE id = NEW.transaction_id)
        BEGIN SELECT RAISE(ABORT, 'split violates ledger rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_splits_immutable_update
        BEFORE UPDATE ON transaction_splits
        BEGIN SELECT RAISE(ABORT, 'splits are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_splits_immutable_delete
        BEFORE DELETE ON transaction_splits
        BEGIN SELECT RAISE(ABORT, 'splits are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transactions_split_amount_fixed
        BEFORE UPDATE OF amount_cents ON transactions
        WHEN NEW.amount_cents <> OLD.amount_cents
            AND EXISTS (SELECT 1 FROM transaction_splits WHERE transaction_id = NEW.id)
        BEGIN SELECT RAISE(ABORT, 'split expense amount is fixed'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_receipts_confirmed_insert
        BEFORE INSERT ON receipts
        WHEN NEW.status = 'CONFIRMED' AND NEW.transaction_id IS NULL
        BEGIN SELECT RAISE(ABORT, 'confirmed receipts need an expense'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_receipts_final
        BEFORE UPDATE ON receipts
        WHEN (OLD.status IN ('CONFIRMED', 'DISCARDED', 'DELETED')
                AND NOT (OLD.status = 'CONFIRMED' AND NEW.status = 'DELETED'
                    AND NEW.transaction_id IS OLD.transaction_id AND NEW.total_cents IS OLD.total_cents))
            OR (NEW.status = 'DELETED' AND OLD.status NOT IN ('CONFIRMED', 'DELETED'))
            OR (NEW.status = 'CONFIRMED' AND (NEW.transaction_id IS NULL OR NEW.total_cents IS NULL))
            OR (NEW.status NOT IN ('CONFIRMED', 'DELETED') AND NEW.transaction_id IS NOT NULL)
        BEGIN SELECT RAISE(ABORT, 'receipt is final or inconsistent'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_receipts_no_delete
        BEFORE DELETE ON receipts
        BEGIN SELECT RAISE(ABORT, 'receipts are discarded, not deleted'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_receipt_items_validate_insert
        BEFORE INSERT ON receipt_items
        WHEN NEW.line_total_cents < 0 OR NEW.discount_cents < 0 OR NEW.discount_cents > NEW.line_total_cents
            OR NEW.quantity_milli <= 0
        BEGIN SELECT RAISE(ABORT, 'receipt item violates rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_receipt_items_validate_update
        BEFORE UPDATE ON receipt_items
        WHEN NEW.line_total_cents < 0 OR NEW.discount_cents < 0 OR NEW.discount_cents > NEW.line_total_cents
            OR NEW.quantity_milli <= 0
            OR (SELECT status FROM receipts WHERE id = OLD.receipt_id) IN ('CONFIRMED', 'DISCARDED', 'DELETED')
        BEGIN SELECT RAISE(ABORT, 'receipt item violates rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_receipt_items_confirmed_delete
        BEFORE DELETE ON receipt_items
        WHEN (SELECT status FROM receipts WHERE id = OLD.receipt_id) IN ('CONFIRMED', 'DELETED')
        BEGIN SELECT RAISE(ABORT, 'confirmed receipt items are kept'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_prices_immutable_update
        BEFORE UPDATE ON price_observations
        BEGIN SELECT RAISE(ABORT, 'price history is immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_prices_immutable_delete
        BEFORE DELETE ON price_observations
        BEGIN SELECT RAISE(ABORT, 'price history is immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_aliases_no_delete
        BEFORE DELETE ON product_aliases
        BEGIN SELECT RAISE(ABORT, 'learned aliases are kept'); END
        """,
    )

    /** Live refunds already recorded against expense `:id` (as an SQL expression on `id`). */
    private fun refundedOf(id: String) = """
        (SELECT COALESCE(SUM(rt.amount_cents), 0) FROM refunds rf
            JOIN transactions rt ON rt.id = rf.refund_transaction_id
            WHERE rf.original_transaction_id = $id AND rt.is_deleted = 0)
    """

    /**
     * v7 refund rules. A refund links a live REFUND entry to a completed, live expense
     * and can never take more than what is still refundable. Refunded expenses cannot be
     * deleted or reduced below what was refunded; refund amounts never change. Links and
     * allocations are append-only, and allocations never exceed their refund.
     */
    private val REFUND_TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS trg_refunds_validate_insert
        BEFORE INSERT ON refunds
        WHEN NOT EXISTS (SELECT 1 FROM transactions o WHERE o.id = NEW.original_transaction_id
                AND o.type = 'EXPENSE' AND o.status = 'SPENT' AND o.is_deleted = 0 AND o.is_adjustment = 0)
            OR NOT EXISTS (SELECT 1 FROM transactions r WHERE r.id = NEW.refund_transaction_id
                AND r.type = 'REFUND' AND r.is_deleted = 0)
            OR ${refundedOf("NEW.original_transaction_id")}
                + (SELECT amount_cents FROM transactions WHERE id = NEW.refund_transaction_id)
                > (SELECT -amount_cents FROM transactions WHERE id = NEW.original_transaction_id)
        BEGIN SELECT RAISE(ABORT, 'refund violates ledger rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_refunds_immutable_update
        BEFORE UPDATE ON refunds
        BEGIN SELECT RAISE(ABORT, 'refunds are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_refunds_immutable_delete
        BEFORE DELETE ON refunds
        BEGIN SELECT RAISE(ABORT, 'refunds are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_refund_allocations_validate_insert
        BEFORE INSERT ON refund_allocations
        WHEN NEW.amount_cents <= 0 OR NEW.item_net_cents < 0 OR NEW.quantity_milli <= 0
            OR NOT EXISTS (SELECT 1 FROM transactions r WHERE r.id = NEW.refund_transaction_id AND r.type = 'REFUND')
            OR (SELECT COALESCE(SUM(amount_cents), 0) FROM refund_allocations
                WHERE refund_transaction_id = NEW.refund_transaction_id) + NEW.amount_cents
                > (SELECT amount_cents FROM transactions WHERE id = NEW.refund_transaction_id)
            OR (NEW.receipt_item_id IS NOT NULL AND (
                SELECT COALESCE(SUM(a.item_net_cents), 0) FROM refund_allocations a
                    JOIN transactions rt ON rt.id = a.refund_transaction_id
                    WHERE a.receipt_item_id = NEW.receipt_item_id AND rt.is_deleted = 0)
                + COALESCE(NEW.item_net_cents, 0)
                > (SELECT line_total_cents - discount_cents FROM receipt_items WHERE id = NEW.receipt_item_id))
        BEGIN SELECT RAISE(ABORT, 'refund allocation violates ledger rules'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_refund_allocations_immutable_update
        BEFORE UPDATE ON refund_allocations
        BEGIN SELECT RAISE(ABORT, 'refund allocations are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_refund_allocations_immutable_delete
        BEFORE DELETE ON refund_allocations
        BEGIN SELECT RAISE(ABORT, 'refund allocations are immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transactions_refund_protect
        BEFORE UPDATE ON transactions
        WHEN (OLD.type = 'REFUND' AND NEW.amount_cents <> OLD.amount_cents)
            OR (OLD.type = 'EXPENSE' AND NEW.is_deleted = 1 AND OLD.is_deleted = 0 AND ${refundedOf("OLD.id")} > 0)
            OR (OLD.type = 'EXPENSE' AND -NEW.amount_cents < ${refundedOf("OLD.id")})
        BEGIN SELECT RAISE(ABORT, 'refunded expense or refund cannot change this way'); END
        """,
    )
    fun createTriggers(db: SupportSQLiteDatabase) {
        (TRIGGERS + RECEIPT_TRIGGERS + REFUND_TRIGGERS).forEach(db::execSQL)
    }

    /** Starter categories; parents listed before children. */
    private val DEFAULT_CATEGORIES = listOf(
        Triple("Salary", "INCOME", null),
        Triple("Other income", "INCOME", null),
        Triple("Housing", "EXPENSE", null),
        Triple("Food", "EXPENSE", null),
        Triple("Groceries", "EXPENSE", "Food"),
        Triple("Dining out", "EXPENSE", "Food"),
        Triple("Transport", "EXPENSE", null),
        Triple("Utilities", "EXPENSE", null),
        Triple("Health", "EXPENSE", null),
        Triple("Entertainment", "EXPENSE", null),
        Triple("Other expenses", "EXPENSE", null),
    )

    fun seedDefaultCategories(db: SupportSQLiteDatabase, now: Long) {
        DEFAULT_CATEGORIES.forEach { (name, kind, parent) ->
            db.execSQL(
                """
                INSERT INTO categories (name, kind, parent_id, is_archived, created_at)
                VALUES (?, ?, (SELECT id FROM categories WHERE name = ? AND kind = ?), 0, ?)
                """,
                arrayOf<Any?>(name, kind, parent, kind, now),
            )
        }
    }
}
