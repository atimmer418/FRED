# FRED production readiness checklist

Written 2026-11-20 from a read-only audit of the working tree (develop plus uncommitted work).
Scope is app work in this repo. Things waiting on counsel, Alpaca or Apple are listed separately in
section 8.

How this was built: 19 auditors each took one area, a second agent re-checked every blocker and high
finding against the code (none were refuted, about 40 had their severity corrected), and a critic
looked for gaps. Full evidence with file and line references is in
`production-readiness-audit-findings.md`. Each item below names the appendix section to look in.

Effort tags: S = under a day, M = a few days, L = a week or more, XL = a multi-week project.

Two things not proven by reading code, flagged where they appear: passkey sign-up in a bundled iOS
build (needs a device test), and anything that only a real Alpaca or Plaid production account can show.

---

## 1. The short version

The features and screens are mostly there. What is holding FRED back is five things:

1. **The iOS app cannot ship as built.** It loads its code from the dev tunnel on your laptop, the
   archive scheme builds Debug, and the release entitlements are wrong.
2. **No email is ever sent.** Every email is mocked, including the account recovery code, which is
   printed to the server log.
3. **A handful of real security holes.** Any logged-in user can read other users' brokerage accounts,
   and recovery codes can be brute-forced.
4. **Tiers are a label, not a product.** There is no payment, no trial, no cancel, and any user can
   set their own tier with one API call. Four promised features are not built.
5. **There is no production environment**, and 82 commits plus 86 changed files exist only on this laptop.

---

## 2. Tier feature scorecard

What each tier card promises (investmentconfirmation step 2) against what is built.
"Gate" is where the tier check happens. A frontend-only gate can be bypassed.

### Piggy Plan ($8)

| Feature | Status | Gate | What is missing |
|---|---|---|---|
| Paycheck-based automated investing | Partial | none | Core money path is solid. User notifications are all mocked, slices under $1 are silently skipped, some order states strand an execution, only ever run in sandbox. |
| FRED default portfolio or BYO | Built | none | Backend does not validate custom symbols. Changing allocation only affects future buys. |
| Automatic portfolio rebalancing | **Missing** | n/a | No rebalancing engine exists anywhere in the backend. My Profile shows a made-up "Next rebalance" date. |
| Portfolio withdrawal education | Built | none | Static decks. "Stress-test this strategy" sends a core user to a locked screen. Copy needs a compliance read. |

### Piggy Plus ($15)

| Feature | Status | Gate | What is missing |
|---|---|---|---|
| Monte Carlo modeling | Partial | frontend only | Real simulation, but copy promises a Freedom Date probability and the feature shows retirement survival odds. Says "thousands" of runs, does 1,000. |
| Multi-goal tracking | Built | backend | Solid. |
| Add 401(k) & Roth IRA | Partial | frontend only | Balances are saved on the device only and never change the Freedom Date as the copy promises. |
| Custom rebalancing schedule | **Missing** | frontend only | Choice is saved in localStorage only, and there is nothing to schedule. |
| Monthly market breakdown | Partial | backend | Generated every month with real data and a compliance gate, but the send is mocked, the unsubscribe link is "#", and the narrative assumes the default portfolio. |

### Piggy Pro ($40)

| Feature | Status | Gate | What is missing |
|---|---|---|---|
| Optimal drawdown walkthrough | Partial | frontend only | Copy promises a year-by-year plan with account sequencing. The app has a static 2-slide deck. |
| Deeper projections & income simulation | Built | frontend only | Scenario labels do not match the tier copy. |
| Tax loss harvesting | Partial | backend | Engine, wash sale guard and scheduler exist and are off by default. There is no screen to enroll, consent or see results. Never run live. Feature id in code is still `household`. |
| Priority support | **Missing** | none | No support channel in the app at all. |
| Early access to new features | **Missing** | none | No flag or beta mechanism. |

Also on the tier screen: the 14-day trial, "No charges, cancel anytime", the yearly prices and the
referral "lifetime" price lines are copy only. Nothing in code backs them.

