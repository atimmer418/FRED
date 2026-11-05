# Goals v3 addendum: what a kid costs, the first-year fund, and the kids quiz

Extends `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`. Everything in that spec
still holds; this document only adds to the KIDS goal.

## Context

Andy, 2026-11-04/05: the kids goal should talk about how expensive kids are. It should show
what a kid costs at points in their life and in total, give a plan to prepare for it, let
someone who has not decided when to have a kid start preparing by investing, and show all of
that before the 529 and brokerage plans. He also wants the plan to feel personal to how many
kids someone wants and when, through a short quiz.

Decisions (Andy):

- The savings plan under the cost figures covers the **first year**: out-of-pocket birth costs
  plus first-year costs, saved by the time the kid arrives.
- Figures are the **national middle-income** set, one set, with source and year shown.
- Figures are shown in **today's dollars**, adjusted for inflation by FRED and labeled so.
- The kids setup becomes a **short quiz** that feeds the tested plan math.
- An **AI-written personal summary** is a separate follow-up project. The AI explains numbers
  the engine computed; it never produces a dollar figure. Not built here; a backlog story records it.

Calls I made (marked "(call)" below): family-size adjustment from the same study; a kid who is
already born gets the cost figures but no first-year fund; an undecided kid gets a 36-month
runway fixed at the plan's start; college is left out of the cost total and handled by the 529
question; the first-year fund can be switched off.

## Non-goals and anti-goals

Non-goals: AI-generated text or numbers, child care modeling, income-tier switching, state or
city figures, tuition inflation modeling, tax advice about 529 plans.

Anti-goals: a cost figure with no source or year; a 2015 number presented as today's cost; a
figure FRED derived presented as a government figure; a first-year fund for a child who is
already two; a plan that silently grows the monthly total with no way to turn the fund off.

## The figures (all verified against the primary documents on 2026-11-04)

| Figure | Value | Source |
|---|---|---|
| Annual spending on a child, ages 0-2 / 3-5 / 6-8 / 9-11 / 12-14 / 15-17 | $12,680 / $12,730 / $12,350 / $13,180 / $13,030 / $13,900 | USDA, Expenditures on Children by Families, 2015 (Misc. Report 1528-2015, revised March 2017), Table 1, middle-income married-couple family, 2015 dollars |
| Total, birth through age 17 | $233,610 (equals 3 × the six bands) | same, Table 1 |
| Family-size factor | only child × 1.27; two children × 1.00; three or more × 0.76 each | same, Table 1 footnote and Table 8 |
| Not included | college, prenatal and birth costs, anything after 17 | same, p. 20 |
| CPI-U, all items, not seasonally adjusted | 2015 annual average 237.017; September 2026 336.940 | U.S. Bureau of Labor Statistics, series CUUR0000SA0 |
| Out-of-pocket cost of pregnancy, birth and postpartum care, employer plans | $2,743 (2023 dollars) | Peterson-KFF Health System Tracker, Sept 9 2025 |
| College, one year, 2025-26 | in-state public tuition and fees $11,950; in-state public full cost of attendance $30,990; private nonprofit tuition and fees $45,000 | College Board, Trends in College Pricing and Student Aid 2025 |

