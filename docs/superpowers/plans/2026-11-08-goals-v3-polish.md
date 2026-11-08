# Goals v3.1 Polish Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix what Andy found on the device after the Goals v3.1 redesign landed on `develop`: timeline labels collide, the kids cap is 5, the last kid card is flush right, unit rows are cramped, the plan page does not match the Freedom Plan page's card styling, the quiz cost step shows Continue before the reveal, and the plan page never says why the money is invested.

**Architecture:** Every change is inside the existing Goals v3.1 surfaces: the pure `buildKidTimeline` model, the goal plan page and its shared `_plan-page.scss`, the quiz page, the kid-cost constants, and the backend `GoalService` validator. No new routes, no new services, no schema change.

**Tech Stack:** Angular 19 standalone + Ionic 8 (signals, Swiper element, WAAPI), Karma/Jasmine, SCSS; Spring Boot 3 + JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-06-goals-quiz-and-plan-page-redesign.md` (the redesign design, still binding where this plan is silent). Andy's device feedback of 2026-11-08 is the authority for the changes below; each task states it verbatim where it matters.

## Global Constraints

- No em dashes anywhere in user-facing copy, code comments, docs or commit messages.
- No AI-written numbers: every figure shown comes from the deterministic plan math.
- Light-only, Manrope, `material-symbols-outlined` icons (only icons already in `DYNAMIC_ICONS` of `frontend/scripts/subset-icons.mjs`, or add them there and note it).
- Reduced motion: any new animation uses `element.animate()` and completes instantly under `prefers-reduced-motion: reduce`.
- Karma `--include` type-checks the whole app; the tree must compile at every commit. Known failing set is exactly the 13 FRED-226 cases; nothing new may fail.
- Commands: frontend from `frontend/` (`npx ng test --watch=false --browsers=ChromeHeadless --include='<glob>'`, `npx ng build --configuration dev`, `npm run lint`); backend from `backend/` (`./gradlew test`).

## Review Focus

1. A family with three kids born in the last three years plus Now: no two labels on the same row of the timeline may overlap at a 318px bar (Task 1 pins the minimum gap).
2. A family where every kid is undated: the bar still has a sensible end (this year + 18) and all undated dots sit before it, spaced apart (Task 1).
3. Six kids in the quiz: the stepper stops at 6, the cap line reads "Six", the cost figure uses the 0.52 factor, the backend accepts 6 and rejects 7 (Task 2).
4. The Freedom Plan page must look exactly as before after the assumptions card moves to the shared stylesheet (Task 3).
5. On the quiz cost step, tapping the stat early must also reveal Continue; leaving the step and coming back must re-hide and re-reveal it (Task 4).

---

### Task 1: Timeline spacing, undated kids at the end

**Files:**
- Modify: `frontend/src/app/pages/goal/kid-timeline.model.ts`
- Modify: `frontend/src/app/pages/goal/kid-timeline.model.spec.ts`
- Modify: `frontend/src/app/components/kid-timeline/kid-timeline.component.scss`
- Modify (only if an assertion depends on the old positions): `frontend/src/app/components/kid-timeline/kid-timeline.component.spec.ts`

**Andy's words:** "the name of the kids on the timeline is cluttered ... spread out the timeline a little more so that there will never be any cluttered overlap of names"; "if the timeline of kids born in the past is very recent, so there's like 5% of the timeline that would be green, but that has like a few kids there, there needs to be enough space ... instead of it only taking up 5% on the visual display, it would take up like 20%"; "not sure kids should be moved more towards the end of the timeline rather than the front"; "An undated kid should stop influencing all 18."

**Interfaces:**
- Consumes: `buildKidTimeline(kids, now)` as today (same signature, same `TimelineDot` shape).
- Produces: the same return type; only the positions, order and end year change. `passedPct` is the Now dot's final position.

**The algorithm (replace the body of `buildKidTimeline`):**

1. `thisYear = now.getFullYear()`. Dated kids are those with `birthYear !== null`. `datedYears = dated kids' birth years`.
2. `start = min(thisYear, ...datedYears)`. `last = (datedYears.length ? max(...datedYears) : thisYear) + 18`. Undated kids no longer feed `start` or `last`.
3. `position(year)` stays the two-segment scale (first ten years onto 0..65, the rest onto 65..100, linear when the span is ten years or less), clamped 0..100.
4. Provisional dots: Now at `position(thisYear)`; each dated kid at `position(birthYear)`; each undated kid at `100`; End at `100`.
5. Stable sort by `(position, rank, original index)` where rank is Now 0, dated kid 1, undated kid 2, End 3.
6. Even out: `MIN_GAP = 12`. Forward pass over the sorted list, `i` from 1: `pos[i] = max(pos[i], pos[i-1] + MIN_GAP)`. Then pin the End at 100 and run a backward pass, `i` from `length - 2` down to 0: `pos[i] = min(pos[i], pos[i+1] - MIN_GAP)`. Clamp every position to 0..100. (Six kids + Now + End is eight dots; seven gaps of 12 is 84, so both passes always fit.)
7. `passed` for a dated kid = `birthYear < thisYear` (year-based, as today in effect). Undated kids are never passed. `passedPct` = the Now dot's final position.
8. Labels: `expectedThisYear`, `undated`, `label`, `year` as today. End label "All 18" for two or more kids, "18" for one.

