# Goals v3.1 Quiz and Plan Page Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the goal setup cards and the dense plan page with a Monte Carlo blue quiz (one question per screen, no keyboard) and a one-screen plan page modeled on the Freedom Plan sheet, and open the Freedom Date the same way.

**Architecture:** The quiz is a pushed route (`/goals/new/:type`, `/goals/:id/edit`) that fills a `GoalForm` draft held in a `GoalDraftService` signal, then pushes the plan page in preview mode; Start/Update POST/PUT and replace the URL. The plan page is a thin template over `buildGoalPlanView`, which grows a timeline model, pills, the audience phrase and the gift-days payoff. The backend gains one boolean (`giftDays`) and two widened bounds; the math replaces birthday/New Year rituals with a flat $1,000 January lump.

**Tech Stack:** Angular 19 standalone + Ionic 8 (signals, Swiper pager, WAAPI), Karma/Jasmine (`npx ng test --watch=false --browsers=ChromeHeadless --include=<spec>`), Spring Boot 3 + JUnit 5 (`./gradlew test --tests <Class>`), `@capacitor/haptics`.

**Spec:** `docs/superpowers/specs/2026-11-06-goals-quiz-and-plan-page-redesign.md` (amends `2026-11-02-goals-v3-plans-design.md` and `2026-11-05-goals-v3-kid-costs-addendum.md`). Mockup: `docs/superpowers/specs/assets/2026-11-06-goals-redesign-mockup.html`.

## Global Constraints

- No em dashes in any user-facing copy, comments included.
- Titles fit on one line at 390px; copy breaks only at sentence boundaries; use "~" for approximations ("~$1,540"), never "about" before a dollar figure; the big reveal number on the cost screen has no tilde.
- Every quiz screen has a title and a one-line subtitle; all titles share one class (27px, weight 800). Nothing per-step overrides it.
- No keyboard opens unless the user taps a big readout (tap-to-type) or a name pen / balance field on the plan page.
- Light-only, Manrope, `material-symbols-outlined` from the subset font; any icon name returned from TypeScript is added to `DYNAMIC_ICONS` in `frontend/scripts/subset-icons.mjs`.
- Analytics: no new events or properties. `goal_changed` and `paywall_nudge_shown` keep their current shapes.
- Plan math conventions stay: ordinary annuity, `r = annualRate/12`, cash under 36 months (`CASH_HORIZON_MONTHS`), proposed amounts round up, status graded only after a logged balance, server re-anchors on plan-input change (kid `label` is not a plan input; `giftDays` is).
- Must-play fades use `element.animate()` (WAAPI), never CSS transitions on insert; honor `prefers-reduced-motion` by skipping count-ups and crossfades, not by skipping the screens.
- Karma gate: only the 14 known FRED-226 failures (nine `should create` smoke specs, FreedomDetailSheet 4% rule [deleted by Task 7], SecuritySettingsPage gating, TabsPage pager, two swipe-settle). Backend gate: `./gradlew test` green. Also `npx ng build --configuration dev`, `npm run lint`, `npm run test:events-documented`.
- Commit after every task; never push; never `git checkout`/`restore`/`stash` on the shared tree. This worktree's branch is `feature/goals-v3-wt`.

## Review Focus

1. A kids plan where every kid is already born: the Baby fund row must not appear on step 4, the dots must count 5 steps, and the plan page shows no baby fund rows. Test in Task 4 (`quizStepsFor` with all-born kids) and Task 5 (view with no unborn kid has no `fund` rows).
2. A nest egg at the slider minimum ($10,000) with gift days: the offer must be absent, never a $0 monthly. Test in Task 2 (`proposeKidAccountPlan` → `giftDaysAvailable === false`, `monthly > 0`).
3. Edit plan on a stored goal, change nothing, Update: the PUT body must equal a round trip of the stored config (no re-anchor, kid `label`s and balances preserved). Test in Task 4 (`formFromGoal` → `formToConfig` round trip equals the stored config for every type).
4. Rename a kid while a balance save is in flight: the label PUT must carry the latest balance and the balance PUT must not revert the label (both chained through `pendingWrite`). Test in Task 6 (page spec: rename during a pending `updateBalance`).
5. The Freedom page opened from the tour row: the `profileFreedom` step must complete on return to my-profile, not on arrival, and the overlay must be hidden while the page is up. Test in Task 7 (tour service spec + Freedom page spec).

---

### Task 1: Shared blue-flow styles and the quiz controls

**Files:**
- Create: `frontend/src/theme/_blue-flow.scss`
- Modify: `frontend/src/app/components/monte-carlo-flow/monte-carlo-flow.component.scss` (replace the duplicated rules with `@use 'src/theme/blue-flow'` and keep only Monte-Carlo-specific rules)
- Create: `frontend/src/app/components/quiz-controls/quiz-slider.component.ts|html|scss|spec.ts`
- Create: `frontend/src/app/components/quiz-controls/quiz-chips.component.ts|spec.ts`
- Create: `frontend/src/app/components/quiz-controls/quiz-stepper.component.ts|spec.ts`
- Create: `frontend/src/app/components/quiz-controls/quiz-switch-row.component.ts|spec.ts`
- Create: `frontend/src/app/components/quiz-controls/quiz-pick-grid.component.ts|spec.ts`
- Create: `frontend/src/app/components/quiz-controls/haptics.ts`

