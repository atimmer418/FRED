# Goals v3 Kid Costs Addendum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a kids goal, show what a kid costs by life stage and in total, plan a first-year fund for kids not yet born, and replace the kids setup form with a five-card quiz, all ahead of the 529 and brokerage plans.

**Architecture:** Verified cost figures live in one frontend constants file with a pure helper. The first-year fund is a third per-kid `AccountState` plus three per-kid fields (target, end year, end month); it reuses the anchor rules but is graded on its own path and stops at its end date. The backend gains nullable record components and no migration. The goal page's KIDS step 1 becomes a quiz and its plan view gains two cards above the account cards.

**Tech Stack:** Spring Boot 3 (records, Jackson, JUnit 5 + Mockito, Gradle), Angular 19 standalone + Ionic (signals, Karma/Jasmine).

**Spec:** `docs/superpowers/specs/2026-11-05-goals-v3-kid-costs-addendum.md` (binding), which extends `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`. This plan continues `docs/superpowers/plans/2026-11-02-goals-v3-plans.md` (Tasks 1-13 complete on this branch).

## Global Constraints

- Every figure, label and source string comes verbatim from the addendum spec. A number FRED derived is never labeled as a government number; the Peterson-KFF birth figure is never labeled as a government number or as 2026 dollars.
- Every derived dollar value is computed from unrounded inputs and rounded once, last (`familyTotal` is never `perChildTotal × kidsCount`).
- No em dashes in any user-facing string, comment or doc.
- Ordinary annuity, `r = annualRate / 12`, index 1 = the month after `now`, proposed amounts round UP to whole dollars (unchanged conventions in `utils/goal-plan.utils.ts`).
- Kid account rate kind, for every kid account (529, brokerage, first-year): horizon from the anchor date to that account's end date under 36 months → cash (`CASH_RATE = 0.04`), else market. There is no goal-level cash shortcut for KIDS. The anchor date is `goal.planAnchoredAt`, except that a setup draft, or an edit draft whose plan inputs changed, uses `now` (that is what the server will store).
- A first-year account whose end month has arrived (`horizonMonths(now, dueYear, dueMonth) === 0`) is finished: monthly 0, no due-now, excluded from the hero total, status and progress.
- The server decides re-anchoring by comparing stored and incoming plan inputs; there is no client flag. The server never rejects a first-year account that is already stored.
- No Flyway migration. No analytics taxonomy changes.
- Deploy order: the backend (Task 2) before any frontend build that contains Task 4.
- `git add` named files only. Never `git checkout`, `restore`, `stash` or `reset`. Never push. Never start or stop dev servers.
- Karma: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='<spec path>'`. AOT gate: `npx ng build --configuration dev`. Lint: `npm run lint`. Backend: `cd backend && ./gradlew test --tests '<FQCN>'`.
- Known pre-existing Karma failures: the 14 FRED-226 specs (nine `should create` smoke specs, `FreedomDetailSheetComponent` 4% rule, `SecuritySettingsPage` gating, `TabsPage` pager, two swipe-settle velocity specs). Anything else failing is new.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016NWfpgW9CAyp2L2VUh7fGw
  ```

## Review Focus

1. A first child in a family planning three kids: its first-year target is $25,700 (rank 1), not $16,500. Tests in Task 1 and Task 4.
2. A kid whose birth month is THIS month is not eligible for a new fund; next month is eligible with n = 1 and the whole target as the monthly. Tests in Task 3.
3. A fund viewed six months after it was created counts down from its STORED end date (30 months left, not 36), through the view model, not just the math helper. Test in Task 3.
4. A balance update the January after the kid arrives still saves (the stored fund is accepted), and that goal's status is not Behind. Tests in Task 2 and Task 3.
5. A stored kids goal from before this change arrives with explicit nulls for every new field and must load, render and save. Tests in Task 2 and Task 3.

---

## Task 1: Kid cost figures (constants, helper, doc)

**Files:**
- Create: `frontend/src/app/constants/kid-costs.ts`
- Create: `frontend/src/app/utils/kid-cost.utils.ts`
- Test: `frontend/src/app/utils/kid-cost.utils.spec.ts`
- Create: `FREDdocs/kid-cost-figures.md`