---

## 3. Do first: protect the work

- [ ] **Push develop and commit or branch the uncommitted work.** 82 commits are ahead of origin and
      86 paths are uncommitted. None of it has run in CI. (S) *tests-ci-observability*
- [ ] Decide what to do with `feature/paycheck-autopilot-phase-1` (29 commits, unmerged, unpushed,
      81 behind develop, owns V10). (decision)

---

## 4. Blockers before any real user

### 4a. Security

- [ ] **Alpaca endpoints leak other users' accounts.** `GET /api/alpaca/account` returns every
      customer account at the firm, and the `{accountId}` endpoints do no ownership check.
      `AlpacaController.java:50-60, 225-247`. (S) *security-auth*
- [ ] **Beneficiary cross-user writes.** Submit by id ignores the owner, and create accepts a
      client-supplied id. `BeneficiaryController.java:91-108, 164-170`. (S) *security-auth*
- [ ] **Beneficiary SSNs are stored in plaintext and returned in full by the API.** (M) *data-model-migrations*
- [ ] **Applicant SSN sits unencrypted in a localStorage KYC draft** (FRED-221). (S) *onboarding-kyc-lifecycle*
- [x] **Recovery code can be brute-forced.** No attempt limit, no rate limit, and success gives a
      full token (FRED-219). (M) *security-auth*
      Fixed 2026-11-23: 5 wrong tries per code, limits per account and per network address, and a
      right code gives a one-time recovery token (not a session) that is the only way to register
      the new passkey. Recovery also ends every session and token the account had. The limits are
      held in memory, so they assume one backend instance.
- [ ] **Add rate limiting** on public auth endpoints and on AI chat. (M) *prod-config-infra*
- [ ] **Session revoke does not work after the first token refresh**, tokens refresh forever, and
      there is no server logout or sign-out button. `AuthController.java:152-194`. (M) *security-auth*
      Part done 2026-11-23: a refreshed token now keeps its session id, so revoke reaches it. Still
      open: tokens refresh forever, tokens from sign-up carry no session, no server logout.
- [ ] **Step-up auth for money is frontend only.** A bare JWT can relink the bank, withdraw,
      liquidate or close the account. (L) *security-auth*
- [ ] **Email change needs no re-auth and no verification** of the new address. It is also broken
      today because the request has no Authorization header (`change-email.page.ts:158-164`). (M)
- [ ] **Sign-up takes any email with no ownership check**, and an abandoned sign-up locks that email. (M)

### 4b. A shippable iOS build

- [ ] **Stop loading the app from `local.fredvested.com`.** `capacitor.config` `server.url` points at
      the dev tunnel. Define which build configuration ships and bundle the assets. (M) *ios-appstore*
- [ ] **Make passkey sign-up work in a bundled build.** Only sign-in is native. Registration uses
      WebView WebAuthn, which should fail once the origin is `capacitor://localhost`.
      Needs a device test to confirm. `NativePasskeyPlugin.swift:17-19`. (M) *ios-appstore*
- [ ] **Fix Release.entitlements.** Associated domain is a string not an array, names
      `api.fredvested.com` while the frontend uses `app.fredvested.com`, and the keychain group does
      not match the bundle id. (S)
- [ ] **Archive scheme uses Debug.** `App.xcscheme:71-74`. (S)
- [ ] **Add universal links** (applinks domain, AASA section, `appUrlOpen` listener). Without them
      Plaid OAuth banks such as Chase cannot return to the app. (M)
- [ ] Add `NSCameraUsageDescription` (document upload opens the camera). (S)
- [ ] Fill in the production environment values, which are placeholders. (S)

### 4c. Email and notifications

- [ ] **Implement a real email provider** behind the existing `EmailSender` port (FRED-101). The
      port and the email log are well shaped for this. Fix the default from-address
      (`noreply@investingapp.com`). (M) *notifications-email*
- [ ] **Send the recovery code by email** and stop printing it to stdout. (S)
      Part done 2026-11-23: the code goes through the email service and is no longer printed. Still
      open: there is no real email provider, so outside development recovery answers "not available".
