# SmartPesa — Kotlin Android application

An offline expense tracker for university students, by Luka Mwango YNA (KCS/053/24), Kiriri Women's University of Science and Technology. This repository contains the independent native Android project, maintained in **Kotlin/** in the original workspace. Its dark surfaces, blue accent, six screens and native splash follow the supplied reference. Amounts use Kenyan shillings.

## Build and install

Open this folder in Android Studio. Install Android SDK 35 and select JDK 17 or 21 for Gradle. Android 8.0 or later is supported. Set the SDK location in an ignored `local.properties`:

```properties
sdk.dir=/your/path/to/Android/Sdk
```

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On this workstation, set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`. Dependencies are pinned in `app/build.gradle.kts`; allow the first synchronization to download them. The normal package is `com.smartpesa.student.nativeapp`, separate from the legacy Flutter app.

**Connected tests must use the disposable application ID:**

```bash
./gradlew :app:connectedDebugAndroidTest -PledgerDeviceTests=true
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first command targets `com.smartpesa.student.ledgertests`. Android's test runner removes its target installation afterward. A Gradle guard rejects connected tests without the isolation flag. Rebuild without the property before installing the normal app: both builds use the same APK output path.

An initial test setup error removed the native installation and cleared its data. This was disclosed and corrected; the legacy Flutter app was unaffected. Database fixture isolation alone does not protect installed applications from the test runner.

## Student workflows

Android navigation keys stay hidden on app screens, sheets and dialogs. Swipe from the bottom edge to reveal them temporarily. The status bar remains available; Android controls its own permission prompts, keyboard and document picker.

- Add income or an expense, choose its category and date, and save. The Save Transaction button stays at the bottom while the form scrolls, including above the keyboard. New ledgers start empty.
- Tap a transaction to inspect it, edit it or confirm deletion. Search and filters remain available in history.
- Set, edit or remove monthly category limits. Removing a limit leaves its transactions intact.
- Follow real spending totals through the dashboard, statistics and category budgets.
- Use **Profile → Message access** to optionally import supported M-PESA messages from the last 90 days and capture new messages. Manual entry works when access is declined.
- Use **Profile → Notifications** to allow Android notifications and choose transaction updates or spending warnings. Denying notifications does not disable financial tracking.
- Imported entries that need a decision say **Check category**. Edit their category; the app remembers that exact merchant for subsequent expenses.
- Change the local profile name or export readable CSV through Android's document picker.
- **Delete all app data** clears the ledger, limits, remembered categories, alert history, staged payments and profile name, and turns message access off. Existing CSV exports and Android's SMS inbox remain outside the app's control.

The balance is recorded income minus recorded expenses, not the current M-PESA wallet balance. Balance totals are all-time; dashboard reporting controls change the spending breakdown. Weeks start Monday. All reporting and parsed message dates use **Africa/Nairobi**, including when the device timezone changes. Monthly limits recur.

## Financial and notification policies

Supported message formats cover Till/Buy Goods, Paybill, sending money, receiving money, airtime and utility payments. Only the `MPESA`/`M-PESA` sender is accepted. Multipart text is joined before parsing. Invalid dates, unknown formats, promotional messages, withdrawals, loans and reversals are ignored rather than turned into guessed purchases. Check unsupported events manually. Cash withdrawals represent movement into cash and must not automatically count as purchases.

Amounts are exact `Long` cents. An imported fee is a separate **Other** expense committed atomically with its payment. It creates no second receipt. Deleting a payment also deletes its linked fee; changing the payment date moves its fee to the same date. A fee may also be edited or deleted deliberately. Deleted message codes remain remembered, so an inbox replay cannot recreate a removed payment until a full data reset and deliberate reimport.

Received money is income regardless of merchant wording. Exact remembered merchant corrections precede built-in rules. Unknown or low-score expenses stay in **Other** and count in spending; intermediate suggestions request review. Technical model names, scores and traces stay out of product screens. Bootstrap weights are explicitly synthetic and cannot auto-confirm predictions; genuine research validation is pending.

