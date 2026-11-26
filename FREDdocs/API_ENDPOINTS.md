# Investing App API Endpoints

All available API endpoints in the backend. Keep this file updated when adding new endpoints.

## Base URL
- **Development:** `http://localhost:8080`
- **Cloudflare Tunnel:** `https://local.fredvested.com`

---

## 1. Authentication & Authorization

### Auth Controller (`/api/auth`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/auth/login` | Legacy password login. Never issues a token (sign-in is by passkey) | No | `LoginRequest { email, password }` | 202 `{ message, email, twoFactorRequired }` or 401 |
| `POST` | `/api/auth/refresh` | Refresh JWT token (called proactively by frontend). The new token stays in the same session and keeps the same session end (12 hours after the passkey sign-in), so it can still be revoked and refreshing never extends the sign-in. `401` once the session has ended | Yes | None | `{ success, jwtToken, id, email }` |
| `GET` | `/api/auth/testuser` | Debug: returns authenticated user info | Yes | None | String |

### WebAuthn/Passkey Controller (`/api/passkey`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/passkey/register/start` | Start passkey registration | No | RegistrationStartRequest | Registration challenge |
| `POST` | `/api/passkey/register/recovery/start` | Start registering the one new passkey that follows account recovery. Opened only by the recovery token from `/api/auth/recovery/verify`; a session token is ignored. At most 5 starts per token. `401` when the token is missing, expired or used up, `403` if the account already has a passkey (including a token that already finished) | No (recovery token) | `{ recoveryToken }` | `{ options }` |
| `POST` | `/api/passkey/register/finish` | Complete passkey registration. With `recoveryToken` it finishes the passkey that follows account recovery, on the account the token names (`400` when the token has no waiting challenge). Without it, it finishes a new sign-up and is refused for an account that already has a passkey | No | RegistrationFinishRequest (`{ email, credential, recoveryToken? }`) | `{ jwtToken, id, email }` |
| `POST` | `/api/passkey/authenticate/start` | Start usernameless passkey authentication | No | None | Authentication challenge |
| `POST` | `/api/passkey/authenticate/finish` | Complete passkey authentication | No | Assertion response | `{ jwtToken, id, email }` |

### Account Recovery (`/api/auth/recovery`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/auth/recovery/initiate` | Email a 6-digit recovery code (15 minutes). Same answer whether or not the email has an account. Limits: 5 codes an hour and 10 a day per account, 20 an hour per network address (best effort: the address header can be forged). `429` when over a limit, `503` where real email is off outside development | No | `{ email }` | `{ success, message }` |
| `POST` | `/api/auth/recovery/verify` | Check the code. 5 wrong tries kill the code; 10 checks an hour per account, 40 per network address; 15 checks in a day close recovery for that account until the day is over. On the right code: removes the account's passkeys, ends every session and token it had, and returns a one-time recovery token (15 minutes). Does not sign the caller in. `401` wrong or expired code, `429` over a limit | No | `{ email, otp }` | `{ success, message, recoveryToken }` |

### Development Auth (`/api/dev`) — Dev only

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/dev/authenticate-as-user` | Authenticate as any user | `X-Dev-Tools-Key` header | `{ email }` | `{ jwtToken, id, email }` |
| `GET` | `/api/dev/list-users` | List all users | `X-Dev-Tools-Key` header | None | User list |

> **These exist only in the `local`, `dev`, `test` and `ci` profiles** and are not registered at all
> in prod. Every request needs `X-Dev-Tools-Key`, matching `app.dev-tools.key` (env var
> `DEV_TOOLS_KEY`) on the backend. A missing or wrong key returns **404 with an empty body**, the
> same as a route that does not exist, so an unexpected 404 here usually means a missing header
> rather than a broken deployment. The backend logs the rejection.

---

## 2. User Management (`/api/user`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/user/progress` | Get onboarding progress (8-step flow) | Yes | None | `UserProgressResponse` (step booleans, nextStep, completionPercentage, profile fields, referral info) |
| `PUT` | `/api/user/progress` | Update onboarding progress | Yes | `{ getStartedCompleted?, surveyInitialCompleted?, fiPlanResultsCompleted?, authFinalizeCompleted?, kycVerificationCompleted?, linkPlaidCompleted?, investmentScheduleCompleted?, investmentConfirmationCompleted? }` | `UserProgressResponse` |
| `PUT` | `/api/user/profile` | Update profile fields | Yes | `{ monthlyInvestment?, retirementIncome? }` | `UserProgressResponse` |
| `POST` | `/api/user/email-change/start` | Step 1 of changing email. Needs a fresh passkey assertion (challenge from `/api/passkey/authenticate/start`), checked on the server. Sends a 6-digit code to the new address and a notice to the old one. Changes nothing yet | Yes | `{ newEmail, passkeySessionId, credential }` | `{ message, expiresInMinutes }` or `{ code, message }` |
| `POST` | `/api/user/email-change/confirm` | Step 2. Applies the pending change if the code is right (15 minutes, 5 tries), notifies the old address, issues a new JWT | Yes | `{ code }` | `{ message, newEmail, jwtToken }` or `{ code, message }` |
| `POST` | `/api/user/referral/apply` | Apply a referral code | Yes | `{ code }` | `{ message }` |
| `GET` | `/api/user/should-prompt-reauth` | Check if device should see passkey prompt vs onboarding | No | Header: `X-Device-ID` (optional) | `{ shouldPromptReauth, message, trackingMethod }` |
| `GET` | `/api/user/sessions` | List all active sessions | Yes | None | `{ currentSessionId, sessions }` |
| `POST` | `/api/user/sessions/{id}/revoke` | Revoke a specific session | Yes | None | `{ message }` |

