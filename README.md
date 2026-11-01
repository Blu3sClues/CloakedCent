# Pocket Budget

A private, fully offline budgeting app for Android.

- Native Android: Kotlin, Jetpack Compose, Material 3
- MVVM + repository pattern, manual dependency injection
- Room on SQLCipher: the whole database is encrypted at rest
- No internet permission, no accounts, no analytics, no cloud services

Application ID `com.pocketbudget.app` · minSdk 26 · targetSdk 36

---

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Selected automatically by Gradle (see below). Your system `java` can be any version. |
| Android SDK | Platform 36 / 36.1, Build-Tools 36.x | Install from Android Studio's SDK Manager. |
| Gradle | 9.7.1 | Downloaded by the wrapper and checked against a pinned SHA-256. Do not install Gradle separately. |
| Android Studio | 2025.3 or newer | Optional for command-line builds. |

### JDK 21

`gradle/gradle-daemon-jvm.properties` requires the Gradle daemon to run on JDK 21,
whatever `JAVA_HOME` or `PATH` point to. Gradle finds JDK 21 from its usual
locations, including `~/.gradle/jdks`. To let it use Android Studio's bundled JDK,
add this line to your **user** Gradle properties (`~/.gradle/gradle.properties`,
not the project's):

```properties
org.gradle.java.installations.paths=C:/Program Files/Android/Android Studio/jbr
```

### SDK location

Android Studio writes `local.properties` (gitignored) automatically. For
command-line-only setups, create it:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

---

## Build and test

Run from the project root (`gradlew.bat` on Windows, `./gradlew` elsewhere).

| Task | Command |
|---|---|
| Debug APK | `gradlew.bat assembleDebug` |
| Unit tests (JVM) | `gradlew.bat testDebugUnitTest` |
| Instrumented tests (emulator/device required) | `gradlew.bat connectedDebugAndroidTest` |
| Lint | `gradlew.bat lintDebug` |
| Install on running emulator/device | `gradlew.bat installDebug` |
| Release build (R8-shrunk, unsigned) | `gradlew.bat assembleRelease` |

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unit test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Instrumented test report: `app/build/reports/androidTests/connected/debug/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

Release signing is not configured yet. Signing keys must never be committed
(`*.jks`, `*.keystore` and `keystore.properties` are gitignored).

---

## Project structure

```
app/src/main/java/com/pocketbudget/app/
├── PocketBudgetApp.kt        Application; owns the AppContainer
├── MainActivity.kt           Single activity, Compose entry point
├── core/money/               Money: exact whole-cent arithmetic
├── data/
│   ├── db/                   Room database, entities, DAOs, migrations, factory
│   ├── receipt/              Image processing, encrypted image store, Tesseract OCR
│   ├── security/             Database key generation and Keystore wrapping
│   └── repository/           Repository implementations
├── domain/
│   ├── model/                Plain Kotlin models (no Android/Room types)
│   └── repository/           Repository interfaces used by ViewModels
├── di/AppContainer.kt        Manual dependency wiring
└── ui/
    ├── theme/                Brand colours and Material 3 theme
    └── feature/<name>/       Screen + ViewModel + UiState per feature
app/schemas/                  Exported Room schemas (committed; used by migration tests)
```

Dependency direction: `ui → domain ← data`. ViewModels depend only on domain
interfaces, never on Room or SQLCipher.

---

## Security model

**Database encryption.** The database (`pocket_budget.db`) is encrypted by
SQLCipher 4 (AES-256). Instrumented tests check that the file has no plaintext
SQLite header, that stored values do not appear in the raw bytes, and that a
wrong key cannot open it.

**Key management.**

1. On first launch, `DatabaseKeyStore` generates a 32-byte passphrase with `SecureRandom`.
2. `AndroidKeystoreKeyWrapper` encrypts it with an AES-256-GCM key that is
   generated in, and never leaves, Android Keystore.
3. Only the wrapped passphrase is written to disk, at
   `noBackupFilesDir/database.key`, atomically (temp file, fsync, rename).
4. On later launches the wrapped passphrase is unwrapped and passed to SQLCipher.

No key is hardcoded, and no key material is logged or included in exception messages.

**Integrity rules.**

- An existing key file is never overwritten.
- If the database exists but its key is missing or damaged, the app shows an
  error and **does not** create a new key or delete data.
- Room's destructive migration fallback is never enabled.
- SQLCipher's corruption handler does not delete encrypted databases; this is
  covered by `EncryptedDatabaseTest.wrongKeyCannotOpenDatabase`.

**Privacy.**

- The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE`, even if a library
  tries to add them.
- `allowBackup="false"`, `fullBackupContent="false"`, and
  `data_extraction_rules.xml` exclude all data from Google cloud backup and
  device-to-device transfer.
- Release builds strip verbose, debug and info logging (`proguard-rules.pro`).
- **Receipt images** are re-encoded on import, which removes EXIF metadata (location,
  device, time). Each page is stored as an AES-256-GCM file in `noBackupFilesDir/receipts`.
  The file name and format version are authenticated with the data. The random media key
  lives only inside the SQLCipher database (`app_metadata`). Decrypted images exist only
  in memory. There is no shared storage and no exported provider. Files left by an
  interrupted import or a discard are removed by the background job.
- **OCR** runs entirely on the device: Tesseract 5.5.1 through Tesseract4Android 4.9.0
  (Apache-2.0), with the English `tessdata_fast` model bundled in the APK. The SHA-256
  of the model is checked before use, and the build checks the SHA-256 of the AAR.
- **Camera and location** permissions are requested only when used. Photos can be
  picked without any permission. Location is a single fix, taken only when the user
  taps *Use current location*, and is never needed for scanning.

---

## Backup design (planned; not yet implemented)

The Keystore key is bound to this device and installation by design, so it
**cannot** protect portable backups. Manual backups will therefore:

1. Be started only by the user, and written to a location the user picks with
   the Storage Access Framework. No cloud integration.
2. Be encrypted with a key derived from a **user-chosen backup password**
   (a memory-hard KDF), independent of the device key.
3. Use a versioned file format containing the database schema version, so a
   restore can run the normal Room migrations.
4. Be restored by validating the backup completely before replacing anything,
   keeping the current database until the restore has succeeded.

The `app_metadata` table is reserved for backup and installation metadata.

---

## Financial data rules

- **Money** is `core.money.Money`: a `Long` of whole cents with overflow-checked
  arithmetic. `Float` and `Double` are never used for monetary values. Parsing
  is strict and never goes through floating point.
- **Foreign keys** are enabled on every connection (`PRAGMA foreign_keys = ON`,
  verified by test).

### Ledger model (schema v2)

| Table | Purpose |
|---|---|
| `accounts` | Financial accounts with an opening balance (cents). Archived, never deleted. |
| `categories` | Income/expense categories; `parent_id` nests them (same kind, no cycles). |
| `payees` | People and merchants, unique by name (case-insensitive lookup). |
| `transfers` | Groups the two legs of a transfer between different accounts. |
| `transactions` | The ledger. One signed entry per account: income `> 0`, expense `< 0`, transfers as two legs. |
| `transaction_revisions` | Immutable snapshot written before every edit, status change or delete. |

Income and expenses are entry *types* in one ledger rather than separate tables,
so every balance comes from a single source and nothing can be double counted.

- **Status** is type-specific once money has moved: income `RECEIVED`, expense
  `SPENT`, transfer and balance adjustment `COMPLETED` (the defaults). Any type
  except adjustments can instead be `SCHEDULED` (planned, money has not moved).
  Database triggers reject mismatched combinations. Balances are reported as
  *current* (everything not scheduled) and *projected* (current + scheduled).
  Confirming a scheduled entry moves it to its type's status exactly once (both
  transfer legs atomically) and is recorded in the audit history. Schema v3
  merged `PENDING`/`CLEARED` into `SPENT`; v4 split `SPENT` by type.
