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
- Receipt images (future) will be stored in app-internal storage only. They will
  not go in shared storage and will not be exposed through an exported provider.

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

- **Status:** `SPENT` (the default: money has moved; used for income too) or
  `SCHEDULED` (planned, money has not moved). Balances are reported as
  *current* (spent only) and *projected* (current + scheduled). Scheduled entries
  never appear in current balances or monthly income/spending. "Mark as spent"
  converts SCHEDULED → SPENT exactly once (both legs for transfers) and records
  the change in the audit history. Schema v3 migrated the former `PENDING` and
  `CLEARED` statuses to `SPENT`.
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
