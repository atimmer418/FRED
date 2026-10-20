# 2025 paycheck detection attempt (archived, reference only)

The three files next to this README are byte-for-byte copies recovered from git history. They are kept so the 2026 Paycheck Autopilot work (`FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md`, sections 1.4 and 14, Phase 0) can refer to what was tried before. Nothing in this folder is wired into the build, the app or any docs index. Treat it as reference only.

## Where the files come from

Commit `90a8cc1ae87fa3a2abc692dd7aec229218a28e06` (2025-09-17) deleted 18 files and trimmed `PlaidService.java`; its message, "removed unused files", gives no further reason. Its parent is `b9b37db7d419986fe6a84bc1b87b991057ef13db`. Each file here was recovered with `git show '90a8cc1^:<original path>' > FREDdocs/archive/2025-paycheck-detection/<file>` (quote the revision; the shell is zsh).

| File here | Original path | Lines | Bytes | SHA-256 |
|---|---|---|---|---|
| `PAYCHECK_DETECTION.md` | `PAYCHECK_DETECTION.md` | 187 | 6599 | `396a3c9b7e5209f15c9486c62c1897189412cde7c4adda9f9f46fbf18a42b01f` |
| `TESTING_PAYCHECK_DETECTION.md` | `TESTING_PAYCHECK_DETECTION.md` | 213 | 5851 | `2385e77c19b8832483719d60bd5264b45e0e31bc2161ec86857217d4a871daa7` |
| `PaycheckDetectionService.java` | `backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java` | 282 | 12095 | `16810b9809e5e55d55f30c661605ec07eaaf9e1b502e74bc812a0c41bcd9bf16` |

Do not edit the three recovered files. If something in them needs a correction or a note, write it in this README. They predate the house rule against em dashes but contain none, so they can stay verbatim.

## Why the Java file does not compile, and why it must stay here

`PaycheckDetectionService.java` imports `PaycheckSourceDto`, `UserPaycheckConfig` and `UserPaycheckConfigRepository` (lines 3, 5 and 6) and autowires `PlaidWebhookService` (lines 37 to 38). All four classes were deleted in the same commit. It also calls `PlaidService.getRecurringIncome(String)` (line 113), which that commit removed from `PlaidService` together with `getImmediateIncomeSourcesFromTransactions` and four private helpers. The class therefore compiles nowhere.

It lives under `FREDdocs/`, outside every Gradle source set (`backend/build.gradle` declares no extra source directories), so `./gradlew compileJava` never sees it. Keep it here. Never copy it under `backend/src`. VS Code's Java extension may underline it if you open it in the editor; that is cosmetic and expected.

One correction to the 2026 spec: section 4.4 names `PaycheckDetectionService.factorFor` as a fourth frequency-factor copy to fold into the `InvestmentFrequency` enum. The recovered class has no factorFor method. Its only frequency logic is `frequenciesMatch` (lines 239 to 260), which compares two frequency strings and never holds a monthly factor. The enum work touches only the three live readers.

## What the 2025 attempt did

- The user typed paycheck details on the survey page (name, employer name, frequency, expected amount and an optional account id). Each entry was a `UserPaycheckConfig` row (see `PAYCHECK_DETECTION.md`, Key Components).
- A daily job, `detectPaychecksDaily` (lines 44 to 67), annotated `@Scheduled(cron = "0 0 2 * * ?")` with no zone (line 44), loaded every config not yet detected (line 50) and handed each one to `processPaycheckDetection` (lines 97 to 145), which, for users with a Plaid access token, decrypted the token and called `PlaidService.getRecurringIncome` (lines 112 to 113).
- `isPaycheckMatch` (lines 171 to 234) scored a Plaid recurring stream against the config on up to four criteria: frequency (with BIWEEKLY / BI_WEEKLY and SEMI_MONTHLY / SEMIMONTHLY aliases in `frequenciesMatch`, lines 239 to 260), amount within 20 percent of the typed amount, employer name contained in the stream name or the reverse, and an exact account id. Two matches were enough when three or more criteria applied; one match when fewer.
- On a match it set `paycheckDetected` and `paycheckDetectedAt` and stored the Plaid account id in `plaidStreamId` (the code comment at line 130 says it used the account id as the stream identifier). `setupWebhookForPaycheck` (lines 265 to 281) only set `webhookConfigured` and `webhookConfiguredAt`; the autowired `plaidWebhookService` (line 38) is never called anywhere in the class.
- Investment triggering was listed as future work (`PAYCHECK_DETECTION.md`, Overview item 5 and Future Enhancements) and was not part of this class.
- `TESTING_PAYCHECK_DETECTION.md` opens by saying a JVM version issue prevented the branch from compiling at the time, then lists the curl steps that were meant to exercise it through the dev auth endpoint (`/api/dev/authenticate-as-user` on the dev host).

## How the 2026 design differs

The 2026 spec asks no paycheck questions: after the bank link it reads Plaid Transactions plus the Recurring Transactions add-on, finds the primary paycheck stream with zero questions, and asks only for one "Looks good" tap and one authenticated "Authorize" tap (spec 1.2). It trusts a stream only after two on-cadence posted deposits, triggers only on a posted deposit matched to a trusted stream (never a prediction or a pending row), maps cadence onto the single `InvestmentFrequency` enum, and records a Reg E authorization. See spec sections 1.2, 4 and 7. Nothing in this folder is a source of truth for that work.

## Other files deleted in the same commit (not recovered here)

Run `git show '90a8cc1^:<path>'` if you need one of them:

- `backend/src/main/java/com/investingapp/backend/controller/IncomeController.java`
- `backend/src/main/java/com/investingapp/backend/controller/PaycheckDetectionController.java`
- `backend/src/main/java/com/investingapp/backend/controller/WebhookController.java`
- `backend/src/main/java/com/investingapp/backend/dto/PaycheckDetectionStatusDto.java`
- `backend/src/main/java/com/investingapp/backend/dto/PaycheckSourceDto.java`
- `backend/src/main/java/com/investingapp/backend/dto/SelectedPaycheckDto.java`
- `backend/src/main/java/com/investingapp/backend/model/UserPaycheckConfig.java`
- `backend/src/main/java/com/investingapp/backend/repository/UserPaycheckConfigRepository.java`
- `backend/src/main/java/com/investingapp/backend/service/PlaidWebhookService.java`
- `backend/src/main/java/com/investingapp/backend/service/UserFinancialConfigService.java`
- `backend/test_paycheck_detection.sh`

The same commit also deleted four unrelated frontend `*.old.*` files (three under `frontend/src/app/components/survey/`, one under `frontend/src/app/components/investment-schedule/`). The recurring-income readers removed from `PlaidService` are visible with `git show 90a8cc1 -- backend/src/main/java/com/investingapp/backend/service/PlaidService.java`.