The USDA study is still the most recent edition (USDA's own page, checked 2026-11-04).

Derived by FRED (never labeled as a government number):

- Inflation factor = 336.940 ÷ 237.017 = **1.421586**.
- Per-child total to 18 for a two-child family = $233,610 × factor = **$332,097**.
- Stages (two-child family, per child): 0-2 **$18,026 a year** ($54,077); 3-5 **$18,097** ($54,290);
  6-11 **$18,147** average ($108,879); 12-17 **$19,142** average ($114,850).
- With the family-size factor: one kid **$421,763**; two kids **$332,097** each ($664,193);
  three or more **$252,393** each (three kids $757,180).
- First-year cost = the 0-2 annual figure × family-size factor. First-year target =
  (first-year cost + $2,743) rounded UP to the next $100: one kid **$25,700**; two kids
  **$20,800**; three or more **$16,500**. The birth figure stays in its own 2023 dollars (call:
  medical out-of-pocket costs do not track general CPI).
- College presets, four years at 2025-26 prices: **$47,800** (in-state public tuition),
  **$123,960** (in-state public full cost), **$180,000** (private tuition).

All figures live in one constants file with their source, year and URL, and one doc
(`FREDdocs/kid-cost-figures.md`) says how to refresh them (CPI value once a year; College Board
each November; check USDA and KFF for new editions).

## The first-year fund

One extra account per kid who is **not yet born**, beside the 529 and the brokerage:

- Eligible: birth year undecided, or the birth month (unknown month → July) is after this month.
  A kid already born never has one (call).
- Target: the first-year target above for the family's kid count, snapshotted into the goal
  when the plan is made (`firstYearTarget`), so a later constants refresh does not move an
  existing plan until the user re-plans.
- Deadline: the birth month and year. Undecided → **36 months from the plan's anchor date**,
  fixed, so the runway counts down instead of sliding (call).
- Rate: the per-account rule already in force (R13): under 36 months at the anchor → cash at
  4%; 36 or more → the market chip. An undecided kid lands on exactly 36, so that fund is
  invested, which is what "start preparing by investing" asks for.
- Plan shape: monthly only. No birthday or New Year lumps (there is no birthday yet).
- On/off: a `firstYearFund` switch on the goal, default ON when at least one kid is eligible
  (call). Off → no first-year accounts, target 0.
- Pinned (now = Nov 15 2026, target $20,800, $0 saved): undecided at 10% → 36 months,
  **$498 a month**; planned for Nov 2028 → 24 months, cash, **$834 a month**; planned for
  Nov 2027 → 12 months, cash, **$1,702 a month**.
- Status, anchors, balances: the fund is a third `AccountState` per kid and follows every
  existing rule (anchor at create and re-plan, balance-only edits keep the anchor, status over
  sums). The goal's target for status and progress adds each fund's target.
- A kids goal is valid when the 529 target > 0, or the brokerage target > 0, or the first-year
  fund is on with at least one eligible kid.

## Data model changes

- Frontend `KidInput.accounts` gains `firstYear?: AccountState | null`. `KidsConfig` gains
  `firstYearFund: boolean` and `firstYearTarget: number`.
- Backend `KidsConfig` record gains `Boolean firstYearFund, BigDecimal firstYearTarget`;
  `Accounts` gains `AccountState firstYear` (nullable). No migration: config is JSON in a TEXT
  column, and missing properties deserialize as null.
- Server validation: `firstYearFund` null → false. When true: `firstYearTarget` in
  (0, 100,000,000], scale ≤ 2, and at least one kid carries a `firstYear` account. A `firstYear`
  account is allowed only on a kid whose `birthYear` is null or ≥ the current year; its balance
  and `anchorMonthly` follow the existing bounds. When false: `firstYearTarget` is stored as 0
  and any `firstYear` accounts are dropped. The "at least one target" rule becomes the validity
  rule above.
- Server re-anchor comparison (KIDS) also compares `firstYearFund`, `firstYearTarget`, and
  whether each kid has a first-year account. Anchor rules apply to the third account exactly as
  to the other two.

## Screens (KIDS only)

### Setup step 1 becomes a quiz

Five cards, one at a time, with Back and Next and a row of progress dots. Each card validates on
Next. Edit mode opens the same quiz from the summary row.

1. **"How many kids are you planning for?"** Stepper 1 to 10. Helper: "Count the ones already
   here too."
2. **"When?"** One row per kid: a name, month and year, and "Not sure yet". Same bounds as today
   (born up to 17 years ago, or planned up to 10 years out).
3. **"How much of college do you want to cover?"** Chips: "Tuition at an in-state public college
   ($47,800)", "Full cost at an in-state public college ($123,960)", "Tuition at a private
   college ($180,000)", "Another amount" (input), "None for now". Helper: "Per kid, four years
   at 2025-26 prices from the College Board. College prices tend to rise, so revisit this every
   few years." Sets the 529 target per kid.
4. **"What should each kid have at 18?"** Chips: "$25,000", "$50,000", "$100,000", "$250,000",
   "Another amount", "Nothing extra". Helper: "Money in a brokerage account that is theirs to
   start with." Sets the brokerage target per kid.
5. **"Get ready for the first year?"** A small preview of the cost of one child (the per-child
   total and the first-year target for this family size), a switch "Plan for the first year"
   (shown only when a kid is eligible; otherwise the line "Your kids are already here, so there
   is no first-year fund to plan."), the goal name, and the CTA "Show my plan".

### Plan view: cost card first, then the fund, then 529 and brokerage

1. **"What a kid costs"** (always shown on a kids goal):
   - Headline: "About $332,000 to raise a child to 18" (the per-child figure for this family
     size, rounded to the nearest $1,000).
   - For more than one kid: "For your 3 kids, about $757,000 in all."
   - Four rows, each "about $X a year, $Y a month": "Baby and toddler, 0 to 2", "Preschool, 3 to
     5", "Grade school, 6 to 11", "Teen years, 12 to 17" (rounded to the nearest $100 a year and
     $10 a month).
   - One row: "Pregnancy and birth, with employer insurance: about $2,700 out of pocket".
   - Note: "National averages for a middle-income, two-parent family, in September 2026 dollars.
     Government figures from 2015, adjusted for inflation by FRED. Your costs will differ.
     College is not included, which is what the 529 below is for."
   - "Where these numbers come from" expands the three source names with their years.
2. **"First-year fund"** (when the fund is on): "Covers about $20,800 per kid: birth costs and
   the first year." Then one line per eligible kid: "Kid 2: $834 a month until Nov 2028" or, for
   an undecided kid, "Kid 3: $498 a month for three years, invested, so you are ready whenever
   it happens". A balance tile per fund ("Saved so far"). When cash: the existing cash label.
3. The existing "529 plans" and "Brokerage" cards, each hidden when its target is 0.

The hero monthly and per-paycheck figures include the first-year fund. The collapsed summary row
reads like "3 kids · $124K college · $100K at 18 · first-year fund · Edit". The my-profile row
subtitle format does not change.

## Follow-up recorded, not built

Backlog story: "Goals: AI-written personal plan summary". The backend sends the computed plan
(fixed numbers) to Claude, which writes a short personal summary through the compliance gate,
the Monthly Market Breakdown pattern; plus a read-only `get_goals` tool for Ask FRED. The AI
never produces or changes a dollar figure.

## Verification

- Constants spec: the six bands sum to $233,610 ÷ 3; factor 1.421586; per-child totals
  $421,763 / $332,097 / $252,393; first-year targets $25,700 / $20,800 / $16,500; stage totals
  sum to the per-child total within $2; college presets $47,800 / $123,960 / $180,000.
- Math spec: the three pinned first-year plans ($498, $834, $1,702); eligibility at the month
  boundary; undecided deadline fixed at anchor + 36 months and counting down; n = 0 → due now.
- Backend tests: fund on needs a target and an eligible kid; fund off strips accounts; a
  first-year account on a kid born last year is rejected; anchors on the third account;
  re-anchor when the switch or target changes; a first-year-only kids goal is valid.
- Page specs: the five quiz cards and their validation; presets set the targets; cost card
  renders before the fund and the account cards; figures change with kid count; fund lines for
  planned vs undecided; fund hidden when all kids are born; no em dash.
- Gates: gradle tests, karma (only the 14 known failures), AOT build, lint, events-documented.