- [ ] **Step 1: Rewrite the spec file around the new algorithm.** Keep the Ruling R4 and single-kid cases; replace position assertions with these, all at `now = 2026-11-07`:

```ts
it('born 2024 and 2025 with Now 2026: dots are evened out to 12 apart and the green reaches the Now dot', () => {
  const t = buildKidTimeline([k('A', 2024), k('B', 2025)], NOW);
  expect(t.dots.map(d => d.label)).toEqual(['A', 'B', 'Now', 'All 18']);
  expect(t.dots.map(d => Math.round(d.position))).toEqual([0, 12, 24, 100]);
  expect(t.passedPct).toBeCloseTo(24, 0);
  expect(t.dots[0].passed).toBeTrue();
  expect(t.dots[1].passed).toBeTrue();
});

it('undated kids sit just before the end, do not move the end, and are never passed', () => {
  const t = buildKidTimeline([k('A', 2030), k('B', null), k('C', null)], NOW);
  expect(t.dots.map(d => d.label)).toEqual(['Now', 'A', 'B', 'C', 'All 18']);
  expect(t.dots.at(-1)!.year).toBe(2048);
  expect(t.dots.slice(2, 4).map(d => Math.round(d.position))).toEqual([76, 88]);
  expect(t.dots.slice(2, 4).every(d => d.undated && !d.passed)).toBeTrue();
});

it('every kid undated: the bar runs Now to this year + 18 with the kids parked before the end', () => {
  const t = buildKidTimeline([k('A', null), k('B', null)], NOW);
  expect(t.dots.map(d => d.label)).toEqual(['Now', 'A', 'B', 'All 18']);
  expect(t.dots.at(-1)!.year).toBe(2044);
  expect(t.dots.map(d => Math.round(d.position))).toEqual([0, 76, 88, 100]);
});

it('six kids plus Now and the end never sit closer than 12 apart', () => {
  const t = buildKidTimeline([k('A', 2024), k('B', 2025), k('C', 2026), k('D', 2027), k('E', null), k('F', null)], NOW);
  expect(t.dots.length).toBe(8);
  for (let i = 1; i < t.dots.length; i++) {
    expect(t.dots[i].position - t.dots[i - 1].position).toBeGreaterThanOrEqual(12 - 1e-9);
  }
  expect(t.dots[0].position).toBe(0);
  expect(t.dots.at(-1)!.position).toBe(100);
});

it('well-spread kids keep their scale positions; only a tie is pushed apart', () => {
  const t = buildKidTimeline([k('A', 2026), k('B', 2036)], NOW);
  // Now and A tie at 0: Now first, A pushed to 12. Span 28 years: 2036 is t = 10 on the first segment, so B stays at 65.
  expect(t.dots.map(d => d.label)).toEqual(['Now', 'A', 'B', 'All 18']);
  expect(t.dots.map(d => Math.round(d.position))).toEqual([0, 12, 65, 100]);
});
```

Where `k = (label, birthYear, birthMonth = null)` builds a kid and `NOW = new Date(2026, 10, 7)`.