- **Balance adjustments** ("Adjust balance" in account settings) are recorded as
  new ledger entries (`is_adjustment = 1`), never by rewriting balances. The
  opening balance is set only at creation. Classification: *balance only*
  (type `ADJUSTMENT`, excluded from income/expense), *income* (increases only),
  or *expense* (decreases only).

### Budgets and recurring finances (schema v5)

- **Budgets** cover a category and all its subcategories, with weekly
  (aligned to the start date's weekday), monthly, quarterly, yearly
  (calendar-aligned) or custom-range periods. Spending counts only completed,
  non-deleted expenses, computed from each budget's own category subtree, so
  a parent budget includes child spending exactly once. Limit changes are
  stored in `budget_limits` and apply from the current period, so history is
  never rewritten. Optional rollover carries only unused allocation forward;
  "Restart" begins a new rollover cycle. Alert thresholds (e.g. 50/75/90/100%)
  notify once per period (`budget_alerts`).
- **Recurring schedules** generate ordinary *scheduled* transactions through
  the existing ledger 14 days before they are due. Duplicates are impossible:
  each occurrence has a unique `(schedule_id, due_date)` row and a deterministic
  request id (`recurring-<id>-<epochDay>`). Dates are always computed from the
  start date, so month-end and Feb 29 anchors are kept. Editing affects only
  occurrences not yet generated; pausing, resuming (missed occurrences are not
  back-filled), skipping and cancelling never touch completed transactions.
  Confirming accepts the actual amount for variable bills (atomic, audited).
- **Background work:** WorkManager runs `PlanningWorker` on app start and every
  12 hours to generate occurrences, post due/overdue reminders and budget
  alerts. Notifications need `POST_NOTIFICATIONS` on Android 13+ (requested in
  context) and hide names and amounts on the lock screen. Dates are local
  calendar dates evaluated at run time, so time zone changes are handled.

### Receipts and purchase history (schema v6)

- **Workflow.** Capture pages with CameraX or the photo picker (multi-page; crop,
  rotate and enhance per page). The pages are stored encrypted, and OCR then fills
  in a *draft*. The user reviews and corrects it, then confirms or discards it.
  Low-confidence lines and values are highlighted on the image and in the form.
  Nothing affects balances or budgets until confirmation.
- **Accounting.** Confirming creates a new expense through the normal ledger, or
  attaches the receipt to an existing one. Possible matches (same amount, or
  scheduled/recurring entries within ±7 days) are only suggested; the user always
  chooses. A scheduled match is confirmed with the receipt total. A completed
  expense with a different amount changes only if the user allows it, and the
  change is recorded as an audited revision. Each expense can have only one
  receipt, enforced by a unique index. Confirmation is atomic and idempotent
  (request id `receipt-<id>`).
- **Reconciliation.** Items after their discounts, minus receipt-level discounts,
  plus tax must equal the total exactly, in integer cents. Otherwise confirmation
  is refused.
- **Category splits.** `transaction_splits` divides the expense by item category.
  Tax and discounts are shared proportionally, using the largest-remainder method
  so the parts add up exactly. Budgets count a split expense once, through its
  splits; ordinary expenses are unchanged. Triggers keep splits within the
  expense and fix its amount once split.
- **Duplicates.** An identical image (SHA-256 of the imported file) is refused
  unless the user chooses to import it anyway. A visually similar photo
  (difference hash) or a receipt with the same store, date and total, or the same
  receipt number, triggers a warning.
- **Products and prices.** Confirmed items create or update products (pack size
  taken from the name when printed, e.g. "1L"). They also teach store-specific
  aliases: the store's receipt wording is mapped to the product the user chose,
  and the alias is suggested on the next receipt. `price_observations` (append-only)
  records each confirmed item with a normalized unit price per kg, litre or item.
  The price is in thousandths of a cent, calculated with exact integer
  arithmetic; lb, g and ml are converted. This is the data for Stage 7 price alerts.

### Spending analysis

- **Screens.** An overview, categories, stores (retailers and their locations) and
  products. Periods: 7, 30 or 90 days; this or last month; this or last year; all
  time; or a custom range. Each period is compared with the equally long period
  before it.
- **What counts as spending.** Completed, non-deleted expenses that are not balance
  adjustments. Scheduled, deleted, income (including refunds recorded as income),
  transfer and adjustment entries are excluded.
- **Categories.** Split expenses count through their splits. A parent category's
  total adds each descendant's amount exactly once.
- **Stores and products.** These use confirmed receipts whose expense still counts;
  each expense and each item is counted once. Product amounts are item prices after
  item discounts, before tax.
- **How it's computed.** Aggregation runs in SQLite (`AnalysisDao`) as Room Flows,
  so results update when the data changes. Averages and percentages use
  whole-cent, half-up integer arithmetic. No schema change was needed (still v6).

- **Amounts in forms** are typed freely and shown as currency (e.g. `$38.00`)
  when the field loses focus; `AmountInput` parses locale grouping/decimal
  separators to exact cents.
- **Theme:** Light / Dark / System, stored in app-private SharedPreferences
  (no financial data) and applied app-wide immediately.
- **Transfers** are never counted as income or expense.
- **No duplicate processing:** every income, expense and transfer carries a
  unique `request_id`. Repeating a request returns the original record, and
  status changes apply exactly once.
- **Atomic writes:** each repository operation runs in one database
  transaction. A transfer writes both legs or nothing.
- **History:** transactions are soft-deleted only (`is_deleted`), and prior
  values are kept in `transaction_revisions`.
- **Database triggers** (`LedgerSchema`) enforce amount signs, valid statuses,
  transfer consistency, distinct transfer accounts, no hard deletes and
  immutable revisions, even if repository code is bypassed.
- **Accounts** can be archived only at a zero projected balance, so totals stay truthful.
- **Single currency for now:** all accounts share the device locale's currency.
- **Sample data:** debug builds offer "Load sample data" on an empty ledger.
  The code lives in `src/debug` and is absent from release builds.

---

## Database migrations

1. Change the entities and increment `version` in `PocketBudgetDatabase`.
2. Add a `Migration` to `Migrations.ALL` (`data/db/Migrations.kt`).
3. Build once. Room exports `app/schemas/.../<version>.json`; commit it.
4. Add a test to `MigrationTest` that creates the old version, inserts
   representative rows, migrates, and checks every row survived.
5. Never edit a released migration or an exported schema file.

Migration tests run against SQLCipher-encrypted databases, as real upgrades do.

---

## Dependency versions

All versions live in `gradle/libs.versions.toml`, whose header comments explain
how they constrain each other. In short:

- compileSdk/targetSdk 36 is the latest installed platform. Compose 1.12+,
  core 1.19+ and lifecycle 2.11+ need compileSdk 37, so they are held back
  until API 37 is installed.
- AGP 9.4.1 needs Gradle 9.6 or newer, and compiles Kotlin itself (built-in
  Kotlin), so the `kotlin-android` plugin and `kapt` are not used. Room uses KSP.
- New releases are adopted only after they have been out for at least two weeks.

Change versions as a set, then run unit tests, instrumented tests and lint.
