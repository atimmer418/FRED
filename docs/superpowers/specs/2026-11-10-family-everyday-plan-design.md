# Family plan: the everyday line (design)

Brainstormed with Andy on 2026-11-10. Builds on `2026-11-02-goals-v3-plans-design.md`,
`2026-11-05-goals-v3-kid-costs-addendum.md` and `2026-11-06-goals-quiz-and-plan-page-redesign.md`.
Where this document and those disagree, this one wins.

## Intent

FRED's family goal today tells a parent what to invest for each kid (baby fund, college, nest
egg). It already knows what a kid of each age costs to raise, but only says so in one sentence
lower on the page. This change makes the family plan the kids' whole month: for each kid, what
to invest and what to set aside for everyday life this month, with the everyday figure moving on
its own as the kid grows. The parent should open the page on the first of the month and know
the number for each child without doing any arithmetic. FRED tells; the parent moves the money.

It also lightens the page. The three explanatory cards leave; their content moves behind an
info button inside each row's detail sheet, and the balance entry moves into that sheet too.

Andy's words: "we want them to not have to worry about financials because FRED is keeping track
of how much they should save this month and how much they should invest this month."

## Goals

- Each kid card shows two groups, Invest and Everyday, with blue group totals; the card's header
  figure is the kid's whole month; the Family plan hero is the whole month across all kids.
- The everyday figure is FRED's existing estimate for that kid's age (USDA figures, inflated to
  today, family-size adjusted), $0 before the first birthday because the baby fund is that year.
- It steps up quietly at each age band, and its sub line changes to what a kid of that age needs.
- The quiz does not change. No backend change. No AI-written number anywhere.
- The page gets lighter: no info cards; a "Tap for details" hint; the row's sheet carries the
  balance, the status and an info button for the three explanations.

## Non-goals

- Budget tracking, bank linking, or any record of what was actually spent.
- Moving or holding money. FRED invests only toward the Freedom Date.
- Notifying the parent when a figure steps up (the card simply shows the new number).
- Changing the status model, the anchors, the quiz, or any other goal type's page beyond what
  this document names.

## Anti-goals (what would make this a failure even if it works)

- A "saved" label on money that is spent within the month. The everyday line is set aside, not
  saved; it never builds a balance and FRED never grades it.
- Government numbers presented as exact: every everyday figure is a FRED estimate and says so
  where the user can find it.
- A family plan whose cards no longer add up to its hero.
- A kids goal that can no longer log a balance (today the inline field is the only place).

## Decisions (Andy, 2026-11-10)

1. The hero is the kids' whole month: invest plus everyday across all kids. Label "Family plan"
   (was "Save this month"). The timeline stays as it is.
2. Each kid card's header figure is that kid's whole month. Cards add up to the hero again.
3. The card has two groups, Invest (baby fund, college, nest egg) and Everyday (one row). Group
   totals in blue; every unit amount black; the header figure blue. Nothing under the header
   figure.
   > **Changed 2026-11-11 (Andy):** the two blue group totals are gone; the group rows carry only
   > their label, and the header figure is the one blue number on the card.
4. The sub line under the kid's name carries the age: "Born 2024 · age 2".
5. The baby fund always carries the Cash pill and is always cash-planned.
6. The everyday row is $0 until the kid turns one; the baby fund is year one. Its sub line reads
   what a kid of that age needs, one line per USDA band (table below).
7. The three info cards (How FRED helps, What the monthly covers, Why invest it) leave the kids
   page. Each row's detail sheet gets an info button that opens them.