- [ ] **Step 2: Run the spec, confirm the new cases fail.** `npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/kid-timeline.model.spec.ts'`
- [ ] **Step 3: Implement the algorithm above.** Update the file's doc comment to describe the even-out pass and the undated-at-end rule.
- [ ] **Step 4: Run the model spec and the component spec; both green.** Component spec: `--include='src/app/components/kid-timeline/kid-timeline.component.spec.ts'`. If a component assertion encoded the old order (undated kid beside Now), update it to the new order.
- [ ] **Step 5: Tighten the label width** in `kid-timeline.component.scss`: `.ktl-lb b { max-width: 66px; }` (12% of a 318px bar is 38px between neighbours, 76px between same-row neighbours, so a 66px label centered on its dot cannot touch the next one on its row).
- [ ] **Step 6: Build + lint, commit.** `git commit -m "fix(goals): even out the family timeline and park undated kids at the end"`

---

### Task 2: Six kids

**Files:**
- Modify: `frontend/src/app/pages/goal/goal-form.model.ts` (`MAX_KIDS = 6`)
- Modify: `frontend/src/app/pages/goal/goal-form.model.spec.ts` (the `MAX_KIDS is 5` case)
- Modify: `frontend/src/app/pages/goal-quiz/goal-quiz.page.html` (cap line)
- Modify: `frontend/src/app/pages/goal-quiz/goal-quiz.page.spec.ts` (the two `toBe(5)` cap assertions near lines 245 and 249)
- Modify: `frontend/src/app/constants/kid-costs.ts`
- Modify: `frontend/src/app/utils/kid-cost.utils.ts` + `.spec.ts`
- Modify: `frontend/src/app/components/kid-cost-sources-sheet/kid-cost-sources-sheet.component.ts` + `.spec.ts`
- Modify: `FREDdocs/kid-cost-figures.md` (the "Four and five kids" section)
- Modify: `docs/superpowers/specs/2026-11-06-goals-quiz-and-plan-page-redesign.md` (every "cap 5" / "1 to 5 kids" mention; grep `kids cap`, `five`, `5 kids`)
- Modify: `backend/src/main/java/com/investingapp/backend/service/GoalService.java:334` (`size() > 5` becomes `> 6`, message if it names five)
- Modify: `backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java` (`fiveKidsAreAccepted` becomes six accepted, seven rejected)

**Andy's words:** "there will only be 6 kids at most, and yes, let's raise the ability to plan for 6 kids."