---

## 3. Investment Schedules (`/api/investment-schedule`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/investment-schedule/create` | Create or update schedule (upsert) | Yes | `CreateInvestmentScheduleRequest` | `InvestmentScheduleResponse` |
| `GET` | `/api/investment-schedule/current` | Get current schedule | Yes | None | `InvestmentScheduleResponse` |
| `GET` | `/api/investment-schedule/all` | Get all schedules | Yes | None | `List<InvestmentScheduleResponse>` |
| `POST` | `/api/investment-schedule/{scheduleId}/pause` | Pause schedule | Yes | None | `InvestmentScheduleResponse` |
| `POST` | `/api/investment-schedule/{scheduleId}/resume` | Resume paused schedule | Yes | None | `InvestmentScheduleResponse` |

**DTOs:**
- `CreateInvestmentScheduleRequest`: `{ investmentAmount, frequency, startDate }` — frequency: WEEKLY, BIWEEKLY, SEMI_MONTHLY, MONTHLY
- `InvestmentScheduleResponse`: `{ id, monthlyAmount, investmentAmount, frequency, startDate, nextInvestmentDate, achRequestId, isPaused, createdAt, updatedAt }`

---

## 4. Investments (`/api/investments`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/investments/execute` | Create lump-sum investment | Yes | `{ amount, type?, symbol?, fundingSource? }` | `{ success, message, executionId, amount, status, queued }` |
| `POST` | `/api/investments/schedule` | Update investment schedule settings | Yes | `{ monthlyInvestment?, payFrequency?, nextInvestmentDate? }` | `{ success, message, ... }` |
| `GET` | `/api/investments/history` | Get last 6 months of executions | Yes | None | `{ success, executions, nextInvestmentDate, monthlyInvestment, payFrequency }` |
| `GET` | `/api/investments/execution/{executionId}` | Get execution details + trades | Yes | None | `{ success, execution, trades }` |
| `GET` | `/api/investments/dashboard` | Investment dashboard summary | Yes | None | `{ success, totalInvested, pendingCount, failedCount, nextInvestmentDate, monthlyInvestment, payFrequency, recentExecutions }` |

**Execute request details:**
- `amount`: Required, $1–$1,000,000
- `type`: `"portfolio"` (default) or `"stock"`
- `symbol`: Required if type=stock
- `fundingSource`: `"bank"` (default) or `"buying_power"`

---

## 5. Portfolio Management (`/api/portfolio`)

### Allocation

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `PUT` | `/api/portfolio/update` | Update portfolio allocations | Yes | `UpdatePortfolioRequest` | `PortfolioResponse` |
| `GET` | `/api/portfolio/current` | Get current portfolio | Yes | None | `PortfolioResponse` |
| `POST` | `/api/portfolio/reset-to-default` | Reset to default allocation | Yes | None | `PortfolioResponse` |

**DTOs:**
- `UpdatePortfolioRequest`: `{ portfolioItems: [{ symbol, name, percentage, assetType? }] }` — percentages must total 100%
- `PortfolioResponse`: `{ id, name, totalPercentage, isDefault, portfolioItems, createdAt, updatedAt }`

