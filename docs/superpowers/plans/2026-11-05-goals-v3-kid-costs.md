# Goals v3 Kid Costs Addendum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a kids goal, show what a kid costs by life stage and in total, plan a first-year fund for kids not yet born, and replace the kids setup form with a five-card quiz, all ahead of the 529 and brokerage plans.

**Architecture:** Verified cost figures live in one frontend constants file with a pure helper; the first-year fund is a third per-kid `AccountState` that reuses every existing anchor and status rule; the backend only gains two config fields and one nullable account; the goal page's KIDS step 1 becomes a quiz and its plan view gains two cards at the top.

**Tech Stack:** Spring Boot 3 (records, Jackson, JUnit 5 + Mockito, Gradle), Angular 19 standalone + Ionic (signals, Karma/Jasmine).

**Spec:** `docs/superpowers/specs/2026-11-05-goals-v3-kid-costs-addendum.md` (binding), which extends `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`. This plan continues `docs/superpowers/plans/2026-11-02-goals-v3-plans.md` (Tasks 1-13 complete on this branch).

## Global Constraints

- Every figure and source string comes verbatim from the addendum spec's figures table. A number FRED derived is never labeled as a government number.
- No em dashes in any user-facing string, comment or doc.
- Ordinary annuity, `r = annualRate / 12`, index 1 = the month after `now`, proposed amounts round UP to whole dollars (unchanged conventions in `utils/goal-plan.utils.ts`).
- Per-account rate kind (ruling R13): cash when that account's horizon at the anchor date is under 36 months, else market; `CASH_RATE = 0.04`.
- The server decides re-anchoring by comparing stored and incoming plan inputs; there is no client flag.
- No Flyway migration: kids config is JSON in the existing TEXT column and new properties are nullable.
- No analytics taxonomy changes.
- `git add` named files only. Never `git checkout`, `restore`, `stash` or `reset`. Never push. Never start or stop dev servers.
- Karma: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='<spec path>'`. AOT gate: `npx ng build --configuration dev`. Lint: `npm run lint`. Backend: `cd backend && ./gradlew test --tests '<FQCN>'`.
- Known pre-existing Karma failures: the 14 FRED-226 specs (nine `should create` smoke specs, `FreedomDetailSheetComponent` 4% rule, `SecuritySettingsPage` gating, `TabsPage` pager, two swipe-settle velocity specs). Anything else failing is new.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016NWfpgW9CAyp2L2VUh7fGw
  ```

## Review Focus

1. A family of one kid vs two vs three: the per-child figure must change (× 1.27, × 1.00, × 0.76) and the first-year target with it. Tests in Task 1.
2. A kid whose birth month is THIS month: not eligible for a first-year fund (the month has started). Next month: eligible with n = 1. Tests in Task 3.
3. An undecided kid opened six months after the plan was made: the runway shows 30 months left, not 36. Test in Task 3.
4. The user turns the fund off after saving balances into it: the accounts are dropped, the goal re-anchors, and the hero total falls. Tests in Task 2 and Task 4.
5. A stored kids goal from before this change (no `firstYearFund`, no `firstYear` accounts) must still load, render and save. Tests in Task 2 and Task 3.

---

## Task 1: Kid cost figures (constants, helper, doc)

**Files:**
- Create: `frontend/src/app/constants/kid-costs.ts`
- Create: `frontend/src/app/utils/kid-cost.utils.ts`
- Test: `frontend/src/app/utils/kid-cost.utils.spec.ts`
- Create: `FREDdocs/kid-cost-figures.md`

