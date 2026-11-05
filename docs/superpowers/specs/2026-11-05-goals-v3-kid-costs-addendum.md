# Goals v3 addendum: what a kid costs, the first-year fund, and the kids quiz

Extends `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`. Everything in that spec
still holds; this document only adds to the KIDS goal. Revised after a three-lens review (23
findings, all folded in).

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
  the engine computed; it never produces a dollar figure. Not built here; a backlog story
  records it.

Calls I made (each marked "(call)" where it appears): the first-year target goes by birth
order using the study's family-size factor; a kid who is already born gets the cost figures but
no first-year fund; an undecided kid gets a 36-month runway with a stored end date; the fund is
off by default when a kid is under 12 months away; college is left out of the cost total and
handled by the college question; quiz defaults.

## Non-goals and anti-goals

Non-goals: AI-generated text or numbers, child care modeling, income-tier switching, state or
city figures, tuition inflation modeling, tax advice about 529 plans.

Anti-goals: a cost figure with no source or year; a 2015 number presented as today's cost; a
figure FRED derived presented as a government figure; a private-source figure under a
government label; "Behind" shown to someone who followed the first-year plan; a first-year fund
that keeps asking for money after the kid arrives; a default plan that tells an expecting parent
to save thousands a month; a college chip that reads as "this covers college".

## The figures (all verified against the primary documents on 2026-11-04)

| Figure | Value | Source |
|---|---|---|
| Annual spending on a child, ages 0-2 / 3-5 / 6-8 / 9-11 / 12-14 / 15-17 | $12,680 / $12,730 / $12,350 / $13,180 / $13,030 / $13,900 | USDA, Expenditures on Children by Families, 2015 (Misc. Report 1528-2015, revised March 2017), Table 1, middle-income married-couple family, 2015 dollars |
| Total, birth through age 17 | $233,610 (equals 3 × the six bands) | same, Table 1 |
| Family-size factor | only child × 1.27; two children × 1.00; three or more × 0.76 each | same, Table 1 footnote and Table 8 |
| Not included | college, prenatal and birth costs, anything after 17 | same, p. 20 |
| CPI-U, all items, not seasonally adjusted | 2015 annual average 237.017; September 2026 336.940 | U.S. Bureau of Labor Statistics, series CUUR0000SA0 |
| Out-of-pocket cost of pregnancy, birth and postpartum care, employer plans | $2,743 (2023 dollars; private source, not a government figure) | Peterson-KFF Health System Tracker, published Sept 9 2025 |
| College, one year, 2025-26 | in-state public tuition and fees $11,950; in-state public full cost of attendance $30,990; private nonprofit tuition and fees $45,000 | College Board, Trends in College Pricing and Student Aid 2025 |

