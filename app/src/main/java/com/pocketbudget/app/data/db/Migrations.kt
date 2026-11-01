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

    /**
     * v4: type-specific completed statuses (income RECEIVED, expense SPENT, transfer
     * COMPLETED) and the is_adjustment flag for "Adjust balance" entries. SCHEDULED
     * is untouched. Revision snapshots are relabelled by their own recorded type.
     * Triggers are dropped for the rewrite and recreated with v4 rules on open.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            (LedgerSchema.STATUS_TRIGGERS + "trg_revisions_immutable_update").forEach {
                db.execSQL("DROP TRIGGER IF EXISTS $it")
            }
            db.execSQL("ALTER TABLE `transactions` ADD COLUMN `is_adjustment` INTEGER NOT NULL DEFAULT 0")
            listOf("transactions", "transaction_revisions").forEach { table ->
                db.execSQL(
                    """
                    UPDATE $table SET status = CASE type
                        WHEN 'INCOME' THEN 'RECEIVED'
                        WHEN 'EXPENSE' THEN 'SPENT'
                        WHEN 'TRANSFER' THEN 'COMPLETED'
                        ELSE status END
                    WHERE status = 'SPENT'
                    """,
                )
            }
        }
    }

    /** v5: budgets (with limit history and alert log) and recurring schedules/occurrences. Additive only. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `category_id` INTEGER NOT NULL, `period_type` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `end_date` INTEGER, `cycle_start` INTEGER NOT NULL, `rollover` INTEGER NOT NULL, `alert_thresholds` TEXT NOT NULL, `state` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE INDEX IF NOT EXISTS `index_budgets_category_id` ON `budgets` (`category_id`)",
                "CREATE TABLE IF NOT EXISTS `budget_limits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `budget_id` INTEGER NOT NULL, `effective_from` INTEGER NOT NULL, `amount_cents` INTEGER NOT NULL, FOREIGN KEY(`budget_id`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_limits_budget_id_effective_from` ON `budget_limits` (`budget_id`, `effective_from`)",
                "CREATE TABLE IF NOT EXISTS `budget_alerts` (`budget_id` INTEGER NOT NULL, `period_start` INTEGER NOT NULL, `threshold` INTEGER NOT NULL, `raised_at` INTEGER NOT NULL, PRIMARY KEY(`budget_id`, `period_start`, `threshold`), FOREIGN KEY(`budget_id`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE TABLE IF NOT EXISTS `recurring_schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `type` TEXT NOT NULL, `account_id` INTEGER NOT NULL, `to_account_id` INTEGER, `amount_cents` INTEGER NOT NULL, `variable_amount` INTEGER NOT NULL, `category_id` INTEGER, `payee_id` INTEGER, `note` TEXT, `frequency` TEXT NOT NULL, `interval_count` INTEGER NOT NULL, `start_date` INTEGER NOT NULL, `end_date` INTEGER, `state` TEXT NOT NULL, `generated_through` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`to_account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`payee_id`) REFERENCES `payees`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE INDEX IF NOT EXISTS `index_recurring_schedules_account_id` ON `recurring_schedules` (`account_id`)",
                "CREATE INDEX IF NOT EXISTS `index_recurring_schedules_to_account_id` ON `recurring_schedules` (`to_account_id`)",
                "CREATE INDEX IF NOT EXISTS `index_recurring_schedules_category_id` ON `recurring_schedules` (`category_id`)",
                "CREATE INDEX IF NOT EXISTS `index_recurring_schedules_payee_id` ON `recurring_schedules` (`payee_id`)",
                "CREATE TABLE IF NOT EXISTS `recurring_occurrences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `schedule_id` INTEGER NOT NULL, `due_date` INTEGER NOT NULL, `transaction_id` INTEGER, `skipped` INTEGER NOT NULL, `reminder_sent` INTEGER NOT NULL, `overdue_sent` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, FOREIGN KEY(`schedule_id`) REFERENCES `recurring_schedules`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_occurrences_schedule_id_due_date` ON `recurring_occurrences` (`schedule_id`, `due_date`)",
                "CREATE INDEX IF NOT EXISTS `index_recurring_occurrences_transaction_id` ON `recurring_occurrences` (`transaction_id`)",
            ).forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