**Interfaces:**
- `kid-costs.ts` exports (plain constants, no Angular):
  - `USDA_2015_ANNUAL_BY_BAND: ReadonlyArray<{ fromAge: number; toAge: number; annual: number }>` = `[{0,2,12680},{3,5,12730},{6,8,12350},{9,11,13180},{12,14,13030},{15,17,13900}]`
  - `USDA_2015_TOTAL = 233610`
  - `CPI_U_2015_AVERAGE = 237.017`, `CPI_U_LATEST = 336.940`, `CPI_U_LATEST_LABEL = 'September 2026'`
  - `CHILDBIRTH_OUT_OF_POCKET = 2743`
  - `COLLEGE_ONE_YEAR = { publicTuition: 11950, publicFullCost: 30990, privateTuition: 45000 }`, `COLLEGE_PRICE_YEAR_LABEL = '2025-26'`
  - `AT_18_PRESETS: ReadonlyArray<number>` = `[25000, 50000, 100000, 250000]`
  - `KID_COST_SOURCES: ReadonlyArray<{ name: string; year: string; url: string }>` with exactly: "USDA, Expenditures on Children by Families" / "2015 (published 2017)" / `https://www.fns.usda.gov/sites/default/files/resource-files/crc2015-march2017.pdf`; "U.S. Bureau of Labor Statistics, Consumer Price Index" / "September 2026" / `https://www.bls.gov/cpi/`; "Peterson-KFF Health System Tracker, pregnancy and childbirth costs" / "2025" / `https://www.healthsystemtracker.org/brief/health-costs-associated-with-pregnancy-childbirth-and-postpartum-care/`; "College Board, Trends in College Pricing and Student Aid" / "2025" / `https://research.collegeboard.org/media/pdf/Trends-in-College-Pricing-and-Student-Aid-2025-final_1.pdf`