- [ ] **Tell users when something fails.** Failed bank pull, failed order, KYC rejected or action
      required, schedule put on hold. Today none of these reach the user by email, push or in app. (L)
- [ ] Fix the "Investment complete" email, which goes out at midnight for every execution, including
      failed ones, before the ACH has settled. (S)

### 4d. Legal slots in the app (the text is counsel's, the slots are ours)

- [ ] **Terms of Service and Privacy Policy do not open.** The consent links are dead and Legal
      Information shows "coming soon" (FRED-138, FRED-139). (S) *legal-disclosures-inapp*
- [ ] **Record consent on the server** with timestamp and document version. (M)
- [ ] **Alpaca agreements.** The three links are `href="#"`, the margin agreement is signed for a
      cash-only account, and FRED keeps no local record. `kyc-verification.component.html:218-222`. (M)
- [ ] **Recurring ACH debit authorization** wording on the final onboarding screen. (S app work once
      counsel supplies the wording)
- [ ] Show a privacy notice before collecting email, SSN and bank link. (S)
- [ ] Add the standard brokerage disclosure block (Alpaca Securities, SIPC, risk of loss) and remove
      the absolute "your money is safe" FAQ answer. (S)
- [ ] Add an on-screen "educational, not advice, AI generated" notice in chat. (S)
- [ ] Add disclaimers where return claims have none (onboarding results, Monte Carlo, goal plans). (S)
- [ ] **Fix the AI knowledge base.** It tells users FRED is a flat $8/month with 2 weeks free and
      describes a Persona selfie recovery that does not exist. `FredKnowledge.java:104-116, 338-371`. (S)
- [ ] Update `what-we-send.md` and the privacy manifest: chat sends data to Anthropic, OpenAI and
      Pinecone, and the manifest declares analytics only. (S)

### 4e. Money safety

- [ ] **An ACH return never stops the schedule.** The hold reasons exist but nothing sets them, so
      FRED keeps pulling. Returns after settlement are never detected. (L) *money-movement*
- [ ] **Daytime funding and trading jobs can place duplicate buys** after a crash or redeploy. They
      run all users in one transaction with no idempotency key on orders (FRED-223). (L)
- [ ] **A timed-out ACH request is recorded as failed** even though Alpaca may have pulled the money. (M)
- [ ] **Partial fills and expired orders strand an execution forever** (FRED-222). (M)
- [ ] **Withdraw and sell** have no server-side checks, no duplicate protection, no record in FRED's
      database, no confirmation step and no tests. (L)
- [ ] **Show execution status and history in the app.** There is no transaction or activity screen. (L)
- [ ] **Order-placing scheduled jobs without a job lock** would double run on two instances. (M)
- [ ] Add a way to freeze one user and a global stop for user-initiated money movement. (M) *critic*
- [ ] No reconciliation between FRED's records and Alpaca. (L)

### 4f. Onboarding and account lifecycle

- [ ] **Rejected and pending KYC applicants see no in-app state.** (M) *onboarding-kyc-lifecycle*
- [ ] **KYC resubmit dead end.** A retry after a dropped call hits a 409 the app shows as raw text. (S)
- [ ] **Trusted contact sent to Alpaca is the account holder.** Needs a real, optional field. (M)
- [ ] KYC form has no age, citizenship or state checks and hardcodes US citizenship. (M)
- [ ] Document upload flow for ACTION_REQUIRED is incomplete. (M)
- [ ] **Failed tier save in onboarding locks a new user out** with a Reactivate button that does
      nothing. `investmentconfirmation.component.ts:783-790`. (S)
- [ ] Failed loads shown as empty states. On Sell & Withdraw this enables Close My Account, and on
      Portfolio Allocation a save overwrites the real allocation with the default. (M) *frontend-ux-debt*
- [ ] Profile tab shows a hardcoded "Hi, Firstname". (S)

### 4g. Production environment