**Interfaces:**
- Consumes: the `.flow-host` tokens and classes in `monte-carlo-flow.component.scss` lines 17-370 (`--sim-deep`, `--sim-mid`, `--glass`, `--glass-border`, `--mint`, `--amber`, `.flow-top`, `.flow-back`, `.flow-x`, `.flow-dots`, `.fscreen`, `.fs-eyebrow`, `.fs-h1`, `.fs-sub`, `.fs-center`, `.big-readout`, `.big-input`, `.readout-hint`, `.sim-range`, `.range-ends`, `.fetch-chip`, `.fs-cta`, `.glass-field`).
- Produces:
  - `_blue-flow.scss` exporting those classes unchanged, plus `.fs-h1` at 27px inside `.quiz-host` (the Monte Carlo host keeps 30px), `.glass`, `.bf-pick`, `.bf-pick.on`, `.bf-switch`, `.bf-switch.on`, `.bf-row` (the compact glass row), `.bf-stepper`.
  - `QuizSliderComponent` selector `app-quiz-slider`: inputs `value: number`, `min: number`, `max: number`, `step: number`, `scale: 'linear' | 'log'` (default linear), `format: (v: number) => string`, `hint: string`, `endLabels: [string, string]`, `tapToType = true`; output `valueChange: EventEmitter<number>`. Renders `.big-readout` (button) or `.big-input` while typing, the `.sim-range`, `.range-ends`. Log scale maps a 0..1000 track to `min * (max/min)^(t/1000)` and snaps to `step`.
  - `QuizChipsComponent` selector `app-quiz-chips`: inputs `options: { label: string; value: T }[]`, `value: T`; output `valueChange`.
  - `QuizStepperComponent` selector `app-quiz-stepper`: inputs `value`, `min`, `max`, `format?`; output `valueChange`; the plus button is `disabled` at `max`, minus at `min`.
  - `QuizSwitchRowComponent` selector `app-quiz-switch-row`: inputs `icon: string`, `title: string`, `line: string`, `on: boolean`, `lineTone: 'normal' | 'saves'` (green when `saves`); output `onChange: EventEmitter<boolean>`; renders `role="switch"` with `aria-checked`.
  - `QuizPickGridComponent` selector `app-quiz-pick-grid`: inputs `options: { icon: string; label: string; amount: number; value: T }[]`, `value: T`; output `valueChange`; renders a 2×2 grid of `.bf-pick` buttons with `aria-pressed`.
  - `haptics.ts`: `export async function tickHaptic(): Promise<void>` calling `Haptics.impact({ style: ImpactStyle.Light })` only when `Capacitor.isNativePlatform()`, swallowing errors.

- [ ] **Step 1: Write the failing specs** (`quiz-slider.component.spec.ts`, `quiz-stepper.component.spec.ts`, `quiz-switch-row.component.spec.ts`, `quiz-pick-grid.component.spec.ts`)

```ts
it('log scale maps the track end points to min and max and snaps to step', () => {
  // min 100000, max 3000000, step 5000: track 0 -> 100000, track 1000 -> 3000000, track 500 -> 547720 snapped to 550000
});
it('tapping the readout shows the input, blur commits the typed value and emits it', () => {});
it('emits valueChange on input and formats the readout with the given formatter', () => {});
// stepper
it('disables plus at max and minus at min', () => {});
// switch row
it('renders role=switch with aria-checked and emits onChange on click', () => {});
it('applies the saves tone class to the line when lineTone is saves', () => {});
// pick grid
it('marks the selected option aria-pressed and emits the value of a tapped option', () => {});
```

- [ ] **Step 2: Run the specs, confirm they fail** (`--include='src/app/components/quiz-controls/**/*.spec.ts'`; expected: compile errors, components not found)

- [ ] **Step 3: Create `_blue-flow.scss`** by moving the rules named above out of `monte-carlo-flow.component.scss` verbatim, then have that file `@use 'src/theme/blue-flow'`; add the `.quiz-host .fs-h1 { font-size: 27px }` override and the new `.bf-*` classes using the mockup's values (`.glass`: `rgba(255,255,255,.09)` fill, `.16` border, radius 16; `.bf-pick.on`: white fill, `#1d4ed8` text; `.bf-switch`: 46×28, knob 22, on = `--mint`).

- [ ] **Step 4: Implement the five components and `haptics.ts`** per the Interfaces block; call `tickHaptic()` from the slider's `input` handler only when the snapped value changed.

- [ ] **Step 5: Run the specs, confirm they pass; run the Monte Carlo analytics spec to confirm the style move broke nothing** (`--include='src/app/components/monte-carlo-flow/*.spec.ts'`)

- [ ] **Step 6: Build and lint** (`npx ng build --configuration dev`, `npm run lint`); expected exit 0.

- [ ] **Step 7: Commit** `feat(goals): shared blue-flow styles and quiz controls`

---

