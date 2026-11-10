# Family Plan: Everyday Line Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each kid card on the family plan page shows an Invest group and an Everyday group (FRED's age-band estimate, $0 through year one), the card header and the hero become the kids' whole month, and the page's info cards and inline balance field move into each row's detail sheet.

**Architecture:** Pure math stays in `utils/kid-cost.utils.ts`; the view model (`goal-plan.view-model.ts`) gains `investMonthly` and a per-kid `everyday[]` and keeps every grading, anchor and due-now path on `investMonthly`; the page's `kidCards` computed assembles groups; the existing unit detail sheet grows an editable balance tile, a status tile and an info button that opens one small new sheet. No backend change.

**Tech Stack:** Angular 19 standalone + signals, Ionic 8 (`ModalController`, `BOTTOM_SHEET_MODAL_OPTS`, `SheetDragDismissDirective`), Karma/Jasmine, SCSS (Manrope, material-symbols-outlined).

**Spec:** `docs/superpowers/specs/2026-11-10-family-everyday-plan-design.md`

## Global Constraints

- No em dashes in any user-facing copy or comment text ("—" is banned; the stat-tile sentinel is the only exception).
- "~$1,910" style, never "about"; no AI-written numbers; every figure from deterministic math.
- Everyday figures are FRED estimates; never label the inflation math or the family-size extension as government numbers.
- Baby fund is always cash-planned and shows the Cash pill.
- Status, anchors (`anchorMonthly`), progress, the due-now rule and the How FRED helps figure read `investMonthly`, never the everyday figure.
- No new analytics events or properties; nothing new sent to PostHog.
- Run everything from `/Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/goals-v3` (never the main checkout); write `.sh` helpers into the scratchpad and run them plainly; never `git checkout`/`restore`/`stash` on a dirty tree; commit, never push.
- Karma: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='<glob>'`. The full suite's known failing set is exactly 13 FRED-226 cases (nine `should create`, SecuritySettingsPage gating, TabsPage pager, two swipe-settle); anything else failing is this work's.
- Commit message trailer on every commit:
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_016NWfpgW9CAyp2L2VUh7fGw`.

## Review Focus

1. A kid born this month (age 0, `kidAgeToday` = 0) must price at $0 with "The baby fund covers year one", not the 0-2 band. (Task 1 test `age 0 returns 0`, Task 2 `year_one` state.)
2. A kid exactly 18 at home must drop the Everyday group and still count as zero in the family-size factor. (Task 1 `age 18 returns 0`, `kidsAtHome` excludes 18; Task 2 `grown` state.)
3. A saved family goal re-planned after this ships must write `anchorMonthly` as the invest figure, so a later status read is not graded against a number that includes cash. (Task 3 `draftBody` test.)
4. Invested units due now with a kid at home: the hero must still read "$X due now" and the everyday figure must stay on the card. (Task 2 due-now test.)
5. A house goal's "Update balance" chip must still scroll to its inline tile after the kids path moves into the sheet. (Task 6 test.)

---

### Task 1: Everyday math per kid

**Files:**
- Modify: `frontend/src/app/utils/kid-cost.utils.ts:115-134`
- Test: `frontend/src/app/utils/kid-cost.utils.spec.ts`

**Interfaces:**
- Produces:
  - `export function everydayMonthlyForKid(age: number | null, kidsAtHome: number): number` (nearest $10; 0 for null, 0, or > 17; USDA band annual × `inflationFactor()` × `familySizeFactor(kidsAtHome)` / 12)
  - `export function kidsAtHome(kids: ReadonlyArray<{ birthYear: number | null; birthMonth: number | null }>, now: Date): number` (count of kids whose `kidAgeToday` is 0 to 17 inclusive)
  - `export function everydayRowLabel(age: number): string` ("Age 2 costs")
  - `export function everydaySubLine(age: number): string` (table below)
  - `everydayMonthlyToday(kids, now)` keeps its signature and becomes the sum of `everydayMonthlyForKid` over the kids with `kidsAtHome(kids, now)`.

Sub lines by band (exact copy):

| ages | line |
|---|---|
| 1-2 | Food, clothes, diapers and childcare |
| 3-5 | Food, clothes, preschool and toys |
| 6-8 | Food, clothes, school and activities |
| 9-11 | Food, clothes, sports and school trips |
| 12-14 | Food, clothes, a phone and activities |
| 15-17 | Food, clothes, driving and activities |

