# FRED production readiness scorecard

Last updated: 2026-11-26. Latest change: The applicant's SSN is no longer saved in the on-device KYC draft.

Every item needed before FRED is production ready, with a size and a status. It is built from
`production-readiness-checklist.md`, `production-readiness-audit-findings.md` (blocker, high and
medium findings the checklist does not already cover; low findings are left out) and the extra
issues found while fixing things. Each status was checked against the code on `develop`, not copied
from the checklist's tick boxes.

## The score

| | |
|---|---|
| Items done | **9 of 173** (18 more are part done; 1 dropped item is not counted) |
| Readiness, weighted by size | **9.2%** (43 of 467 points) |
| App work only (leaving out items that wait on counsel, Alpaca, Apple or a vendor) | 11.7% (43 of 369 points, 145 items) |

How it is worked out: small = 1 point, medium = 3, large = 8 (roughly days of work: under a day, a
few days, a week or more). A done item earns all its points, a part done item earns half, an open
item earns none. The percentage is points earned over points possible.

Read it with care. It measures how much of the listed work is finished. It does not say the
remaining items are equally urgent: one open security or money item can block a launch by itself.

### By area

| Area | Done | Part | Open | Points | Weighted |
|---|---|---|---|---|---|
| A. Protect the work | 1 | 1 | 0 | 1.5 of 2 | 75% |
| B. Security | 7 | 1 | 10 | 16.5 of 41 | 40% |
| C. A shippable iOS build | 0 | 0 | 8 | 0 of 14 | 0% |
| D. Email and notifications | 0 | 2 | 8 | 4.5 of 39 | 12% |
| E. Legal slots in the app | 0 | 0 | 15 | 0 of 21 | 0% |
| F. Money safety | 0 | 0 | 13 | 0 of 56 | 0% |
| G. Onboarding and account lifecycle | 0 | 1 | 18 | 0.5 of 31 | 2% |
| H. Production environment | 0 | 2 | 5 | 3 of 26 | 12% |
| I. Tiers and billing | 0 | 2 | 29 | 3 of 116 | 3% |
| J. Ask FRED chat | 1 | 1 | 4 | 2.5 of 14 | 18% |
| K. Before the public App Store launch | 0 | 4 | 17 | 5 of 55 | 9% |
| L. Should fix soon | 0 | 4 | 19 | 6.5 of 52 | 13% |

### Score history

| Date | What changed | Done | Weighted |
|---|---|---|---|
| 2026-11-25 | Starting point (develop at `a2aa48d1`) | 6 of 172 | 7.7% |
| 2026-11-25 | Sessions: every token belongs to a session and a sign-in lasts 12 hours at most | 7 of 173 | 8.4% |
| 2026-11-25 | Beneficiary SSNs: encrypted in the database, last four digits only in responses (one new item found: L23) | 8 of 174 | 9.0% |
| 2026-11-26 | Applicant SSN removed from the on-device KYC draft. B8 (server logout and sign-out button) dropped by Andy and no longer counted | 9 of 173 | 9.2% |

## Decisions needed from Andy

Work on these items cannot start, or cannot finish, until the question is answered.

