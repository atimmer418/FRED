# Kid Cost Figures

Every "what a kid costs" number in the app comes from one file:
`frontend/src/app/constants/kid-costs.ts`. The math on top of it lives in
`frontend/src/app/utils/kid-cost.utils.ts`, and `kid-cost.utils.spec.ts` pins the results. This page
says where each number came from, what FRED derives from them, and how to refresh them.

## What the app shows and where

The KIDS goal (Goals v3) shows:

- **What a kid costs**: a per-child and family total to age 18, four life stages with a yearly and
  monthly figure, and the out-of-pocket birth cost. Shown on the quiz's third card and on the plan view.
- **The first-year fund**: a savings target per kid not yet born, built from the 0-2 figure plus the
  birth cost.
- **College presets**: four years at today's College Board prices, offered on the quiz's college question.

## The figures

All verified against the primary documents on 2026-11-04.

| Figure | Value | Source | Table / page | Dollar-year |
|---|---|---|---|---|
| Annual spending on a child, ages 0-2 / 3-5 / 6-8 / 9-11 / 12-14 / 15-17 | $12,680 / $12,730 / $12,350 / $13,180 / $13,030 / $13,900 | USDA, Expenditures on Children by Families (Misc. Report 1528-2015, revised March 2017) | Table 1, middle-income married-couple family | 2015 |
| Total, birth through 17 | $233,610 (equals 3 x the six bands) | same | Table 1 | 2015 |
| Family-size factor | only child x 1.27; two children x 1.00; three or more x 0.76 each | same | Table 1 footnote and Table 8 | 2015 |
| CPI-U, all items, not seasonally adjusted | 2015 annual average 237.017; September 2026 336.940 | U.S. Bureau of Labor Statistics, series CUUR0000SA0 | CPI-U | n/a |
| Out-of-pocket pregnancy, birth and postpartum care, employer plans | $2,743 | Peterson-KFF Health System Tracker, published Sept 9 2025 | brief | 2023 (private source) |
| College, one year: in-state public tuition and fees / in-state public full cost of attendance / private nonprofit tuition and fees | $11,950 / $30,990 / $45,000 | College Board, Trends in College Pricing and Student Aid 2025 | 2025 report | 2025-26 |

URLs (also in `KID_COST_SOURCES`):

- USDA: https://www.fns.usda.gov/sites/default/files/resource-files/crc2015-march2017.pdf
- BLS: https://www.bls.gov/cpi/
- Peterson-KFF: https://www.healthsystemtracker.org/brief/health-costs-associated-with-pregnancy-childbirth-and-postpartum-care/
- College Board: https://research.collegeboard.org/media/pdf/Trends-in-College-Pricing-and-Student-Aid-2025-final_1.pdf

The USDA study is the most recent edition as of 2026-11-04 (USDA's own page).

## What FRED derives

These are FRED's own arithmetic. Never label them as government numbers. Every dollar value is
computed from unrounded inputs and rounded once, at the end.

| Derived value | Formula |
|---|---|
| Inflation factor | CPI-U latest / CPI-U 2015 average = 336.940 / 237.017 = 1.421586 |
| Family-size factor | 1 kid or fewer: 1.27. 2 kids: 1.00. 3 or more: 0.76 |
| Per child to 18 | round(233,610 x inflation x size factor) |
| Family total | round(233,610 x inflation x size factor x kids). Never per-child total x kids: the rounded per-child number gives a different answer. |
| Stage total | sum over the stage's bands of 3 x band annual x inflation x size factor |
| Stage annual and monthly | annual = stage total / years in the stage; monthly = annual / 12 |
| First-year target for a kid of birth rank r | (0-2 annual x inflation x size factor for r + $2,743), rounded to cents, then rounded UP to the next $100 |

Stages: 0-2 is the 0-2 band (3 years); 3-5 is the 3-5 band (3 years); 6-11 is the 6-8 and 9-11
bands (6 years); 12-17 is the 12-14 and 15-17 bands (6 years). Stage figures are kept unrounded;
the view rounds them for display.

Pinned results (Nov 2026 constants):

| Family | Per child | Family total |
|---|---|---|
| Only child | $421,763 | $421,763 |
| Two children | $332,097 | $664,193 |
| Three children | $252,393 | $757,180 |

First-year targets by birth rank: rank 1 $25,700; rank 2 $20,800; rank 3 and later $16,500.

Birth rank is the kid's position when all kids (born and planned) are ordered by birth month, with
an unknown month counted as July and undecided kids last in list order. Rank 1 behaves like an only
child, because in a firstborn's first year there is one child at home however many are planned.

College presets are four times the one-year figure: $47,800 / $123,960 / $180,000.

The family-size factor assumes the kids are at home at the same time. The screen says so.

## What is excluded, and who the study describes

The USDA figures leave out college, prenatal and birth costs, and anything after age 17 (study p. 20).
They describe a **middle-income, married-couple family**, national averages. They are not a forecast
for any one family, and the app says "your costs will differ".

## The birth figure

The Peterson-KFF number ($2,743, employer plans) is a private-source figure in **2023 dollars**. FRED
does not inflate it. It stays in its own dollar-year and the screen labels it "2023 dollars". Do not
put it under a government label.

## College copy note

The college helper says: "at 3% a year, $47,800 today is about $81,000 in 18 years." That is a fixed
string in the app copy, not plan math. It is computed as 47,800 x 1.03^18 = 81,376 (yearly
compounding). **When the College Board figures are refreshed, recompute this by hand** and update the
string if the in-state public tuition total changes.

## Refresh procedure

1. **Once a year**, update `CPI_U_LATEST` and `CPI_U_LATEST_LABEL` from BLS series CUUR0000SA0 (all
   items, not seasonally adjusted). Update the BLS entry's year in `KID_COST_SOURCES` to match.
2. **Each November**, update the College Board figures (`COLLEGE_ONE_YEAR`, `COLLEGE_PRICE_YEAR_LABEL`
   and the source entry). Then redo the 3% example above.
3. **Check for new editions** of the USDA study (USDA's Expenditures on Children by Families page)
   and the Peterson-KFF brief. A new USDA edition changes the bands, the total, the factors and the
   CPI base year, so treat it as a rework of this file and the spec pins, not a one-line edit.
4. **Update the spec pins** in `kid-cost.utils.spec.ts` in the same commit. The pins (inflation
   factor, per-child and family totals, stage totals, first-year targets, college presets) are
   there to make a refresh visible.

A refresh never moves an existing plan: each kid's first-year target is snapshotted on the kid when
the plan is created or the kid count or timing changes. New numbers show up for new or re-planned
goals only.
