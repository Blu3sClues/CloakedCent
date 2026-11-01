package com.pocketbudget.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema migration, in order.
 *
 * Rules:
 * - Bump [PocketBudgetDatabase] `version`, build once to export the new schema
 *   JSON into app/schemas, and commit that JSON alongside the migration.
 * - Copy CREATE statements from the exported JSON so migrated and fresh
 *   databases are identical.
 * - Never edit an exported schema or a released migration.
 * - Every migration gets a test in MigrationTest.
 * - Destructive fallback is never enabled: a missing migration must crash in
 *   development rather than wipe a user's financial records.
 */
object Migrations {

    /** v2: financial ledger (accounts, categories, payees, transfers, transactions, revisions). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `opening_balance_cents` INTEGER NOT NULL, `include_in_totals` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `parent_id` INTEGER, `is_archived` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, FOREIGN KEY(`parent_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE INDEX IF NOT EXISTS `index_categories_parent_id` ON `categories` (`parent_id`)",
                "CREATE TABLE IF NOT EXISTS `payees` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `default_category_id` INTEGER, `created_at` INTEGER NOT NULL, FOREIGN KEY(`default_category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_payees_name` ON `payees` (`name`)",
                "CREATE INDEX IF NOT EXISTS `index_payees_default_category_id` ON `payees` (`default_category_id`)",
                "CREATE TABLE IF NOT EXISTS `transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `request_id` TEXT NOT NULL, `from_account_id` INTEGER NOT NULL, `to_account_id` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, FOREIGN KEY(`from_account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`to_account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transfers_request_id` ON `transfers` (`request_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transfers_from_account_id` ON `transfers` (`from_account_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transfers_to_account_id` ON `transfers` (`to_account_id`)",
                "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `request_id` TEXT, `account_id` INTEGER NOT NULL, `type` TEXT NOT NULL, `amount_cents` INTEGER NOT NULL, `status` TEXT NOT NULL, `occurred_on` INTEGER NOT NULL, `category_id` INTEGER, `payee_id` INTEGER, `transfer_id` INTEGER, `note` TEXT, `revision` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`payee_id`) REFERENCES `payees`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`transfer_id`) REFERENCES `transfers`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_request_id` ON `transactions` (`request_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transactions_account_id` ON `transactions` (`account_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transactions_category_id` ON `transactions` (`category_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transactions_payee_id` ON `transactions` (`payee_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transactions_transfer_id` ON `transactions` (`transfer_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transactions_occurred_on` ON `transactions` (`occurred_on`)",
                "CREATE TABLE IF NOT EXISTS `transaction_revisions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transaction_id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `action` TEXT NOT NULL, `account_id` INTEGER NOT NULL, `type` TEXT NOT NULL, `amount_cents` INTEGER NOT NULL, `status` TEXT NOT NULL, `occurred_on` INTEGER NOT NULL, `category_id` INTEGER, `payee_id` INTEGER, `note` TEXT, `recorded_at` INTEGER NOT NULL, FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_revisions_transaction_id_revision` ON `transaction_revisions` (`transaction_id`, `revision`)",
            ).forEach(db::execSQL)
            LedgerSchema.seedDefaultCategories(db, System.currentTimeMillis())
        }
    }

    /**
     * v3: statuses simplified to SCHEDULED / SPENT. PENDING and CLEARED both meant
     * "money has moved", so both become SPENT; balances and history are unchanged
     * in meaning. Revision snapshots are relabelled too so they stay readable. The
     * validation and immutability triggers are dropped for this one-off rewrite,
     * inside the migration transaction, and recreated with the new rules on open.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            (LedgerSchema.STATUS_TRIGGERS + "trg_revisions_immutable_update").forEach {
                db.execSQL("DROP TRIGGER IF EXISTS $it")
            }
            db.execSQL("UPDATE transactions SET status = 'SPENT' WHERE status IN ('PENDING', 'CLEARED')")
            db.execSQL("UPDATE transaction_revisions SET status = 'SPENT' WHERE status IN ('PENDING', 'CLEARED')")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
