# Stage 6: Receipts (approved design, not yet implemented)

Approved after the Stage 5 assessment. Builds on schema v5 (`d1efa22`).

## Decisions

| Topic | Decision |
|---|---|
| OCR | Tesseract, fully offline. ML Kit is rejected because it brings in Firebase components and Google logging. |
| Camera | Integrated CameraX (`CAMERA` permission, requested in context). Imports use the system photo picker (no permission). |
| Store location | Optional. Captured only when the user taps "Set store location"; `ACCESS_COARSE_LOCATION` is requested at that moment. Uses the platform `LocationManager`, not Play Services. |
| Backup encryption | Argon2id from a maintained, verified implementation (e.g. BouncyCastle). Verify the version and checksums before adopting. |

## Requirements

- All receipt processing stays offline. Never add `INTERNET` or cloud dependencies.
- Never automatically confirm OCR results or create expenses. The user reviews and confirms every receipt.
- Preserve the product aliases learned from user corrections.
- Compare products using normalized unit prices.
- Keep receipt images and OCR data encrypted and private.
- Keep duplicate protection for both receipts and expenses.
- Keep the existing rules: integer cents, soft delete, audit revisions, foreign keys, and an additive migration (v5 → v6) with tests.

## Link to the ledger

- The expense transaction stays the source of truth. A receipt is a document attached to it. Budgets and balances never read receipt items.
- `receipts.transaction_id` is nullable and unique (RESTRICT). A draft has no transaction.
- Receipts that span several categories use `transaction_splits` (`transaction_id`, `category_id`, `amount_cents`):
  - A trigger enforces that the splits add up exactly to the transaction amount.
  - Budget spending uses the splits when they exist and the transaction's own category otherwise.
  - Tests must show that budgets for transactions without splits are unchanged.
- Corrections that change an expense go through `TransactionRepository.update`, so a revision is written.

## Tables (schema v6)

| Table | Key columns |
|---|---|
| `stores` | name, normalized_name, address?, latitude_e7?/longitude_e7? (Long), payee_id? |
| `receipts` | transaction_id? (unique), store_id?, purchased_at, subtotal/tax/discount/total cents, status (DRAFT, NEEDS_REVIEW, CONFIRMED, DISCARDED), receipt_number?, ocr_engine_version, request_id (unique), is_deleted |
| `receipt_images` | receipt_id, page, random file_name, sha256 (unique), perceptual_hash, byte_size, format_version |
| `receipt_ocr_lines` | receipt_id, line_no, text, bounding box, confidence. Kept so receipts can be re-parsed. |
| `receipt_items` | receipt_id, line_no, raw_text, product_id?, description, quantity_milli (Long), unit (EACH/KG/LB/L…), unit_price_cents, line_total_cents, discount_cents, category_id?, confidence, user_verified |
| `products` | name, normalized_name, brand?, default_category_id?, pack size and unit |
| `product_aliases` | store_id, normalized_alias → product_id. Unique (store_id, normalized_alias), learned from corrections. |
| `price_observations` | product_id, store_id, receipt_item_id (unique), observed_on, unit_price_cents, quantity_milli, unit, normalized price per base unit (integer) |
| `price_alerts` | Prevents repeat alerts, the same way `budget_alerts` does |

## Prices

- Price history is recorded from verified items only.
- Per-unit prices (per kg, per L, per each) use whole-number maths and are rounded only for display.
- An alert fires when a new price is above the median of earlier observations, compared both at the same store and across all stores. Each alert is sent once.
- Receipt-level tax and discounts are spread across items in whole cents using the largest-remainder method, so the shares add up exactly.

## OCR

- Use Tesseract4Android behind an `OcrEngine` interface, so the engine can be replaced later (e.g. PaddleOCR).
  - Confirm whether it is published on Maven Central or JitPack.
  - Pin the version and enable Gradle dependency verification.
- Bundle the `eng` model (fast ≈ 4 MB; best ≈ 15 MB) in assets. On first use, copy it to `noBackupFilesDir/tessdata` and check its SHA-256.
- Prepare images before OCR: grayscale, binarize, deskew, and crop to the receipt.
- APK size: restrict `abiFilters` to arm64-v8a, plus x86_64 for the emulator. Expect the release APK to grow from about 9 MB to 17–22 MB; measure it.
- OCR runs on a background dispatcher. Nothing leaves the device.

## Verification flow

- OCR → parser → DRAFT. The review screen shows the image with each field's source text highlighted, and flags low-confidence fields.
- Amounts are parsed with the existing strict `AmountInput`/`Money` parser, never with floating point.
- Items + tax − discounts must equal the total. If not, show the difference and block confirmation.
- Confirming creates or links the expense, marks items `user_verified`, saves the alias corrections and writes price observations, all in one transaction.

## Duplicate protection

1. **Same image:** the SHA-256 of the image bytes blocks an exact duplicate. A perceptual hash warns about a re-photographed receipt.
2. **Same receipt:** warn when the store, date and total match an existing receipt, or when the receipt number matches.
3. **Expense already entered:** before creating an expense, look for one with the same amount within ±3 days, including SCHEDULED ones.
   - If there is a strong match, attach the receipt to it by default.
   - If the match is scheduled, use `confirm(id, actualAmount)`.
4. **Safe retries:** the expense is created with request ID `receipt-<receiptId>`, and the unique `transaction_id` on receipts stops one expense from getting two receipts.

## Image storage and backups

- Images are re-encoded (longest side ≈ 2000 px), which removes EXIF data, including GPS.
- Store them under `noBackupFilesDir/receipts/`, encrypted with AES-256-GCM:
  - Each file has a random nonce, and the image id plus format version are used as associated data.
  - The media key is random and lives inside the SQLCipher database.
- Images are decrypted into memory only. There is no plaintext temp file, no exported provider and no shared storage.
- The file is written before its database row. A cleanup step removes files that have no row.
- **Backups (manual, user-started, written through the Storage Access Framework):**
  - The archive contains a header (format version, schema version, Argon2id parameters, salt), a database copy encrypted with the password-derived key, and the encrypted image files copied unchanged.
  - Images can be copied as they are because their key travels inside the encrypted database.
  - A manifest records the SHA-256 of every file.
  - An "Include images" option is available.
  - A restore validates everything before replacing the current data.