### Dashboard

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/portfolio/dashboard` | Full portfolio dashboard (equity, positions, gain/loss) | Yes | None | `PortfolioDashboardData` |
| `GET` | `/api/portfolio/history` | Portfolio value history for charting | Yes | Query: `period` (1D/1W/1M/3M/6M/1Y/ALL, default 1M) | `PortfolioHistory` |
| `GET` | `/api/portfolio/positions` | Current holdings with summary | Yes | None | `{ positions, summary }` |
| `GET` | `/api/portfolio/performance` | Performance metrics (Today + Total) | Yes | None | `[{ period, startValue, endValue, totalReturn, totalReturnPercent }]` |

---

## 6. Banking & Plaid (`/api/plaid`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/plaid/create_link_token` | Create Plaid link token | Yes | None | LinkTokenResponse |
| `POST` | `/api/plaid/exchange_public_token` | Exchange public token for access token | Yes | `{ public_token }` | Success/Error |
| `GET` | `/api/plaid/primary-bank-account` | Primary bank account, display fields only (never the Plaid access token) | Yes | None | `{ accountId, institutionName, accountName, accountType, accountSubtype, mask, itemStatus, transactionsConsented, consentExpiresAt }` (`transactionsConsented` true, false or null for unknown; `consentExpiresAt` ISO date-time with offset, or null) |
| `POST` | `/api/plaid/update-mode-complete` | Finish an update-mode Link (reconnect or consent): re-reads consent and its expiry, re-registers the webhook, repairs a login. Never exchanges a token | Yes | None | `{ result }`: `CONSENT_GRANTED`, `CONSENT_STILL_MISSING`, `REPAIRED` or `NO_CHANGE`; 409 `STALE_ITEM` or `UPDATE_MODE_UNAVAILABLE`; 502 `PLAID_ERROR` |
| `GET` | `/api/paycheck-detection/status` | Paycheck detection state (spec 8.1), database only; the first call for a user with no sync cursor starts the initial sync in the background. Not called by the app yet (Phase 1) | Yes | None | the spec 8.1 body (`PaycheckDetectionStatusResponse`) |

---

## 7. Alpaca Brokerage (`/api/alpaca`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/alpaca/account` | Get Alpaca account info | Yes | None | Account JSON |
| `POST` | `/api/alpaca/create-account` | Create Alpaca brokerage account (full KYC) | Yes | `CreateAlpacaAccountRequest { emailAddress, phoneNumber, streetAddress, city, state, postalCode, givenName, familyName, dateOfBirth, taxId, fundingSource[], isControlPerson, isAffiliatedExchangeOrFinra, isPoliticallyExposed, immediateFamilyExposed }` | `{ account_id, account_number }` |
| `POST` | `/api/alpaca/sync-account-number` | Sync account number from Alpaca | Yes | None | `{ account_number }` |
| `GET` | `/api/alpaca/assets` | Search/list available assets | Yes | Query: `status`, `asset_class`, `search` | Assets JSON |
| `GET` | `/api/alpaca/accounts/{accountId}/ach-relationships` | List ACH relationships | Yes | None | ACH relationships JSON |
| `GET` | `/api/alpaca/account/{accountId}/status` | Get account status by Alpaca account ID | Yes | None | Status JSON |
| `GET` | `/api/alpaca/my-account-status` | Get current user's Alpaca account status | Yes | None | `AccountStatusResponse { accountStatus, hasActionRequired }` |
| `POST` | `/api/alpaca/upload-document` | Upload KYC document (only when status=ACTION_REQUIRED) | Yes | `{ documentType, mimeType, content }` (content=base64) | `{ success, raw_response }` |
| `POST` | `/api/alpaca/acats/transfer` | Initiate ACATS transfer from another brokerage | Yes | `{ dtcNumber, accountNumber }` | `{ status, transferId, message }` |

---

## 8. Trading (`/api/trading`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/trading/positions` | Get current positions | Yes | None | Positions list |
| `POST` | `/api/trading/sell/percentage` | Sell percentage of a position | Yes | `{ symbol, percentage }` (0.01–100.00) | Order details |
| `POST` | `/api/trading/liquidate` | Sell all positions | Yes | None | Order details |
| `POST` | `/api/trading/withdraw` | Withdraw cash to bank | Yes | `{ amount }` | Transfer details |
| `GET` | `/api/trading/account/balance` | Get balance and buying power | Yes | None | Balance info |

---