- [ ] **Step 1: Write the failing tests** in `kid-cost.utils.spec.ts` (new `describe('everyday per kid')`):

```ts
const NOW = new Date(2026, 10, 10); // 10 Nov 2026

it('prices a 2-year-old only child at $1,910 (12,680 x 1.421586 x 1.27 / 12, nearest $10)', () => {
  expect(everydayMonthlyForKid(2, 1)).toBe(1910);
});
it('prices two kids at home at $1,500 each for the 0-2 band', () => {
  expect(everydayMonthlyForKid(2, 2)).toBe(1500);
});
it('returns 0 for age null, age 0 (the baby fund is year one) and age 18 and up', () => {
  expect(everydayMonthlyForKid(null, 1)).toBe(0);
  expect(everydayMonthlyForKid(0, 1)).toBe(0);
  expect(everydayMonthlyForKid(18, 1)).toBe(0);
});
it('uses each USDA band: 1, 3, 6, 9, 12 and 15 price differently from their neighbours below', () => {
  const bands = [1, 3, 6, 9, 12, 15].map(age => everydayMonthlyForKid(age, 2));
  expect(bands).toEqual([1500, 1510, 1460, 1560, 1540, 1650]);
});
it('counts kids at home as those aged 0 to 17 today', () => {
  expect(kidsAtHome([{ birthYear: 2024, birthMonth: 3 }, { birthYear: 2027, birthMonth: 3 }, { birthYear: 2008, birthMonth: 1 }, { birthYear: 2026, birthMonth: 11 }], NOW)).toBe(2);
});
it('everydayMonthlyToday is the sum of the per-kid figures', () => {
  const kids = [{ birthYear: 2024, birthMonth: 3 }, { birthYear: 2020, birthMonth: 6 }];
  expect(everydayMonthlyToday(kids, NOW)).toBe(everydayMonthlyForKid(2, 2) + everydayMonthlyForKid(6, 2));
});
it('labels and sub lines follow the band', () => {
  expect(everydayRowLabel(2)).toBe('Age 2 costs');
  expect(everydaySubLine(1)).toBe('Food, clothes, diapers and childcare');
  expect(everydaySubLine(5)).toBe('Food, clothes, preschool and toys');
  expect(everydaySubLine(8)).toBe('Food, clothes, school and activities');
  expect(everydaySubLine(11)).toBe('Food, clothes, sports and school trips');
  expect(everydaySubLine(14)).toBe('Food, clothes, a phone and activities');
  expect(everydaySubLine(17)).toBe('Food, clothes, driving and activities');
});
```

Keep the existing `everydayMonthlyToday` pins (`1910` for the one-kid family) and re-derive any that move by the per-kid rounding; note the move in the test name.

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/utils/kid-cost.utils.spec.ts'`
Expected: FAIL, `everydayMonthlyForKid is not exported` (compile error).

- [ ] **Step 3: Implement the four functions and re-base `everydayMonthlyToday`** in `kid-cost.utils.ts`. The band lookup reuses `USDA_2015_ANNUAL_BY_BAND`. Update the `everydayMonthlyToday` doc comment: per-kid rounding, summed.

- [ ] **Step 4: Run to verify they pass** (same command). Expected: all green, including the old pins.

- [ ] **Step 5: Commit**: `feat(goals): everyday cost per kid by age band, with row label and sub line`.

---

### Task 2: View model: `investMonthly`, `everyday[]`, whole-month hero, baby fund always cash

**Files:**
- Modify: `frontend/src/app/pages/goal/goal-plan.view-model.ts:107-141` (interface), `:187-193` (`isDueNowOnly`, `amountLead`), `:226` (`howFredHelpsLine`), `:337-345`, `:415`, `:535-544`, `:584-592` (`firstYearRows`), `:614`, `:679-688`
- Test: `frontend/src/app/pages/goal/goal-plan.view-model.spec.ts`, `frontend/src/app/services/dev/goals-mock.spec.ts`