1. **Stop loading the app from local.fredvested.com and define the build that ships** (medium). What is the beta build: the production config against a new prod backend, or a hardened beta config against api-dev? Today no config is both production:true and pointed at a backend that exists.
2. **Fix Release.entitlements** (small). Which domain is the production passkey domain: api.fredvested.com (the backend already serves the Apple association file there) or fredvested.com? This cannot be changed later without users making new passkeys.
3. **Implement a real email provider and fix the default from-address** (medium). Which email provider (the analytics spec names Resend), and which from address should mail come from?
4. **Paycheck Autopilot notification layer from the approved spec** (large). Does the private beta run manual schedules only? If yes, only the payday, invested, transfer-returned and authorization-copy notices are needed now and the rest can wait for Phase 2.
5. **Fix the AI knowledge base (flat $8 a month, 2 weeks free, Persona selfie recovery)** (small). What should FRED chat say about price until billing is decided: list the three tiers, or say nothing about price?
6. **Trade confirmations are hidden and there is no in-app way to see them** (small). Does Alpaca send trade confirmations to customers directly for this setup, or must the app show them? If the app must, should they appear on the Documents page?
7. **Chat has no tier gating and is not part of any tier's promise** (small). Which tiers include Ask FRED chat, and what is the usage allowance (free for all with a cap, or from a given tier up)?
8. **Trusted contact sent to Alpaca is the account holder** (medium). Should FRED leave the trusted contact out (if Alpaca allows it), or add an optional trusted contact step to KYC?
9. **KYC form has no age, citizenship or state checks and hardcodes US citizenship** (medium). Is the beta for US citizens only (with a decline screen for everyone else), or should the form ask citizenship and support permanent residents?
10. **Stand up a production backend** (large). Where will beta users live: a new production backend, or the existing api-dev service for the private beta?
11. **Remove or fix the referral "Upgrade Your Plan" card** (small). Remove the referral upgrade card for now, or keep it and wire it to real billing later?
12. **Make monthly versus yearly real state, or remove the toggle** (medium). Will FRED sell yearly plans at launch? If not, the toggle and yearly prices should come off the tier screen.
13. **Automatic rebalancing (Core) and custom rebalancing schedule (Plus); remove the fake "Next rebalance" date** (large). Build a rebalancing engine (multi-week), or remove rebalancing from the tier cards and My Profile for launch?
14. **Optimal drawdown walkthrough: build what the copy describes, or rewrite the copy** (large). Build the real year-by-year drawdown plan, or rewrite the Pro card copy to match the static deck that exists?
15. **Outside 401(k) and Roth balances: store on the server and feed the Freedom Date** (large). Should outside balances change the Freedom Date as the card says, or should the card copy be narrowed to the simulator only?
16. **Monte Carlo copy against what it shows** (small). Rewrite the card to describe retirement survival odds over 1,000 runs, or change the feature to show a Freedom Date probability?
17. **Priority support: any support channel, then a Pro lane** (medium). Can a 24 hour human reply be staffed at launch? If not, mark the row Coming Soon or soften the copy.
18. **Early access: a feature flag mechanism, or drop the line** (medium). Build a feature flag system for Pro early access, or drop the line from the Pro card?
19. **Trial, "cancel anytime", yearly and lifetime price copy: back with code or remove** (medium). For each of trial, cancel anytime, yearly prices and lifetime referral prices: keep and build it, or take it off the tier screen until billing exists?
20. **No billing at all: no charge, failed payment handling, receipts or billing history** (large). Is the private beta free? If yes, billing can follow the beta. If no, which payment rail (Apple in-app purchase or ACH) once counsel answers?
21. **Auto-invest keeps running when the subscription is missing or expired** (medium). When a user has no active plan, should FRED keep pulling and investing their paycheck money, pause it, or keep investing during a grace period?
22. **Changing the portfolio only affects future buys, and the save screen does not say so** (small). When a user changes their portfolio, should FRED move existing holdings, or just say clearly that only new money follows the new mix?
23. **Plus users see most of the Monte Carlo results locked as Pro, and the methodology sheet describes scenarios they cannot run** (small). Is it acceptable that about half of a Plus user's results screen is a locked Pro upsell?
24. **No in-app place to read the market breakdown; it is email only and the card does not say so** (medium). Should the monthly breakdown also be readable in the app, or stay email only with the card saying so?
25. **iPad: drop the device family or support it properly** (small). Should FRED ship as iPhone only (drop iPad from the build), or do you want to support iPad layouts properly?
26. **Chat: output compliance check, retention and deletion, regex safety gate, history scoped to the user** (medium). How long should chat messages be kept, and do you want an automatic check on every chat reply before the user sees it (the audit lists this as a decision, likely with counsel)?
27. **ACATS transfer form is live but half built: finish or hide (FRED-175)** (small). Hide the ACATS transfer card for now, or finish it (review step, saved record, status tracking) before launch?
28. **One-time purchase of any single stock has no asset checks** (small). Should buying a single stock stay in the app at launch? The audit suggests asking counsel, because it makes FRED a self-directed trading app as well as an automated portfolio.
29. **Small contributions silently skip portfolio slices, and an all-skipped run is reported as a success** (medium). When a slice of a paycheck is under $1, should FRED raise the minimum contribution, fold that money into the other funds, or leave it as cash and tell the user?
30. **Older chat conversations exist only on the phone** (medium). Should older conversations be kept on the server and listed in the app, or is losing them on sign-out acceptable?

## Every item

Status is Done, Part or Open. Flags: **D** needs a decision from Andy, **X** waits on an outside
party (counsel, Alpaca, Apple or a vendor), **A** comes from the audit findings only, **N** was
found after the audit.

### A. Protect the work

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| A1 | Push develop and commit or branch the uncommitted work | small | Part |  | Push the 16 local develop commits so CI runs on them (MMB redesign, FRED-236 scheduler fix b244bca9, goals style 47d6b6f1). |
| A2 | Decide what to do with feature/paycheck-autopilot-phase-1 | small | Done |  | Merged: commit 74576ad0 'Merge feature/paycheck-autopilot-phase-1 into develop' is an ancestor of origin/develop. |