## 9. Beneficiaries (`/api/beneficiaries`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/beneficiaries` | Get all beneficiaries | Yes | None | `List<Beneficiary>` |
| `GET` | `/api/beneficiaries/active` | Get active beneficiaries only | Yes | None | `List<Beneficiary>` |
| `GET` | `/api/beneficiaries/summary` | Get allocation summary | Yes | None | `BeneficiaryAllocationSummary` |
| `GET` | `/api/beneficiaries/primary` | Get primary beneficiaries | Yes | None | `List<Beneficiary>` |
| `GET` | `/api/beneficiaries/contingent` | Get contingent beneficiaries | Yes | None | `List<Beneficiary>` |
| `POST` | `/api/beneficiaries` | Create beneficiary | Yes | `Beneficiary` (validated) | `Beneficiary` |
| `PUT` | `/api/beneficiaries/{id}` | Update beneficiary | Yes | `Beneficiary` (validated) | `Beneficiary` |
| `DELETE` | `/api/beneficiaries/{id}` | Delete beneficiary | Yes | None | 200 OK |
| `POST` | `/api/beneficiaries/{id}/submit` | Submit beneficiary to Alpaca | Yes | None | `{ success, message }` |
| `POST` | `/api/beneficiaries/submit-all` | Submit all approved beneficiaries to Alpaca | Yes | None | `{ success, message }` |

**Social Security Numbers.** A beneficiary's SSN is stored encrypted (AES-GCM, through
`EncryptedStringConverter`) and is write-only:

- `POST` must send `socialSecurityNumber` as `XXX-XX-XXXX`.
- `PUT` may leave `socialSecurityNumber` out. That keeps the number on file. Sending one replaces it
  and sets the beneficiary back to `PENDING`.
- No response carries `socialSecurityNumber`. Every `Beneficiary` in a response carries `ssnLast4`
  (the last four digits) instead.
- A body that fails validation gets `400 { error }` naming the rule that failed, never the value sent.
- The full number is decrypted only to send the beneficiary list to Alpaca (submit, submit-all, and
  the automatic resend after a delete).

---

## 10. PIN Security (`/api/user/pin`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/user/pin/set` | Set or reset 4-digit PIN | Yes | `{ pin }` (4 digits) | `PinResponse { success, message, locked, lockoutSecondsRemaining }` |
| `POST` | `/api/user/pin/verify` | Verify PIN (5 attempts, escalating lockout) | Yes | `{ pin }` | `PinResponse` |
| `GET` | `/api/user/pin/status` | Check if PIN is set | Yes | None | `Boolean` |
| `GET` | `/api/user/pin/lockout-status` | Get lockout status | Yes | None | `PinResponse` |
| `DELETE` | `/api/user/pin/delete` | Remove PIN and reset lockout | Yes | None | `PinResponse` |

---

## 11. AI Chat (`/api/chat`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/chat` | Send message to AI assistant | Yes | `ChatRequest` | `ChatResponse` |
| `GET` | `/api/chat/history` | Get chat history | Yes | Query: `sessionId?`, `userId?` | `List<ChatMessage>` |
| `GET` | `/api/chat/suggestions` | Get personalized question suggestions for the signed-in user | Yes | None (any `userId` query param is ignored) | `List<String>` (3 suggestions) |

---

## 12. Documents (`/api/documents`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/documents/tax` | Get tax documents from Alpaca | Yes | Query: `start?`, `end?` | Tax documents JSON |
| `GET` | `/api/documents/tax/{documentId}/download` | Download tax document as PDF | Yes | None | PDF bytes |

---

## 13. Monthly Freedom Update (`/api/monthly-freedom-update`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/monthly-freedom-update/check` | Check if monthly update should show | Yes | None | `MonthlyFreedomUpdateDTO { shouldShow, ... }` |
| `GET` | `/api/monthly-freedom-update/generate` | Generate full monthly update data | Yes | Query: `reopen` (boolean, default false) | `MonthlyFreedomUpdateDTO` |
| `POST` | `/api/monthly-freedom-update/dismiss` | Mark update as seen | Yes | None | `{ success: true }` |

---

## 14. System & Health

| Method | Endpoint | Description | Auth | Response |
|--------|----------|-------------|------|----------|
| `GET` | `/hello` | Health check | No | "Hello World" |
| `GET` | `/.well-known/apple-app-site-association` | iOS universal links config | No | JSON |

---