**Interfaces:**
- Consumes: Task 1's `everydayMonthlyForKid`, `kidsAtHome`, `everydayRowLabel`, `everydaySubLine`, `kidAgeToday`.
- Produces (on `GoalPlanView`):
  - `investMonthly: number` (every type; equals `monthlyTotal` for all but KIDS)
  - `everyday?: EverydayView[]` (KIDS only, one per kid in config order)
  - `export interface EverydayView { monthly: number; label: string; subLine: string; fromLine: string | null; state: 'unborn' | 'year_one' | 'priced' | 'grown' }`
  - `export type HeroLabel = 'Save this month' | 'Due now' | 'Family plan'`
  - KIDS `monthlyTotal = investMonthly + sum(everyday[].monthly)`; `heroLabel` is `'Family plan'` unless due-now (then `'Due now'`).
  - `fromLine`: unborn with a month and year: `From Mar 2027: the baby fund is year one` (use `MONTH_ABBR`-style three-letter month); unborn with no month or undated: `From the day they arrive: the baby fund is year one`; year_one: `The baby fund covers year one`; priced and grown: `null`. `label` is `everydayRowLabel(age)` when priced, else `'Everyday'`.

- [ ] **Step 1: Write the failing tests** in `goal-plan.view-model.spec.ts` (new `describe('everyday line')`, using the file's existing sample-family builders and `NOW`):

```ts
it('prices each kid at home and leaves the rest at 0: unborn, year one, priced, grown', () => {
  // kids: expected Mar 2027 (unborn); born this month (year_one); born 2024 Mar (priced, age 2); born 2008 Jan (grown, 18)
  const view = buildGoalPlanView(kidsGoalWith(fourKids), ctx);
  expect(view.everyday!.map(e => e.state)).toEqual(['unborn', 'year_one', 'priced', 'grown']);
  expect(view.everyday!.map(e => e.monthly)).toEqual([0, 0, everydayMonthlyForKid(2, 2), 0]);
  expect(view.everyday![0].fromLine).toBe('From Mar 2027: the baby fund is year one');
  expect(view.everyday![1].fromLine).toBe('The baby fund covers year one');
  expect(view.everyday![2]).toEqual(jasmine.objectContaining({ label: 'Age 2 costs', subLine: 'Food, clothes, diapers and childcare', fromLine: null }));
});
it('the hero is the whole month: investMonthly plus the everyday sum, labeled Family plan', () => {
  const view = buildGoalPlanView(sampleFamily, ctx);
  const everyday = view.everyday!.reduce((s, e) => s + e.monthly, 0);
  expect(everyday).toBeGreaterThan(0);
  expect(view.monthlyTotal).toBe(view.investMonthly + everyday);
  expect(view.heroLabel).toBe('Family plan');
  expect(view.perPaycheck).toBe(InvestmentFrequencyUtils.toPerPaycheckEquivalent(view.monthlyTotal, ctx.frequency));
});
it('status, progress, due-now and How FRED helps ignore the everyday figure', () => {
  const view = buildGoalPlanView(sampleFamily, ctx);
  const before = /* the same goal with every kid undated, so everyday is 0 */;
  expect(view.status).toEqual(before.status);
  expect(view.progressPct).toBe(before.progressPct);
  expect(view.howFredHelps).toContain(fmtMoney(view.investMonthly));
});
it('invested units due now still read "$X due now" with a kid at home', () => {
  const view = buildGoalPlanView(dueNowFamilyWithKidAtHome, ctx);
  expect(view.heroLabel).toBe('Due now');
  expect(view.headline).toContain('due now');
  expect(view.everyday![0].monthly).toBeGreaterThan(0);
});
it('the baby fund is always cash, even five years out', () => {
  const view = buildGoalPlanView(kidsGoalWith([{ ...kid, birthYear: NOW.getFullYear() + 5, birthMonth: 6 }]), ctx);
  expect(view.firstYear!.rows[0].plan.rateKind).toBe('cash');
  expect(view.firstYear!.rows[0].pill).toBe('Cash');
});
it('simple goals: investMonthly equals monthlyTotal and heroLabel stays Save this month', () => {
  const view = buildGoalPlanView(houseGoal(), ctx);
  expect(view.investMonthly).toBe(view.monthlyTotal);
  expect(view.heroLabel).toBe('Save this month');
  expect(view.everyday).toBeUndefined();
});
```

Re-pin the sample family's `monthlyTotal` figures in this spec and in `goals-mock.spec.ts` (today 2_249 / 1_036 style pins) as `investMonthly` pins plus the new `monthlyTotal`; name the move in the test titles.

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/goal-plan.view-model.spec.ts'`
Expected: FAIL on `everyday` / `investMonthly` being undefined.

- [ ] **Step 3: Implement.** In `kidsView`: compute `ages = kids.map(k => kidAgeToday(...))`, `atHome = kidsAtHome(kids, ctx.now)`, build `everyday[]`; `investMonthly` is today's `monthlyTotal` expression at `:415`; `monthlyTotal = investMonthly + sum`. In `buildGoalPlanView` (`:337-345`): `isDueNowOnly(view.investMonthly, view.dueNow)`; `heroLabel: dueNowOnly ? 'Due now' : view.kids ? 'Family plan' : 'Save this month'`; `perPaycheck` from `monthlyTotal`. `howFredHelpsLine(investMonthly, units)`. The `subtitle` lead (what My Profile's family row shows) is `"$<dueNow> due now"` when `isDueNowOnly(investMonthly, dueNow)`, else `"$<monthlyTotal>/mo"`: the whole month, the same figure as the hero (give `amountLead` a third parameter or split it; one place). Add to the hero test: `expect(view.subtitle).toContain(`${fmtMoney(view.monthlyTotal)}/mo`)`. Keep `coversLine` as is (it already quotes `everydayMonthlyToday`, which Task 1 re-based). Simple and vacation views set `investMonthly: plan.monthly`. In `firstYearRows` replace `rateKind: kidAccountRateKind(anchorDate, due.year, due.month)` with `rateKind: 'cash'` and say why in the comment (Andy, 2026-11-10).