### Task 2: Backend: `giftDays`, kids cap 5, trips 1..10

**Files:**
- Modify: `backend/src/main/java/com/investingapp/backend/model/goals/KidsConfig.java:11` (add `Boolean giftDays` as the last component)
- Modify: `backend/src/main/java/com/investingapp/backend/service/GoalService.java:332-333` (cap 5, message "Add between 1 and 5 kids."), `:446` (trips 1..10, message "Pick 1 to 10 trips a year."), the KIDS branch of `planInputsChanged` (`giftDays` is a plan input; treat null as false)
- Test: `backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java`, `.../model/goals/GoalConfigCodecTest.java`

**Interfaces:**
- Produces: `KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids, Boolean firstYearFund, Boolean giftDays)`; a missing `giftDays` parses as null and is re-serialized as `false`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void kidsConfigWithoutGiftDaysParsesAndReserializesFalse()   // codec: old JSON lacks the field -> giftDays false in output
@Test void sixKidsAreRejected()                                      // GoalValidationException "Add between 1 and 5 kids."
@Test void fiveKidsAreAccepted()
@Test void elevenTripsAreRejectedAndTenAccepted()
@Test void flippingGiftDaysIsAPlanInputChangeAndReanchors()          // update with only giftDays toggled -> planAnchoredAt moves, anchor balances reset
@Test void nullAndFalseGiftDaysAreTheSameForReanchoring()           // stored null, request false -> no re-anchor
```

- [ ] **Step 2: Run them, confirm they fail** (`./gradlew test --tests '*GoalServiceTest' --tests '*GoalConfigCodecTest'`)

- [ ] **Step 3: Implement** the record component, the two bounds, and the `planInputsChanged` clause (`!Objects.equals(Boolean.TRUE.equals(a.giftDays()), Boolean.TRUE.equals(b.giftDays()))`); in the codec's KIDS re-serialization write `giftDays` as `Boolean.TRUE.equals(...)`.

- [ ] **Step 4: Run the full backend suite** (`./gradlew test`); expected BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** `feat(goals): giftDays flag, five-kid cap, ten trips a year`

---

### Task 3: Math and models: January lump, cost factors for 4 and 5 kids, savings ranges, audience phrase

**Files:**
- Modify: `frontend/src/app/models/goal.model.ts` (`KidsConfig.giftDays?: boolean | null`; `VacationConfig.tripsPerYear: number`)
- Modify: `frontend/src/app/utils/goal-plan.utils.ts` (+ spec)
- Modify: `frontend/src/app/utils/kid-cost.utils.ts`, `frontend/src/app/constants/kid-costs.ts` (+ spec)
- Create: `frontend/src/app/utils/goal-quiz.utils.ts` (+ spec)
- Modify: `frontend/src/app/pages/goal/goal-form.model.ts` (`MAX_KIDS = 5`; `KidsForm.giftDays: boolean`; `GoalForm.tripsPerYear: number`; `defaultFirstYearFund` returns true when any kid is unborn; `formToConfig`/`formFromGoal` carry `giftDays`) (+ spec)
- Modify: `FREDdocs/kid-cost-figures.md` (4/5 factors, "FRED estimate", the January lump)

**Interfaces:**
- Produces, in `goal-plan.utils.ts`:
  - `export const GIFT_DAY_LUMP = 1000;` `export const GIFT_MIN_MONTHS = 24;` (delete `RITUAL_MIN_MONTHS`, `RITUAL_MIN_ANNUAL`, `friendlyRound`, `lowerOneNotch`, `KidLumpSchedule`).
  - `export function kidLumpSchedule(n: number, now: Date): number[]` (January indices in 1..n).
  - `export interface AccountPlan { n; rateKind; rate; monthly; newYearLump: number; lumps: LumpOccurrence[]; giftDaysAvailable: boolean; dueNow; deadline; assumedBirthYear; milestones }` (drop `birthdayLump`, `ritualsReason`).
  - `proposeKidAccountPlan(input: { target; balance; birthYear; birthMonth; marketRate; rateKindOverride; now; giftDays: boolean })`: `giftDaysAvailable = n >= GIFT_MIN_MONTHS && target - futureValue(0, 0, n, rate, januaryLumpsOf(1000)) > 0`; lumps and `newYearLump = 1000` only when `giftDays && giftDaysAvailable`, else `0` and `[]`; monthly solved net of lumps as before.
- Produces, in `kid-cost.utils.ts`: `familySizeFactor(kidsCount)` returns 1.27 / 1.00 / 0.76 / 0.64 / 0.568 for 1..5 (5 for anything higher); `export function firstYearFactor(rank: number): number` returning 1.27 / 1.00 / 0.76 (rank ≥ 3 → 0.76) used by `firstYearTargetForRank` (pins unchanged: 25,700 / 20,800 / 16,500); `export function perKidMonthly(kidsCount: number): number` (per-kid total ÷ 216, rounded to $10); `export function familyMonthly(kidsCount: number): number` (per-kid × count ÷ 216, rounded to $10); `FamilySizeWording` gains `'four' | 'five'`.
- Produces, in `goal-quiz.utils.ts`:
  - `export type FundKind = 'baby' | 'college' | 'nest';`
  - `export function fundSavingsRange(kind: FundKind, kids: ReadonlyArray<{ birthYear: number | null; birthMonth: number | null }>, now: Date, annualRate: number): { low: number; high: number }`: baby = Σ unborn kids' `firstYearTargetForRank` (low = high); college = Σ over kids of `requiredMonthly(0, T, n, rate) × n` for T = 47,800 (low) and 123,960 (high); nest = same for T = 50,000 and 250,000; `n = horizonMonths(now, kidDeadline(...))`, an undated kid counts as born this year; each end rounded to $5,000 when ≥ $20,000 else $1,000.
  - `export function formatSavings(r: { low: number; high: number }): string` → `"$37K"`, `"$50K to $135K"`, `"$1.2M"` for ≥ $1,000,000 (one decimal, trailing `.0` dropped).
  - `export function costLead(kidsCount: number): string` → `"That's about"`, `"For both kids, that's about"`, `"For all 3 kids, that's about"`.
  - `export function audienceLabel(kids: ReadonlyArray<{ label: string }>): string` → `"for Ellie"` (one kid, its label), `"for both kids"`, `"for all 3 kids"`.
  - `export function kidYearBounds(now: Date): { min: number; max: number }` → `{ min: year - 17, max: year + 10 }`.
  - `export function kidYearLabel(value: number | null, now: Date): string` → `"Not sure"` for null, `"Born 2024"` below this year, `"This year"`, else the year.

- [ ] **Step 1: Write the failing specs**

```ts
// goal-plan.utils.spec.ts
it('kidLumpSchedule returns every January index inside 1..n', () => { /* now = 2026-11, n = 26 -> [2, 14, 26] */ });
it('proposeKidAccountPlan with giftDays posts $1,000 each January and solves a lower monthly', () => {
  /* target 250000, born 2026 (July), n 216 at 10%: giftDays false -> monthly 417; giftDays true -> newYearLump 1000, monthly < 417, futureValue(...) >= 250000 */
});
it('giftDaysAvailable is false when the lumps alone reach the target', () => { /* target 10000, n 216 -> giftDaysAvailable false, newYearLump 0, monthly > 0 even with giftDays true */ });
it('giftDaysAvailable is false under 24 months', () => {});
it('no birthday lumps exist anywhere in the schedule', () => { /* every lump monthIndex is a January */ });
// kid-cost.utils.spec.ts
it('per-kid totals for 1..5 kids are 421763, 332097, 252393, 212542, 188631 within $1', () => {});
it('firstYearTargetForRank keeps 25700, 20800, 16500 and 16500 for rank 5', () => {});
it('familyMonthly(3) is 3510 and perKidMonthly(2) is 1540', () => {});
// goal-quiz.utils.spec.ts
it('baby savings sums the unborn kids rank targets: born 2024, 2028, undated -> 37300', () => {});
it('college savings for the sample family is 50K to 135K and nest 55K to 275K', () => { /* now 2026-11, 10% */ });
it('formatSavings collapses equal ends and uses K and M', () => {});
it('audienceLabel: one kid uses its label, two is both kids, three is all 3 kids', () => {});
it('kidYearBounds follows the clock: 2026 -> 2009..2036, 2027 -> 2010..2037', () => {});
// goal-form.model.spec.ts
it('MAX_KIDS is 5 and defaultFirstYearFund is true whenever a kid is unborn', () => {});
it('formToConfig carries giftDays and formFromGoal reads a missing giftDays as false', () => {});
```

- [ ] **Step 2: Run them, confirm they fail** (`--include='src/app/utils/*.spec.ts' --include='src/app/pages/goal/goal-form.model.spec.ts'`)

- [ ] **Step 3: Implement** per the Interfaces block. Delete the ritual pins in `goal-plan.utils.spec.ts` (the $296 / $1,000 / $500 example, the 35% share test, the shrink-loop tests) and replace with the gift-days pins above. Update the pinned worked example in `2026-11-02-goals-v3-plans-design.md` with one line pointing to this plan's pins.

- [ ] **Step 4: Run the four spec files, confirm they pass**

- [ ] **Step 5: Update `FREDdocs/kid-cost-figures.md`**: the 0.64 / 0.568 factors with the derivation (third-child marginal 0.28 applied to the fourth and fifth), labeled "FRED estimate"; the $1,000 January lump replacing the rituals.

- [ ] **Step 6: Commit** `feat(goals): January gift lump, cost factors for four and five kids, savings ranges`

---

### Task 4: The quiz: draft service, step definitions, Kids steps, other types, calc screen, routes

**Files:**
- Create: `frontend/src/app/services/goal-draft.service.ts` (+ spec)
- Create: `frontend/src/app/pages/goal-quiz/goal-quiz.steps.ts` (+ spec)
- Create: `frontend/src/app/pages/goal-quiz/goal-quiz.page.ts|html|scss|spec.ts`
- Create: `frontend/src/app/pages/goal-quiz/goal-quiz-calc.component.ts|html|scss` (the calc screen)
- Modify: `frontend/src/app/app.routes.ts:114-121` (add `goals/:id/edit`; `goals/new/:type` points at `GoalQuizPage`; `goals/:id` stays on `GoalPage`)
- Modify: `frontend/src/app/pages/goal/goal-form.model.ts` (`validateForm` already covers the fields; add `export function draftFromGoal(goal: Goal): GoalForm` as an alias of `formFromGoal` if the name helps readability, otherwise reuse `formFromGoal`)

**Interfaces:**
- Consumes: `GoalForm`, `defaultForm(type, now)`, `formFromGoal(goal)`, `formToConfig(form, existing, now)`, `validateForm(form, now, existing)` from `goal-form.model.ts`; Task 1 controls; Task 3 utils (`fundSavingsRange`, `formatSavings`, `costLead`, `kidYearBounds`, `kidYearLabel`, `perKidMonthly`, `familyMonthly`, `kidCostFigures`, `collegePresets`); `GoalsService.goals`.
- Produces:
  - `GoalDraftService` (providedIn root): `readonly draft = signal<{ form: GoalForm; existing: Goal | null } | null>(null)`, `set(form, existing)`, `clear()`.
  - `goal-quiz.steps.ts`: `export type QuizStepId = 'kids_count' | 'kids_when' | 'kids_cost' | 'kids_fund' | 'kids_college' | 'kids_nest' | 'house_price' | 'house_terms' | 'car_price' | 'car_terms' | 'vacation' | 'emergency' | 'wedding' | 'custom';` `export interface QuizStep { id: QuizStepId; eyebrow: string; title: string; subtitle: string; }` `export function quizStepsFor(form: GoalForm, now: Date): QuizStep[]` (Kids: count, when, cost, fund, college only when `collegeChoice !== 'none'`, nest only when `at18Choice !== 'none'`; the fund step is always present but its Baby fund row is hidden when no kid is unborn). Titles and subtitles are the spec's strings verbatim.
  - `GoalQuizPage`: reads `:type` or `:id`; `form = signal<GoalForm>`, `stepIndex = signal(0)`, `steps = computed(() => quizStepsFor(form(), now))`; `isLast = computed(...)`; `next()`, `back()`, `close()`; on the last step `showPlan()` → `draftService.set(form(), existing)` → shows the calc screen for 900..2500ms → `navCtrl.navigateForward` to `/goals/new/:type/plan` (new) or `/goals/:id` (edit); the plan page reads the draft from the service and renders preview mode whenever a draft is present (Task 6 defines the route and the mode). Close on new → `navigateBack('/my-profile')`; close on edit → `navigateBack(['/goals', id])`.
  - Kids step bodies (templates inside `goal-quiz.page.html`, one `<ng-container>` per step id): count = icon row + `app-quiz-stepper` (1..5, note at 5); when = one `.bf-row` per kid with an inline `<input type="range">` bound to `kidYearBounds`, value label from `kidYearLabel`, `undecided` set when the slider passes `max`; cost = the crossfade pair (`statA` general, `statB` family monthly), `animateCrossfade()` with WAAPI after 2200ms and on tap; fund = three `app-quiz-switch-row`s whose off-state line is `'Saves you ' + formatSavings(fundSavingsRange(kind, kids, now, rate)) + '.'`; college = `app-quiz-pick-grid` with `collegePresets()` + skip, and the "Set my own amount" link swapping to an `app-quiz-slider` ($5,000..$300,000 step $1,000); nest = `app-quiz-slider` ($0..$500,000 step $10,000, default 100,000) with the live chip `requiredMonthly(0, v, 216, rate)`.
  - Other types: steps per the spec's "The other quizzes" list, built from the same controls; emergency has the `By` chips 1/2/3 years writing `targetYear = year + n`; custom has the icon chip row from `CUSTOM_ICON_OPTIONS`.
  - CTA: `'Continue'` or `'Show my plan'` with `auto_awesome` on `isLast()`.

- [ ] **Step 1: Write the failing specs**

```ts
// goal-quiz.steps.spec.ts
it('kids steps are count, when, cost, fund, college, nest by default', () => {});
it('college off removes kids_college and nest off removes kids_nest; both off makes kids_fund last', () => {});
it('house has price and terms; vacation, emergency, wedding and custom have one step each', () => {});
it('every step title is at most 24 characters and every subtitle at most 60', () => {}); // the one-line proxy
// goal-draft.service.spec.ts
it('set stores the form and existing goal; clear empties it', () => {});
// goal-quiz.page.spec.ts
it('starts at the type default form for /goals/new/kids and at formFromGoal for /goals/:id/edit', () => {});
it('the Baby fund row is hidden when every kid is born', () => {});
it('turning College off on step 4 changes the CTA of the new last step to Show my plan with the icon', () => {});
it('the when-step slider past the end marks the kid undecided and labels it Not sure', () => {});
it('the cost screen shows the general stat first and the family monthly after the crossfade; tap flips back', () => { /* fake timers; reduced motion path swaps without animate() */ });
it('Show my plan stores the draft, shows the calc screen for at least 900ms, then navigates to the plan preview route', () => {});
it('close on a new quiz navigates back to /my-profile without touching the draft service', () => {});
it('edit round trip: formFromGoal then formToConfig equals the stored config for KIDS, HOUSE, VACATION, EMERGENCY_FUND, WEDDING, CUSTOM', () => {});
```

- [ ] **Step 2: Run them, confirm they fail**

- [ ] **Step 3: Implement** the service, the step table, the page and the calc component per the Interfaces block; add the routes. The page's host gets `class="quiz-host flow-host"` so Task 1's styles apply; `appKeyboardAvoid` is not needed (no fields) except around the big-input, which the Monte Carlo pattern already handles.

- [ ] **Step 4: Run the specs, confirm they pass; build; lint**

- [ ] **Step 5: Commit** `feat(goals): the blue quiz flow for every goal type`

---

### Task 5: View model: timeline, pills, hero copy, gift-days payoff, "this month"

**Files:**
- Modify: `frontend/src/app/pages/goal/goal-plan.view-model.ts` (+ spec)
- Create: `frontend/src/app/pages/goal/kid-timeline.model.ts` (+ spec)

**Interfaces:**
- Consumes: Task 3 `audienceLabel`, `proposeKidAccountPlan` (with `giftDays`), `GIFT_DAY_LUMP`; existing `planStatus`, `toPerPaycheckEquivalent`.
- Produces:
  - `kid-timeline.model.ts`: `export interface TimelineDot { kind: 'kid' | 'now' | 'end'; label: string; year: number | null; undated: boolean; passed: boolean; position: number /* 0..100 */ }` and `export function buildKidTimeline(kids: ReadonlyArray<{ label: string; birthYear: number | null }>, now: Date): { dots: TimelineDot[]; passedPct: number }`. Scale: `t = years from the earliest event`; the first ten years map to 0..65% linearly, the rest to 65..100% linearly to the last 18th birthday; undated kids sit at this year with `undated: true`; the end dot's label is `"All 18"` (one kid: `"18"`) with the year.
  - `GoalPlanView` gains: `heroLabel: 'Save this month' | 'Due now'`, `audience: string` (`"for all 3 kids"`, `"for your home"`, `"for your car"`, `"for N trips a year"`, `"for your safety net"`, `"for the wedding"`, or the goal name), `timeline?: ReturnType<typeof buildKidTimeline>` (KIDS only), `giftDays?: { on: boolean; available: boolean; lump: number; monthlyIfOn: number; perPaycheckIfOn: number | null }` (KIDS, when any kid unit has `giftDaysAvailable`), `howFredHelps: string` (the three variants, with `fmtMoney(monthlyTotal)` substituted), and each `KidPlanRow`/`FirstYearRow` gains `pill: 'Invested' | 'Cash'`; simple views gain `pill` on the view.
  - `subtitle` for KIDS becomes `${lead} ${audience}` (`"$2,830/mo for all 3 kids"`); other types `${lead} ${audience}` likewise.
  - Remove `reasonLine` and `kidCosts` from the view (the page no longer shows them); `relative` stays for the detail sheet.

- [ ] **Step 1: Write the failing specs**

```ts
// kid-timeline.model.spec.ts
it('born 2024, 2028, undated at 2026-11: dots are Kid 1 (0, passed), Now, Kid 3 (undated, this year), Kid 2, All 18 2046 (100)', () => {});
it('the first ten years take 65% of the width', () => { /* an event 10 years after the earliest sits at 65 */ });
it('everyone born: passedPct equals Now position and no undated dots', () => {});
// goal-plan.view-model.spec.ts
it('KIDS subtitle reads "$X/mo for all 3 kids" and audience uses a single kid label', () => {});
it('giftDays payoff: monthlyIfOn is lower by about $1,000 × kids ÷ 12 and perPaycheckIfOn follows', () => {});
it('giftDays is absent when no kid unit has giftDaysAvailable', () => {});
it('pills: college and nest egg are Invested at 18 years out, a baby fund due in 19 months is Cash', () => {});
it('howFredHelps: all invested, mixed (names the baby funds), all cash (vacation)', () => {});
it('a plan with no unborn kid has no fund rows and no timeline undated dots', () => {});
```

- [ ] **Step 2: Run them, confirm they fail**

- [ ] **Step 3: Implement** per the Interfaces block; `howFredHelps` strings verbatim from the spec.

- [ ] **Step 4: Run the two spec files, confirm they pass**

- [ ] **Step 5: Commit** `feat(goals): plan view model for the one-screen page`

---

### Task 6: The plan page: hero, bands, per-kid pager, rename in place, inline balances, menu, preview

**Files:**
- Rewrite: `frontend/src/app/pages/goal/goal.page.ts|html|scss|spec.ts` (delete the setup/quiz half: `quizCard`, `setupStep`, the five cards, the simple-goal form; keep `updateBalance`, `pendingWrite`, `handleWriteError`, `markCompleted`, `confirmRemove`, the tier nudge, analytics)
- Create: `frontend/src/app/components/kid-timeline/kid-timeline.component.ts|html|scss` (renders `buildKidTimeline` output)
- Create: `frontend/src/app/components/goal-unit-detail-sheet/` (milestones at 22/30, cash note, sources, per-paycheck per unit; `BOTTOM_SHEET_MODAL_OPTS` + `SheetDragDismissDirective`)
- Modify: `frontend/src/app/app.routes.ts` (add `goals/new/:type/plan` → `GoalPage` preview)
- Modify: `frontend/src/app/pages/my-profile/my-profile.page.ts|html` (row subtitle from `view.subtitle`; remove the goals disclaimer paragraph; `addGoal()` still opens the picker and pushes `/goals/new/:type`)
- Delete: `frontend/src/app/components/goal-type-picker-sheet/` stays; nothing else deleted here.

**Interfaces:**
- Consumes: Task 5 view fields; `GoalDraftService.draft`; `GoalsService.create/update/remove`; Task 1 switch row (reused on the page with the light theme class `bf-switch--light`).
- Produces (`GoalPage` public API the spec names):
  - `mode = computed<'preview-new' | 'preview-edit' | 'saved'>`; `view = computed(() => buildGoalPlanView(...))` from the draft in preview modes, from the stored goal otherwise.
  - `startPlan()` (POST, then `router.navigate(['/goals', id], { replaceUrl: true })` and `draftService.clear()`), `updatePlan()` (PUT, then the same navigation), `openMenu()` (action sheet: Edit plan → `/goals/:id/edit`; Rename; Mark completed / Reopen; Remove goal), `beginRename(kidIndex)`, `commitRename(kidIndex, label)` (PUT chained on `pendingWrite`, body built from `existing()` inside the chain), `toggleGiftDays(on)` (PUT; plan input), `openUnitDetail(kidIndex, unit)`.
  - Template order: header; hero (`heroLabel`, big monthly, `audience · ~$X a paycheck`); band (`app-kid-timeline` for KIDS, the progress bar with a pill for others); for KIDS the "PER KID" zone with the status chip at its right and a Swiper pager of kid cards (`slidesPerView: 'auto'`, `spaceBetween: 10`, peek 20px), each unit row's target text tappable to reveal an inline "Saved so far" field that saves on blur through `updateBalance`; for simple goals a "Saved so far" tile; the gift-days row (`giftDays` present); Return chips; the `howFredHelps` card; the CTA in preview modes only.
- The old `goal.page.spec.ts` (1,357 lines) is replaced; keep and adapt the specs for `updateBalance` serialization, tier 403 → nudge, 401 → sign-out, deep-link failure, due-now hero, and the Save-during-pending-balance ordering.

- [ ] **Step 1: Write the failing specs** (`goal.page.spec.ts`, new file)

```ts
it('preview-new builds the view from the draft and Start this plan POSTs then replaces the URL with /goals/:id', () => {});
it('preview-edit shows Update my plan and PUTs the draft config', () => {});
it('saved mode shows no CTA; the menu offers Edit plan, Rename, Mark completed, Remove goal', () => {});
it('rename in place: tapping the pen shows an input; check PUTs the label and the kid card and hero audience update', () => {});
it('rename during a pending balance save carries the latest balance and the balance PUT does not revert the label', () => {});
it('toggling gift days PUTs giftDays and the hero monthly and per paycheck drop', () => {});
it('each kid unit row shows its Invested or Cash pill and tapping the label opens the detail sheet', () => {});
it('no draft on /goals/new/kids/plan navigates back to /my-profile', () => {});
it('the hero number counts up on first render unless reduced motion is on', () => {});
it('my-profile row subtitle reads "$2,830/mo for all 3 kids"', () => {}); // in my-profile.goals-render.spec.ts
```

- [ ] **Step 2: Run them, confirm they fail**

- [ ] **Step 3: Implement** the page, the timeline component and the detail sheet per the Interfaces block and the spec's "The plan page" section; move the Freedom-sheet hero/tile/insight/chip styles the page reuses into `theme/_plan-page.scss` so Task 7 shares them.

- [ ] **Step 4: Run the page specs and the three my-profile specs, confirm they pass; build; lint; events-documented**

- [ ] **Step 5: Commit** `feat(goals): one-screen plan page with preview mode, kid cards and rename in place`

---

### Task 7: The Freedom page and the tour handoff

**Files:**
- Create: `frontend/src/app/pages/freedom-plan/freedom-plan.page.ts|html|scss|spec.ts` (content and behavior of `FreedomDetailSheetComponent`, rendered as a page using `theme/_plan-page.scss`)
- Delete: `frontend/src/app/components/freedom-detail-sheet/` (component, scss, spec)
- Modify: `frontend/src/app/app.routes.ts` (add `freedom` → `FreedomPlanPage`)
- Modify: `frontend/src/app/pages/my-profile/my-profile.page.ts:729-760` (`openFreedomSheet()` → `openFreedom()` navigates to `/freedom`; the `freedomSheetClosed()` call moves to `ionViewWillEnter` guarded by a `returningFromFreedom` flag set before navigating)
- Modify: `frontend/src/app/services/first-time-tour.service.ts:146` (rename `freedomSheetClosed()` → `freedomPlanLeft()`, same body; keep a deprecated alias for one release if other callers exist, none expected)
- Modify: `frontend/src/app/components/first-time-tour/first-time-tour.component.ts` (hide the overlay while the router URL starts with `/freedom`)
- Modify specs: `my-profile.page.spec.ts`, `my-profile.analytics.spec.ts`, `first-time-tour.service.spec.ts`, `first-time-tour.component.spec.ts`

**Interfaces:**
- Consumes: `FreedomStatsService`, `RateAssumptionsService`, the sheet's `save()` (which calls `updateUserProfile`) and its analytics `surface: 'freedom_sheet'` (keep the surface value; it is a declared property value).
- Produces: `FreedomPlanPage` with the same public computed names as the sheet (`freedomYear`, `freedomAge`, `timeAwayLabel`, `progressPct`, `plus100Months`, `canSave`, `save()`, `selectReturn`, `resetRates`) so the 261-line sheet spec ports with path changes; `goBack()` → `navCtrl.navigateBack('/my-profile')`.

- [ ] **Step 1: Port the sheet spec to `freedom-plan.page.spec.ts` and add**

```ts
it('save writes income and investing through updateUserProfile and navigates back', () => {});
it('leaving the page sets the tour handoff so my-profile completes profileFreedom on enter', () => {});
// first-time-tour.service.spec.ts
it('freedomPlanLeft advances from profileFreedom exactly as freedomSheetClosed did', () => {});
// first-time-tour.component.spec.ts
it('the overlay is hidden while the url is /freedom and shown again on /my-profile', () => {});
// my-profile.page.spec.ts
it('tapping the Freedom row navigates to /freedom instead of presenting a modal', () => {});
```

- [ ] **Step 2: Run them, confirm they fail**

- [ ] **Step 3: Implement**; delete the sheet; grep for `FreedomDetailSheetComponent` and `openFreedomSheet` to confirm no references remain.

- [ ] **Step 4: Run the Freedom, my-profile and tour specs; build; lint; events-documented**

- [ ] **Step 5: Commit** `feat(freedom): the Freedom Plan opens as a page`

---

### Task 8: Icons, docs, backlog, dev mock, and the gates

**Files:**
- Modify: `frontend/scripts/subset-icons.mjs:20-40` (`DYNAMIC_ICONS` += any of `edit`, `check`, `auto_awesome`, `cake`, `travel_explore`, `do_not_disturb_on`, `info`, `close`, `arrow_back_ios_new`, `more_horiz`, `check_circle` that are returned from TypeScript; template literals are scanned automatically), regenerate `frontend/src/assets/fonts/material-symbols-outlined.woff2`, bump `?v=3` → `?v=4` in `frontend/src/global.scss` and `frontend/src/index.html`
- Modify: `frontend/src/app/services/dev/goals-mock.ts` (+ spec) (`tripsPerYear` values stay ≤ 4 are fine; add `giftDays: true` to one KIDS mock so the "Next up" row shows; kids ≤ 5)
- Modify: `FREDdocs/backlog.md` via the `backlog-add` skill: (a) "Goals v3.1: quiz and plan page redesign" (supersedes the UI half of FRED-229's acceptance criteria; link the spec); (b) "One portfolio, split across long-term goals by share" (text from the spec's Follow-ups; starts after this ships)
- Modify: `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md` (one line at the top of the amendments section pointing to the 2026-11-06 design)
- Modify: `FREDdocs/analytics-events.md` only if a comment names the freedom sheet (grep `freedom-detail-sheet`)

- [ ] **Step 1: Run `npm run subset-icons` from `frontend/`; confirm the woff2 changed and every icon the new templates use is in the output** (`node scripts/subset-icons.mjs --check` if the script supports it; otherwise grep the script's log for each name)

- [ ] **Step 2: Mock spec**: `it('one_of_each still yields started, on_plan, ahead, behind and done through buildGoalPlanView', ...)` passes after the view-model change.

- [ ] **Step 3: Backlog entries via `backlog-add`; design pointer; analytics doc check**

- [ ] **Step 4: Copy audit**: with the dev server (`npx ng run app:serve:local --host=localhost --port=8100 --live-reload=false`) render each quiz screen for Kids (all six), House, Car, Vacation, Emergency fund, Wedding, Something else, and the plan page, at 390×844 in headless Chrome; list any title that wraps or sentence that breaks mid-way in the task report. Fix by shortening, never by wrapping.

- [ ] **Step 5: Gates**: `./gradlew test`; `npx ng test --watch=false --browsers=ChromeHeadless` (only the known failures, now 13 since the sheet spec is gone); `npx ng build --configuration dev`; `npm run lint`; `npm run test:events-documented`; `grep -rn "—" frontend/src/app/pages/goal frontend/src/app/pages/goal-quiz frontend/src/app/pages/freedom-plan` returns nothing.

- [ ] **Step 6: Commit** `chore(goals): icon subset, backlog stories, docs for the redesign`

---

## Owner steps after the branch lands

- `npx cap sync ios` (regenerated icon font).
- Device pass per the spec's Verification list; the dev servers run from the main checkout.
- Decide when the portfolio-split story starts; the "How FRED helps" copy is live before it.