## 15. Admin (`/api/admin`), every profile, curl only

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/admin/plaid/items` | One row per user holding a Plaid token, by `publicId`; never the token or cursor | `X-Admin-Key` + `X-Admin-Actor` | None | `PlaidItemRow[]` |
| `POST` | `/api/admin/plaid/{userId}/remove-item?reason=` | Queue `/item/remove`, mark `REMOVED`; schedule, holds and ACH untouched | `X-Admin-Key` + `X-Admin-Actor` | None | 200 `{ result: "QUEUED" }`; 404; 409 `NO_PLAID_ITEM` or `ITEM_ALREADY_REMOVED` |
| `POST` | `/api/admin/users/{id}/delete?reason=` | Delete the user (reason ADMIN); replaces `POST /api/dev/users/{id}/delete` | `X-Admin-Key` + `X-Admin-Actor` | None | 200 `{ deleted: true, userId }`; 404; 409 `DELETION_DEFERRED` |

> **Every profile, prod included.** A missing or wrong `X-Admin-Key` (env `ADMIN_TOOLS_KEY`; prod
> refuses to start without it) gets **404 with an empty body**; a blank `X-Admin-Actor` gets **400**.
> Every call writes one `admin_actions` row. Curl only (no CORS). A write verb's user id comes from
> `SELECT id FROM users WHERE public_id = ?`.

## Admin: Paycheck Autopilot (`/api/admin/paycheck`)

Every profile, prod included; curl only. Each call needs `X-Admin-Key` (`ADMIN_TOOLS_KEY`; else 404, empty body) and a non-blank `X-Admin-Actor` (else 400), and writes one `admin_actions` row, refusals included. No verb writes the schedule status or a blocking hold, or moves money. Unknown user: 404 `USER_NOT_FOUND`.

| Method | Path under `/api/admin/paycheck` | Does | Refusals |
|--------|------|------|----------|
| `GET` | `/{userId}/status` | 8.1 status, streams with raw descriptors, 50 cycles, 20 inbox rows | |
| `GET` | `/{userId}/plaid-item` | `plaid_*` user columns, token and cursor as presence only | |
| `POST` | `/{userId}/sync`, `/evaluate`, `/reconcile` | Pipeline (MANUAL), detection (RECONCILE), one-user reconcile | 409 `NO_ITEM` (sync) |
| `POST` | `/{userId}/hold` `{ reason, note }` | Sets ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, BANK_CHANGE or null; no event | 422 `HOLD_RESERVED`; 409 `HOLD_BLOCKING`, `NO_SCHEDULE` |
| `POST` | `/{userId}/clear-review` `{ note }` | Clears MANUAL_REVIEW; `schedule_resumed` (system, `review_cleared`) only when ACTIVE | 409 `ACCOUNT_REJECTED`, `NOT_IN_REVIEW`, `NO_SCHEDULE` |
| `POST` | `/{userId}/promote-stream/{streamId}` | PRIMARY (a CANDIDATE or DORMANT stream becomes PROVISIONAL; PROVISIONAL and CONFIRMED keep their state), old primary SECONDARY, mirrored | 404; 409 `STREAM_NOT_PROMOTABLE` |
| `POST` | `/{userId}/retire-stream/{streamId}` | RETIRED `ADMIN`, its OPEN cycle VOIDED `ADMIN`, mirror cleared | 404 |
| `POST` | `/{userId}/cycles/{cycleId}/void` | VOIDED `ADMIN` for OPEN or CONFIRMED with no execution | 404; 409 `CYCLE_HAS_EXECUTION`, `CYCLE_NOT_VOIDABLE` |
| `GET` | `/webhooks?status=FAILED` | Inbox rows in one status, newest first, at most 200 (bounded in the query) | 400 `BAD_STATUS` |
| `POST` | `/webhooks/{eventId}/replay` | Row back to RECEIVED, attempts 0 | 404 `WEBHOOK_NOT_FOUND`; 409 `WEBHOOK_IN_FLIGHT` |

---

## Authentication Notes

- JWT tokens go in the `Authorization: Bearer <token>` header.
- Tokens last up to 1 hour, HS256 signed, and always belong to a session. Frontend refreshes proactively. A passkey sign-in lasts 12 hours at most, however often it is refreshed.
- Primary auth is via passkey (biometric). Login with email/password triggers 2FA OTP.
- `/api/dev/*` endpoints are development-only, disabled in production.
- `/api/admin/*` endpoints exist in every profile and take `X-Admin-Key` plus `X-Admin-Actor`, never a JWT (section 15).
- See AUTHENTICATION_AND_JWT.md for full auth system docs.

*Last Updated: February 27, 2026*