**Interfaces:**
- `kid-costs.ts` exports (plain constants, no Angular; each with a one-line comment naming its source table and dollar-year; file header states derived values are FRED's own):
  - `USDA_2015_ANNUAL_BY_BAND: ReadonlyArray<{ fromAge: number; toAge: number; annual: number }>` = `[{0,2,12680},{3,5,12730},{6,8,12350},{9,11,13180},{12,14,13030},{15,17,13900}]`
  - `USDA_2015_TOTAL = 233610`
  - `CPI_U_2015_AVERAGE = 237.017`, `CPI_U_LATEST = 336.940`, `CPI_U_LATEST_LABEL = 'September 2026'`
  - `CHILDBIRTH_OUT_OF_POCKET = 2743`, `CHILDBIRTH_DOLLAR_YEAR_LABEL = '2023 dollars'`
  - `COLLEGE_ONE_YEAR = { publicTuition: 11950, publicFullCost: 30990, privateTuition: 45000 }`, `COLLEGE_PRICE_YEAR_LABEL = '2025-26'`
  - `AT_18_PRESETS: ReadonlyArray<number>` = `[25000, 50000, 100000, 250000]`
  - `KID_COST_SOURCES: ReadonlyArray<{ name: string; year: string; url: string; usedIn: 'costs' | 'college' }>` with exactly: "USDA, Expenditures on Children by Families" / "2015 (published 2017)" / `https://www.fns.usda.gov/sites/default/files/resource-files/crc2015-march2017.pdf` / costs; "U.S. Bureau of Labor Statistics, Consumer Price Index" / "September 2026" / `https://www.bls.gov/cpi/` / costs; "Peterson-KFF Health System Tracker, pregnancy and childbirth costs" / "2025, in 2023 dollars" / `https://www.healthsystemtracker.org/brief/health-costs-associated-with-pregnancy-childbirth-and-postpartum-care/` / costs; "College Board, Trends in College Pricing and Student Aid" / "2025" / `https://research.collegeboard.org/media/pdf/Trends-in-College-Pricing-and-Student-Aid-2025-final_1.pdf` / college
- `kid-cost.utils.ts` exports (pure):
  - `inflationFactor(): number`
  - `familySizeFactor(kidsCount: number): number` (1 or less → 1.27; 2 → 1; 3 or more → 0.76)
  - `type KidCostStageKey = '0-2' | '3-5' | '6-11' | '12-17'`
  - `interface KidCostStage { key: KidCostStageKey; label: string; years: number; annual: number; monthly: number; total: number }` (labels "Baby and toddler, 0 to 2", "Preschool, 3 to 5", "Grade school, 6 to 11", "Teen years, 12 to 17"; unrounded numbers; the view rounds)
  - `type FamilySizeWording = 'only' | 'two' | 'three_plus'`
  - `interface KidCostFigures { kidsCount: number; sizeFactor: number; sizeWording: FamilySizeWording; perChildTotal: number; familyTotal: number; stages: KidCostStage[]; childbirthOutOfPocket: number; asOfLabel: string }`
  - `kidCostFigures(kidsCount: number): KidCostFigures` (`perChildTotal` and `familyTotal` rounded to whole dollars from unrounded inputs)
  - `firstYearTargetForRank(rank: number): number` (`Math.ceil((USDA 0-2 annual × inflationFactor() × familySizeFactor(rank) + CHILDBIRTH_OUT_OF_POCKET) / 100) * 100`)
  - `kidRanks(kids: ReadonlyArray<{ birthYear: number | null; birthMonth: number | null }>): number[]` (rank per input index: kids ordered by `(birthYear, birthMonth ?? 7)` ascending, undecided kids last in list order; ties keep list order; ranks start at 1)
  - `interface CollegePreset { key: 'public_tuition' | 'public_full' | 'private_tuition'; label: string; amount: number }`, `collegePresets(): CollegePreset[]` (labels "In-state public tuition, at today's prices", "In-state public full cost, at today's prices", "Private college tuition, at today's prices"; amounts 4 × the one-year figures)
  - `costCardSources()` (the three `usedIn: 'costs'` entries)

- [ ] **Step 1: Write the failing spec**

```ts
describe('kid cost constants', () => {
  it('six bands of three years sum to the USDA total', () =>
    expect(USDA_2015_ANNUAL_BY_BAND.reduce((s, b) => s + 3 * b.annual, 0)).toBe(USDA_2015_TOTAL));
  it('pins the inflation factor', () => expect(inflationFactor()).toBeCloseTo(1.421586, 6));
});
describe('familySizeFactor', () => {
  it('follows the USDA table', () => { expect(familySizeFactor(1)).toBe(1.27); expect(familySizeFactor(2)).toBe(1); expect(familySizeFactor(3)).toBe(0.76); expect(familySizeFactor(7)).toBe(0.76); expect(familySizeFactor(0)).toBe(1.27); });
});
describe('kidCostFigures', () => {
  it('pins per-child and family totals from unrounded inputs', () => {
    expect(kidCostFigures(1).perChildTotal).toBe(421763); expect(kidCostFigures(1).familyTotal).toBe(421763);
    expect(kidCostFigures(2).perChildTotal).toBe(332097); expect(kidCostFigures(2).familyTotal).toBe(664193);
    expect(kidCostFigures(3).perChildTotal).toBe(252393); expect(kidCostFigures(3).familyTotal).toBe(757180);
  });
  it('names the family size', () => expect([1, 2, 3, 5].map(n => kidCostFigures(n).sizeWording)).toEqual(['only', 'two', 'three_plus', 'three_plus']));
  it('pins the four stages for two kids', () => { const s = kidCostFigures(2).stages; expect(s.map(x => x.key)).toEqual(['0-2', '3-5', '6-11', '12-17']); expect(s.map(x => Math.round(x.total))).toEqual([54077, 54290, 108879, 114850]); expect(Math.round(s[2].annual)).toBe(18147); expect(Math.round(s[3].annual)).toBe(19142); expect(Math.round(s[0].monthly)).toBe(1502); });
  it('stage totals sum to the per-child total within two dollars', () => { const f = kidCostFigures(3); expect(Math.abs(f.stages.reduce((a, s) => a + s.total, 0) - f.perChildTotal)).toBeLessThanOrEqual(2); });
  it('carries the birth figure and the as-of label', () => { const f = kidCostFigures(2); expect(f.childbirthOutOfPocket).toBe(2743); expect(f.asOfLabel).toBe('September 2026'); });
});
describe('firstYearTargetForRank', () => {
  it('goes by birth order', () => expect([1, 2, 3, 4].map(firstYearTargetForRank)).toEqual([25700, 20800, 16500, 16500]));
});
describe('kidRanks', () => {
  it('orders by birth month with undecided last', () => expect(kidRanks([{ birthYear: null, birthMonth: null }, { birthYear: 2029, birthMonth: 3 }, { birthYear: 2024, birthMonth: null }])).toEqual([3, 2, 1]));
  it('keeps list order for two undecided kids', () => expect(kidRanks([{ birthYear: null, birthMonth: null }, { birthYear: null, birthMonth: null }])).toEqual([1, 2]));
});
describe('collegePresets and sources', () => {
  it('is four years at the College Board prices', () => expect(collegePresets().map(p => p.amount)).toEqual([47800, 123960, 180000]));
  it('keeps the College Board off the cost card', () => { expect(costCardSources().length).toBe(3); expect(costCardSources().some(s => s.name.startsWith('College Board'))).toBeFalse(); });
  it('has no em dash and every source has a year and an https url', () => { expect(JSON.stringify(KID_COST_SOURCES)).not.toMatch(/—/); KID_COST_SOURCES.forEach(s => { expect(s.year.length).toBeGreaterThan(0); expect(s.url.startsWith('https://')).toBeTrue(); }); });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/utils/kid-cost.utils.spec.ts'`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement the constants and the helper**

- [ ] **Step 4: Write `FREDdocs/kid-cost-figures.md`**

Sections: what the app shows and where; the figures table copied from the spec with sources, tables and dollar-years; what FRED derives and the exact formulas (inflation factor, family-size factor, rank-based first-year target, rounding order); what is excluded and the family type the study describes; that the birth figure is a private-source 2023 figure left unadjusted; the refresh procedure (once a year update `CPI_U_LATEST` and its label from BLS series CUUR0000SA0; each November update the College Board figures; check the USDA page and the KFF brief for new editions; update the spec pins in the same commit); a note that a refresh never moves an existing plan because each kid's target is snapshotted.

- [ ] **Step 5: Run to verify it passes, then the AOT build**

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/constants/kid-costs.ts frontend/src/app/utils/kid-cost.utils.ts frontend/src/app/utils/kid-cost.utils.spec.ts FREDdocs/kid-cost-figures.md
git commit -m "feat(goals): verified kid cost figures with sources and a refresh doc"
```

---

## Task 2: Backend support for the first-year fund

**Files:**
- Modify: `backend/src/main/java/com/investingapp/backend/model/goals/KidsConfig.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/GoalService.java`
- Test: `backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java`
- Test: `backend/src/test/java/com/investingapp/backend/model/goals/GoalConfigCodecTest.java`
- Test: `backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java`

**Interfaces:**
- `KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids, Boolean firstYearFund)`; `Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts, BigDecimal firstYearTarget, Integer firstYearDueYear, Integer firstYearDueMonth)`; `Accounts(AccountState brokerage, AccountState plan529, AccountState firstYear)` (`firstYear` nullable).
- In `GoalService`, the per-account helpers take an `enum AccountSlot { BROKERAGE, PLAN_529, FIRST_YEAR }` instead of a boolean; `mapAccounts` calls the mapper for `FIRST_YEAR` only when that account is non-null and otherwise writes null.
- `validateKids(KidsConfig incoming, KidsConfig storedOrNull, int year)`:
  - Fund OFF (`firstYearFund` false or null): returns a config with `firstYearFund = false` and every kid's `firstYear`, `firstYearTarget`, `firstYearDueYear`, `firstYearDueMonth` null.
  - Fund ON: at least one kid has a non-null `firstYear` ("Turn off the first-year fund or add a kid who is not here yet."). A kid with one needs `firstYearTarget` in `(0, 100_000_000]` scale <= 2 ("Enter a first-year amount greater than $0."), `firstYearDueMonth` 1..12 and a non-null `firstYearDueYear`; the account passes the existing balance and `anchorMonthly` bounds. A kid without one has its target and due fields nulled.
  - NEW account rule (create, or `storedOrNull` has no `firstYear` at that kid index): the kid's `birthYear` is null or `>= year` ("The first-year fund is for kids who are not here yet.") and `firstYearDueYear` is in `[year, year + 11]`. A stored account skips this rule.
  - Target rule: valid when `plan529Target > 0` or `brokerageTarget > 0` or the fund is on with a kid holding a fund; the existing message stays for the all-zero case.
- `kidsPlanChanged` additionally compares `firstYearFund` (null as false) and, per kid index, first-year presence, `firstYearTarget` and the due year and month.
- Anchor rules cover the third slot: create and re-anchor → `(balance, null, balance, anchorMonthly as sent)`; balance-only → keep stored anchors, stamp `balanceUpdatedAt` only when that balance changed.

- [ ] **Step 1: Write the failing tests**

```java
// GoalConfigCodecTest
@Test void parsesKidsConfigWithoutTheNewProperties()       // legacy json: firstYearFund, firstYearTarget, due fields and accounts.firstYear all null
@Test void parsesKidsConfigWithExplicitNullsForTheNewProperties()
@Test void roundTripsAFirstYearAccountWithTargetAndDueDate()
// GoalServiceTest
@Test void kidsFundOnRequiresAKidWithAFund()
@Test void kidsFundAccountRequiresATargetAndADueDate()
@Test void kidsRejectANewFirstYearAccountOnAKidBornLastYear()   // create
@Test void storedFirstYearAccountSurvivesTheNewYear()            // stored fund, kid birthYear = year - 1, balance-only PUT succeeds and keeps anchors
@Test void kidsAllowFirstYearOnlyGoal()                          // both targets 0, fund on, one undecided kid
@Test void kidsFundOffStripsEveryFirstYearField()
@Test void kidsCreateAnchorsTheFirstYearAccount()
@Test void updateReanchorsWhenTheFundIsSwitchedOff()
@Test void updateReanchorsWhenAFirstYearTargetOrDueDateChanges()
@Test void kidsUpdateStampsOnlyTheChangedFirstYearBalance()
@Test void legacyStoredKidsGoalStillUpdates()                    // stored config lacks the new properties; balance-only PUT keeps anchors
// GoalControllerTest
@Test void acceptsAKidsBodyWithTheNewProperties()                // MockMvc POST with firstYearFund, per-kid target/due and accounts.firstYear -> 201
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.GoalServiceTest' --tests 'com.investingapp.backend.model.goals.GoalConfigCodecTest'`
Expected: compilation failure (record arity).