- [ ] **Step 4: Run to verify they pass** (same command, plus `--include='src/app/services/dev/goals-mock.spec.ts'`). Expected: green.

- [ ] **Step 5: Commit**: `feat(goals): the family plan is the kids' whole month; everyday per kid in the view; baby fund always cash`.

---

### Task 3: Page data: card groups, header figure, age on the sub line, invest-only anchor

**Files:**
- Modify: `frontend/src/app/pages/goal/goal.page.ts:51-70` (types), `:257-289` (`kidCards`), `:681-686` (`kidSubLine`), `:879-892` (`draftBody`)
- Test: `frontend/src/app/pages/goal/goal.page.spec.ts`

**Interfaces:**
- Consumes: Task 2's `GoalPlanView.investMonthly`, `everyday[]`, `EverydayView`; `kidAgeToday`.
- Produces:
  - `KidCard` gains `investMonthly: number` (sum of unfinished unit monthlies, today's `monthly`), `everyday: EverydayView`, and `monthly` becomes `investMonthly + everyday.monthly` (the header figure).
  - `kidSubLine(kid)`: `"Born 2024 · age 2"`, `"Born 2026 · under 1"`, unchanged `"This year"`, `"Arrives 2028"`, `"Not dated yet"`.
  - `draftBody` writes `anchorMonthly: view.investMonthly`.

- [ ] **Step 1: Write the failing tests** in `goal.page.spec.ts` (page specs use the file's `create()` and `text()` helpers):

```ts
it('each kid card header is the kid\'s whole month and the cards add up to the hero', async () => {
  const fixture = await create({ id: '3' }, { goals: [kidsGoal(3)] });
  finishCountUp(fixture);
  const page = fixture.componentInstance;
  const cards = page.kidCards();
  cards.forEach(c => expect(c.monthly).toBe(c.investMonthly + c.everyday.monthly));
  expect(cards.reduce((s, c) => s + c.monthly, 0)).toBe(page.view().monthlyTotal);
  expect(text(fixture, '.fd-hero-label')).toBe('Family plan');
});
it('the sub line carries the age: born kids "Born 2024 · age 2", under a year "under 1", unborn unchanged', ...);
it('a new family plan posts anchorMonthly as the invest figure, not the whole month', async () => {
  // preview-new flow: startPlan(); assert the POST body's anchorMonthly === page.view().investMonthly and < page.view().monthlyTotal
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/goal.page.spec.ts'`
Expected: FAIL (`investMonthly`/`everyday` undefined on `KidCard`; hero label "Save this month"; anchor equals monthlyTotal).

- [ ] **Step 3: Implement** the type changes, the `kidCards` additions (`everyday: v.everyday?.[i]`, falling back to `{ monthly: 0, label: 'Everyday', subLine: '', fromLine: null, state: 'grown' }` only if the view has none), `kidSubLine` with `kidAgeToday(kid.birthYear, kid.birthMonth, this.now)` (`age === 0` → "under 1"; `age >= 1` → `age ${age}`), and `draftBody`.

- [ ] **Step 4: Run to verify they pass.** Expected: green.

- [ ] **Step 5: Commit**: `feat(goals): kid cards carry the everyday figure and the age; anchors stay invest-only`.

---

### Task 4: Unit detail sheet: live detail, Saved so far and Status tiles, Everyday variant, info button

**Files:**
- Modify: `frontend/src/app/components/goal-unit-detail-sheet/goal-unit-detail-sheet.component.ts|html|scss`
- Test: `frontend/src/app/components/goal-unit-detail-sheet/goal-unit-detail-sheet.component.spec.ts`

**Interfaces:**
- Produces:
  - `UnitDetail` gains: `kind: 'fund' | 'account' | 'everyday'`; `balance: number | null` (null for everyday); `status: { text: string; tone: 'good' | 'warn' | 'info' | 'muted' } | null` (null for everyday); `title: UnitTitle` widens to include `'Everyday'`.
  - Inputs: `@Input({ required: true }) detail!: Signal<UnitDetail | null>` (replaces the plain object; the sheet renders `detail()` and closes itself if it turns null); `@Input() onBalance: ((value: number) => Promise<void>) | null = null`; `@Input() notes: InfoNote[] = []`.
  - `export interface InfoNote { icon: string; lead: string; text: string }` (declared in Task 5's sheet file; this task imports it, so do Task 5's file first or declare the type in a shared `goal-plan-info-sheet/info-note.ts`).
  - Markup: `.ud-info` round button (34px, #f1f5f9, `info` glyph 19px #2563eb) on the title row, rendered only when `notes.length > 0`, `(click)="openInfo()"` which presents `GoalPlanInfoSheetComponent` with `{ notes }` and `BOTTOM_SHEET_MODAL_OPTS`.
  - Tiles: row one Every month · From your pay (as today); row two `.ud-bal` tile with `<input class="fd-tile-input" type="text" inputmode="numeric" aria-label="Saved so far">` whose blur calls `onBalance(Math.max(0, Math.round(Number(digits))))` (shown when `balance !== null`; read-only text when `onBalance` is null or `finished`), and `.ud-status` tile with `status.text` on one line and a `tone` class (`good` #059669 on #ecfdf5, `warn` #b45309 on #fffbeb, `info` #2563eb on #eef4ff, `muted` #6b7280 on #f1f5f9); shown when `status !== null`. `.ud-tile-val` 19px → 16px; tile padding 9px 11px.
  - Milestone cards unchanged (`.ud-ms-tile`).

- [ ] **Step 1: Write the failing tests** (extend the existing spec; build details with `signal(detail)`):

```ts
it('shows the Saved so far field and the Status word, and saves the balance on blur', fakeAsync(() => {
  const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
  const fixture = mount(signal({ ...nestEgg, balance: 4200, status: { text: 'On plan', tone: 'good' } }), { onBalance });
  expect(input(fixture, '.ud-bal input').value).toBe('4200');
  expect(text(fixture, '.ud-status')).toBe('On plan');
  expect(el(fixture, '.ud-status').classList).toContain('good');
  blur(fixture, '.ud-bal input', '5,000');
  expect(onBalance).toHaveBeenCalledWith(5000);
}));
it('re-renders when the live detail changes', () => { const d = signal(nestEgg); const fixture = mount(d); d.set({ ...nestEgg, monthly: 600 }); fixture.detectChanges(); expect(text(fixture, '.ud-tiles .ud-tile-val')).toContain('$600'); });
it('the Everyday variant has no balance, no status and no milestones, and keeps the sources link', ...);
it('shows the info button only when notes are passed, and opens the info sheet with them', ...); // ModalController spy: create called with component GoalPlanInfoSheetComponent and componentProps.notes
it('a finished fund shows the balance read-only', ...);
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/components/goal-unit-detail-sheet/*.spec.ts'`
Expected: FAIL (inputs and selectors missing).

- [ ] **Step 3: Implement** per the Interfaces block. Digits-only sanitize: strip everything but `0-9`, empty → 0.

- [ ] **Step 4: Run to verify they pass.** Expected: green.

- [ ] **Step 5: Commit**: `feat(goals): the unit sheet takes the balance, shows the status, and carries an info button`.

---

### Task 5: The "How FRED plans this" info sheet

**Files:**
- Create: `frontend/src/app/components/goal-plan-info-sheet/goal-plan-info-sheet.component.ts|html|scss|spec.ts`, `frontend/src/app/components/goal-plan-info-sheet/info-note.ts`

**Interfaces:**
- Produces: `GoalPlanInfoSheetComponent` (standalone, `host: { class: 'ion-content-scroll-host' }`, `hostDirectives: [SheetDragDismissDirective]`, modeled on `KidCostSourcesSheetComponent`) with `@Input({ required: true }) notes!: InfoNote[]`; `InfoNote = { icon: string; lead: string; text: string }` in `info-note.ts`. Markup: handle, `<h3>How FRED plans this</h3>`, `<p class="pi-sub">The same three notes for every unit of the family plan.</p>`, one `.fd-how`-styled card per note (`material-symbols-outlined` icon, `<b>{{ lead }}</b> {{ text }}`), Done button (`.pi-done`, same look as `.ud-done`).

- [ ] **Step 1: Write the failing tests**:

```ts
it('renders one card per note with its icon, lead and text', ...);
it('Done dismisses the modal', ...); // ModalController.dismiss spy
```

- [ ] **Step 2: Run to verify they fail** (component missing).
- [ ] **Step 3: Implement.** Reuse `.fd-how` from `theme/_plan-page.scss` via `@use` where the sheet's scss imports it, or copy its three rules if the theme file is not importable from a component (check how `goal-unit-detail-sheet.component.scss` gets `.fd-tile`).
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit**: `feat(goals): How FRED plans this sheet`.

---

### Task 6: Page markup and wiring: groups, rows open the sheet, hint, inline field and info cards out

**Files:**
- Modify: `frontend/src/app/pages/goal/goal.page.html:54-128` (per-kid block), `:166-184` (`.fd-how` block), `frontend/src/app/pages/goal/goal.page.scss` (groups, row button, hint, remove `.gp-bal*`), `frontend/src/app/pages/goal/goal.page.ts:138` (`balanceOpen`), `:503-539` (`openUnitDetail`, `toggleBalance`, `onChipTap`), `:689-743` (`unitDetail`)
- Test: `frontend/src/app/pages/goal/goal.page.spec.ts`

**Interfaces:**
- Consumes: Task 3's `KidCard.investMonthly / everyday / monthly`; Task 4's `UnitDetail` shape, `detail: Signal`, `onBalance`, `notes`; Task 5's `InfoNote`.
- Produces:
  - Markup per card: header (unchanged, `.gp-kmonthly` shows `card.monthly`); `.gp-group` row `Invest` with `.gp-group-total` `fmtMoney(card.investMonthly)`; one `<button class="gp-krow">` per unit (the whole row; keeps `.gp-kic`, `.gp-ktx` with `.gp-klabel` and `.gp-ktarget` now as spans, the amount `em`); then, unless `card.everyday.state === 'grown'`, `.gp-group.second` `Everyday` with the total when priced, and either the everyday `<button class="gp-krow">` (`shopping_basket`, `card.everyday.label`, Cash pill, `card.everyday.subLine`, amount) or `<p class="gp-from">{{ card.everyday.fromLine }}</p>`.
  - Zone label: `<span>Per kid <span class="gp-hint">Tap for details</span></span>` (`.gp-hint`: 9.5px, 700, uppercase, 0.06em, #b3bccb, margin-left 8px).
  - `.gp-group`: 9px 800 uppercase 0.1em #9aa6ba, flex space-between, margin-top 10px; `.gp-group-total`: 13px 800 #2563eb tabular; `.gp-group.second`: border-top 1px #eef1f6, padding-top 10px. Unit `em` color #111827.
  - `openUnitDetail(kidIndex, unit: KidBalanceKey | 'everyday')` presents the sheet with `detail: computed(() => this.unitDetail(kidIndex, unit))`, `onBalance: unit === 'everyday' ? null : (value) => this.updateBalance({ kid: kidIndex, account: unit }, value)`, `notes: this.infoNotes()` where `infoNotes` is a computed `InfoNote[]` from `view().howFredHelps` (`account_balance_wallet`, "How FRED helps."), `coversLine` (`checklist`, "What the monthly covers."), `whyInvestLine` (`trending_up`, "Why invest it."), skipping nulls.
  - `unitDetail` adds `kind`, `balance` (`row.balance`; null for everyday), `status: { text: this.statusChip(), tone }` with tone by chip: Ahead / On plan / Done → good, Behind → warn, Update balance → info, Plan started / Plan updated → muted; the everyday case: title `'Everyday'`, targetLine `` `Age ${age} · FRED estimate` ``, notes `[subLine + '.', 'Steps up on its own at 3, 6, 9, 12 and 15, the ages where the USDA figures change.']`, sourcesLine `` `FRED estimate: USDA 2015 figures for a middle-income family, adjusted to ${kidCostFigures(atHome).asOfLabel} dollars and to ${atHome} kid${atHome === 1 ? '' : 's'} at home.` ``, `sourcesSheetKids: atHome`, milestones `[]`.
  - Remove: `balanceOpen`, `toggleBalance`, `balanceKey`, the kids branch of `onBalanceBlur` (the simple/vacation tile keeps `onBalanceBlur('current' | 'vacation', $event)`), the `.gp-bal*` markup and styles. `onChipTap`: kids goal → `openUnitDetail(card.index, firstUnfinished.key)` then focus `.ud-bal input` after present; other types → today's scroll-and-focus.
  - The `.fd-how` block renders only when `!v.kids` (simple goals keep it).

- [ ] **Step 1: Write the failing tests**:

```ts
it('renders Invest and Everyday groups with blue totals, black unit amounts, and the Tap for details hint', ...); // .gp-group-total texts; getComputedStyle color checks or class presence
it('tapping a unit row opens its sheet with a live detail and a balance hook; no inline Saved so far field exists', ...); // ModalController spy: componentProps.detail is a function returning the Nest egg detail; onBalance defined; querySelector('.gp-bal-input') null
it('tapping the everyday row opens the Everyday sheet with no balance hook', ...);
it('the Update balance chip opens the sheet for the first unfinished unit of the active kid', ...);
it('a house goal\'s chip still scrolls to its inline Saved so far tile', ...);
it('the three info cards are gone from a kids plan and still shown on a house plan', ...); // .fd-how count 0 vs 2
it('an unborn kid shows the from line in place of an everyday row', ...);
```
Delete the tests that covered the inline kids balance field and `toggleBalance`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/goal.page.spec.ts'`
Expected: FAIL on the new selectors; the deleted tests no longer run.

- [ ] **Step 3: Implement** per the Interfaces block. Nested interactive elements: the row is a `<button>`, so `.gp-klabel` / `.gp-ktarget` become `<span>`s.

- [ ] **Step 4: Run to verify they pass**, then the whole goals folder: `--include='src/app/pages/goal/**/*.spec.ts'`. Expected: green.

- [ ] **Step 5: Commit**: `feat(goals): groups on the kid card; rows open the sheet; info cards and inline balance leave the page`.

---

### Task 7: Dev mock, docs, backlog, gates

**Files:**
- Modify: `frontend/src/app/services/dev/goals-mock.ts:67-80` (+spec), `FREDdocs/kid-cost-figures.md`, `docs/superpowers/specs/2026-11-06-goals-quiz-and-plan-page-redesign.md` (dated notes under the hero and per-kid sections pointing to the 2026-11-10 design), `FREDdocs/backlog.md` (one story through the `backlog-add` skill)

- [ ] **Step 1: Mock family**: make the second mock kid expected next March (`birthYear: year + 1, birthMonth: 3`) so the mock shows one priced kid and one "From Mar <year+1>" line; update `goals-mock.spec.ts` pins.
- [ ] **Step 2: Docs**: in `kid-cost-figures.md` add "Everyday figure per kid" (formula, per-kid rounding, the sub-line table, where shown) and note the baby fund is always cash; add the two dated blockquotes to the 2026-11-06 spec.
- [ ] **Step 3: Backlog**: `backlog-add` one FRED story "Family plan everyday line" with the design path as its spec.
- [ ] **Step 4: Gates**: `npm run lint` ("All files pass linting."), `npx ng build --configuration dev` (0 errors), `npm run test:events-documented` (PASS), em-dash sweep (`grep -rn "—" frontend/src/app/pages/goal frontend/src/app/components/goal-unit-detail-sheet frontend/src/app/components/goal-plan-info-sheet frontend/src/app/utils/kid-cost.utils.ts | grep -v "'—'"` prints nothing), full Karma (`TOTAL: 13 FAILED` with exactly the known set).
- [ ] **Step 5: Commit**: `chore(goals): mock family, kid-cost doc and backlog story for the everyday line`.
