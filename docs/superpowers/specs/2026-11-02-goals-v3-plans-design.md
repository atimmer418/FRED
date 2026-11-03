# Goals v3: plans FRED proposes (design)

## Context

Today's goals (my-profile, FRED-216 lineage) are "projection only": the user names a target and
year, then supplies their own per-paycheck saving. FRED grades it on-track or late with
straight-line math (remaining ÷ monthly), no growth, localStorage only, Plus-gated. That is
backwards from what the Goals section is for. The user should state the outcome ("3 kids, $100K
in a 529 and $250K in a brokerage each by 18", "a $1M house") and FRED should hand back a plan
they can follow: how much, into which account, how often, at an assumed return.

Decisions made in brainstorming (Andy, 2026-11-01/02):

- FRED proposes the whole plan shape. For kids that is a monthly amount + a birthday lump + a
  New Year lump, all compounding. The user only states the target.
- Per paycheck is shown next to every monthly figure (FRED's invest-per-paycheck model).
- Rate is the existing 8/10/12% chips (RateAssumptionsService), shared with the Freedom Date.
- Milestones for kids: value at 18 (the target), and what it could grow to at 22 and 30 if
  untouched.
- Kids: each kid has a label and a birth year, specific (past or future) or "Undecided".
  Contributions start now either way.
- Goal types for v1: Kids (529 + brokerage), House, Vacation, Car, Emergency fund, Wedding,
  Something else (free-form, what today's goal is).
- Short goals (under 3 years), emergency fund, and vacation plan at a cash rate, not the market
  chips. Auto by horizon, labeled so the user sees why.
- Tier: free to see and to have ONE active planned goal; Plus unlocks more. Freedom Date row
  does not count.
- Persistence: backend (new table + API). Survives devices; lets Ask FRED read goals later.
- UI: my-profile Goals card stays as the summary list. Tapping a goal (or adding one) pushes a
  full page. Add flow = type-picker sheet, then the page in setup mode.
- Out of scope, separate project: investing toward goals inside FRED (custodial accounts via
  Alpaca, separate portfolios). Data model leaves room for a future `linked_account_id`.

A four-lens design review (math, product, engineering, builder check; 23 findings stood after
adversarial verification) shaped the sections below. Calls I made from it are marked "(call)".

House style: no em dashes in any user-facing copy. Manrope, material-symbols-outlined icons,
light-only, section-card patterns per FREDdocs/FRED_UI_STYLE_GUIDE.md.

## Goals of the work

- A user can create a planned goal of each v1 type and immediately see a plan FRED proposes.
- Plans are mathematically honest: compounding at the chosen rate, cash rate for short money,
  rounding never breaks the target date, no number shown that the math did not apply.
- The plan reads like something a person can follow ("$296 a month, about $136 a paycheck,
  $1,000 every birthday, $500 every New Year").
- Existing v2 localStorage goals are imported, never lost.
- Backend stores inputs plus a small plan anchor; frontend computes plans.

Non-goals: moving money, custodial accounts, 529 custody, per-goal rates, debt payoff,
retirement (that is the Freedom Date row).

Anti-goals: a plan that assumes 10% on next summer's vacation money; a fabricated calendar date
for a kid who does not exist yet; "Behind" shown to someone who simply never typed a balance
into FRED; a goal that silently disappears on reinstall.

## The math (frontend, `utils/goal-plan.utils.ts`, pure functions, fully spec'd)

Conventions, stated once and pinned in the spec:

- **Ordinary annuity.** Every monthly contribution and every lump posts at the END of its month,
  after that month's growth. Matches `monthsToTarget` (`utils/freedom-projection.utils.ts`) and
  the backend `calculateMonthsToTarget`. r = annualRate / 12 (nominal monthly, not compounded).
- **Horizon n** = whole months from the first of next month through the deadline month,
  inclusive. Deadline for a kid account = (birthYear + 18, birthMonth); for a target-year goal =
  December of targetYear. `n = max(0, ...)`. n is never clamped to 600; inputs are bounded at
  validation instead (below) so n < 600 always.
- **n = 0** (deadline already here): monthly 0, lumps none, "due now" amount = max(0, target −
  balance); status Done when balance ≥ target. `requiredMonthly` returns 0 for n = 0 (never
  divides by zero).
- **Rounding** goes UP to whole dollars on every proposed amount so the date never slips.

Functions:

1. `toPerPaycheckEquivalent(monthly, frequency)` in `InvestmentFrequencyUtils`: monthly ÷ factor,
   cents HALF_UP, mirroring backend `InvestmentFrequency.fromMonthly`. Returns `null` for an
   unknown/empty frequency (call); callers hide the per-paycheck line then. Spec pins all four
   frequencies against backend values.
2. `requiredMonthly(pv, fv, n, annualRate)`: `m = (fv − pv(1+r)^n) / (((1+r)^n − 1)/r)`; r = 0 →
   `(fv − pv)/n`; n = 0 → 0; result floored at 0 and rounded up.
3. `futureValue(pv, monthly, n, annualRate, lumps)`: month-by-month, grow then post. `lumps` is a
   list of `{ monthIndex: 1..n, amount }` ABSOLUTE occurrences (not a calendar-month rule), so
   the caller controls first/last occurrence.
4. `kidLumpSchedule(n, startDate, birthYear|null, birthMonth|null)`: returns the birthday and
   New Year occurrence indices inside 1..n. Birthday occurrences begin the year AFTER birth
   (birthYear + 1) and never include the deadline month itself (the money is there ON the 18th
   birthday). Unknown birth month → July (call; never December, which would stack on New Year).
   New Year occurrences = every January inside the horizon.
5. `proposeKidAccountPlan(input)` for one account (529 or brokerage) of one kid:
   - rate: `horizon < 36 months → CASH_RATE` (same rule as simple goals), else the chosen chip.
   - `s0 = 12 × requiredMonthly(pv, target, n, rate)` (annual need, flat).
   - Rituals only when `n ≥ 24` AND `s0 ≥ $600` (call). Otherwise monthly-only with a
     `reason: 'short_horizon' | 'small_plan'` flag the UI explains in one line.
   - `B = friendly(0.20 × s0)`, `Y = friendly(0.10 × s0)`. friendly(): nearest $50 under $500,
     nearest $100 under $2,000, nearest $250 above. A lump with zero occurrences in the horizon
     is set to 0 and not displayed.
   - `FV_lumps = futureValue(0, 0, n, rate, schedule)`; `m = requiredMonthly(pv, target − FV_lumps,
     n, rate)`. If `target − FV_lumps ≤ 0`, step B then Y down one friendly notch (max 6 steps)
     until m > 0; if still ≤ 0, drop rituals.
   - verify in spec: `futureValue(pv, m, n, rate, schedule) ≥ target` across the grid.
   - milestones: at 18 = target; at 22 = `target × (1+r)^48`; at 30 = `target × (1+r)^144`
     (chained from the target, not from the rounded FV).
6. `proposeSimplePlan(input)`: HOUSE/CAR/WEDDING/CUSTOM/EMERGENCY_FUND. Monthly only.
   - house/car: target = price × downPaymentPct (default 20% for both; Andy said "financing a
     car"), or full price when toggled. UI shows "20% down on a $1M home · $200K" so the plan is
     traceable to what was typed.
   - emergency: target = monthlyExpenses × months (3 or 6 chips); horizon default 24 months
     (editable target year).
   - rate: `horizon < 36 months OR type is EMERGENCY_FUND → CASH_RATE (0.04)`, else chip. The
     rate KIND is frozen in the plan anchor (below) so a 37-month goal does not jump to cash a
     month later with no user action; it is re-evaluated only on re-plan.
   - headline "Reaches $T in Mon YYYY" (simple goals have no milestone tiles; the one extra fact
     shown is "from you $S, from growth $G").
7. `proposeVacationPlan(input)`: `monthly = ceil(tripsPerYear × budgetPerTrip / 12)`; no growth;
   per paycheck shown. Progress = `savedThisYear` only when `savedYear === currentYear`, else 0
   with a "New year, new trips. Saved so far resets, tap to update." line. Expected so far =
   monthly × months elapsed this calendar year. Vacation goals never read Done.
8. Status (`planStatus(goal, now)`): see "Plan anchor and status" below.

Pinned worked example (10%, $0 start, $250K brokerage, 18-year runway):

| case | value |
|---|---|
| flat monthly, n = 216 | $416.28 → **$417/mo** (biweekly $191.83) |
| flat monthly, n = 217 | $412.16 → $413/mo |
| rituals, n = 216, born Nov 2026 (November birthday), start Dec 2026 | s0 $4,995 → **$296/mo + $1,000 birthday + $500 New Year**, FV $250,526, lumps = 30% of plan, biweekly $136.41 |
| milestone at 22 | $372,338.52 |
| milestone at 30 | $825,912.24 |

## Plan anchor and status

The plan FRED shows is always LIVE: re-solved from today's balance and today's inputs, because
"what should I do from here" is the useful question. Status needs a fixed reference, so each
goal stores a small anchor:

- `anchor_balance` (KIDS: per account per kid inside config), `plan_anchored_at`,
  `plan_rate_kind` ('market' | 'cash'), `anchor_monthly` (the monthly FRED proposed at anchor;
  KIDS: per account), and `balance_updated_at` (KIDS: per account).
- Set on create. RESET (re-anchored to current balance, now) on any PUT that changes a plan
  input (targets, kids, price, year, trips). A PUT that changes only balances keeps the anchor
  and stamps `balance_updated_at`.
- Expected balance at the logged date = `futureValue(anchor_balance, anchor_monthly, months
  between plan_anchored_at and balance_updated_at, anchor rate, lumps)`.
- Status: `balance_updated_at` null → **"Plan started"** (neutral, never Behind). Else
  **Ahead / On plan / Behind by $X, as of Mon YYYY**, graded at the logged date only. Last log
  older than 60 days → a soft "Update your balance" prompt on the page, chip unchanged.
- Catch-up: when Behind, the live hero monthly is already the catch-up figure; the page says so
  in one line ("$X a month gets you back on plan"). No separate catch-up number.
- Flipping the shared rate chip changes the live plan, not the anchor, so status does not jump.
  The chip row is labeled "Return assumption, shared with your Freedom Date".

(Andy's pick on the status model is the one open question; default is the above.)

## Data model

Backend table `goals` (Flyway `V9__goals.sql`; guard with `information_schema` checks like
V6-V8; rules in `FREDdocs/flyway-baseline.md`). One row per goal; kids are one goal with N kids.

| column | type | notes |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| user_id | BIGINT NOT NULL, FK users | owner; every query scoped by principal |
| client_id | VARCHAR(36) NULL | v2 import idempotency; `UNIQUE KEY uq_goals_user_client (user_id, client_id)` |
| type | VARCHAR(20) NOT NULL | KIDS, HOUSE, VACATION, CAR, EMERGENCY_FUND, WEDDING, CUSTOM |
| name | VARCHAR(50) NOT NULL | |
| icon | VARCHAR(30) NOT NULL | server-side allowlist per type |
| config | TEXT NOT NULL | JSON, hand-serialized from typed records (house pattern; NOT a MySQL JSON column, ddl-auto=validate would fail against the TEXT-mapped entity) |
| current_balance | DECIMAL(12,2) NOT NULL DEFAULT 0 | simple goals only; KIDS balances live in config |
| balance_updated_at | DATETIME(6) NULL | simple goals; KIDS per account in config |
| anchor_balance | DECIMAL(12,2) NOT NULL DEFAULT 0 | simple goals |
| anchor_monthly | DECIMAL(10,2) NOT NULL DEFAULT 0 | simple goals |
| plan_rate_kind | VARCHAR(10) NOT NULL | 'market' or 'cash' |
| plan_anchored_at | DATETIME(6) NOT NULL | |
| completed | BOOLEAN NOT NULL DEFAULT 0 | |
| sort_order | INT NOT NULL DEFAULT 0 | |
| created_at / updated_at | DATETIME(6) | `@PrePersist/@PreUpdate` with `EasternTime.now()` |

Config shapes (TypeScript union in `services/goals.service.ts`; Java records per type on the
backend, `FAIL_ON_UNKNOWN_PROPERTIES = true`, re-serialized from the record, never the request
string):

- KIDS: `{ brokerageTarget, plan529Target, kids: [{ label, birthYear: number|null, birthMonth:
  number|null, accounts: { brokerage: AccountState, plan529: AccountState } }] }` where
  `AccountState = { balance, balanceUpdatedAt|null, anchorBalance, anchorMonthly }`. Targets are
  family-level; at least one of the two targets > 0 (a 529-only family is valid). Per-kid target
  override is NOT in v1 (call).
- HOUSE / CAR: `{ price, downPaymentPct: number|null (null = full price), targetYear }`
- VACATION: `{ tripsPerYear: 1|2|3|4, budgetPerTrip, savedThisYear, savedYear }`
- EMERGENCY_FUND: `{ monthlyExpenses, months: 3|6, targetYear }`
- WEDDING / CUSTOM: `{ targetAmount, targetYear }`

Validation (service, per type): amounts `0 < x ≤ 100,000,000`, scale 2; `targetYear` in
[currentYear, currentYear + 50]; `birthYear` null or in [currentYear − 17, currentYear + 10]
(a kid already 18 is rejected with "Already 18 or older. Use Something else with a target year
instead."); `birthMonth` null or 1..12; kids 1..10; kid label ≤ 30 chars trimmed; name ≤ 50;
tripsPerYear 1..4; months 3|6; icon in the per-type allowlist; import list ≤ 50 entries.

API (`/api/goals`, authenticated principal): `GET`, `POST`, `PUT /{id}`, `DELETE /{id}`,
`POST /import`. Server enforces owner match (`findByIdAndUser`), validation above, and the
tier rule: `countByUserAndCompletedFalse ≥ 1` and tier not in {plus, pro} → 403 with
`ErrorCodeResponse { code: "TIER_LIMIT", message }` (new tiny DTO; `MessageResponse` has no
discriminator). Completed goals do not count (call). `/import` BYPASSES the tier rule
(grandfathered device data; only future adds are gated) and is all-or-nothing in one
transaction (call). Tier read from `user.getSelectedTier()`, exact match, fail closed.

Optional future column `linked_account_id` is NOT added now.

## Screens

### my-profile Goals card (summary, edited in place)

- Freedom Date row first, unchanged.
- One row per goal: type icon, name, one-line 11px subtitle ("$340/mo · 3 kids · 2044 to
  2048", "$833/mo · 4 trips a year", "$1,310/mo · 20% down · 2032"), right chip one word
  (Started / On plan / Ahead / Behind / Done), thin bar = balance ÷ target. Never emit "—".
- "Add a goal" opens a type-picker bottom sheet (7 tiles: icon + one line of what you get).
  Picking pushes `/goals/new/:type`.
- Tier: everyone sees the card and can have ONE active goal. "Add a goal" with one active goal
  and not Plus/Pro → existing `openPlusNudge('goals')` with copy "Plan more than one goal with
  Piggy Plus". The frosted veil and `lockchip` go away.
- Disclaimer: "FRED invests only toward your Freedom Date. Goal plans are projections at the
  rate you choose. FRED never holds or moves goal money."
- Imported v2 goals show a neutral "Plan updated, take a look" chip until opened once.

### Goal page (`/goals/new/:type` and `/goals/:id`), one component, two modes

Standard pushed-page header from `theme/_settings-shell.scss`. Setup mode is two steps so the
plan is never below an open keyboard (call):

1. **Setup step 1, inputs.** One card. Kids: family targets (529, brokerage; either may be
   empty), helper line "A 529 is a state college plan. The brokerage is a custodial or regular
   account. You open both outside FRED.", kid list with a +/− stepper (1..10), each kid a label
   and a Month + Year picker with "Undecided" (month may be "Not sure"). House/car: price,
   down-payment chips (10/20/full), target year. Vacation: trips-per-year chips, budget per
   trip. Emergency: monthly expenses, 3/6 months chips, target year. Wedding/custom: amount,
   year. CTA "Show my plan".
2. **Setup step 2 / edit mode, the plan.** Hero pinned at top: monthly figure large,
   per-paycheck line under it (hidden when no schedule), rate chips 8/10/12 labeled "Return
   assumption, shared with your Freedom Date" with "Reset to standard", or the cash label
   "Planned as cash at about 4%, like a high-yield savings account. Money you need within 3
   years should stay out of the market." Shared rate is written only on Start/Save (same commit
   semantics as the freedom sheet). Inputs collapse to one tappable summary row ("3 kids ·
   $100K 529 · $250K brokerage · Edit").
   - Kids: two account cards first, "529 plans" and "Brokerage", each with family totals
     ("$X/mo, $B each birthday, $Y each New Year, all kids") and "Show each kid" expanding to
     one compact card per kid: rituals, 18/22/30 as a 3-up row in compact money ($372K), and
     the balance field for that account. Undecided or future-born kid: relative copy only ("At
     18 / At 22 / At 30", "18-year plan"), note "Undecided plans as if born this year, so you
     save a little more than you may need", and the pre-birth lump labeled "Every summer $B
     (becomes the birthday gift once they arrive)". Monthly-only plans show the reason line
     ("Under two years to go, so this one is a steady monthly amount").
   - Simple goals: "Reaches $T in Mon YYYY", "from you $S, from growth $G", balance tile,
     status line with "as of Mon YYYY", catch-up sentence when Behind.
   - Footer CTA: "Start this plan" / "Save changes". Edit mode adds "Mark completed" (not for
     vacation) and "Remove goal" with a confirm sheet (today there is none).
   - Entering setup mode with one active goal and not Plus/Pro → nudge immediately, no form. On
     POST 403 `TIER_LIMIT` → keep form state, open nudge, no toast.

Plan recomputes live (signals + computed, freedom-sheet pattern). `appKeyboardAvoid` on
ion-content. `/goals/:id` guards `Number.isInteger(+id) && +id > 0`, else back to my-profile.

### Import of v2 localStorage goals

On my-profile load, after a successful GET, if `fred.goals.v2.<userId>` has entries: POST
`/goals/import` with each as CUSTOM (`clientId` = v2 id, name, icon, targetAmount, targetYear,
currentBalance = startingValue, balanceUpdatedAt = now, completed). Only after a 2xx remove the
local key. Toast once: "Your goals moved to your account. FRED built a plan for each one, tap to
review." The v2 `perPaycheckOutsideFRED` is dropped (FRED now proposes the amount).

## Files and conventions to follow

All paths relative to `/Users/andrewtimmer/PersonalTypeshit/FRED`.

### Backend

- `backend/src/main/resources/db/migration/V9__goals.sql`: table above. If another branch takes
  V9 first, renumber BEFORE it runs anywhere (never edit a run migration).
- `model/Goal.java`: `@ManyToOne(LAZY) @JoinColumn(name="user_id", nullable=false) @JsonIgnore
  User user` (`Beneficiary` pattern); `GoalType` enum `@Enumerated(STRING)`; `config` as
  `@Column(columnDefinition="TEXT") String configJson` (see `ChatActionAudit.paramsJson`).
- `model/goals/` typed config records: `KidsConfig`, `HouseCarConfig`, `VacationConfig`,
  `EmergencyFundConfig`, `TargetConfig`, plus `GoalConfigCodec` (Jackson, fail on unknown).
- `repository/GoalRepository.java`: `findByUserOrderBySortOrderAscCreatedAtAsc`,
  `findByIdAndUser`, `countByUserAndCompletedFalse`, `existsByUserAndClientId`, `deleteAllByUser`.
- `service/GoalService.java`: `@Service @Transactional`, `User` first param; validation, anchor
  rules (create/re-plan/balance-only), tier rule, import (all-or-nothing, bypasses tier).
- `service/UserDeletionService.java` (EXISTING, must change): add
  `goalRepository.deleteAllByUser(user)` before `userRepository.delete(user)` in the explicit
  child-delete block (lines ~137-159; no cascade convention). Update `UserDeletionServiceTest`.
  Without this, KYC-rejection and close-account deletes throw an FK violation.
- `controller/GoalController.java` at `/api/goals`: `ResponseEntity<?>`, try/catch → status,
  private `getCurrentUser()` copied from `InvestmentScheduleController`. No SecurityConfig
  change (`anyRequest().authenticated()`).
- `dto/GoalRequest`, `GoalResponse` (ctor from entity), `GoalImportRequest` (`@Size(max=50)`),
  `ErrorCodeResponse`.
- Tests (JUnit5 + Mockito, `./gradlew test`): `GoalServiceTest` (per-type validation incl.
  birthYear bounds and 529-only kids, anchor reset vs balance-only PUT, tier rule counting
  active only, ownership, import idempotency and tier bypass), `GoalControllerTest` (principal
  via `SecurityContextHolder` as `MarketBreakdownControllerTest`; 401/403 body shape/404),
  `GoalConfigCodecTest` (unknown property rejected, round trip).
- Later, not now: `get_goals` tool in `FredChatToolsService` (one Tool.builder entry, one case,
  one private method, one constructor dep).

### Frontend

- `utils/goal-plan.utils.ts` (+ spec): all math above.
- `utils/investment-frequency.utils.ts`: `toPerPaycheckEquivalent` (+ spec pins). Leave the
  private copy in `investmentconfirmation.component.ts:526` alone.
- `services/goals.service.ts`: REWRITE as backend-backed store. Keep `GoalIcon` (widened),
  `RebalanceSchedule`, `ProfilePrefs` (prefs stay in localStorage). HttpClient against
  `${environment.backendApiUrl}/goals`, per-file `getAuthHeaders()`, per-user cache + single
  flight + `clearCache()` on writes (as `AuthService.getCurrentInvestmentSchedule`), cache keyed
  by `localStorage.userId`, `refresh()` no-op when userId empty, clear on logout via
  `authService.isLoggedIn$` (as `mfu-store.service.ts:50-55`). Expose `goals` signal. Keep
  `readLegacyV2(userId)` + `clearLegacyV2(userId)` for the import.
- `services/rate-assumptions.service.ts`: add exported `CASH_RATE = 0.04`.
- `pages/goal/goal.page.ts|html|scss|spec.ts` (new, standalone): routes `goals/new/:type` and
  `goals/:id` in `app.routes.ts`, `loadComponent`, no `data.onboardingOnly`. `goBack()` =
  `navCtrl.navigateBack('/my-profile')`. Stepper from `monte-carlo-flow.component.html:229-252`
  restyled light; chips from `.fd-scenario-chips`; numeric inputs `type="text"
  inputmode="numeric"` with sanitize.
- `components/goal-type-picker-sheet/` (new): `BOTTOM_SHEET_MODAL_OPTS`, `hostDirectives:
  [SheetDragDismissDirective]`, role `'picked'` + `GoalType`.
- `components/goal-add-sheet/`: DELETE. The three my-profile specs (`my-profile.page.spec.ts`,
  `my-profile.goals-render.spec.ts`, `my-profile.analytics.spec.ts`) are rewritten against the
  new service shape (goals signal + HttpClient mock), keeping the same-DOM-node-after-25-CD-cycles
  assertion.
- `pages/my-profile/my-profile.page.ts|html|scss`: `goalSummaries` computed over goals signal +
  `currentFrequency` + `rates.annualReturn()` (memoized, `trackBy: trackGoal`; never fresh
  objects per CD tick); row tap `router.navigate(['/goals', id])`; add → picker →
  `router.navigate(['/goals/new', type])`; new `ionViewWillEnter()` → `goalsService.refresh()`
  (beneficiaries.page.ts:83-87 pattern); remove veil/`openGoalsVeilNudge`; nudge condition =
  active goals ≥ 1 && !isPlusOrPro; v2 import once; replace the Freedom row's "—" placeholder
  while here. Freedom Date row otherwise untouched.
- Read `FREDdocs/analytics-roadmap.md` BEFORE the analytics edits (CLAUDE.md rule, added
  2026-11-02): no PII or raw amounts, band anything that can grow, declare properties in both
  taxonomies, never `flyway repair` on dev.
- `services/analytics/analytics-events.ts`: `goal_changed` gains `goal_type: GoalType` and
  `plan_rate: '8'|'10'|'12'|'cash'`; `icon` kept only meaningful for CUSTOM; widen both
  `GoalIcon` unions in one commit; bands only, never raw dollars (`analytics-privacy-guard.ts`
  strips `amount|balance|...` keys). Update `FREDdocs/analytics-events.md` or
  `scripts/docs/assert-events-documented.mjs` fails the build.
- `services/dev/goals-mock.ts` (+spec): optional, `environment.local` only, `IN_KARMA → null`,
  one sample per type.

### Docs

- `FREDdocs/backlog.md`: new story via backlog-add; note FRED-216 goals AC superseded.
- `FREDdocs/analytics-events.md`: `goal_changed` changes.
- `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`: this design, committed first.

## Verification

1. Math spec (`goal-plan.utils.spec.ts`): PMT vs simulation round trip (|FV − target| < $1
   before rounding, FV ≥ target after); r = 0 linear; n = 0 (due now, no divide by zero); pv ≥
   target; kid born this year / in 2030 (no lump before birthYear + 1) / 2010 (n ≈ 25, no
   rituals, reason flag) / 2009 (cash rate, 12-month) / null (relative copy flag); no displayed
   lump with zero occurrences; lumps share ≤ 35% for n in {24, 36, 120, 216}; birthday never
   in deadline month; milestones strictly increasing and chained from target; cash switch at
   35 vs 36 months and emergency always cash; vacation ceil and savedYear reset; anchor
   expected-balance at `plan_anchored_at` = anchor balance; status "Plan started" when never
   logged. Pinned: n = 216 → $417 flat, rituals $296 + $1,000 + $500 (FV $250,526), 22 =
   $372,338.52, 30 = $825,912.24.
2. Frequency parity: `toPerPaycheckEquivalent` pinned for 4 frequencies + null → null.
3. Backend: `./gradlew test` green incl. new tests and `UserDeletionServiceTest`. Boot locally,
   Flyway applies V9 (`flyway_schema_history`), `ddl-auto=validate` passes. curl: ownership 404,
   core user 403 `TIER_LIMIT` on second active goal, completed goal frees the slot, import twice
   → count unchanged, import of 3 goals as core succeeds, delete user with goals succeeds.
4. Frontend: `npx ng build --configuration dev`, `npm run lint`, karma for new specs + the three
   my-profile specs. Reduced-motion and keyboard checks on the page.
5. Device/browser (local.fredvested.com via `servlocal`; tier mock `plus` then `core`): one of
   each type; back navigation refreshes the summary; edit a kid's birth year and watch the plan
   recompute; 8/10/12 chip changes nothing on my-profile until Save, then moves Freedom Date too;
   core blocked at second goal (card and deep link to `/goals/new/kids`); v2 import toast once;
   remove confirmation; vacation at a mocked new year shows reset line.
6. Copy: grep new html/ts and `my-profile.page.ts` for em dashes; read every plan sentence
   aloud in FRED's voice.