**Values:**
- `MAX_KIDS = 6`; cap line copy: `Six is the most FRED plans for at once`.
- Sixth family-size factor, same rule as four and five (third child's marginal 0.28 applied again): `(2.84 + 0.28) / 6 = 0.52`. `FRED_ESTIMATE_FAMILY_SIZE_FACTORS = { four: 0.64, five: 0.568, six: 0.52 }`, doc comment extended with the arithmetic.
- `familySizeFactor(6)` returns `six`; anything above 6 reads as six. `FamilySizeWording` gains `'six'`; `sizeWordingFor(6) === 'six'`. The sources sheet's `COUNT_WORDS` gains `6: 'six'` and its note covers "four, five or six".
- Backend: kids 1..6 valid; 7 rejected with the existing error path.

- [ ] **Step 1: Update the frontend specs first** (`MAX_KIDS` is 6; quiz stepper clamps at 6; `familySizeFactor(6)` is 0.52 and `(7)` is 0.52; wording list ends `'five', 'six', 'six'`; sources sheet for 6 says "six kids"). Run each, confirm red.
- [ ] **Step 2: Implement** the constants, util, model, html copy, sources sheet.
- [ ] **Step 3: Run the touched specs green:** `goal-form.model.spec.ts`, `goal-quiz.page.spec.ts`, `kid-cost.utils.spec.ts`, `kid-cost-sources-sheet.component.spec.ts`.
- [ ] **Step 4: Backend test first:** add `sevenKidsAreRejected`, rename/extend the five case to six. Run `./gradlew test --tests '*GoalServiceTest*'`, confirm red, change the validator, confirm green.
- [ ] **Step 5: Docs:** `FREDdocs/kid-cost-figures.md` section becomes "Four, five and six kids (FRED estimate)" with the 0.52 line; spec mentions of the cap read 6.
- [ ] **Step 6: Build + lint, commit.** `git commit -m "feat(goals): plan for up to six kids"`

---

### Task 3: Plan page matches the Freedom Plan page

**Files:**
- Modify: `frontend/src/theme/_plan-page.scss`
- Modify: `frontend/src/app/pages/freedom-plan/freedom-plan.page.html` + `.scss`
- Modify: `frontend/src/app/pages/goal/goal.page.html`, `.scss`, `.ts`
- Modify: `frontend/src/app/pages/goal/goal.page.spec.ts` (only where a selector it queries is renamed)
- Modify: `frontend/src/app/pages/freedom-plan/freedom-plan.page.spec.ts` (only where a selector it queries is renamed)

**Andy's words:** "look at the styling on the Freedom Date goal page. The styling is a lot cleaner there in terms of how the cards look with their gray background and the spacing in there, and also the Assumptions tab card. That Assumptions card is what we should have here. Basically an exact copy almost."; "when you get to the last kid in the slider, it is clamped to the far right side of the screen when there should be some padding there"; "the three lists objects of Baby Fund, College, and Nest Egg with their amount listed underneath it are very underspaced and look cluttered".

**Changes:**

A. **Assumptions card becomes shared.** Move `.fp-assume`, `.fp-assume-name`, `.fp-assume-sub` and the `.fp-chips button` font-size rule from `freedom-plan.page.scss` into `_plan-page.scss` as `.fd-assume`, `.fd-assume-name`, `.fd-assume-sub`, `.fd-assume-chips` (same values, verbatim). Freedom page html uses the new class names; its scss drops the moved rules. The Freedom page must render pixel-identical.

B. **Goal page uses it.** Replace the `.fd-seg` "Return" block in `goal.page.html` (the `@if (v.rateKind === 'market')` branch) with:

```html
<p class="fd-zone">
  Assumptions
  @if (selectedRate() !== defaultRate) {
    <button type="button" class="fd-reset" (click)="selectRate(defaultRate)">Reset to standard</button>
  }
</p>
<div class="fd-assume">
  <div class="fd-assume-name">
    Avg annual return
    <span class="fd-assume-sub">what this plan grows by</span>
  </div>
  <div class="fd-scenario-chips fd-assume-chips">
    @for (s of returnScenarios; track s.rate) {
      <button type="button" [class.on]="selectedRate() === s.rate" [attr.aria-pressed]="selectedRate() === s.rate" (click)="selectRate(s.rate)">{{ s.label }}</button>
    }
  </div>
</div>
```

`defaultRate = DEFAULT_ANNUAL_RETURN` (import from `services/rate-assumptions.service`) as a readonly field on `GoalPage`. The cash branch (`gp-cash-note`) stays, restyled to the shared card look (13px radius, `#f8fafc` on `#eef1f6`, padding `11px 13px`), under the same "Assumptions" zone label.

C. **Last card gets its gutter.** In `goal.page.ts` the pager params become `{ slidesPerView: 'auto', spaceBetween: 10, slidesOffsetAfter: 20 }`.

D. **Unit rows breathe.** In `goal.page.scss`: `.gp-krow { padding: 11px 0; gap: 11px; }` with `.gp-krow + .gp-krow { border-top: 1px solid #eef1f6; }`; `.gp-kic` 32px square, radius 10px; `.gp-klabel` font-size 13px; `.gp-ktarget { margin-top: 3px; font-size: 11px; }`; `.gp-krow em` 13px. The inline `.gp-bal` indent follows the icon (`margin-left: 43px`).

E. **Kid card matches the tiles.** `.gp-kcard { border-radius: 13px; padding: 12px 13px 4px; }`; `.gp-kh { gap: 10px; padding-bottom: 10px; }`; `.gp-av` 34px (icon 18px) to match `.fd-insight-icon`.

- [ ] **Step 1:** Run `goal.page.spec.ts` and `freedom-plan.page.spec.ts` once to know the baseline (both green apart from the known 13).
- [ ] **Step 2:** Make A and confirm the Freedom page spec stays green.
- [ ] **Step 3:** Make B, C, D, E. Add one spec case to `goal.page.spec.ts`: the market plan page renders `.fd-assume` with three chips and no `.fd-reset` at 10%; after `selectRate(0.12)` the reset button appears and clicking it restores 10%.
- [ ] **Step 4:** Both specs green; build + lint; commit `style(goals): plan page cards and assumptions match the Freedom Plan page`.

---

### Task 4: Continue fades in after the cost reveal

**Files:**
- Modify: `frontend/src/app/pages/goal-quiz/goal-quiz.page.ts`, `.html`, `.scss`
- Modify: `frontend/src/app/pages/goal-quiz/goal-quiz.page.spec.ts`

**Andy's words:** "make the continue button fade in for that step 3 where the reveal of the monthly payment comes in for how much the kids will be."

**Behavior:**
- On the `kids_cost` step the CTA starts hidden (`opacity: 0; pointer-events: none` via a `cta-hidden` class) and becomes visible when the family figure (stat B) is showing: either when the `COST_HOLD_MS` timer flips it, or when the user taps the stat early. Reveal = remove the class and run `cta.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 400, easing: 'ease-out', fill: 'both' })`; under `prefers-reduced-motion: reduce` (use `matchMedia`) skip the animation and just show it.
- Flipping back to stat A does not hide it again. Leaving the step and re-entering (the existing cost-step entry code that resets `costStat` to 'A' and starts the timer) hides it again until the next reveal.
- Every other step: CTA visible as today, no class.
- Implement as a signal `ctaRevealed` set by a single `revealCta()` called from the flip path when the next stat is 'B'; the template binds `[class.cta-hidden]="currentStep().id === 'kids_cost' && !ctaRevealed()"`.

- [ ] **Step 1: Specs first** in `goal-quiz.page.spec.ts` (use the existing helpers that walk to the cost step and the fake timers pattern already in the file): on arriving at `kids_cost` the CTA has `cta-hidden`; after `COST_HOLD_MS` it does not; tapping the stat before the timer reveals it immediately; leaving to the next step and coming back hides it again. Run, confirm red.
- [ ] **Step 2: Implement.** Keep the WAAPI call behind an `el.animate` existence check (jsdom-free Karma Chrome has it; guard anyway, matching the existing `costStatElements` animation code).
- [ ] **Step 3: Spec green, build + lint, commit** `feat(goals): Continue fades in after the cost reveal`.

---

### Task 5: "Why invest it" card (dispatch only after Andy approves the copy)

**Files:**
- Modify: `frontend/src/app/pages/goal/goal-plan.view-model.ts` + `.spec.ts`
- Modify: `frontend/src/app/pages/goal/goal.page.html`

**Andy's words:** "put another information tab to say what the purpose of investing this money is, because they might want to take it out to actually pay for the kid, and they should know why they're contributing this money"; "I need a reason to just give me undeniable viability of investing this money ... wouldn't they just say Won't I just need this money for the kid to spend on them? So what's the point in investing it?"

**View model:** `GoalPlanView` gains `whyInvest: string | null`. For a KIDS plan it is built from the invested units only (rate kind `market`, not finished): `reaches = sum of unit targets`, `fromYou = sum over units of (monthly × months left + every lump inside the horizon)`, `fromGrowth = max(0, reaches - balances - fromYou)`. Null when there is no invested unit, when `fromGrowth <= 0`, or when the plan is due now. Simple market plans use the existing `fromYou` / `fromGrowth`. Figures rounded to the nearest $1,000 with `fmtMoney` (approved copy goes here verbatim once Andy signs it off; the placeholders `{reaches}`, `{fromYou}`, `{fromGrowth}` are the three figures):

> Why invest it. You will not spend this for years: college and the nest egg come due at 18, and those years are what make invested money grow. Of the {reaches} this plan reaches, about {fromYou} comes from you; growth adds the other {fromGrowth}. Take it out early and growth stops, and the monthly from you goes up. Anything you need within three years is planned as cash instead.

**Template:** a second `.fd-how` card directly under "How FRED helps", icon `trending_up` (already subset), `<p><b>Why invest it.</b> {{ v.whyInvest }}</p>`, shown only when `v.whyInvest` is non-null.

- [ ] **Step 1: Spec first** in `goal-plan.view-model.spec.ts`: the sample two-kid family at 10% yields a `whyInvest` whose three figures satisfy `fromYou + fromGrowth + balances = reaches` within rounding; an all-cash plan yields null; a due-now plan yields null.
- [ ] **Step 2: Implement; spec green; build + lint; commit** `feat(goals): the plan page says why the money is invested`.