8. The whole row is the tap target and opens the row's sheet. The gray "Tap for details" hint
   (My Profile's `.zone-label-hint` style) sits on the "Per kid" zone label.
9. The inline "Saved so far" field under a row goes away. The row's sheet carries an editable
   "Saved so far" tile with a one-line Status tile next to it, condensed in height. Edits save
   on their own; there is no save button.
10. "What it grows to" shows At 18 / At 22 / At 30 as three distinct little cards.

## Calls I made (check these while reading)

- **Simple goals keep their info cards.** Decision 7 is about the kids page; a house or wedding
  goal has no per-unit sheet to carry the notes, so its two cards stay where they are.
- **"Start this plan" and "Save changes" stay as they are.** They appear only in the preview
  modes (a brand-new goal, or a re-run quiz on a saved goal). A saved goal's page has no button
  today and gets none; balances and renames save on their own, as they already do.
- **The hero's "~$X a paycheck" line follows the new total**, so it is the kids' whole month per
  paycheck. The band line, the progress and the status chip stay about the invested units.
- **The everyday figure is rounded per kid** to the nearest $10, and the family figure (hero,
  "What the monthly covers") is the sum of the per-kid figures, so the two never disagree. This
  replaces rounding the family sum once; the kid-cost pins move by at most $10 per kid.
- **The family-size factor counts every kid at home aged 0 to 17, the baby included**, even
  though the baby's own row is $0 in year one. An only child at home is priced as an only child
  however many are planned (unchanged).
- **Under a year reads "under 1"**: "Born 2026 · under 1". An unborn kid keeps "Arrives 2028" and
  "Not dated yet" with no age.
- **A kid 18 or older has no Everyday group** (the USDA study stops at 17); the card shows the
  Invest group only.
- **The Status tile shows the goal's status**, the same word the chip shows ("Started", "On
  plan", "Ahead", "Behind", "Done"), on one line. Status is per goal, not per unit, and this
  document does not change that.
- **The my-profile Goals row for a family uses the same whole-month figure as the hero.**
- **Milestone cards already exist** in the shipped sheet (`.ud-ms-tile`, #f8fafc with a border);
  the mockup drew them bare. Keep them as cards and check they read as three on a 390px screen.
- **Two totals, named apart.** `GoalPlanView` keeps `monthlyTotal` as the figure the hero shows
  (for KIDS now invest plus everyday) and gains `investMonthly`, the invested units alone.
  Everything that grades, anchors or reasons about the plan reads `investMonthly`: the
  `anchorMonthly` written in the POST / PUT body (`goal.page.ts` `draftBody`), the due-now rule
  (`isDueNowOnly(investMonthly, dueNow)`), and the "Already investing $X or more a month?" line
  in How FRED helps. When the invested units are due now, the hero shows "$X due now" exactly as
  today and the everyday figures stay on the cards.
- **The kid card stays on the page.** `KidCard` / `KidUnitRow` and `kidSubLine` live in
  `goal.page.ts` today and stay there (one place). The view model exposes per-kid everyday
  figures (`everyday: EverydayView[]`, one per kid, keyed by kid index) and the page's `kidCards`
  computed adds the group totals and the header figure. "Card header = invest + everyday" and
  "hero = sum of the cards" are therefore `goal.page.spec.ts` assertions; the view-model spec
  pins `everyday[]`, `investMonthly` and `monthlyTotal`.
- **The Status tile shows the chip's exact text**, one line: "Plan started", "Plan updated",
  "On plan", "Ahead", "Behind", "Done", or "Update balance" when the last log is over 60 days
  old. Colors follow the chip: green for Ahead / On plan / Done, amber for Behind, blue for
  Update balance, gray for Plan started / Plan updated.
- 2026-11-10, final review: an unborn kid whose baby fund is off reads 'everyday costs start at the first birthday'; no trailing periods on from lines.

## What the user sees

### The hero

Label "Family plan". Figure: the whole month, every kid, invest plus everyday. Meta line: "2 kids
· ~$1,172 a paycheck" (paycheck line hidden when there is no FRED schedule, as today). Timeline
unchanged. "Due now" hero unchanged for the due-now case.

### The kid card

```
[avatar] Maya                                  $2,595/mo
         Born 2024 · age 2
──────────────────────────────────────────────────────────
INVEST                                             $685
[child_care] Baby fund  CASH                       ✓ Done
             $17,400 by Dec 2025
[school]     College  INVESTED                      $110
             $47,800 by 2042
[savings]    Nest egg  INVESTED                     $575
             $250,000 by 2042
──────────────────────────────────────────────────────────
EVERYDAY                                          $1,910
[shopping_basket] Age 2 costs  CASH               $1,910
             Food, clothes, diapers and childcare
```

- Group labels in the zone-label style (uppercase, 9px, #9aa6ba); group totals 13px 800 #2563eb.
- Unit amounts #111827. Header figure #2563eb, no text under it.
- Each row is one button (label, pill, target line and amount inside) that opens the row's
  sheet. The rename pen in the header is unchanged.
- Everyday group for a kid not yet born: no amount; one line in place of the row, "From Mar 2027:
  the baby fund is year one" (month and year from the kid's expected date; "From the day they
  arrive" when undated or expected this year with no month, the "This year" sub-line case). An
  unborn kid whose baby fund is off reads "From Mar 2027: everyday costs start at the first
  birthday" (same date rule). For a kid under one: "The baby fund covers year one", or
  "Everyday costs start at the first birthday" with no fund. For 18 and up: no group. No from
  line ends with a period.
- Zone label: "PER KID  Tap for details" on the left, the status chip and dots on the right.

Everyday sub line by USDA band (the figure's age band, not the stage grouping the quiz uses):

| Age | Sub line |
|---|---|
| under 1 | (no row amount) The baby fund covers year one |
| 1 to 2 | Food, clothes, diapers and childcare |
| 3 to 5 | Food, clothes, preschool and toys |
| 6 to 8 | Food, clothes, school and activities |
| 9 to 11 | Food, clothes, sports and school trips |
| 12 to 14 | Food, clothes, a phone and activities |
| 15 to 17 | Food, clothes, driving and activities |

Row label: "Age 2 costs" (the kid's age in whole years). Pill: Cash.

### The row's sheet (existing `GoalUnitDetailSheetComponent`, extended)

Top right: a round `info` button that opens the "How FRED plans this" sheet. Monte Carlo's
`.res-info` gives the shape only (38px round, 19px glyph); its colors are for a dark hero, so
here it is #f1f5f9 with a #2563eb glyph, inside the sheet's padding on the title row.

Tiles, two rows of two, condensed (value 16px, tile padding 9px 11px):

- Every month · From your pay (as today)
- **Saved so far** (editable, `type="text" inputmode="numeric"`, saves on blur through the
  page's existing `updateBalance`; the sheet's figures update without closing) · **Status**
  (one line, the chip's exact text and colors; see the calls above)

Then "What it grows to" as three cards, the notes, the sources line, "Where these numbers come
from" where it applies, and the Done button. A finished baby fund shows Done in the Every month
tile and its Saved so far tile read-only.

The **Everyday row's sheet** is the same component with: title "Everyday", target line "Age 2 ·
FRED estimate", tiles Every month and From your pay only (no balance, no status: nothing to
log), no milestones, notes "Food, clothes, diapers and childcare." and "Steps up on its own at
3, 6, 9, 12 and 15, the ages where the USDA figures change.", sources line "FRED estimate: USDA
2015 figures for a middle-income family, adjusted to <asOfLabel> dollars and to <N> kid(s) at
home.", and the "Where these numbers come from" link (the existing `KidCostSourcesSheet` with
the kids-at-home count).

### The "How FRED plans this" sheet (new, small)

Same chrome as every FRED bottom sheet (`BOTTOM_SHEET_MODAL_OPTS`, `SheetDragDismissDirective`,
`ion-content-scroll-host`), built the way `KidCostSourcesSheetComponent` is. Handle, title "How
FRED plans this", sub line "The same three notes for every unit of the family plan.", then the
three notes as the `.fd-how` cards look today (icon, bold lead, text): How FRED helps, What the
monthly covers, Why invest it, taken from the view (`howFredHelps`, `coversLine`,
`whyInvestLine`; a null one is skipped). Done button.

### The "Update balance" chip

On a kids goal it opens the row's sheet for the first unfinished unit of the kid card in view
(today it opens the first unit, finished or not; this is a small change), with the Saved so far
field focused. Simple and vacation goals keep their inline "Saved so far" tile, so for them the
chip still scrolls to and focuses that field as it does today.

### My Profile

The family row's "$X/mo" is the whole month. Nothing else changes.

## The math

All in `utils/kid-cost.utils.ts`, pure, spec'd, no new constants.

- `everydayMonthlyForKid(age: number | null, kidsAtHome: number): number`: `null` or `0` or
  `> 17` returns 0; else the USDA band annual for `age` × `inflationFactor()` ×
  `familySizeFactor(kidsAtHome)` / 12, rounded to the nearest $10.
- `kidsAtHome(kids, now)`: the count of kids whose `kidAgeToday` is 0 to 17.
- `everydayMonthlyToday(kids, now)` becomes the sum of `everydayMonthlyForKid` over the kids
  (per-kid rounding, then summed). Existing pins are re-derived; the one-kid pin (1,910) holds.
- `everydaySubLine(age)` returns the table line; `everydayRowLabel(age)` returns "Age N costs".

In the view model (`goal-plan.view-model.ts`), `GoalPlanView` gains `investMonthly` (the
invested units alone; equal to `monthlyTotal` for every type but KIDS) and, for KIDS,
`everyday: EverydayView[]` with one entry per kid in config order:
`{ monthly: number; label: string; subLine: string; fromLine: string | null; state: 'unborn' |
'year_one' | 'priced' | 'grown' }` (`fromLine` is the "From Mar 2027: the baby fund is year one"
or "The baby fund covers year one" text, null when priced or grown). `monthlyTotal` for KIDS is
`investMonthly` plus the everyday sum. `heroLabel` for KIDS is `'Family plan'` (type `HeroLabel`
widens). Status, anchors, `progressPct`, the band line, `rateKind`, the due-now rule and the
How FRED helps figure are computed exactly as before, from `investMonthly`. The page's
`kidCards` computed adds, per kid, `investMonthly` (the sum of that kid's unit rows) and the
header figure `investMonthly + everyday[index].monthly`.

Baby fund: `firstYearRows` passes `rateKind: 'cash'` always (drop the `kidAccountRateKind`
call there; the helper stays for the 529 and nest egg). The cash note for the fund becomes
"Planned as cash at ~4%: money with a date on it stays out of the market." A saved goal whose
fund was anchored as market keeps its stored anchor until its next re-anchor, so its status
can read a little behind in the meantime (dev data only; nothing throws).

Pinned example (Nov 2026 constants, 10%): Maya born Mar 2024, Sam expected Mar 2027, 529 target
$47,800, brokerage target $250,000. Maya: age 2, one kid at home, everyday = 12,680 × 1.421586 ×
1.27 / 12 = 1,907.6 → **$1,910**; sub line "Food, clothes, diapers and childcare". Sam: unborn,
everyday 0, line "From Mar 2027: the baby fund is year one". Hero = invest total + $1,910.

## Files (all frontend; paths from `frontend/src/app/`)

- `utils/kid-cost.utils.ts` (+spec): the four functions above; `everydayMonthlyToday` re-based.
- `pages/goal/goal-plan.view-model.ts` (+spec): `everyday[]`, `investMonthly`, the new
  `monthlyTotal`, `heroLabel: 'Family plan'`, the baby fund always cash; due-now and How FRED
  helps read `investMonthly`.
- `pages/goal/goal.page.ts|html|scss` (+spec): `kidCards` adds the group totals and the header
  figure, and the age on `kidSubLine`; groups render; row-as-button; the hint; remove
  `toggleBalance`, `balanceOpen`, `balanceKey` and the kids-row `onBalanceBlur` path and the
  `.gp-bal*` block and styles (the simple-goal tile keeps its blur handler); `onChipTap` opens
  the sheet for kids goals; `draftBody` writes `anchorMonthly: view.investMonthly`;
  `unitDetail()` builds the Everyday detail and passes the balance hooks and the three notes;
  remove the `.fd-how` block for KIDS only (simple goals keep theirs).
- `components/goal-unit-detail-sheet/*` (+spec): info button, Saved so far and Status tiles,
  condensed tile sizes, the Everyday variant.
- `components/goal-plan-info-sheet/*` (new, +spec): the three notes.
- `pages/my-profile/*`: no code change expected; the summary spec's family figure pin moves.
- `services/dev/goals-mock.spec.ts`: pins for the sample family.
- Docs: `FREDdocs/kid-cost-figures.md` gains the per-kid everyday figure and the sub-line table;
  `2026-11-06-goals-quiz-and-plan-page-redesign.md` gets a dated note under the hero and the
  per-kid card pointing here; a backlog story through backlog-add.

House rules that apply: no em dashes in copy; "~$1,910" not "about"; Manrope and
material-symbols-outlined; light only; no PII or raw amounts to PostHog (no new events here);
never label the family-size extension or the inflation math as government numbers.

## Left to the builder

- The exact mechanism by which the sheet's tiles update after a balance save (a signal input,
  a callback that returns the new detail, or a re-read from the page; one of these, with a spec).
- Tile and card dimensions inside the sheet, within the sizes above and the existing styles.
- The dev mock's sample family (give it one kid at home and one expected, so both everyday
  states show).

## Verification

1. `kid-cost.utils.spec.ts`: the band for each age 1 to 17, 0 and 18 return 0, null returns 0,
   family-size factor by kids at home, the pin above, `everydayMonthlyToday` equals the sum of
   the per-kid figures.
2. `goal-plan.view-model.spec.ts`: `everyday[]` per kid; `monthlyTotal = investMonthly + sum`;
   status, anchors, progress, due-now and the How FRED helps figure unchanged by the everyday
   figure (same values as before for the sample family); baby fund always cash; the four
   everyday states (unborn, under one, priced, 18 and up); sub lines per band.
3. `goal.page.spec.ts`: card header = invest + everyday and hero = sum of the card headers;
   groups and blue totals render; a row tap opens the sheet (no inline balance field exists);
   the hint text; the "Update balance" chip opens the sheet for the first unfinished unit; the
   POST / PUT body carries `anchorMonthly` = `investMonthly`; the `.fd-how` block is absent for
   KIDS and present for a house goal; heroLabel "Family plan"; a house goal's chip still scrolls
   to its inline tile.
4. Sheet specs: Saved so far saves on blur through the page's `updateBalance`; Status word and
   color; the Everyday variant hides balance and status; the info button opens the info sheet
   with the three notes.
5. `npx ng build --configuration dev`, `npm run lint`, `npm run test:events-documented`, em-dash
   sweep of the touched files; full Karma against the known FRED-226 set.
6. Device: 390px; the two groups and totals; a row tap; the balance edit saving from the sheet;
   the hero count-up on the bigger figure; the kid-card pager still swipes with rows as buttons.