- [ ] **Step 3: Implement the record change and the service changes**

Update every existing `new KidsConfig(...)`, `new KidsConfig.Kid(...)` and `new KidsConfig.Accounts(...)` call site in main and test code to the new arity (the existing tests pass nulls for the new components).

- [ ] **Step 4: Run the three test classes, then the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/model/goals/KidsConfig.java backend/src/main/java/com/investingapp/backend/service/GoalService.java backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java backend/src/test/java/com/investingapp/backend/model/goals/GoalConfigCodecTest.java backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java
git commit -m "feat(goals): first-year fund as a third kid account on the server"
```

---

## Task 3: First-year fund math, model types and view model

**Files:**
- Modify: `frontend/src/app/models/goal.model.ts`
- Modify: `frontend/src/app/utils/goal-plan.utils.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/pages/goal/goal-plan.view-model.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/services/dev/goals-mock.ts` (+ `.spec.ts`)

**Interfaces:**
- `goal.model.ts`: `KidInput` gains `firstYearTarget?: number | null`, `firstYearDueYear?: number | null`, `firstYearDueMonth?: number | null`; `KidInput.accounts: { brokerage: AccountState; plan529: AccountState; firstYear?: AccountState | null }`; `KidsConfig` gains `firstYearFund?: boolean | null`. Readers treat missing and null alike (off, no fund).
- `goal-plan.utils.ts` adds:
  - `FIRST_YEAR_UNDECIDED_MONTHS = 36`
  - `isKidNotYetBorn(birthYear: number | null, birthMonth: number | null, now: Date): boolean` (null year → true; else `horizonMonths(now, birthYear, birthMonth ?? 7) > 0`)
  - `firstYearDue(birthYear: number | null, birthMonth: number | null, createdAt: Date): { year: number; month: number }` (decided → `(birthYear, birthMonth ?? 7)`; undecided → the month 36 months after `createdAt`'s month)
  - `interface FirstYearPlan { n: number; rateKind: RateKind; rate: number; monthly: number; target: number; finished: boolean; due: { year: number; month: number }; undecided: boolean }`
  - `proposeFirstYearPlan(input: { target: number; balance: number; due: { year: number; month: number }; undecided: boolean; marketRate: number; rateKind: RateKind; now: Date }): FirstYearPlan` (`n = horizonMonths(now, due.year, due.month)`; `rate = rateKind === 'cash' ? CASH_RATE : marketRate`; `finished = n === 0`; `monthly = finished ? 0 : requiredMonthly(balance, target, n, rate)`)
  - `kidAccountRateKind(anchorDate: Date, endYear: number, endMonth: number): RateKind` (`horizonMonths(anchorDate, endYear, endMonth) < CASH_HORIZON_MONTHS ? 'cash' : 'market'`)
  - `planStatus` gains an optional `extraExpected?: number` (default 0) added to the expected balance; nothing else about it changes.
- `goal-plan.view-model.ts`:
  - `GoalPlanContext` gains `reanchorPending?: boolean` (true for a setup draft or an edit draft whose plan inputs changed). `anchorDate = reanchorPending ? ctx.now : new Date(goal.planAnchoredAt)`.
  - For KIDS, EVERY account's kind comes from `kidAccountRateKind(anchorDate, endYear, endMonth)` (529 and brokerage end at the 18th-birthday deadline from `kidDeadline`; first-year ends at the stored due date). The goal-level cash shortcut is removed for KIDS; `ctx.rateKindOverride` is ignored for KIDS. The goal-level `rateKind` reported by the view is cash only when every unfinished account plan is cash.
  - `interface FirstYearRow { kidIndex: number; label: string; plan: FirstYearPlan; balance: number; hidden: boolean }` (`hidden` when the due date is more than 12 months before `ctx.now`)
  - `GoalPlanView` gains `kidCosts?: KidCostFigures` (always set for KIDS, from `kidCostFigures(kids.length)`) and `firstYear?: { totalMonthly: number; endsLabel: string | null; rows: FirstYearRow[] }` (set when at least one kid has a `firstYear` account; `endsLabel` = "Mon YYYY" of the latest due date among unfinished rows, null when none).
  - `monthlyTotal` adds unfinished first-year monthlies. `headline`: when both account targets are 0 and a fund exists → "Ready for the first year"; otherwise unchanged. `subtitle`: fund-only → `"$M/mo · N kids · first-year fund"`; otherwise unchanged.
  - Status: `extraExpected = Σ over unfinished first-year accounts of futureValue(anchorBalance, anchorMonthly, min(monthsLogged, horizonMonths(anchorDate, due.year, due.month)), thatAccountRate, [])` where `monthsLogged = monthsBetween(goal.planAnchoredAt, balanceUpdatedAtUsed)` and `thatAccountRate = kind === 'cash' ? CASH_RATE : goal.planRate`. The balance and target sums add unfinished first-year accounts (`balance`, and `firstYearTarget`); `balanceUpdatedAt` candidates include theirs. Finished accounts are excluded from balance, target, expected and `progressPct`. First-year accounts contribute no lumps.
  - The 529 and brokerage account views are omitted from `kids` when their target is 0.
- `goals-mock.ts`: the KIDS mock gains `firstYearFund: true` and, on the undecided kid, a `firstYear` account with `firstYearTarget: 20800` and a due date 30 months after the mock's `now` (so a countdown is visible).

- [ ] **Step 1: Write the failing spec cases**

```ts
// goal-plan.utils.spec.ts  (NOW = new Date(2026, 10, 15))
describe('isKidNotYetBorn', () => {
  it('is true for undecided', () => expect(isKidNotYetBorn(null, null, NOW)).toBeTrue());
  it('is false for a birth month that has started', () => expect(isKidNotYetBorn(2026, 11, NOW)).toBeFalse());
  it('is true from next month on', () => expect(isKidNotYetBorn(2026, 12, NOW)).toBeTrue());
  it('assumes July when the month is unknown', () => { expect(isKidNotYetBorn(2026, null, NOW)).toBeFalse(); expect(isKidNotYetBorn(2027, null, NOW)).toBeTrue(); });
});
describe('firstYearDue', () => {
  it('uses the birth month when decided', () => expect(firstYearDue(2028, 11, NOW)).toEqual({ year: 2028, month: 11 }));
  it('assumes July for an unknown month', () => expect(firstYearDue(2028, null, NOW)).toEqual({ year: 2028, month: 7 }));
  it('is 36 months after the creation month when undecided', () => expect(firstYearDue(null, null, NOW)).toEqual({ year: 2029, month: 11 }));
});
describe('kidAccountRateKind', () => {
  it('is market at 36 months and cash at 35', () => { expect(kidAccountRateKind(NOW, 2029, 11)).toBe('market'); expect(kidAccountRateKind(NOW, 2029, 10)).toBe('cash'); });
});
describe('proposeFirstYearPlan', () => {
  const p = (target: number, due: { year: number; month: number }, rateKind: 'market' | 'cash', undecided = false, now = NOW) =>
    proposeFirstYearPlan({ target, balance: 0, due, undecided, marketRate: 0.10, rateKind, now });
  it('pins rank 1 undecided: 36 months invested, $616', () => { const r = p(25700, { year: 2029, month: 11 }, 'market', true); expect(r.n).toBe(36); expect(r.monthly).toBe(616); });
  it('pins rank 2 undecided at $498', () => expect(p(20800, { year: 2029, month: 11 }, 'market', true).monthly).toBe(498));
  it('pins rank 1 planned for Nov 2028 in cash at $1,031', () => { const r = p(25700, { year: 2028, month: 11 }, 'cash'); expect(r.n).toBe(24); expect(r.monthly).toBe(1031); });
  it('pins rank 2 planned for Nov 2028 at $834 and Nov 2027 at $1,702', () => { expect(p(20800, { year: 2028, month: 11 }, 'cash').monthly).toBe(834); expect(p(20800, { year: 2027, month: 11 }, 'cash').monthly).toBe(1702); });
  it('asks for the whole target when the kid is due next month', () => { const r = p(20800, { year: 2026, month: 12 }, 'cash'); expect(r.n).toBe(1); expect(r.monthly).toBe(20800); });
  it('counts down from the due date', () => expect(p(25700, { year: 2029, month: 11 }, 'market', true, new Date(2027, 4, 15)).n).toBe(30));
  it('is finished at the due month with no monthly', () => { const r = p(20800, { year: 2026, month: 11 }, 'cash'); expect(r.finished).toBeTrue(); expect(r.monthly).toBe(0); });
});
describe('planStatus extraExpected', () => {
  it('adds to the expected balance', () => { /* same args twice, extraExpected 5000 lowers delta by exactly 5000 */ });
});
// goal-plan.view-model.spec.ts
it('always carries kid cost figures for a kids goal, sized by the kid count')            // 3 kids -> perChildTotal 252393
it('adds unfinished first-year plans to the monthly total, one row per kid with a fund')
it('counts a fund down from its stored due date when viewed six months later')            // planAnchoredAt 2026-05-15T10:00:00, due { 2029, 5 }, ctx.now Nov 15 2026 -> rows[0].plan.n 30
it('keeps a fresh undecided fund invested and a 24-month fund in cash')
it('re-evaluates kinds at now when a re-anchor is pending')                                // reanchorPending true, due 30 months out -> cash
it('plans a 529 added to a cash first-year-only goal at the market rate')                  // $47,800 over 216 months at 10% -> 80
it('grades a cash fund beside a market 529 at the cash rate')                              // fund balance futureValue(0, 834, 24, 0.04, []) logged at month 24, 529 on plan -> on_plan
it('reads on plan six months after a fully funded fund ended')                             // not behind
it('drops a finished fund from the hero total, status and progress and hides it after 12 months')
it('says Ready for the first year and never 18th birthday for a fund-only goal')
it('names the fund share end month')                                                        // firstYear.endsLabel "Nov 2029"
it('omits the 529 card when its target is 0')
it('renders a stored kids goal whose new fields are explicit nulls')
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/utils/goal-plan.utils.spec.ts' --include='src/app/pages/goal/goal-plan.view-model.spec.ts' --include='src/app/services/dev/goals-mock.spec.ts'`
Expected: FAIL (missing exports).