- `kid-cost.utils.ts` exports (pure):
  - `inflationFactor(): number` (= `CPI_U_LATEST / CPI_U_2015_AVERAGE`)
  - `familySizeFactor(kidsCount: number): number` (1 → 1.27; 2 → 1; 3 or more → 0.76; below 1 treated as 1)
  - `type KidCostStageKey = '0-2' | '3-5' | '6-11' | '12-17'`
  - `interface KidCostStage { key: KidCostStageKey; label: string; years: number; annual: number; monthly: number; total: number }` with labels "Baby and toddler, 0 to 2", "Preschool, 3 to 5", "Grade school, 6 to 11", "Teen years, 12 to 17"; `annual` = stage total ÷ years; `monthly` = annual ÷ 12 (unrounded numbers; the view rounds)
  - `interface KidCostFigures { kidsCount: number; sizeFactor: number; perChildTotal: number; familyTotal: number; stages: KidCostStage[]; childbirthOutOfPocket: number; firstYearCost: number; firstYearTarget: number; asOfLabel: string }`
  - `kidCostFigures(kidsCount: number): KidCostFigures` (all dollar values rounded to whole dollars except `sizeFactor`; `firstYearCost` = the 0-2 annual figure × inflation × size factor; `firstYearTarget = Math.ceil((firstYearCost + CHILDBIRTH_OUT_OF_POCKET) / 100) * 100`; `asOfLabel = CPI_U_LATEST_LABEL`)
  - `interface CollegePreset { key: 'public_tuition' | 'public_full' | 'private_tuition'; label: string; amount: number }`, `collegePresets(): CollegePreset[]` with labels "Tuition at an in-state public college", "Full cost at an in-state public college", "Tuition at a private college" and amounts 4 × the one-year figures

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
  it('pins the two-child figures', () => { const f = kidCostFigures(2); expect(f.perChildTotal).toBe(332097); expect(f.familyTotal).toBe(664193); expect(f.firstYearCost).toBe(18026); expect(f.firstYearTarget).toBe(20800); });
  it('pins one kid and three kids', () => { expect(kidCostFigures(1).perChildTotal).toBe(421763); expect(kidCostFigures(1).firstYearTarget).toBe(25700); expect(kidCostFigures(3).perChildTotal).toBe(252393); expect(kidCostFigures(3).familyTotal).toBe(757180); expect(kidCostFigures(3).firstYearTarget).toBe(16500); });
  it('pins the four stages for two kids', () => { const s = kidCostFigures(2).stages; expect(s.map(x => x.key)).toEqual(['0-2', '3-5', '6-11', '12-17']); expect(s.map(x => Math.round(x.total))).toEqual([54077, 54290, 108879, 114850]); expect(Math.round(s[2].annual)).toBe(18147); expect(Math.round(s[3].annual)).toBe(19142); expect(Math.round(s[0].monthly)).toBe(1502); });
  it('stage totals sum to the per-child total within two dollars', () => { const f = kidCostFigures(3); expect(Math.abs(f.stages.reduce((a, s) => a + s.total, 0) - f.perChildTotal)).toBeLessThanOrEqual(2); });
  it('carries the birth figure and the as-of label', () => { const f = kidCostFigures(2); expect(f.childbirthOutOfPocket).toBe(2743); expect(f.asOfLabel).toBe('September 2026'); });
});
describe('collegePresets', () => {
  it('is four years at the College Board prices', () => expect(collegePresets().map(p => p.amount)).toEqual([47800, 123960, 180000]));
});
describe('sources', () => {
  it('names four sources with a year and a url and no em dash', () => { expect(KID_COST_SOURCES.length).toBe(4); expect(JSON.stringify(KID_COST_SOURCES)).not.toMatch(/—/); KID_COST_SOURCES.forEach(s => { expect(s.year.length).toBeGreaterThan(0); expect(s.url.startsWith('https://')).toBeTrue(); }); });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/utils/kid-cost.utils.spec.ts'`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement the constants and the helper**

Each constant carries a one-line comment naming its source table and dollar-year. The file header states that derived values are FRED's own and are never to be labeled as government numbers.

- [ ] **Step 4: Write `FREDdocs/kid-cost-figures.md`**

Sections: what the app shows and where; the figures table copied from the spec with sources, tables and dollar-years; what FRED derives and the exact formulas; what is excluded (college, anything after 17) and the family type the study describes; the refresh procedure (once a year update `CPI_U_LATEST` and its label from BLS series CUUR0000SA0; each November update the College Board figures; check the USDA page and the KFF brief for new editions; re-run the spec, whose pins must be updated in the same commit); a note that changing a figure never moves an existing plan because `firstYearTarget` is snapshotted.

- [ ] **Step 5: Run to verify it passes, then the AOT build**

Expected: all PASS; `npx ng build --configuration dev` exit 0.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/constants/kid-costs.ts frontend/src/app/utils/kid-cost.utils.ts frontend/src/app/utils/kid-cost.utils.spec.ts FREDdocs/kid-cost-figures.md
git commit -m "feat(goals): verified kid cost figures with sources and a refresh doc"
```

---

## Task 2: Backend support for the first-year fund

**Files:**
- Modify: `backend/src/main/java/com/investingapp/backend/model/goals/KidsConfig.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/GoalService.java` (`validateKids`, `kidsPlanChanged`, `mapAccounts`, `accountAt`, the keep-anchors and re-anchor paths)
- Test: `backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java`
- Test: `backend/src/test/java/com/investingapp/backend/model/goals/GoalConfigCodecTest.java`

**Interfaces:**
- `KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids, Boolean firstYearFund, BigDecimal firstYearTarget)`; `Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts)` unchanged; `Accounts(AccountState brokerage, AccountState plan529, AccountState firstYear)` with `firstYear` nullable.
- Validation in `validateKids`: `firstYearFund == null` → false. Fund ON: `firstYearTarget` non-null, `0 < x <= 100_000_000`, scale <= 2 ("Enter a first-year amount greater than $0."); at least one kid has a non-null `firstYear` ("Turn off the first-year fund or add a kid who is not here yet."); a non-null `firstYear` on a kid whose `birthYear` is non-null and `< currentYear` is rejected ("The first-year fund is for kids who are not here yet."); each `firstYear` passes the existing account balance and `anchorMonthly` bounds. Fund OFF: the returned config has `firstYearFund = false`, `firstYearTarget = 0.00`, and every kid's `firstYear = null` (normalized, not rejected). Target rule: valid when `plan529Target > 0` or `brokerageTarget > 0` or the fund is on; the existing message stays for the all-zero case.
- `kidsPlanChanged` additionally compares `firstYearFund` (null as false), `firstYearTarget` (null as 0) and, per kid index, whether `firstYear` is present.
- Anchor rules: every place that handles the two accounts also handles `firstYear` when non-null (create and re-anchor: `(balance, null, balance, anchorMonthly as sent)`; balance-only: keep stored anchors, stamp `balanceUpdatedAt` only when that balance changed). A stored config without the new properties (all null) behaves as fund off.

- [ ] **Step 1: Write the failing tests**

```java
// GoalConfigCodecTest
@Test void parsesKidsConfigWithoutTheNewProperties()      // legacy json -> firstYearFund null, firstYearTarget null, accounts.firstYear null
@Test void roundTripsAFirstYearAccount()
// GoalServiceTest
@Test void kidsFundOnRequiresATarget()
@Test void kidsFundOnRequiresAnEligibleKid()
@Test void kidsRejectFirstYearAccountOnAKidBornLastYear()
@Test void kidsAllowFirstYearOnlyGoal()                    // both targets 0, fund on, one undecided kid -> valid
@Test void kidsFundOffDropsFirstYearAccountsAndZeroesTarget()
@Test void kidsCreateAnchorsTheFirstYearAccount()          // anchorBalance == balance, anchorMonthly as sent, balanceUpdatedAt null
@Test void updateReanchorsWhenTheFundIsSwitchedOff()
@Test void updateReanchorsWhenTheFirstYearTargetChanges()
@Test void kidsUpdateStampsOnlyTheChangedFirstYearBalance()
@Test void legacyStoredKidsGoalStillUpdates()              // stored config lacks the new properties; a balance-only PUT keeps anchors
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.GoalServiceTest' --tests 'com.investingapp.backend.model.goals.GoalConfigCodecTest'`
Expected: compilation failure (record arity).

