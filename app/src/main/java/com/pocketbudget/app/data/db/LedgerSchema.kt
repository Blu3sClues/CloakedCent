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
    """

    /** Triggers whose SQL changed in a later schema version; migrations drop them so onOpen recreates them. */
    val STATUS_TRIGGERS = listOf("trg_transactions_validate_insert", "trg_transactions_validate_update")

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

    fun createTriggers(db: SupportSQLiteDatabase) {
        TRIGGERS.forEach(db::execSQL)
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