Transactions and notification decisions commit together. Receipts follow successful saves. A category/month can warn once at 80% and once at 100%; jumping directly to 100% suppresses a redundant 80% warning. A pending 80% event is consumed when 100% is reached. Editing expenses or limits reevaluates current-month spending. Removing and restoring a limit does not reset that month's warning history. Historical imports suppress receipts and evaluate current limits once at the end.

Pending notifications live in an encrypted outbox. Immediate delivery follows commit, with WorkManager retry/recovery. A retry uses the same notification ID and `setOnlyAlertOnce`; delivery and acknowledgement cannot be an atomic operation with Android, so strict exactly-once delivery is not claimed. Disabled or denied notifications are consumed, avoiding a later flood. Alerts omit amounts, merchant details and message codes. Tapping them opens history or budgets after any active save finishes.

Parsed live payments are staged in encrypted storage before processing; WorkManager input contains only a generation number. Access changes and deletion invalidate older import generations. Android force-stop prevents background reception until the app is opened again; device battery policies can delay jobs. No Internet permission is requested. Inbox access remains an optional Android permission, and store distribution has separate SMS-permission requirements.

## Storage and upgrades

SQLCipher encrypts the Room ledger. A random password is encrypted with an Android Keystore AES-GCM key. The app never resets a database merely because a key is missing or unreadable; it shows a storage error and preserves files. Uninstalling removes app data and its protected key. Export any records you intend to keep before uninstalling.

Upgrading a plaintext v1/v2 ledger exports it into an encrypted temporary file. Counts, schema version, integrity and ordered contents of every source table are checked before installation and plaintext cleanup. Recovery refuses mismatching contents even when row counts agree. Room migrations preserve existing transactions and limits; destructive fallback is disabled. Backup and device-transfer exclusions remain active. Profile and permission preferences are ordinary app-private preferences; financial records, corrections, outbox and staged payments are in the encrypted database. Personal CSV exports are readable and require a private destination.

No data is automatically moved from the separately installed Flutter app.

## Code map

| Location | Responsibility |
| --- | --- |
| `MainActivity.kt`, `res/` | Native splash, launcher logo, system bars and notification navigation |
| `ui/SmartPesaApp.kt` | Four primary tabs, entry/history screens and saved screen state |
| `ui/screens/`, `ui/components/`, `ui/theme/` | Student interface, shared cards/charts/icons and dark palette |
| `data/Money.kt` | Exact parsing, precision/overflow rejection and currency formatting |
| `data/FinanceDatabase.kt`, `app/schemas/` | Room entities, atomic writes, deduplication, corrections, outbox and migrations |
| `data/EncryptedStore.kt` | Keystore wrapping and recoverable plaintext conversion |
| `data/FinanceViewModel.kt` | Room flows and save/settings results that reconnect after rotation |
| `domain/` | Strict payment parser, category decisions and reporting/CSV policies |
| `services/LedgerService.kt` | Shared write path for manual input, edits and message ingestion |
| `services/MpesaReceiver.kt`, `MessageImport.kt` | Multipart reception, durable live recovery and optional inbox import |
| `services/NotificationDelivery.kt`, `BackgroundJobs.kt` | Private notifications, stable delivery and persisted background work |
| `assets/` | Merchant rules, explicitly synthetic bootstrap weights and portable inference fixtures |
| `src/test/`, `src/androidTest/` | Financial regression tests and isolated Android/database/UI checks |

Trace a manual save: `AddScreen` → `FinanceViewModel` → `LedgerService` → atomic `FinanceDao` commit → updated Room `Flow` → refreshed Compose screens. Trace a message: trusted sender + complete text → strict parser → encrypted staging → categorization/correction → atomic deduplicated commit → notification dispatch. Comments explain why each integrity or lifecycle rule exists.

For teaching, demonstrate these paths in order: native startup and theme; exact amounts; Room/Flow; edit/delete and CSV; monthly limits; Android permissions and notifications; strict messages and duplicates; category correction; encryption/recovery and data deletion. Use test transactions on a separate test installation, not the student's personal ledger.