- [ ] **Step 3: Implement the model fields, the math, the view model and the mock**

Update the existing view-model spec block "A goal planned as cash keeps every account in cash" to the new per-account rule (a goal is only stored as cash when every account was under 36 months at its anchor, so an unchanged stored goal renders the same).

- [ ] **Step 4: Run the three specs green, then the AOT build**

The page does not pass `reanchorPending` yet (Task 4); the build must still pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/goal.model.ts frontend/src/app/utils/goal-plan.utils.ts frontend/src/app/utils/goal-plan.utils.spec.ts frontend/src/app/pages/goal/goal-plan.view-model.ts frontend/src/app/pages/goal/goal-plan.view-model.spec.ts frontend/src/app/services/dev/goals-mock.ts frontend/src/app/services/dev/goals-mock.spec.ts
git commit -m "feat(goals): first-year fund math and kid costs in the plan view model"
```

---

## Task 4: Kids quiz, cost card and first-year fund on the goal page

**Files:**
- Modify: `frontend/src/app/pages/goal/goal-form.model.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/pages/goal/goal.page.ts|html|scss|spec.ts`
- Modify: `FREDdocs/backlog.md` (one new story via the `backlog-add` skill)

**Interfaces:**
- `goal-form.model.ts`:
  - `GoalForm.kids` gains `firstYearFund: boolean`, `collegeChoice: 'public_tuition' | 'public_full' | 'private_tuition' | 'custom' | 'none'`, `at18Choice: number | 'custom' | 'none'`.
  - `defaultFirstYearFund(kids: KidFormRow[], now: Date): boolean` (true only when at least one kid is eligible and every eligible kid is undecided or has `horizonMonths(now, birthYear, birthMonth ?? 7) >= 12`).
  - `defaultForm('KIDS', now)`: one undecided "Kid 1"; `collegeChoice: 'public_tuition'`, `plan529Target: 47800`; `at18Choice: 25000`, `brokerageTarget: 25000`; `firstYearFund: true`.
  - `formFromGoal(goal)`: choices inferred from stored targets (a target equal to a preset selects it; 0 → `'none'`; otherwise `'custom'`); `firstYearFund` from the config (missing or null → false).
  - `formToConfig(form, existing, now)` (new third parameter): for KIDS with the fund on, per kid index: if `existing` has a `firstYear` account at that index and that kid's timing (`birthYear`, `birthMonth`, undecided) is unchanged → carry the account, `firstYearTarget` and due date over; else if `isKidNotYetBorn(...)` → a new account (zeroed, or the existing balance at that index if any), due date from `firstYearDue(birthYear, birthMonth, now)`; else none. Targets: when the kid count and every kid's timing are unchanged versus `existing`, stored targets are kept; otherwise every fund's target is `firstYearTargetForRank(kidRanks(kids)[i])`. `firstYearFund` in the config is true only when the switch is on and at least one kid ends up with an account. Fund off → no first-year fields.
  - `planInputsChanged(form, existing, now)` (new third parameter): for KIDS compares `formToConfig(form, existing, now)` against `existing.config` on the two targets, kids count, each kid's `birthYear`/`birthMonth`, `firstYearFund` (missing or null as false) and per kid first-year presence, target and due date. Name, icon and labels are never plan inputs.
  - `validateForm(form, now)`: the kids target error fires only when both targets are 0 or empty AND no kid would hold a fund; a `'custom'` choice requires its amount.
  - `kidsQuizErrors(form, card: 1 | 2 | 3 | 4 | 5, now): Record<string, string>` (1: none; 2: kid birth years; 3: none; 4: custom college amount; 5: custom at-18 amount, name, and the kids target rule).
- `GoalPage`:
  - `type KidBalanceKey = KidAccountKey | 'firstYear'`, used by the balance path type and `setupBalances`.
  - `quizCard = signal<1 | 2 | 3 | 4 | 5>(1)`; `nextQuizCard()` (validates the current card with `kidsQuizErrors`, then advances; on card 5 behaves as `showPlan()`); `prevQuizCard()`; `backToInputs()` sets `quizCard` to 1; a failed `showPlan()` or `save()` validation sets `quizCard` to the first card whose `kidsQuizErrors` is non-empty; while on the quiz, live revalidation uses `kidsQuizErrors` for the current card.
  - `setCollegeChoice(choice)`, `setAt18Choice(choice)`, `toggleFirstYearFund()`; `readonly anyKidEligible`, `readonly quizCosts = computed(() => kidCostFigures(form().kids.kids.length))`, `readonly fundPreview = computed(...)` (the total monthly the fund would add and the latest end label, from the view model run on a draft with the fund on), `readonly fundShortRunwayMonths` (the smallest horizon among eligible decided kids when under 12, else null), `readonly fundLoggedBalance` (sum of first-year balances on the saved goal).
  - Changing the kid count or a kid's timing on cards 1 and 2 re-applies `defaultFirstYearFund` until the user has touched the switch.
  - `draftGoal()`: when the mode is edit and `planInputsChanged(form, goal, now)` is true, the draft's `planAnchoredAt` is `now` in local ISO form; the view context passes `reanchorPending` (true in setup or when inputs changed). The setup branch keeps first-year accounts with their typed balances.
  - `withAccountAnchors` also writes each unfinished first-year account's `anchorMonthly` from its plan. `updateBalance` handles `{ kid, account: 'firstYear' }`.
  - `summarizeInputs` for KIDS: `"3 kids · $48K college · $25K at 18 · first-year fund · Edit"`, dropping any part that is off.
- Markup (KIDS only; every other type is untouched):
  - Step 1: the five cards in the spec's order (count, when, what a kid costs with the switch, college, at-18 with name and CTA) with the exact questions, selection-row labels, helper lines, live fund line, short-runway line, drop-balances line and all-born line from the spec; progress dots `.gl-dots`; Back hidden on card 1; Next on cards 1 to 4; "Show my plan" on card 5. Cards 4 and 5 use stacked selection rows (label left, amount right, selected border), not chips.
  - Step 2 order: hero (with the fund-share line when a fund and other targets exist), return assumption, inputs row, status, "What a kid costs" (open in setup, collapsed in edit behind "Show costs by age"), "First-year fund", then the existing account cards; every string from the spec's "Plan view" section, including the headline by family size, the family line, the four stage rows, the birth row, the note with its conditional ending, the sources toggle (`costCardSources()`), the per-kid fund lines for decided, undecided and finished rows, the cash line, the undecided footnote, the fund footer, and the 529 card's preset line.
- Existing specs: update the KIDS cases in `goal-form.model.spec.ts` and `goal.page.spec.ts` to the new defaults, the quiz markup and the three-argument `formToConfig` and `planInputsChanged`.
- Backlog: one new FRED- story, text: "Goals: AI-written personal plan summary. The backend sends a goal's computed plan (fixed numbers) to Claude, which writes a short personal summary through the compliance gate, the Monthly Market Breakdown pattern, plus a read-only get_goals tool for Ask FRED. The AI never produces or changes a dollar figure. Follows Goals v3 (kid costs and quiz shipped on feature/goals-v3)." Re-scan the highest FRED number right before inserting.

- [ ] **Step 1: Write the failing specs**

```ts
// goal-form.model.spec.ts
it('defaults the kids quiz to public tuition, $25,000 at 18 and the fund on for an undecided kid')
it('defaults the fund off when an eligible kid is under 12 months out and on at 12')
it('gives the first child the rank 1 target even in a three-kid plan')        // targets [25700, 20800, 16500] by birth order
it('stores a due date: the birth month for a decided kid, now + 36 months for an undecided one')
it('carries a stored fund over unchanged after the kid has arrived')           // existing account, timing unchanged, isKidNotYetBorn false -> still present
it('does not flag a plan change because a kid was born')
it('recomputes targets when a kid is added and keeps them when nothing about timing changed')
it('turns the fund off in the config when no kid would hold one')
it('accepts a first-year-only kids plan')
it('infers the choices from stored targets')                                    // 123960 -> public_full, 0 -> none, 70000 -> custom
it('flags a plan-input change when the fund switch flips or a due date moves')
it('validates one quiz card at a time')
// goal.page.spec.ts
it('walks the five quiz cards in order with the cost card third and blocks Next on an invalid card')
it('shows the monthly the fund adds next to the switch and the short-runway line under 12 months')
it('sets the 529 target from a college row and the brokerage target from an at-18 row')
it('orders the plan view: hero, return assumption, inputs, status, costs, fund, accounts')
it('words the per-child headline by family size')                              // "an only child" / "two-child family" / "three or more"
it('lists a decided fund until its month, an undecided fund with its date and the invested note, and a finished fund as saved of target')
it('hides the fund switch and card when every kid is already born and no fund is stored')
it('sends firstYearFund, per-kid targets, due dates and first-year anchors on save')
it('shows the same first-year monthly before and after a re-plan save')
it('drops the fund from the hero total when it is switched off and warns about logged balances')
it('says the accounts and the fund live outside FRED')
it('has no em dash in the quiz, the cost card or the fund card')
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/*.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement the form model, the quiz, the cost card and the fund card; update the existing KIDS specs**

- [ ] **Step 4: Add the backlog story via the skill**

- [ ] **Step 5: Run the goal specs green, then the whole suite, the AOT build and lint**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless` → only the 14 known failures. `npx ng build --configuration dev && npm run lint` → exit 0.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/goal FREDdocs/backlog.md
git commit -m "feat(goals): kids quiz, what a kid costs, and the first-year fund"
```

---

## Final gate

- `cd backend && ./gradlew test` green.
- `cd frontend && npx ng build --configuration dev && npm run lint && npm run test:events-documented` exit 0.
- `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`: only the 14 known failures.
- No literal em dash under `frontend/src/app/pages/goal`, `frontend/src/app/constants`, `frontend/src/app/utils/kid-cost.utils.ts`, `FREDdocs/kid-cost-figures.md`.
- Whole-branch review covers Tasks 1-13 of the base plan plus this addendum.