The USDA study is still the most recent edition (USDA's own page, checked 2026-11-04).

Derived by FRED (never labeled as a government number). Every dollar value is computed from
unrounded inputs and rounded once, last:

- Inflation factor = 336.940 ÷ 237.017 = **1.421586**.
- Per child to 18: only child **$421,763**; two-child family **$332,097** each (family
  $664,193); three or more **$252,393** each (three kids $757,180). The family-size factor
  assumes the kids are at home at the same time; the screen says so.
- Stages for a two-child family, per child: 0-2 **$18,026 a year** ($54,077); 3-5 **$18,097**
  ($54,290); 6-11 **$18,147** average ($108,879); 12-17 **$19,142** average ($114,850).
- **First-year target goes by birth order** (call), because in a firstborn's first year there
  is one child at home however many are planned: target = (0-2 annual figure × inflation ×
  factor for that kid's rank + $2,743) rounded UP to the next $100. Rank 1 **$25,700**; rank 2
  **$20,800**; rank 3 and later **$16,500**. Rank = position when all kids (born and planned)
  are ordered by birth month, unknown month counted as July, undecided kids last in list order.
  The birth figure stays in its own 2023 dollars (call) and the screen labels it.
- College presets, four years at 2025-26 prices: **$47,800** (in-state public tuition),
  **$123,960** (in-state public full cost), **$180,000** (private tuition). These are today's
  prices; the screen says so and says the future bill will be higher. FRED's example: at 3% a
  year, $47,800 today is about $81,000 in 18 years.

All figures live in one constants file with their source, year and URL, and
`FREDdocs/kid-cost-figures.md` says how to refresh them.

## The first-year fund

One extra account per kid who is **not yet born when the account is created**, beside the 529
and the brokerage.

- **Eligible to get a fund:** birth year undecided, or the birth month (unknown → July) is
  after the current month. Eligibility is judged when the account is created, never again.
- **Target:** by birth order as above, **snapshotted on the kid** (`firstYearTarget`). Targets
  are recomputed only when the kid count or any kid's timing changes; a constants refresh alone
  never moves a stored plan.
- **End date, stored on the kid** (`firstYearDueYear`, `firstYearDueMonth`): a decided kid's
  birth month and year; an undecided kid gets the month 36 months after the month the account
  is created (call). Stored, so the runway counts down and survives re-plans.
- **Rate, per account:** horizon from the plan's anchor date to that account's end date under
  36 months → cash at 4%; 36 or more → the market chip. This rule now applies to every kid
  account (529, brokerage, first-year) with no goal-level shortcut, so adding a college plan to
  a cash first-year-only goal plans the 529 at the market rate. A fresh undecided fund lands on
  exactly 36 months, so it is invested. A later re-plan re-evaluates: an undecided fund with
  under three years left moves to cash.
- **Plan shape:** monthly only.
- **On/off:** a `firstYearFund` switch on the goal. Default ON only when at least one kid is
  eligible and every eligible kid is undecided or at least 12 months out; otherwise default OFF
  (call). The quiz always shows what the fund adds per month before the user decides.
- **Pinned** (now = Nov 15 2026, $0 saved): rank 1 undecided at 10% → 36 months, **$616 a
  month**; rank 2 undecided → **$498**; rank 1 planned for Nov 2028 → 24 months, cash, **$1,031**;
  rank 2 planned for Nov 2028 → **$834**; rank 2 planned for Nov 2027 → 12 months, cash,
  **$1,702**; rank 2 due Dec 2026 → 1 month, **$20,800**.
- **Validity:** a kids goal is valid when the 529 target > 0, or the brokerage target > 0, or
  the fund is on with at least one kid holding a fund.

### After the end date

A fund whose end month has arrived (horizon 0) is **finished**: it asks for no monthly, shows no
"due now" amount, and leaves the hero total, the status and the progress bar entirely.

- Decided kid: "{label}: $B saved of $T for the first year."
- Undecided kid: "{label}: $B saved of $T. Three years are up, so set a date to keep planning."
- A finished fund stays stored until the user turns the fund off or removes the kid. Its row is
  hidden once the end date is more than 12 months past.
- The server never rejects a first-year account that is already stored. The "kid must not be
  born yet" rule applies only to an account that is new relative to the stored goal.

### Status

First-year accounts are graded on their own path, not folded into the 529 and brokerage sums:
expected = the existing expected value for the 529 and brokerage sums + for each unfinished
first-year account `futureValue(anchorBalance, anchorMonthly, min(monthsLogged, monthsFromAnchorToEnd), thatAccountRate, [])`.
Contributions stop being expected at the end date, and a cash fund is graded at the cash rate.
Balance and target sums include unfinished first-year accounts only.

### Preview equals what is saved

In edit mode, when a plan input has changed, the page drafts the goal as anchored now, exactly
as the server will store it, so the monthly shown before "Save changes" is the monthly shown
after.

## Data model changes

- Frontend `KidInput` gains `firstYearTarget?: number | null`, `firstYearDueYear?: number | null`,
  `firstYearDueMonth?: number | null`; `KidInput.accounts` gains `firstYear?: AccountState | null`;
  `KidsConfig` gains `firstYearFund?: boolean | null`. Optional and nullable because stored goals
  arrive with explicit nulls.
- Backend `KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids, Boolean firstYearFund)`;
  `Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts, BigDecimal firstYearTarget, Integer firstYearDueYear, Integer firstYearDueMonth)`;
  `Accounts(AccountState brokerage, AccountState plan529, AccountState firstYear)`. No migration:
  config is JSON in a TEXT column and missing properties deserialize as null.
- Server validation. Fund OFF (false or null): every kid's `firstYear`, `firstYearTarget` and due
  fields are stored as null (normalized, not rejected). Fund ON: at least one kid has a
  `firstYear` account; a kid with one needs `firstYearTarget` in (0, 100,000,000] scale ≤ 2 and a
  due month 1 to 12 with a due year; its balance and `anchorMonthly` follow the existing bounds.
  A first-year account that is NEW (create, or no stored first-year account at that kid index)
  additionally needs the kid's `birthYear` null or ≥ the current year and a due year from the
  current year to the current year + 11. A stored account is always accepted.
- Server re-anchor comparison (KIDS) also compares `firstYearFund` and, per kid, whether a
  first-year account is present, its target and its due date. Anchor rules apply to the third
  account exactly as to the other two.
- Deploy the backend before any frontend build that sends the new fields.

## Screens (KIDS only)

### Setup step 1 becomes a quiz

Five cards, one at a time, with Back and Next and a row of progress dots. Each card validates on
Next. Edit mode opens the same quiz from the summary row. Going back from the plan returns to
card 1; a failed save jumps to the first card with an error.

1. **"How many kids are you planning for?"** Stepper 1 to 10. Helper: "Count the ones already
   here too."
2. **"When?"** One row per kid: a name, month and year, and "Not sure yet". Same bounds as today
   (born up to 17 years ago, or planned up to 10 years out).
3. **"What a kid costs"** (shown before any saving question, as Andy asked):
   - "About $332,000 per child to 18 in a two-child family." (wording by family size, below)
   - "The first year alone, with birth costs: about $25,700 for a first child."
   - Source line: "National averages for a middle-income, two-parent family. USDA figures from
     2015, adjusted to September 2026 dollars by FRED. Birth cost from Peterson-KFF, 2023
     dollars, with employer insurance."
   - When a kid is eligible: a switch "Plan for the first year" with the live line "Adds about
     $X a month until Mon YYYY". When any eligible kid is under 12 months out: "That is a lot to
     save in N months. Most of the first year is paid from your income as you go, so save what
     you can." When turning it off would drop logged balances: "Turning this off removes the $X
     you logged for it from this plan."
   - When no kid is eligible: "Your kids are already here, so there is no first-year fund to
     plan."
4. **"How much do you want to set aside for college?"** Selection rows (label left, amount
   right): "In-state public tuition, at today's prices" $47,800; "In-state public full cost, at
   today's prices" $123,960; "Private college tuition, at today's prices" $180,000; "Another
   amount" (input); "None for now". Helper: "This goes in a 529, a state college savings plan
   you open outside FRED. Per kid, four years at 2025-26 prices from the College Board. College
   will likely cost more by the time your kid starts: at 3% a year, $47,800 today is about
   $81,000 in 18 years. Raise the amount any time." Default: in-state public tuition (call).
5. **"How much do you want each kid to start adult life with?"** Selection rows: "$25,000",
   "$50,000", "$100,000", "$250,000", "Another amount", "Nothing extra". Helper: "A custodial or
   regular brokerage account you open outside FRED." Default: $25,000 (call). Then the goal name
   and the CTA "Show my plan".

### Plan view

Order: hero, return assumption, inputs row, status, **What a kid costs**, **First-year fund**,
529 plans, Brokerage.

- **Hero.** The monthly and per-paycheck totals include unfinished first-year funds. With a fund
  and other targets: a line under the headline, "$F of this is the first-year fund, which ends
  Mon YYYY." A fund-only goal's headline is "Ready for the first year" and it never says "18th
  birthday" or "18-year plan"; its my-profile subtitle is "$M/mo · N kids · first-year fund".
- **What a kid costs.** Open by default in setup, collapsed in edit mode behind "Show costs by
  age" (the headline, family line and one-line source stay visible).
  - Headline by family size: "About $422,000 to raise an only child to 18" / "About $332,000
    per child to 18 in a two-child family" / "About $252,000 per child to 18 in a family of
    three or more" (nearest $1,000).
  - More than one kid: "For your 3 kids, about $757,000 in all, if they are home at the same
    time."
  - Four rows, "about $X a year, $Y a month" (nearest $100 and $10): "Baby and toddler, 0 to
    2", "Preschool, 3 to 5", "Grade school, 6 to 11", "Teen years, 12 to 17".
  - "Pregnancy and birth, with employer insurance: about $2,700 out of pocket (Peterson-KFF,
    2023 dollars)".
  - Note: "Yearly costs are national averages for a middle-income, two-parent family: USDA
    figures from 2015, adjusted to September 2026 dollars by FRED. Families with more children
    spend less on each one, and an only child costs more (USDA). Your costs will differ. College
    is not included" followed by ", which is what the 529 below is for." when a college amount
    is set, otherwise ". You can add a college plan from Edit."
  - "Where these numbers come from" lists the three cost sources (USDA, BLS, Peterson-KFF) with
    years. The College Board is credited on the college question.
- **First-year fund** (when any kid holds a fund):
  - Per kid: "{label}: about $T for birth costs and the first year, at today's prices."
  - Decided, unfinished: "$M a month until Mon YYYY." Cash rows add "Planned as cash at about
    4%, since you need it within 3 years."
  - Undecided, unfinished: "$M a month until Mon YYYY. No date yet, so this one is invested at
    your return assumption."
  - Finished rows use the two lines in "After the end date".
  - A "Saved so far" tile per fund.
  - Under the card when any row is undecided: "Markets can be down when the day comes, so treat
    this as a head start. Once you pick a date less than three years away, FRED plans this fund
    as cash."
  - Footer: "FRED estimate: first-year average (USDA, adjusted) plus birth costs (Peterson-KFF),
    rounded up. Keep this in your own savings or brokerage account. FRED does not hold or move
    goal money."
- **529 plans** and **Brokerage** cards: unchanged, each hidden when its target is 0. When a
  college preset is selected the 529 card adds "Based on 2025-26 college prices, which tend to
  rise."
- The collapsed inputs row reads like "3 kids · $48K college · $25K at 18 · first-year fund ·
  Edit", dropping any part that is off.

## Follow-up recorded, not built

Backlog story: "Goals: AI-written personal plan summary". The backend sends the computed plan
(fixed numbers) to Claude, which writes a short personal summary through the compliance gate,
the Monthly Market Breakdown pattern; plus a read-only `get_goals` tool for Ask FRED. The AI
never produces or changes a dollar figure.

## Verification

- Constants spec: bands sum to $233,610 ÷ 3; factor 1.421586; per-child totals $421,763 /
  $332,097 / $252,393 and family totals $664,193 / $757,180 from unrounded inputs; first-year
  targets by rank $25,700 / $20,800 / $16,500 / $16,500; stage totals sum to the per-child total
  within $2; college presets $47,800 / $123,960 / $180,000; cost-card sources exclude the
  College Board.
- Math spec: the six pinned first-year plans; eligibility at the month boundary; an undecided
  end date of anchor month + 36; a kid due next month has n = 1; a finished fund has monthly 0
  and no due-now.
- View-model specs: a fund counts down from its stored end date when viewed later; status reads
  on plan for a fully funded fund six months after its end date and for a cash fund beside a
  market 529 (24 months of $834 at 4% is $20,802); a finished fund leaves the totals; adding a
  529 to a cash first-year-only goal plans the 529 at the market rate ($80 a month for $47,800
  over 216 months); a fund-only goal never says 18th birthday; the preview before save equals
  the plan after save; a stored goal with explicit nulls for every new field renders.
- Backend tests: fund on needs a kid with a fund; a NEW fund on a kid born last year is rejected
  while a STORED one survives the new year; fund off strips every first-year field; anchors on
  the third account; re-anchor when the switch, a target or a due date changes; a
  first-year-only kids goal is valid; a legacy stored goal still updates.
- Page specs: the five cards in order with the cost card third; defaults; the switch default
  rule and its live monthly; presets set the targets; the plan view order; headline wording by
  family size; decided, undecided and finished fund rows; outside-FRED lines present; no em
  dash.
- Gates: gradle tests, karma (only the 14 known failures), AOT build, lint, events-documented.
