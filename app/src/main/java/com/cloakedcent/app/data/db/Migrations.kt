package com.cloakedcent.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cloakedcent.app.domain.model.TextKeys

/**
 * Every schema migration, in order.
 *
 * Rules:
 * - Bump [CloakedCentDatabase] `version`, build once to export the new schema
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

    /**
     * v6: receipts, purchased items, products, store-specific aliases, price history and
     * expense category splits. Additive only; triggers for the new tables are created
     * on open. Existing expenses have no splits, so budgets are unchanged.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `stores` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalized_name` TEXT NOT NULL, `address` TEXT, `latitude_e7` INTEGER, `longitude_e7` INTEGER, `payee_id` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`payee_id`) REFERENCES `payees`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE INDEX IF NOT EXISTS `index_stores_normalized_name` ON `stores` (`normalized_name`)",
                "CREATE INDEX IF NOT EXISTS `index_stores_payee_id` ON `stores` (`payee_id`)",
                "CREATE TABLE IF NOT EXISTS `receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `request_id` TEXT NOT NULL, `status` TEXT NOT NULL, `store_id` INTEGER, `store_name` TEXT NOT NULL, `store_address` TEXT NOT NULL, `purchased_on` INTEGER, `purchased_minute` INTEGER, `subtotal_cents` INTEGER, `tax_cents` INTEGER NOT NULL, `discount_cents` INTEGER NOT NULL, `total_cents` INTEGER, `receipt_number` TEXT NOT NULL, `default_category_id` INTEGER, `transaction_id` INTEGER, `latitude_e7` INTEGER, `longitude_e7` INTEGER, `uncertain_fields` TEXT NOT NULL, `field_sources` TEXT NOT NULL, `ocr_engine` TEXT, `ocr_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `confirmed_at` INTEGER, FOREIGN KEY(`store_id`) REFERENCES `stores`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`default_category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipts_request_id` ON `receipts` (`request_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipts_transaction_id` ON `receipts` (`transaction_id`)",
                "CREATE INDEX IF NOT EXISTS `index_receipts_store_id` ON `receipts` (`store_id`)",
                "CREATE INDEX IF NOT EXISTS `index_receipts_default_category_id` ON `receipts` (`default_category_id`)",
                "CREATE INDEX IF NOT EXISTS `index_receipts_purchased_on` ON `receipts` (`purchased_on`)",
                "CREATE TABLE IF NOT EXISTS `receipt_images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `receipt_id` INTEGER NOT NULL, `page` INTEGER NOT NULL, `file_name` TEXT NOT NULL, `source_sha256` TEXT NOT NULL, `content_sha256` TEXT NOT NULL, `perceptual_hash` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `byte_size` INTEGER NOT NULL, `format_version` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, FOREIGN KEY(`receipt_id`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_images_receipt_id_page` ON `receipt_images` (`receipt_id`, `page`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_images_file_name` ON `receipt_images` (`file_name`)",
                "CREATE INDEX IF NOT EXISTS `index_receipt_images_source_sha256` ON `receipt_images` (`source_sha256`)",
                "CREATE TABLE IF NOT EXISTS `receipt_ocr_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `receipt_id` INTEGER NOT NULL, `page` INTEGER NOT NULL, `line_index` INTEGER NOT NULL, `text` TEXT NOT NULL, `confidence` INTEGER NOT NULL, `box_left` INTEGER, `box_top` INTEGER, `box_right` INTEGER, `box_bottom` INTEGER, FOREIGN KEY(`receipt_id`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_ocr_lines_receipt_id_page_line_index` ON `receipt_ocr_lines` (`receipt_id`, `page`, `line_index`)",
                "CREATE TABLE IF NOT EXISTS `products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalized_name` TEXT NOT NULL, `brand` TEXT, `default_category_id` INTEGER, `pack_quantity_milli` INTEGER, `pack_unit` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`default_category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_products_normalized_name` ON `products` (`normalized_name`)",
                "CREATE INDEX IF NOT EXISTS `index_products_default_category_id` ON `products` (`default_category_id`)",
                "CREATE TABLE IF NOT EXISTS `receipt_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `receipt_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `raw_text` TEXT NOT NULL, `description` TEXT NOT NULL, `product_id` INTEGER, `quantity_milli` INTEGER NOT NULL, `unit` TEXT NOT NULL, `unit_price_cents` INTEGER, `line_total_cents` INTEGER NOT NULL, `discount_cents` INTEGER NOT NULL, `category_id` INTEGER, `confidence` INTEGER NOT NULL, `uncertain` INTEGER NOT NULL, `source_page` INTEGER, `source_line` INTEGER, `user_verified` INTEGER NOT NULL, FOREIGN KEY(`receipt_id`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_items_receipt_id_position` ON `receipt_items` (`receipt_id`, `position`)",
                "CREATE INDEX IF NOT EXISTS `index_receipt_items_product_id` ON `receipt_items` (`product_id`)",
                "CREATE INDEX IF NOT EXISTS `index_receipt_items_category_id` ON `receipt_items` (`category_id`)",
                "CREATE TABLE IF NOT EXISTS `product_aliases` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `store_id` INTEGER NOT NULL, `normalized_alias` TEXT NOT NULL, `product_id` INTEGER NOT NULL, `times_confirmed` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`store_id`) REFERENCES `stores`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_product_aliases_store_id_normalized_alias` ON `product_aliases` (`store_id`, `normalized_alias`)",
                "CREATE INDEX IF NOT EXISTS `index_product_aliases_product_id` ON `product_aliases` (`product_id`)",
                "CREATE TABLE IF NOT EXISTS `price_observations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `product_id` INTEGER NOT NULL, `store_id` INTEGER NOT NULL, `receipt_item_id` INTEGER NOT NULL, `observed_on` INTEGER NOT NULL, `quantity_milli` INTEGER NOT NULL, `unit` TEXT NOT NULL, `paid_cents` INTEGER NOT NULL, `unit_price_cents` INTEGER, `normalized_dimension` TEXT, `normalized_millicents` INTEGER, `created_at` INTEGER NOT NULL, FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`store_id`) REFERENCES `stores`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`receipt_item_id`) REFERENCES `receipt_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_price_observations_receipt_item_id` ON `price_observations` (`receipt_item_id`)",
                "CREATE INDEX IF NOT EXISTS `index_price_observations_product_id_observed_on` ON `price_observations` (`product_id`, `observed_on`)",
                "CREATE INDEX IF NOT EXISTS `index_price_observations_store_id` ON `price_observations` (`store_id`)",
                "CREATE TABLE IF NOT EXISTS `transaction_splits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transaction_id` INTEGER NOT NULL, `category_id` INTEGER NOT NULL, `amount_cents` INTEGER NOT NULL, `receipt_id` INTEGER, `created_at` INTEGER NOT NULL, FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`receipt_id`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_splits_transaction_id_category_id` ON `transaction_splits` (`transaction_id`, `category_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transaction_splits_category_id` ON `transaction_splits` (`category_id`)",
                "CREATE INDEX IF NOT EXISTS `index_transaction_splits_receipt_id` ON `transaction_splits` (`receipt_id`)",
            ).forEach(db::execSQL)
        }
    }

    /**
     * v7: refunds (`refunds`, `refund_allocations`) and the REFUND entry type, whose
     * rule is added to the transaction triggers (dropped here, recreated on open).
     * Store, product and alias keys are recomputed with the v7 normalization so
     * "Walmart" / "WALMART SUPERCENTER" share a key. Existing rows are never merged:
     * if two products now share a key, the later one keeps a unique suffixed key.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LedgerSchema.STATUS_TRIGGERS.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
            listOf(
                "CREATE TABLE IF NOT EXISTS `refunds` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `refund_transaction_id` INTEGER NOT NULL, `original_transaction_id` INTEGER NOT NULL, `receipt_item_id` INTEGER, `created_at` INTEGER NOT NULL, FOREIGN KEY(`refund_transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`original_transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`receipt_item_id`) REFERENCES `receipt_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_refunds_refund_transaction_id` ON `refunds` (`refund_transaction_id`)",
                "CREATE INDEX IF NOT EXISTS `index_refunds_original_transaction_id` ON `refunds` (`original_transaction_id`)",
                "CREATE INDEX IF NOT EXISTS `index_refunds_receipt_item_id` ON `refunds` (`receipt_item_id`)",
                "CREATE TABLE IF NOT EXISTS `refund_allocations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `refund_transaction_id` INTEGER NOT NULL, `category_id` INTEGER, `receipt_item_id` INTEGER, `amount_cents` INTEGER NOT NULL, `item_net_cents` INTEGER, `quantity_milli` INTEGER, `created_at` INTEGER NOT NULL, FOREIGN KEY(`refund_transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`receipt_item_id`) REFERENCES `receipt_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                "CREATE INDEX IF NOT EXISTS `index_refund_allocations_refund_transaction_id` ON `refund_allocations` (`refund_transaction_id`)",
                "CREATE INDEX IF NOT EXISTS `index_refund_allocations_category_id` ON `refund_allocations` (`category_id`)",
                "CREATE INDEX IF NOT EXISTS `index_refund_allocations_receipt_item_id` ON `refund_allocations` (`receipt_item_id`)",
            ).forEach(db::execSQL)
            rekey(db, "SELECT id, name FROM stores ORDER BY id", "UPDATE stores SET normalized_name = ? WHERE id = ?", unique = false) {
                TextKeys.storeKey(it)
            }
            // Clear keys first so the unique index never sees two rows with one key mid-update.
            db.execSQL("UPDATE products SET normalized_name = '~' || id")
            rekey(db, "SELECT id, name FROM products ORDER BY id", "UPDATE products SET normalized_name = ? WHERE id = ?", unique = true) {
                TextKeys.descriptionKey(it).ifEmpty { TextKeys.normalize(it) }
            }
            // Aliases are unique per store; on a clash the earlier alias keeps the key.
            val aliases = mutableListOf<Triple<Long, Long, String>>()
            db.query("SELECT id, store_id, normalized_alias FROM product_aliases ORDER BY id").use { c ->
                while (c.moveToNext()) aliases += Triple(c.getLong(0), c.getLong(1), c.getString(2))
            }
            val taken = aliases.map { it.second to it.third }.toMutableSet()
            aliases.forEach { (id, store, old) ->
                val key = TextKeys.descriptionKey(old)
                if (key != old && key.isNotEmpty() && taken.add(store to key)) {
                    taken.remove(store to old)
                    db.execSQL("UPDATE product_aliases SET normalized_alias = ? WHERE id = ?", arrayOf<Any>(key, id))
                }
            }
        }

        private fun rekey(db: SupportSQLiteDatabase, select: String, update: String, unique: Boolean, key: (String) -> String) {
            val rows = mutableListOf<Pair<Long, String>>()
            db.query(select).use { c -> while (c.moveToNext()) rows += c.getLong(0) to c.getString(1) }
            val used = mutableSetOf<String>()
            rows.forEach { (id, name) ->
                var value = key(name)
                if (unique && !used.add(value)) value = "$value~$id"
                db.execSQL(update, arrayOf<Any>(value, id))
            }
        }
    }

    /**
     * v8: confirmed receipts can be deleted (status DELETED). No table changes; the receipt
     * triggers that know the status are dropped here and recreated on open.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LedgerSchema.RECEIPT_DELETE_TRIGGERS.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        }
    }

    /**
     * v9: deletion is permanent. The triggers that forbade removing rows now allow it for
     * rows marked for erasure (see ErasureDao). Everything kept by earlier soft deletes is
     * erased here: deleted transactions with their history, splits and refund links;
     * deleted and discarded receipts with their items, prices and text; and payees, stores,
     * products and aliases nothing uses any more. A VACUUM is then requested so the file
     * itself keeps no trace (it cannot run inside the migration's transaction).
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LedgerSchema.ERASURE_TRIGGERS.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
            val receipts = "SELECT id FROM receipts WHERE status IN ('DELETED', 'DISCARDED')"
            val items = "SELECT id FROM receipt_items WHERE receipt_id IN ($receipts)"
            val transactions = "SELECT id FROM transactions WHERE is_deleted = 1"
            listOf(
                "UPDATE transaction_splits SET receipt_id = NULL WHERE receipt_id IN ($receipts)",
                "UPDATE refunds SET receipt_item_id = NULL WHERE receipt_item_id IN ($items)",
                "UPDATE refund_allocations SET receipt_item_id = NULL, item_net_cents = NULL, quantity_milli = NULL WHERE receipt_item_id IN ($items)",
                "DELETE FROM price_observations WHERE receipt_item_id IN ($items)",
                "DELETE FROM receipt_items WHERE receipt_id IN ($receipts)",
                "DELETE FROM receipt_ocr_lines WHERE receipt_id IN ($receipts)",
                "DELETE FROM receipt_images WHERE receipt_id IN ($receipts)",
                "DELETE FROM receipts WHERE status IN ('DELETED', 'DISCARDED')",
                "UPDATE receipts SET transaction_id = NULL WHERE transaction_id IN ($transactions)",
                "DELETE FROM transaction_splits WHERE transaction_id IN ($transactions)",
                "DELETE FROM refund_allocations WHERE refund_transaction_id IN ($transactions)",
                "DELETE FROM refunds WHERE refund_transaction_id IN ($transactions) OR original_transaction_id IN ($transactions)",
                "UPDATE recurring_occurrences SET transaction_id = NULL WHERE transaction_id IN ($transactions)",
                "DELETE FROM transaction_revisions WHERE transaction_id IN ($transactions)",
                "DELETE FROM transactions WHERE is_deleted = 1",
                "DELETE FROM transfers WHERE id NOT IN (SELECT transfer_id FROM transactions WHERE transfer_id IS NOT NULL)",
            ).forEach(db::execSQL)
            forgetUnsupportedAliases(db)
            listOf(
                """
                DELETE FROM product_aliases
                WHERE product_id NOT IN (SELECT product_id FROM receipt_items WHERE product_id IS NOT NULL)
                    AND product_id NOT IN (SELECT product_id FROM price_observations)
                """,
                """
                DELETE FROM products
                WHERE id NOT IN (SELECT product_id FROM receipt_items WHERE product_id IS NOT NULL)
                    AND id NOT IN (SELECT product_id FROM price_observations)
                    AND id NOT IN (SELECT product_id FROM product_aliases)
                """,
                """
                DELETE FROM stores
                WHERE id NOT IN (SELECT store_id FROM receipts WHERE store_id IS NOT NULL)
                    AND id NOT IN (SELECT store_id FROM price_observations)
                    AND id NOT IN (SELECT store_id FROM product_aliases)
                """,
                """
                DELETE FROM payees
                WHERE id NOT IN (SELECT payee_id FROM transactions WHERE payee_id IS NOT NULL)
                    AND id NOT IN (SELECT payee_id FROM transaction_revisions WHERE payee_id IS NOT NULL)
                    AND id NOT IN (SELECT payee_id FROM stores WHERE payee_id IS NOT NULL)
                    AND id NOT IN (SELECT payee_id FROM recurring_schedules WHERE payee_id IS NOT NULL)
                """,
            ).forEach(db::execSQL)
            db.execSQL(
                "INSERT OR REPLACE INTO app_metadata (meta_key, meta_value) VALUES (?, '1')",
                arrayOf<Any>(AppMetadataKeys.VACUUM_PENDING),
            )
        }

        /** An alias is supported by the confirmed items at its store that teach its key. */
        private fun forgetUnsupportedAliases(db: SupportSQLiteDatabase) {
            val support = mutableMapOf<Pair<Long, String>, Int>()
            db.query(
                """
                SELECT r.store_id, i.raw_text, i.description FROM receipt_items i JOIN receipts r ON r.id = i.receipt_id
                WHERE r.status = 'CONFIRMED' AND r.store_id IS NOT NULL
                """,
            ).use { c ->
                while (c.moveToNext()) {
                    val key = TextKeys.descriptionKey(c.getString(1).ifBlank { c.getString(2) })
                    if (key.isNotEmpty()) support.merge(c.getLong(0) to key, 1, Int::plus)
                }
            }
            val aliases = mutableListOf<Triple<Long, Pair<Long, String>, Int>>()
            db.query("SELECT id, store_id, normalized_alias, times_confirmed FROM product_aliases").use { c ->
                while (c.moveToNext()) aliases += Triple(c.getLong(0), c.getLong(1) to c.getString(2), c.getInt(3))
            }
            aliases.forEach { (id, key, times) ->
                val count = support[key] ?: 0
                if (count == 0) {
                    db.execSQL("DELETE FROM product_aliases WHERE id = ?", arrayOf<Any>(id))
                } else if (times > count) {
                    // Confirmations taught by erased receipts are no longer counted.
                    db.execSQL("UPDATE product_aliases SET times_confirmed = ? WHERE id = ?", arrayOf<Any>(count, id))
                }
            }
        }
    }

    /**
     * v10: users manage categories. No table changes; the triggers that let a category being
     * deleted hand its locked records to another category are dropped here and recreated on open.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LedgerSchema.CATEGORY_TRIGGERS.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        }
    }

    /**
     * Confirmed receipts no longer keep their reading: erase the OCR lines and the pointers
     * into them, so they show the page alone. The finality triggers are dropped to clear the
     * pointers and recreated on open.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val confirmed = "SELECT id FROM receipts WHERE status = 'CONFIRMED'"
            listOf(
                "DROP TRIGGER IF EXISTS trg_receipts_final",
                "DROP TRIGGER IF EXISTS trg_receipt_items_validate_update",
                "DELETE FROM receipt_ocr_lines WHERE receipt_id IN ($confirmed)",
                "UPDATE receipts SET field_sources = '' WHERE status = 'CONFIRMED'",
                "UPDATE receipt_items SET source_page = NULL, source_line = NULL WHERE receipt_id IN ($confirmed)",
            ).forEach(db::execSQL)
            db.execSQL(
                "INSERT OR REPLACE INTO app_metadata (meta_key, meta_value) VALUES (?, '1')",
                arrayOf<Any>(AppMetadataKeys.VACUUM_PENDING),
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11,
    )
}
