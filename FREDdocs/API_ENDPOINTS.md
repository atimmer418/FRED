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
| `POST` | `/api/auth/login` | Login with credentials (triggers 2FA OTP) | No | `LoginRequest { email, password }` | 202 `{ message, email, twoFactorRequired }` or 200 `{ jwtToken, id, email }` |
| `POST` | `/api/auth/refresh` | Refresh JWT token (called proactively by frontend) | Yes | None | `{ success, jwtToken, id, email }` |
| `GET` | `/api/auth/testuser` | Debug: returns authenticated user info | Yes | None | String |

### WebAuthn/Passkey Controller (`/api/passkey`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/passkey/register/start` | Start passkey registration | No | RegistrationStartRequest | Registration challenge |
| `POST` | `/api/passkey/register/finish` | Complete passkey registration | No | RegistrationFinishRequest | `{ jwtToken, id, email }` |
| `POST` | `/api/passkey/authenticate/start` | Start usernameless passkey authentication | No | None | Authentication challenge |
| `POST` | `/api/passkey/authenticate/finish` | Complete passkey authentication | No | Assertion response | `{ jwtToken, id, email }` |

### Account Recovery (`/api/auth/recovery`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `POST` | `/api/auth/recovery/initiate` | Initiate recovery (SSN lookup, OTP email) | No | `{ ssn }` | `{ success, message, token }` |
| `POST` | `/api/auth/recovery/verify` | Verify OTP, purge passkeys, issue recovery JWT | No | `{ ssn, otp }` | `{ success, message, token }` |

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
| `POST` | `/api/user/update-email` | Change email address (issues new JWT) | Yes | `{ newEmail, currentEmail? }` | `{ message, newEmail, jwtToken }` |
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
| `POST` | `/api/investment-schedule/update-ach-request-id` | Update ACH request ID | Yes | `{ achRequestId, userEmail }` | `InvestmentScheduleResponse` |
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
| `GET` | `/api/plaid/primary-bank-account` | Primary bank account, display fields only (never the Plaid access token) | Yes | None | `{ accountId, institutionName, accountName, accountType, accountSubtype, mask, itemStatus }` |

---

## 7. Alpaca Brokerage (`/api/alpaca`)

| Method | Endpoint | Description | Auth | Request Body | Response |
|--------|----------|-------------|------|-------------|----------|
| `GET` | `/api/alpaca/account` | Get Alpaca account info | Yes | None | Account JSON |
| `POST` | `/api/alpaca/create-account` | Create Alpaca brokerage account (full KYC) | Yes | `CreateAlpacaAccountRequest { emailAddress, phoneNumber, streetAddress, city, state, postalCode, givenName, familyName, dateOfBirth, taxId, fundingSource[], isControlPerson, isAffiliatedExchangeOrFinra, isPoliticallyExposed, immediateFamilyExposed }` | `{ account_id, account_number }` |
| `POST` | `/api/alpaca/sync-account-number` | Sync account number from Alpaca | Yes | None | `{ account_number }` |
| `GET` | `/api/alpaca/assets` | Search/list available assets | Yes | Query: `status`, `asset_class`, `search` | Assets JSON |
| `POST` | `/api/alpaca/accounts/{accountId}/ach-relationships/plaid` | Create ACH relationship via Plaid | Yes | `{ plaidAccessToken, plaidAccountId, accountOwnerName }` | ACH relationship |
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
| `GET` | `/api/chat/suggestions` | Get personalized question suggestions | Yes | Query: `userId` | `List<String>` (3 suggestions) |

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

## Authentication Notes

- JWT tokens go in the `Authorization: Bearer <token>` header.
- Tokens are 1-hour expiry, HS512 signed. Frontend refreshes proactively.
- Primary auth is via passkey (biometric). Login with email/password triggers 2FA OTP.
- `/api/dev/*` endpoints are development-only, disabled in production.
- See AUTHENTICATION_AND_JWT.md for full auth system docs.

*Last Updated: February 27, 2026*