### B. Security

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| B1 | Alpaca endpoints leak other users' accounts | small | Done |  | Commit eda5229f. AlpacaController.java:56-67 GET /account now returns only the caller's own account; :258-268 and :277-287 return 404 unless isOwnAlpacaAccount (:90-92). |
| B2 | Beneficiary cross-user writes | small | Done |  | Commit eda5229f. BeneficiaryService.java:89 forces setId(null) on create; :190-193 findOwned uses findByIdAndUserId for update, delete and submit; submitToAlpaca now takes the user. |
| B3 | Beneficiary SSNs stored in plaintext and returned in full by the API | medium | Done |  | Done 2026-11-25. The column is encrypted with the app's AES-GCM field encryption (EncryptedStringConverter on Beneficiary.socialSecurityNumber), responses carry only ssnLast4, an edit may leave the number out to keep the one on file, and a mistyped number is never logged. Proved by BeneficiarySsnAtRestTest against a real database. Rows written before the change were not converted (decision: dev only, no production data). The column was widened by a repeatable migration because V12 is held by an unpushed branch. |
| B4 | Applicant SSN sits unencrypted in a localStorage KYC draft (FRED-221) | small | Done |  | Done 2026-11-26. KycDraftUtils is the only way in or out of the draft and never writes or returns taxId; a resumed applicant lands on part 1 and retypes only the number; every draft on the phone is scrubbed at launch (AppComponent); the settings form writes no draft and removes an old one. The rest of the draft, date of birth included, is kept on purpose. Two dead debug log lines that could print the number on the server were also removed. |
| B5 | Recovery code can be brute-forced (FRED-219) | medium | Done |  | Commit f3776f10. AccountRecoveryService.java:156-165 and :218-227 hold per-account, per-email and per-address AttemptLimiters; 5 wrong tries per code; a right code gives a one-time recovery token, not a session. |
| B6 | Add rate limiting on public auth endpoints and on AI chat | medium | Part |  | Still no limits on: passkey sign-up and sign-in start and finish (/api/passkey/**), /api/auth/login and /api/auth/refresh, /api/user/should-prompt-reauth, and chat messages (POST /api/chat and GET /api/chat/stream need a per-user ... |
| B7 | Sessions: every token belongs to a session that can be revoked, and no session lasts forever | medium | Done |  | Fixed 2026-11-25. Every token belongs to a session (sign-up included), a token with no session is refused, and a passkey sign-in lasts 12 hours at most however often it is refreshed. Signing in again on a device replaces its old session, so a revoked token stays dead. |
| B8 | Server logout and a working sign-out button | small | Dropped |  | Dropped by Andy on 2026-11-26. Not counted in the totals. |
| B9 | Step-up auth for money is frontend only | large | Open |  | Server must require a fresh passkey (or PIN proof) for withdraw, liquidate, bank relink, large lump sum and account close. |
| B10 | Email change needs no re-auth and no verification, and is broken (no Authorization header) | medium | Done |  | Commit 6b632c95. POST /api/user/update-email is gone from UserController. |
| B11 | Sign-up takes any email with no ownership check, and an abandoned sign-up locks that email | medium | Open |  | Verify the email (code or link) before or during sign-up, and let an unfinished sign-up with no passkey be replaced or expire. |
| B12 | Investment history, dashboard and execution endpoints serialize the whole User entity | small | Open | A | Add JsonIgnore on user or return a DTO, or remove the unused routes. |
| B13 | SSN lookup hash is plain unsalted SHA-256 | medium | Open | A | Switch to a keyed hash (HMAC with a secret) and re-hash existing rows. |
| B14 | Upload and request-size validation is thin | small | Open | A | Allow-list document and mime types, cap the base64 size, and set a JSON request body limit. |
| B15 | Anyone who knows an email can block that account's recovery for a day | small | Open | N | Recovery limits count requests per account, so a stranger can use them up. |
| B16 | Per-address limits trust headers that can be forged if the backend is reachable directly | small | Open | N | Only trust CF-Connecting-IP and X-Forwarded-For when the request came through the proxy, or block direct access to the backend. |
| B17 | Sign-up and recovery answers reveal whether an email has an account | small | Open | N | Sign-up returns a 409 for a taken email, and response timing differs. |
| B18 | Login limits, recovery codes and token cutoffs live in memory | medium | Open | N | They are lost on restart and assume one backend instance. |
| B19 | Two phones restored from one backup share a device id and sign each other out | small | Open | N | The device id is a random value kept in web storage, which a phone restore copies. |

### C. A shippable iOS build

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| C1 | Stop loading the app from local.fredvested.com and define the build that ships | medium | Open | D | Make server.url dev only, add a release build script (production build, cap sync, archive), then run the app from the bundled assets on a device. |
| C2 | Make passkey sign-up work in a bundled build | medium | Open |  | Add a native register method to the plugin and use it in sign-up and recovery. |
| C3 | Fix Release.entitlements | small | Open | D | Write the domain as an array, fix the keychain group, make the domain equal WEBAUTHN_RP_ID_PROD, and remove the stale rpId values in the environment files. |
| C4 | Archive scheme uses Debug | small | Open |  | Set Archive and Profile to Release. |
| C5 | Add universal links so Plaid OAuth banks can return to the app | medium | Open |  | Add the applinks domain to entitlements, an applinks section to the association file, an appUrlOpen listener that feeds the Plaid resume code, and register the production redirect URI in the Plaid dashboard. |
| C6 | Add NSCameraUsageDescription | small | Open |  | Add the camera usage string and test Take Photo on the document upload screen. |
| C7 | Fill in the production environment values | small | Open | X | Set the real API host and PostHog key once the prod backend and prod PostHog project exist. |
| C8 | Dev mock toggles are committed in the ON position | small | Open | A | Commit the toggles off and add a check that fails a production build when any mock is on. |

### D. Email and notifications

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| D1 | Implement a real email provider and fix the default from-address | medium | Open | D X | Write one real sender class, change the from default to a fredvested.com address, add the email variables to the prod env list, and add a startup guard so prod cannot boot with the logging sender. |
| D2 | Send the recovery code by email and stop printing it | small | Part |  | a real email provider (FRED-101, the previous row), then set EMAIL_ENABLED=true and EMAIL_PROVIDER to it. |
| D3 | Tell users when something fails | large | Open |  | Everything in the item is still open: (1) in-app notice and real email for a failed bank pull and a failed order; (2) KYC approved and action-required notices (still TODOs) and a rejected-account screen; (3) a visible hold state ... |
| D4 | Fix the 'Investment complete' email sent at midnight for every execution | small | Open |  | Use the approved 'transfer started' copy, limit it to recurring executions whose funding has started, and send 'invested' only from the completed step. |
| D5 | No notice for withdrawals, sells, bank changes or account closure | large | Open | A | Everything the row names is still to do: emails for withdrawal requested and completed, sell or liquidate placed, bank account changed, and account closed. |
| D6 | Day-after 'closer to freedom' email ignores the outcome, and one investment sends up to four overlapping emails | medium | Open | A | Agree one notice list, send every payday notice through NotificationService.sendOnce (it exists now), skip failed executions, drop the duplicate success or day-after email, and add job locks to the three notification jobs. |
| D7 | Failure emails can carry raw broker error text and unformatted amounts | small | Open | A | Map failures to a few user-safe messages, keep raw text in the execution record only, and format money as currency. |
| D8 | Transactional emails are inline plain text with no shared template, footer or disclosures | medium | Open | X A | One shared layout with a support address and disclosure footer, all copy in one place, then one review pass by counsel on lines such as 'Your freedom date just moved closer'. |
| D9 | No notification preferences exist | medium | Open | A | A small server-side preferences record, a Notifications page in settings, and senders that check it. |
| D10 | Paycheck Autopilot notification layer from the approved spec | large | Part | D A | The claimed list understates what is left. |

### E. Legal slots in the app

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| E1 | Terms of Service and Privacy Policy do not open | small | Open | X | Build the link slots and a real Legal Information page (app work, about a day). |
| E2 | Record consent on the server with timestamp and document version | medium | Open |  | Migration for accepted-at and version fields, save them in the call that completes the last onboarding step, reject the step without them, and add a re-consent path for a new version. |
| E3 | Alpaca agreements: dead links, margin agreement signed for a cash account, no local record | medium | Open | X | All three parts. Real agreement URLs and the answer on whether a cash-only account signs the margin agreement come from Alpaca. |
| E4 | Recurring ACH debit authorization wording on the final onboarding screen | small | Open | X | Add the paragraph slot above Confirm (amount, frequency, bank, how to revoke) and store its acceptance with the consent record. |
| E5 | Show a privacy notice before collecting email, SSN and bank link | small | Open | X | Add a 'By continuing you agree to...' line with working links on the account creation screen. |
| E6 | Add the brokerage disclosure block and remove the absolute 'your money is safe' FAQ answer | small | Open | X | Add the disclosure block (Legal page plus a short footer line), rewrite the FAQ answer and the matching chat knowledge chunk. |
| E7 | On-screen 'educational, not advice, AI generated' notice in chat | small | Open | X | Add a persistent one-line notice under the composer or in the empty state. |
| E8 | Add disclaimers where return claims have none (onboarding results, Monte Carlo, goal plans) | small | Open |  | A shared small-print line on the get-started slides, fi-plan-results, the Monte Carlo result view and the goal plan page. |
| E9 | Fix the AI knowledge base (flat $8 a month, 2 weeks free, Persona selfie recovery) | small | Open | D | Rewrite the fees, recovery, partner and 'what FRED is' chunks to match the app, and remove the dev note. |
| E10 | Update what-we-send.md and the privacy manifest for chat data sent to Anthropic, OpenAI and Pinecone | small | Open |  | Add an AI chat section to what-we-send.md (Anthropic, OpenAI, Pinecone and what each gets), the matching rows in privacy-manifest-and-label.md, and the manifest entries. |
| E11 | Trade confirmations are hidden and there is no in-app way to see them | small | Open | D X A | Ask Alpaca how confirmations are delivered. |
| E12 | Documents page shows 'No Documents Yet' when the load fails, and only looks back 3 years | small | Open | A | Add an error state with retry, widen or page the date range, and show every tax form for a year rather than one. |
| E13 | Close-account confirmation says all data will be deleted, which is not accurate | small | Open | X A | Change the copy to say what is deleted and what is kept for legal reasons. |
| E14 | Marketing opt-in is saved but nothing reads it, and there is no way to change it later | medium | Open | A | Email preferences section in Settings, and make every sender check the stored preference before a real provider is switched on. |
| E15 | FAQ does not cover fees, cancelling, closing the account, recovery or how to get help | small | Open | A | Add the missing entries (fees once pricing is settled), soften 'optimal' in the first answer, and keep the FAQ and chat knowledge in step. |

### F. Money safety

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| F1 | An ACH return never stops the schedule, and late returns are never seen | large | Open |  | Set the matching hold on the schedule when a transfer is returned or rejected, tell the user how to clear it, and keep polling settled transfers for a return window. |
| F2 | Daytime funding and trading jobs can place duplicate buys after a crash or redeploy (FRED-223) | large | Open |  | Give each trade a fixed client_order_id saved before the order is sent, move each execution into its own short transaction, and add job locks to both polls. |
| F3 | A timed-out ACH request is recorded as failed even though Alpaca may have pulled the money | medium | Open |  | Treat timeouts and 5xx as unknown, look up the account's recent transfers to adopt or rule out the transfer, and alert on rows claimed long ago that are still SCHEDULED. |
| F4 | Partial fills and expired orders strand an execution forever (FRED-222) | medium | Open |  | Re-check partially filled trades, treat expired and done_for_day as final, keep polling sibling orders after one fails, never mark a zero-trade execution complete, and alert on executions stuck in TRADING_INITIATED. |
| F5 | Withdraw and sell have no server checks, no duplicate protection, no record, no confirmation step and no tests | large | Open |  | Everything in the item: a money-out table, a per-tap id with a unique key, server checks against withdrawable cash and bank link status, a symbol format check, a poll to a final state, a confirm step for sell and withdraw, and ... |
| F6 | Show execution status and history in the app (no transaction or activity screen) | large | Open |  | Build an activity screen that lists executions with their status (pending, funding failed, trade failed, done) from the existing /api/investments/history endpoint. |
| F7 | Order-placing scheduled jobs without a job lock would double run on two instances | medium | Open |  | Add a job lock to the funding poll, the trading poll, both DRIP jobs and the TLH jobs. |
| F8 | A way to freeze one user and a global stop for user-initiated money movement | medium | Open |  | A per-user freeze that blocks schedule pulls, lump sums, sells and withdrawals, plus one global switch for user-initiated money movement. |
| F9 | No reconciliation between FRED's records and Alpaca | large | Open |  | A nightly job that compares Alpaca transfers and orders with FRED rows, flags one-sided records, stale non-final rows and idle cash, and sends the result somewhere a person will see it. |
| F10 | Transfer limits and input checks on money-in endpoints are loose or inconsistent | small | Open | A | One shared check for every money-in path: configured min and max, two decimal places, a per-day total cap, fixed lists for frequency, type and funding source, and a maximum on the recurring amount. |
| F11 | The bank account that gets debited is picked by the server, not by the user | small | Open | A | Send the account id chosen in Plaid Link with the public token, store that account, and reject anything that is not checking or savings. |
| F12 | If turning off margin fails at account creation it is never retried | small | Open | A | Store a cash-only-confirmed flag per user, retry until it succeeds, and do not let trading start before it is set. |
| F13 | DRIP can reinvest old dividends in bulk and never checks available cash | small | Open | A | Start the window when DRIP was turned on, skip dividends older than a few days, check available cash net of pending investments before buying, and retry failed rows a limited number of times. |

### G. Onboarding and account lifecycle

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| G1 | Rejected and pending KYC applicants see no in-app state | medium | Open |  | Add an account status surface (under review, action required, not approved) on the main tabs, and refresh status on resume and tab activation. |
| G2 | KYC resubmit dead end (409 shown as raw text) | small | Open |  | Treat 409 ACCOUNT_ALREADY_EXISTS as success on the client, read the 'error' field, and set kycVerificationCompleted on the server inside the KYC save. |
| G3 | Trusted contact sent to Alpaca is the account holder | medium | Open | D X | Stop sending the applicant as their own trusted contact. |
| G4 | KYC form has no age, citizenship or state checks and hardcodes US citizenship | medium | Open | D | 18+ check on client and server, a citizenship question (or a clear US-citizens-only decline screen), and server-side validation of the KYC fields. |
| G5 | Document upload flow for ACTION_REQUIRED is incomplete | medium | Open |  | Show which document Alpaca asked for, keep an 'uploaded, in review' state, validate file type and size on the server, add the camera usage string, and test with an ACTION_REQUIRED sandbox account. |
| G6 | Failed tier save in onboarding locks a new user out with a dead Reactivate button | small | Open |  | Do not let onboarding finish with no tier saved (retry or show an error), give Reactivate a real destination, and decide how a tier gets set in production now that the client cannot set it. |
| G7 | Failed loads shown as empty states (Sell & Withdraw enables Close My Account, Portfolio Allocation save overwrites the real allocation) | medium | Open |  | Add a distinct 'could not load, try again' state with retry on Sell & Withdraw, Recurring, Tax Documents, Beneficiaries, Change Bank, Security sessions and Portfolio Allocation. |
| G8 | Profile tab shows a hardcoded "Hi, Firstname" | small | Open |  | Bind the greeting to the user's first name and hide it when there is none. |
| G9 | Returning user on a new or wiped phone has no direct Sign in button | small | Open | A | Add 'I already have an account' on get-started that tries passkey sign-in first and offers email recovery as the fallback. |
| G10 | Onboarding progress flags can be set by the client | small | Open | A | Work out KYC, bank link and schedule completion on the server from real state (Alpaca account, Plaid token, schedule row) and ignore those flags in the request. |
| G11 | Security Settings calls an endpoint that does not exist, so the account number never shows | small | Open | A | Add an endpoint that returns the signed-in user's brokerage account number, or remove the row. |
| G12 | Passkey registration and recovery leave the user stuck on some failures | small | Part | A | Sign-up screen only (authfinalize): 1. |
| G13 | One-time invest runs on a single tap with no confirmation step | small | Open | A | Put a confirm sheet in front of Make Investment that states the amount and the bank it is pulled from. |
| G14 | Change Bank Account shows errors in a green success card and dead-ends when the link token fails | small | Open | A | Track status as a typed state, use plain error copy, make Try again re-run the Plaid setup, and show nothing when the user simply closes Plaid. |
| G15 | Security settings: silent failures and a hardcoded 'Verified via Alpaca' | small | Open | A | Drive the KYC row from the real account status, add a confirm sheet and error toast to device revoke, and put the PIN switch back with a toast when removing the PIN fails. |
| G16 | Close account clears storage but does not reset in-memory sign-in state | small | Open | A | Call AuthService.logout() before clearing storage and navigating, so a closed account is not routed back into the tabs on the next resume. |
| G17 | AI chat sends the message text in the URL and has no stream timeout | medium | Open | A | Move the stream to a POST with a JSON body, and add an inactivity timeout that fails the message into the existing retry state. |
| G18 | No route guards: a signed-in user whose progress fails to load is sent to the tabs even if onboarding is not finished | small | Open | A | In the fallback, use the next-step hint in the JWT: go to the tabs only when onboarding is complete, otherwise stay put and show a retry. |
| G19 | Changing email in FRED does not update the contact email at Alpaca | small | Open | N | Alpaca keeps sending statements and notices to the old address. |

### H. Production environment

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| H1 | Stand up a production backend | large | Open | D X | Create the prod service and database, commit a deploy definition (start command, health path, one replica), add the Alpaca block and the full env var list to the prod profile, and write a first-boot runbook. |
| H2 | Crash and error reporting on frontend, iOS and backend | medium | Open |  | Add an error reporting tool to the Angular app (global ErrorHandler), the iOS shell and the backend, with personal data scrubbed. |
| H3 | Alerting on scheduler failures | medium | Part |  | Raise an ops alert from the catch blocks that still only log: InvestmentScheduler.java:58, :77, :94, :112, :128, :154, :166 and the D+1 payday block after :171; AccountStatusScheduler.java:47, :69; TlhScheduler.java:50, :60, :70, ... |
| H4 | Real health endpoint, logging config, and stop logging emails, JWTs and chat text | medium | Open |  | Add a health endpoint that checks the database, add a logging config, log a user id in place of email, never log a token, and remove the sensitive console logs. |
| H5 | Database backups, restore and failed-migration runbook | medium | Open |  | Turn on backups for the prod database, do one restore test, write down the prod JDBC URL template and what to do when a migration fails, and lower the connection timeout for prod. |
| H6 | Encryption key policy for production (weak keys only warn, no rotation) | medium | Open |  | Make prod refuse an encryption key under 32 bytes and a short JWT secret, remove the JWT fallback key, decide where the prod encryption key is stored so it can be recovered, and plan rotation. |
| H7 | Global HTTP interceptor with 401 handling | medium | Part |  | Part done 2026-11-25: a token the server refuses at refresh is dropped, so the passkey prompt appears. Still open: a 401 on any other request is not handled, so a revoked device can sit on failing screens until its next refresh. |

### I. Tiers and billing

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| I1 | Stop users setting their own tier (PUT /user/profile and POST /user/subscription/confirm) | medium | Part |  | On local, dev, test and ci profiles any signed-in user can still set any tier through both endpoints. |
| I2 | Add a real subscription record (tier, status, period, trial start and end, billing period) | large | Open |  | All of it: table, status, period dates, trial dates, and one place that answers what a user is entitled to. |
| I3 | Enforce tiers on the server for Monte Carlo, outside accounts, drawdown, deeper projections and custom rebalance | medium | Open |  | All of it. The existing checks also compare raw strings in different ways (case sensitive in goals and TLH, case insensitive in referrals) and ignore trial, payment and private beta state. |
| I4 | Wire every Upgrade and Reactivate button | large | Open | X | All of it. Needs the payment rail, which waits on counsel. |
| I5 | Manage plan screen (see plan, upgrade, downgrade, cancel) | large | Open | X | All of it. Upgrade and cancel depend on the payment rail. |
| I6 | Remove or fix the referral "Upgrade Your Plan" card | small | Open | D | All of it. The free grant now fails with 403 in production because of the tier lock, so there the button just shows "Upgrade failed". |
| I7 | Make monthly versus yearly real state, or remove the toggle | medium | Open | D | All of it. |
| I8 | Emit the billing analytics events that are defined but never fired | small | Open | X | All of it. There is nothing to emit until a trial, a charge or a cancel exists. |
| I9 | Make plan names and prices consistent across the app, chat knowledge and FAQ | small | Open |  | All of it. |
| I10 | Tests for tier and subscription code | medium | Part |  | No direct tests for SubscriptionService (confirmSubscription, expireSubscription); it is only mocked. |
| I11 | Automatic rebalancing (Core) and custom rebalancing schedule (Plus); remove the fake "Next rebalance" date | large | Open | D X | All of it, including the small step of removing the made-up date now. |
| I12 | Tax loss harvesting UI (enroll, consent, status, savings) and remove the made-up savings estimate | large | Open | X | All of it. The engine has only run in dry-run mode, and turning it on needs counsel and likely Alpaca sign-off. |
| I13 | Optimal drawdown walkthrough: build what the copy describes, or rewrite the copy | large | Open | D | All of it. Size is large if built, small if the copy is rewritten. |
| I14 | Outside 401(k) and Roth balances: store on the server and feed the Freedom Date | large | Open | D | All of it. The balances also still live only on the device, including the ones the drawdown deck uses. |
| I15 | Monte Carlo copy against what it shows | small | Open | D | All of it. |
| I16 | Monthly market breakdown: real sends, working unsubscribe with opt-out flag, postal address, narrative for custom portfolios | medium | Open | X | All four parts. Real sends need an email provider account and sending domain. |
| I17 | Priority support: any support channel, then a Pro lane | medium | Open | D | A basic email link does exist, so there is a channel of sorts. |
| I18 | Early access: a feature flag mechanism, or drop the line | medium | Open | D | All of it. |
| I19 | Trial, "cancel anytime", yearly and lifetime price copy: back with code or remove | medium | Open | D X | All of it. Removing the copy is small; backing it with code is the billing project. |
| I20 | No billing at all: no charge, failed payment handling, receipts or billing history | large | Open | D X A | All of it: charging, retries on a failed charge, grace period, downgrade on non-payment, refunds, receipts and history. |
| I21 | Referral rewards are stubs, and the 30 day paying rule counts from tier selection | medium | Open | X A | All of it: store and apply the lifetime discount, send the founder reward, and count the 30 days from a real payment. |
| I22 | Auto-invest keeps running when the subscription is missing or expired | medium | Open | D A | Decide the rule and encode it. |
| I23 | Backend does not validate custom portfolio symbols | small | Open | A | Check each symbol against Alpaca asset info on save, reject duplicates, and handle a saved symbol that later stops trading. |
| I24 | Changing the portfolio only affects future buys, and the save screen does not say so | small | Open | D A | At minimum tell the user on save that existing holdings stay as they are. |
| I25 | Plus users see most of the Monte Carlo results locked as Pro, and the methodology sheet describes scenarios they cannot run | small | Open | D A | Decide how much upsell a paying Plus user sees, and make the methodology text match the run they got. |
| I26 | Methodology sheet claims calibration to S&P 500 data 1926-2024, and outside balances are simulated as 100% stocks | small | Open | X A | Back the claim with data or reword it, and decide how a 401(k) or Roth should be modelled. |
| I27 | No in-app place to read the market breakdown; it is email only and the card does not say so | medium | Open | D A | Add an in-app view of the current and past breakdowns, or say "by email" on the tier card. |
| I28 | Market breakdown scheduler has no job lock and no off switch, and pays for real Alpaca and Claude calls while sends are mocked | small | Open | A | Add a job lock and an enable flag, off where sends are mocked. |
| I29 | Mocked sends count as already sent, and a month that fails on days 1 to 3 is skipped with no alert | small | Open | A | Do not treat MOCKED as sent once a real provider is on, add an alert when a month produces nothing, and add a way to backfill a missed month. |
| I30 | TLH engine has never run live; go-live gates are open | large | Open | X A | Counsel and likely Alpaca sign-off, sign-off on the replacement ETF pairs, an end-to-end live-mode run in sandbox, and a multi-instance safe lock. |
| I31 | Scenario labels and return assumptions do not match the tier copy and need a compliance look | small | Open | X A | Make the names match, and have counsel review calling an 8% stock return "Conservative" and offering 12%. |

### J. Ask FRED chat

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| J1 | Chat has no tier gating and is not part of any tier's promise | small | Open | D A | Decide, then enforce in ChatController on the server and add chat to the tier cards if it is a selling point. |
| J2 | Chat stream setup blocks the request thread, uses the shared common pool, and OpenAI calls have no timeouts | medium | Open | A | Give chat its own bounded executor with a 'FRED is busy' reply when full, set short timeouts on the embedding call, and move the RAG lookup into the async task. |
| J3 | Provider-down experience is one generic error, and failed attempts leave duplicate rows | medium | Open | A | Typed error events (provider down, rate limited, session expired) with matching copy, send a 401 through the normal re-auth path, and add an error-rate log line or metric. |
| J4 | Local chat history is not user-scoped and is not cleared on logout | small | Done | A | Fixed in commit a2aa48d1 ('chat kept on the phone is private to one account'). |
| J5 | Almost no automated tests for the chat path | medium | Part | A | Still no backend tests for: FredChatActionService (rejects unknown actions, amount range, 6 per minute rate limit, audit rows written); FredChatToolsService (the tool executor); the streaming trigger-tag holdback in LLMService ... |
| J6 | Older chat conversations exist only on the phone | medium | Open | D N | The server restores only the latest conversation if it is under 2 hours old. |

### K. Before the public App Store launch

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| K1 | Account deletion that meets Apple 5.1.1(v) | large | Open |  | Deletion path for users with no brokerage account, a way to request deletion inside the 60 day window (or a queued deletion), and an easy to find entry in settings. |
| K2 | Push notifications end to end | large | Open | X | APNs entitlement, a real backend sender, and move the permission prompt to after login. |
| K3 | Privacy manifest and App Store label declare KYC, contact and financial data | small | Part |  | Add contact data to the manifest: name, email address, phone number, physical address (linked, app functionality). |
| K4 | Minimum app version check, forced update and maintenance mode | medium | Open |  | All of it. |
| K5 | iPad: drop the device family or support it properly | small | Open | D | Pick one and apply it. |
| K6 | Remove "coming soon" placeholders users can see | small | Open |  | Remove or replace all five. |
| K7 | Raise minimum iOS to 16 or handle iOS 15 | small | Open |  | Raise the target to 16 in the project and Podfile (or build a real iOS 15 path). |
| K8 | Offline and no-connection state | medium | Open |  | All of it. |
| K9 | CI: run all frontend specs, add a production build gate and an iOS build, fix the 13 failing specs (FRED-226) | medium | Open |  | All four pieces are still open: - Fix or delete the 13 FRED-226 failures: nine stale "should create" specs, SecuritySettings gating, the TabsPage pager spec, and two swipe-settle specs. |
| K10 | Behaviour tests for auth (JWT filter, login, passkey, recovery, PIN) | medium | Part |  | JWT filter tests: an expired, tampered, wrongly signed or missing token gets 401 on a protected route, and public routes stay open. |
| K11 | Chat: output compliance check, retention and deletion, regex safety gate, history scoped to the user | medium | Part | D | Fix the regex gate in SafetyService.java so ordinary questions ("how much", "recession", "correction", "scam") are not blocked. |
| K12 | Bank re-link flow and processing of Plaid webhooks | medium | Part |  | Turn the webhook processor on where real users are: set PAYCHECK_AUTOPILOT_ENABLED=true in prod, or decouple the ITEM webhook handling from the autopilot flag so bank-health webhooks run even when paycheck detection is off. |
| K13 | Bank balance check before an ACH pull | medium | Open |  | All of it. The audit notes that whether a check is required is partly an Alpaca and Plaid contract question, and the Plaid Balance or Signal product has to be enabled on the Plaid account. |
| K14 | ACATS transfer form is live but half built: finish or hide (FRED-175) | small | Open | D | Hide the card (small), or finish it properly (large: review step, stored record, status, no fake id). |
| K15 | One-time purchase of any single stock has no asset checks | small | Open | D | Hide the tab and reject type 'stock' on the server, or validate the symbol with Alpaca before the bank pull and filter the search list. |
| K16 | Accessibility: zoom disabled, text does not scale, icons read out by name | medium | Open |  | Remove the zoom lock, hide icon spans from VoiceOver, turn clickable divs into buttons, label PIN, bank link and recovery controls, then one VoiceOver pass. |
| K17 | Real-device pass on everything still marked pending (tabs, tour, sheets, trays, skeletons, date sheet) | medium | Open |  | Andy runs each feature on a real iPhone and logs the result. |
| K18 | The four money events in beta-qa-checklist.md, seen on the first real account | small | Open | X | Wait for the first real account with Alpaca production and a production PostHog project, then check each event in the outbox and in PostHog. |
| K19 | No global exception handling on backend or frontend | small | Open | A | One backend @RestControllerAdvice with a consistent error shape and full stack traces in logs, and one Angular ErrorHandler. |
| K20 | API contract suite is read-only and thin, with no end-to-end test of the money path | medium | Open | A | All of it: a stubbed end-to-end run of the money path in the CI gate (create a schedule, run an investment, sell/withdraw, ideally KYC and Plaid link against stubs); add chat.sh to run-all.sh; make DB-backed backend tests fail or ... |
| K21 | Analytics publisher is off by default, nothing alerts when it stops, and the production PostHog key is empty | small | Open | X A | Create the production PostHog project and keys, set the publisher flag in production, add an alert for a stalled publisher or failed outbox rows. |

### L. Should fix soon

| # | Item | Size | Status | Flags | Note (what is left, or what fixed it) |
|---|---|---|---|---|---|
| L1 | Strip console.log calls from production builds | small | Open |  | Add a console-drop step to the production build and remove the lines that print tokens, email and passkey options. |
| L2 | Remove @CrossOrigin("*") from controllers and the hardcoded dev origins | small | Open |  | Delete the annotations and move the allowed origin list to per-profile config. |
| L3 | Remove the legacy test and password-login endpoints that are still public | small | Open |  | Delete testuser, the password login handler and /hello (or replace /hello with a real health endpoint). |
| L4 | Error responses return internal exception messages; raw backend error text reaches users | medium | Open |  | Add a global error handler with safe messages and stop passing exception text to responses and to the screen. |
| L5 | JWT in localStorage with no Content Security Policy; passkey user verification is only preferred | medium | Open |  | Add a CSP, move the token to the Keychain plugin (or accept the risk), and switch user verification to REQUIRED. |
| L6 | Market-open and holiday logic is hand-rolled and incomplete | medium | Open |  | Use the Alpaca clock or calendar for market open, and fix the holiday list. |
| L7 | Small contributions silently skip portfolio slices, and an all-skipped run is reported as a success | medium | Open | D | Decide how to treat sub-$1 slices (raise the minimum, roll the cash into another slice, or tell the user) and never mark a run with zero trades as complete. |
| L8 | Two of three Alpaca HTTP clients have no timeouts | small | Open |  | Set connect and read timeouts on the other clients and add basic handling for rate-limit replies. |
| L9 | Account and ACH relationship status are never refreshed after approval | medium | Open |  | Re-check ACTIVE accounts and the ACH relationship on a schedule and before a transfer, and stop pulls when Alpaca restricts or cancels. |
| L10 | No admin or support tooling, and no runbooks | large | Part |  | Admin tooling still missing: look up a user and read their state, view a user's normal schedule and executions, retry a stuck execution, clear a hold on the normal schedule, refresh a stuck KYC or account status, resend an email. |
| L11 | Referral crediting bugs (double count, wrong user flagged, code reveals email name) | medium | Open |  | Flag the referred user, make crediting happen once, and generate codes that do not come from the email. |
| L12 | No duplicate-person check across FRED users | medium | Open |  | Look up the SSN hash at KYC submit and block or flag a second user for the same person. |
| L13 | Missing indexes on scheduler and history lookups; no optimistic locking on the users row | medium | Part |  | Indexes, none added yet. |
| L14 | Ionicons and Plaid Link are loaded from CDNs at runtime | small | Open |  | Bundle ionicons from the npm package and load the Plaid script only when the bank link screen opens. |
| L15 | api-dev may need a manual Flyway history fix before it can boot current develop | small | Open |  | All of it. Andy takes a mysqldump of api-dev and reads flyway_schema_history. |
| L16 | No recovery for a nightly batch row that was claimed but never got a transfer id | medium | Open | A | Add a reconciler for rows claimed more than about 15 minutes ago with no transfer id: adopt the matching Alpaca transfer or mark the rows failed and alert. |
| L17 | Queue state is stored as text inside error_message and matched by substring | medium | Open | A | Move the queue state (and the original amount) into real columns so the money path does not depend on message wording. |
| L18 | Account status poller holds one transaction across an Alpaca call per pending user | small | Open | A | Do the Alpaca call outside the transaction and commit each user's change on its own. |
| L19 | Trade fill precision is narrower than what Alpaca returns | small | Open | A | Widen the columns in a new migration to match the tax lot tables so the two ledgers agree. |
| L20 | KYC submission can create an Alpaca account that FRED never records | medium | Open | A | Record the attempt before the broker call (or store the returned id durably on failure) so a retry can find and adopt the existing Alpaca account. |
| L21 | Plaid sandbox secret rotation is still owed, and credentials once in git history must not be reused | small | Part | A | Andy rotates the leaked sandbox secret in the Plaid dashboard, sets the new value on Railway dev and test and in the local gitignored file, then confirms the rotation so the old secret stops working. |
| L22 | Backend test run silently skips database-gated tests when no MySQL is reachable | small | Part | A | Make a skip impossible to miss in CI. |
| L23 | Beneficiary list is marked approved on any 2xx from Alpaca, and the payload has never been checked against the Alpaca sandbox | small | Open | N | Send a real beneficiary list to the Alpaca sandbox, confirm the field names and that the list is stored, and only then mark rows approved. Found in the audit and confirmed while encrypting the SSNs. |

## Keeping this current

Update this file in the same commit as every fix: change the item's status and note, re-count the
score table and the area table, and add a row to the score history. New problems found along the way
are added as new items with the N flag, which lowers the percentage; that is expected. An item Andy
drops is marked Dropped, kept in its table for the record, and left out of every count and total.