- [ ] **Stand up a production backend.** None exists. No deploy definition in the repo, prod profile
      has no Alpaca, email or AI config, and the env var checklist is incomplete. (L) *prod-config-infra*
- [ ] **Crash and error reporting** on frontend, iOS and backend. There is none. (M)
- [ ] **Alerting on scheduler failures.** They are swallowed and logged. (M)
- [ ] Real health endpoint, logging config, and stop logging emails, JWTs and chat text. (M)
- [ ] Database backups, restore and failed-migration runbook. (M)
- [ ] Encryption key policy for production (weak keys only warn at startup, no rotation). (M)
- [ ] Global HTTP interceptor with 401 handling. A rejected session leaves the user stuck. (M)

---

## 5. Tiers and billing

Billing itself waits on counsel. Everything below is needed whichever payment rail is chosen.

### Payment-rail independent work

- [ ] **Remove `selectedTier` from `PUT /user/profile`** and lock down
      `POST /user/subscription/confirm`, which grants a tier with no proof of payment. (M)
- [ ] **Add a real subscription record**: tier, status, period, trial start and end, billing period. (L)
- [ ] **Enforce tiers on the server** for Monte Carlo, outside accounts, drawdown, deeper
      projections and custom rebalance. Today only goals, TLH and the market breakdown check. (M)
- [ ] **Wire every Upgrade and Reactivate button.** All of them only `console.log`. (L)
- [ ] **Manage plan screen**: see plan, upgrade, downgrade, cancel. (L)
- [ ] **Remove or fix the referral "Upgrade Your Plan" card**, which grants a tier for free with
      hardcoded prices and wrong plan names. (S)
- [ ] Make monthly versus yearly real state, or remove the toggle. (M)
- [ ] Emit the billing analytics events that are defined but never fired. (S)
- [ ] Make plan names and prices consistent across the app, chat knowledge and FAQ. (S)
- [ ] Tests for tier and subscription code. There are none. (M)

### Build or stop promising (decide per feature)

- [ ] **Automatic rebalancing** (Core) and **custom rebalancing schedule** (Plus). Build the engine
      (XL) or remove from the cards and from My Profile. Remove the fake "Next rebalance" date now (S).
- [ ] **Tax loss harvesting UI**: enroll, consent, status, savings. Remove the made-up
      "You could save ~$X/yr" estimate. (L)
- [ ] **Optimal drawdown walkthrough**: build what the copy describes, or rewrite the copy. (decision)
- [ ] **Outside 401(k) and Roth balances**: store on the server and feed the Freedom Date. (L)
- [ ] **Monte Carlo copy** against what it shows. (S once decided)
- [ ] **Monthly market breakdown**: real sends, working unsubscribe with an opt-out flag, postal
      address in the footer, narrative that matches a custom portfolio. (M)
- [ ] **Priority support**: any support channel at all, then a Pro lane. (M)
- [ ] **Early access**: a feature flag mechanism, or drop the line. (M)
- [ ] **Trial, "cancel anytime", yearly and lifetime price copy**: back with code or remove.

---

## 6. Before the public App Store launch

- [ ] Account deletion that meets Apple 5.1.1(v): easy to find, available to users with no brokerage
      account, not blocked for 60 days after a transfer. (L)
- [ ] Push notifications end to end: APNs entitlement, token forwarding, backend sender. Ask for
      permission after login, not at cold start. (L)
- [ ] Privacy manifest and App Store label declare KYC, contact and financial data. (S)
- [ ] Minimum app version check, forced update and maintenance mode. (M)
- [ ] iPad: drop the device family or support it properly. (decision, S)
- [ ] Remove "coming soon" placeholders users can see. (S)
- [ ] Raise minimum iOS to 16 (passkey login needs it) or handle iOS 15. (S)
- [ ] Offline and no-connection state. (M)
- [ ] CI: run all frontend specs (about 100 never run), add a production build gate and an iOS
      build, fix the 13 known failing specs (FRED-226). (M)