- [ ] **Step 3: Implement the record change and the service changes**

Update every existing `new KidsConfig(...)` and `new KidsConfig.Accounts(...)` call site in main and test code to the new arity.

- [ ] **Step 4: Run the two test classes, then the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/model/goals/KidsConfig.java backend/src/main/java/com/investingapp/backend/service/GoalService.java backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java backend/src/test/java/com/investingapp/backend/model/goals/GoalConfigCodecTest.java
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
- `goal.model.ts`: `KidInput.accounts: { brokerage: AccountState; plan529: AccountState; firstYear?: AccountState | null }`; `KidsConfig` gains `firstYearFund?: boolean` and `firstYearTarget?: number` (optional so goals stored before this change still type-check; readers treat missing as off and 0).
- `goal-plan.utils.ts` adds:
  - `FIRST_YEAR_UNDECIDED_MONTHS = 36`
  - `isKidNotYetBorn(birthYear: number | null, birthMonth: number | null, now: Date): boolean` (null year → true; else `horizonMonths(now, birthYear, birthMonth ?? 7) > 0`)
  - `firstYearDeadline(birthYear: number | null, birthMonth: number | null, anchorDate: Date): { year: number; month: number; undecided: boolean }` (decided → `(birthYear, birthMonth ?? 7)`; undecided → the month 36 months after `anchorDate`'s month)
  - `interface FirstYearPlan { n: number; rateKind: RateKind; rate: number; monthly: number; target: number; dueNow: number; deadline: { year: number; month: number }; undecided: boolean }`
  - `proposeFirstYearPlan(input: { target: number; balance: number; deadline: { year: number; month: number }; undecided: boolean; marketRate: number; rateKindOverride: RateKind | null; now: Date }): FirstYearPlan` (`n = horizonMonths(now, deadline.year, deadline.month)`; rate through `resolveRate` with `forceCash: false`; `monthly = requiredMonthly(balance, target, n, rate)`; `n === 0` → `monthly 0`, `dueNow = max(0, target − balance)`)
- `goal-plan.view-model.ts`:
  - `KidAccountKey` stays `'plan529' | 'brokerage'` for the two account cards.
  - `interface FirstYearRow { kidIndex: number; label: string; plan: FirstYearPlan; balance: number }`
  - `GoalPlanView` gains `kidCosts?: KidCostFigures` (always set for KIDS, from `kidCostFigures(kids.length)`) and `firstYear?: { target: number; totalMonthly: number; rows: FirstYearRow[] }` (set when `config.firstYearFund` is true and at least one kid has a `firstYear` account).
  - For each kid with a `firstYear` account: `anchorDate` = `new Date(goal.planAnchoredAt)` (setup drafts anchor at `ctx.now`); `deadline = firstYearDeadline(birthYear, birthMonth, anchorDate)`; account kind per R13 = `goal.planRateKind === 'cash' ? 'cash' : (horizonMonths(anchorDate, deadline.year, deadline.month) < 36 ? 'cash' : 'market')`; live plan = `proposeFirstYearPlan({ target: config.firstYearTarget, balance: account.balance, deadline, undecided, marketRate: ctx.marketRate, rateKindOverride: kind, now: ctx.now })`.
  - `monthlyTotal` adds every first-year monthly. The goal-level `rateKind` stays cash only when every account plan, first-year plans included, is cash.
  - Status sums add each first-year account's `balance`, `anchorBalance`, `anchorMonthly`; the status target adds `firstYearTarget` once per first-year account; first-year accounts contribute no lumps; `balanceUpdatedAt` candidates include theirs. `progressPct` uses the same enlarged sums.
  - The 529 and brokerage account views are omitted from `kids` when their target is 0.
- `goals-mock.ts`: the KIDS mock gains `firstYearFund: true`, `firstYearTarget: 20800`, and a `firstYear` account on the undecided kid.

- [ ] **Step 1: Write the failing spec cases**

```ts
// goal-plan.utils.spec.ts  (NOW = new Date(2026, 10, 15))
describe('isKidNotYetBorn', () => {
  it('is true for undecided', () => expect(isKidNotYetBorn(null, null, NOW)).toBeTrue());
  it('is false for a birth month that has started', () => expect(isKidNotYetBorn(2026, 11, NOW)).toBeFalse());
  it('is true from next month on', () => expect(isKidNotYetBorn(2026, 12, NOW)).toBeTrue());
  it('assumes July when the month is unknown', () => { expect(isKidNotYetBorn(2026, null, NOW)).toBeFalse(); expect(isKidNotYetBorn(2027, null, NOW)).toBeTrue(); });
});
describe('firstYearDeadline', () => {
  it('uses the birth month when decided', () => expect(firstYearDeadline(2028, 11, NOW)).toEqual({ year: 2028, month: 11, undecided: false }));
  it('is 36 months after the anchor month when undecided', () => expect(firstYearDeadline(null, null, NOW)).toEqual({ year: 2029, month: 11, undecided: true }));
});
describe('proposeFirstYearPlan', () => {
  const p = (deadline: { year: number; month: number }, undecided: boolean, now = NOW) =>
    proposeFirstYearPlan({ target: 20800, balance: 0, deadline, undecided, marketRate: 0.10, rateKindOverride: null, now });
  it('pins the undecided runway: 36 months, market, $498', () => { const r = p({ year: 2029, month: 11 }, true); expect(r.n).toBe(36); expect(r.rateKind).toBe('market'); expect(r.monthly).toBe(498); });
  it('pins a kid planned for Nov 2028: 24 months, cash, $834', () => { const r = p({ year: 2028, month: 11 }, false); expect(r.n).toBe(24); expect(r.rateKind).toBe('cash'); expect(r.monthly).toBe(834); });
  it('pins a kid planned for Nov 2027: 12 months, cash, $1,702', () => expect(p({ year: 2027, month: 11 }, false).monthly).toBe(1702));
  it('counts an undecided runway down from its anchor', () => expect(p({ year: 2029, month: 11 }, true, new Date(2027, 4, 15)).n).toBe(30));
  it('is due now at the deadline', () => { const r = p({ year: 2026, month: 11 }, false); expect(r.n).toBe(0); expect(r.monthly).toBe(0); expect(r.dueNow).toBe(20800); });
  it('honors a market override on a short runway', () => expect(proposeFirstYearPlan({ target: 20800, balance: 0, deadline: { year: 2028, month: 11 }, undecided: false, marketRate: 0.10, rateKindOverride: 'market', now: NOW }).rateKind).toBe('market'));
});
// goal-plan.view-model.spec.ts
it('always carries kid cost figures for a kids goal, sized by the kid count')        // 3 kids -> kidCosts.perChildTotal 252393
it('adds first-year plans to the monthly total and exposes one row per eligible kid')
it('keeps an undecided fund invested and a 24-month fund in cash')                    // per-row rateKind
it('counts first-year balances, anchors and targets in status and progress')
it('omits the 529 card when its target is 0')
it('renders a legacy kids goal with no first-year fields')                            // firstYear undefined, kidCosts set, no throw
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/utils/goal-plan.utils.spec.ts' --include='src/app/pages/goal/goal-plan.view-model.spec.ts' --include='src/app/services/dev/goals-mock.spec.ts'`
Expected: FAIL (missing exports).

- [ ] **Step 3: Implement the model fields, the math, the view model and the mock**

- [ ] **Step 4: Run the three specs green, then the AOT build**

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
  - `defaultForm('KIDS', now)`: one undecided "Kid 1", `collegeChoice: 'public_tuition'` with `plan529Target: 47800`, `at18Choice: 50000` with `brokerageTarget: 50000`, `firstYearFund: true`.
  - `formFromGoal`: choices are inferred from the stored targets (a target equal to a preset selects that chip; 0 → `'none'`; otherwise `'custom'`); `firstYearFund` from the config (missing → false).
  - `formToConfig(form, existing, now)`: `firstYearFund` is true only when the switch is on AND at least one kid is eligible (`isKidNotYetBorn`); `firstYearTarget = firstYearFund ? kidCostFigures(kids.length).firstYearTarget : 0`, except that in edit mode with an unchanged kid count and the fund already on, the stored `firstYearTarget` is kept (the snapshot rule); each eligible kid gets a `firstYear` account (carried over by index from `existing`, else zeroed); ineligible kids and fund-off get none.
  - `planInputsChanged`: also compares `firstYearFund`, `firstYearTarget` and per-kid first-year presence (mirrors the server).
  - `validateForm`: the kids target error fires only when both targets are 0/empty AND the fund is off or no kid is eligible; a `'custom'` choice requires its amount.
  - `kidsQuizErrors(form, card: 1 | 2 | 3 | 4 | 5, now): Record<string, string>`: the subset of `validateForm` relevant to one card (1: none; 2: kid birth years; 3: custom college amount; 4: custom at-18 amount; 5: name and the kids target rule).
- `GoalPage`:
  - `quizCard = signal<1 | 2 | 3 | 4 | 5>(1)`, `nextQuizCard()` (validates the current card, then advances; on card 5 behaves as `showPlan()`), `prevQuizCard()`, `setCollegeChoice(choice)`, `setAt18Choice(choice)`, `toggleFirstYearFund()`, `readonly anyKidEligible = computed(...)`, `readonly quizCosts = computed(() => kidCostFigures(form().kids.kids.length))`.
  - Setup balances and `updateBalance` accept `{ kid: number; account: 'firstYear' }`. `withAccountAnchors` also writes each first-year account's `anchorMonthly` from its plan. `draftGoal()` builds first-year accounts through `formToConfig`.
  - `summarizeInputs` for KIDS: `"3 kids · $124K college · $50K at 18 · first-year fund · Edit"`, dropping any part that is off.
- Markup (KIDS only; every other type is untouched):
  - Step 1: five cards per the spec section "Setup step 1 becomes a quiz", with the exact questions, chips, helper lines and CTA text listed there; progress dots `.gl-dots`; `Back` hidden on card 1; `Next` on cards 1-4, "Show my plan" on card 5.
  - Step 2, in this order: card "What a kid costs" (headline rounded to the nearest $1,000 with "About"; the family line for more than one kid; four stage rows "about $X a year, $Y a month" rounded to $100 and $10; the birth row; the note; a "Where these numbers come from" toggle listing `KID_COST_SOURCES` names and years); card "First-year fund" when `view().firstYear` exists ("Covers about $T per kid: birth costs and the first year."; one line per row: decided `"{label}: ${m} a month until {Mon YYYY}"`, undecided `"{label}: ${m} a month for three years, invested, so you are ready whenever it happens"`; a "Saved so far" tile per row; the cash label when that row is cash); then the existing account cards.
- Backlog: one new FRED- story, text: "Goals: AI-written personal plan summary. The backend sends a goal's computed plan (fixed numbers) to Claude, which writes a short personal summary through the compliance gate, the Monthly Market Breakdown pattern, plus a read-only get_goals tool for Ask FRED. The AI never produces or changes a dollar figure. Follows Goals v3 (kid costs and quiz shipped on feature/goals-v3)." Re-scan the highest FRED number right before inserting.

- [ ] **Step 1: Write the failing specs**

```ts
// goal-form.model.spec.ts
it('defaults the kids quiz to public tuition, $50,000 at 18 and the fund on')
it('turns the fund off in the config when every kid is already born')
it('snapshots the first-year target for the family size')            // 1 kid 25700, 2 kids 20800, 3 kids 16500
it('keeps a stored first-year target when the kid count is unchanged')
it('accepts a first-year-only kids plan')
it('infers the chips from stored targets')                            // 123960 -> public_full, 0 -> none, 70000 -> custom
it('flags a plan-input change when the fund switch flips')
it('validates one quiz card at a time')
// goal.page.spec.ts
it('walks the five quiz cards and blocks Next on an invalid card')
it('sets the 529 target from a college chip and the brokerage target from an at-18 chip')
it('shows the cost card before the first-year fund and the account cards')   // DOM order
it('changes the per-child headline with the kid count')               // 1 kid "About $422,000", 3 kids "About $252,000"
it('lists a decided kid with a month and an undecided kid as three years, invested')
it('hides the fund switch and card when every kid is already born')
it('sends firstYearFund, firstYearTarget and per-kid first-year anchors on save')
it('drops the fund from the hero total when it is switched off')
it('has no em dash in the quiz or the cost card')
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/*.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement the form model, the quiz, the cost card and the fund card**

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