- [ ] Behaviour tests for auth (JWT filter, login, passkey, recovery, PIN). (M)
- [ ] Chat: output-side compliance check, retention and deletion of chat data, fix the regex safety
      gate, scope history to the user. (M)
- [ ] Bank re-link flow and processing of Plaid webhooks, which are stored but ignored. (M)
- [ ] Bank balance check before an ACH pull. (M)
- [ ] ACATS transfer form is live but half built: finish or hide (FRED-175). (decision)
- [ ] One-time purchase of any single stock has no asset checks. (decision)
- [ ] Accessibility: zoom is disabled, text does not scale, icons are read out by name. (M)
- [ ] Real-device pass on everything still marked pending (tabs, tour, sheets, trays, skeletons, date sheet).
- [ ] The four money events in `beta-qa-checklist.md`, seen on the first real account.

---

## 7. Should fix soon

- Strip the 182 `console.log` calls from production builds. Some print sensitive values.
- Remove `@CrossOrigin("*")` from about 20 controllers and the hardcoded dev origins.
- Remove the legacy test and password-login endpoints that are still public.
- Error responses return internal exception messages. Raw backend error text reaches users in places.
- JWT in localStorage with no Content Security Policy. Passkey user verification is "preferred".
- Market-open and holiday logic is hand-rolled and incomplete.
- Small contributions silently skip portfolio slices, and an all-skipped run is reported as a success.
- Two of three Alpaca HTTP clients have no timeouts.
- Account and ACH relationship status are never refreshed after approval.
- No admin or support tooling, and no runbooks.
- Referral crediting bugs (double count, wrong user flagged, code reveals email name).
- No duplicate-person check across FRED users.
- Missing indexes on scheduler and history lookups. No optimistic locking on the users row.
- Ionicons and Plaid Link are loaded from CDNs at runtime.
- api-dev may need a manual Flyway history fix before it can boot current develop.

---

## 8. Decisions and outside parties

**For counsel**

- Per-paycheck payment plan, and whether charging outside Apple in-app purchase is allowed under
  guideline 3.1.1. This decides the App Review outcome for subscriptions.
- Terms of Service, Privacy Policy, recurring ACH authorization wording, billing terms on the tier screen.
- How FRED describes its regulatory status. The app says it three different ways, including
  "Powered by a licensed ria & broker-dealer" with FINRA and SIPC badges.
- Whether automatic rebalancing and TLH need advisory registration before they can run.
- Closing an account hard-deletes the trade ledger. Retention rules likely require keeping it.
- SSN retention for rejected applicants, and the SSN kept after KYC.
- Compliance read of marketing copy ("proven" portfolio, withdrawal education figures, Monte Carlo
  methodology claims).

**For Alpaca**

- Production keys and go-live review. Everything has only run in sandbox.
- Raw account and routing numbers from Plaid versus a Plaid processor token.
- Which agreements a cash-only account must sign.

**For Apple and other vendors**

- Plaid production keys. APNs key. Production PostHog project. Email provider account and sending domain.

**For Andy**

- Is the private beta free? Beta users already bypass the tier gate, so billing can follow the beta
  if so. The self-grant hole should still be closed first.
- Where beta users live. The only backend today runs the dev profile.
- Build or drop, per feature, in section 5.

---

## 9. Already solid (no need to re-check)

- Nightly investment scheduling and the ACH batch: idempotent keys, job locks, calls kept outside transactions.
- Lump-sum double-tap protection.
- Startup guards that stop prod pointing at sandbox, and stop dev tools loading in prod.
- Dev mocks are inert when `environment.local` is false.
- Plaid webhook signature verification.
- Passkey ceremonies and one-time challenges.
- Flyway owns the schema in every profile, with no entity drift across V1 to V9.
- Multi-goal tracking and its server-side tier limit.
- TLH is off by default and needs both Pro and an explicit opt-in.
- Market breakdown generation: real data, grounding and compliance gate.
- AI chat is bound to the signed-in user, tools are read-only, actions need a Confirm tap.
- Analytics layer and opt-out. Tax documents page. App icons, launch screen, Face ID string.
