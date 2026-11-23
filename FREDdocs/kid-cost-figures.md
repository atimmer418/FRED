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
| Family-size factor | 1 kid or fewer: 1.27. 2 kids: 1.00. 3 kids: 0.76 (USDA). 4 kids: 0.64. 5 kids: 0.568. 6 kids: 0.52 (FRED estimate, see below). Six is the cap. |
| Per child to 18 | round(233,610 x inflation x size factor) |
| Family total | round(233,610 x inflation x size factor x kids). Never per-child total x kids: the rounded per-child number gives a different answer. |
| Stage total | sum over the stage's bands of 3 x band annual x inflation x size factor |
| Stage annual and monthly | annual = stage total / years in the stage; monthly = annual / 12 |
| First-year target for a kid of birth rank r | (0-2 annual x inflation x first-year factor for r + $2,743), rounded to cents, then rounded UP to the next $100. The first-year factor is 1.27 / 1.00 / 0.76 for rank 1 / 2 / 3 and later; the 4, 5 and 6 kid estimates never apply here. |
| Monthly cost, per kid | per child to 18 / 216 months, nearest $10 |
| Monthly cost, family | per child to 18 x kids / 216 months, nearest $10 |

### Four, five and six kids (FRED estimate)

USDA publishes the family-size adjustment only for one child, two children and "three or more". FRED
extends it for four, five and six kids with USDA's own marginal cost of a third child, and these
three factors must be labeled "FRED estimate" wherever they are shown (they are not government numbers).

In family-size units (per-kid factor x kids), USDA gives 2 x 1.00 = 2.00 for two kids and
3 x 0.76 = 2.28 for three, so the third child adds 0.76 x 3 - 1.00 x 2 = 0.28. FRED applies the
same 0.28 to the fourth, fifth and sixth child:

- Four kids: (2.28 + 0.28) / 4 = 2.56 / 4 = **0.64** per kid
- Five kids: (2.56 + 0.28) / 5 = 2.84 / 5 = **0.568** per kid
- Six kids: (2.84 + 0.28) / 6 = 3.12 / 6 = **0.52** per kid

Constants: `USDA_FAMILY_SIZE_FACTORS` and `FRED_ESTIMATE_FAMILY_SIZE_FACTORS` in
`frontend/src/app/constants/kid-costs.ts`.

Stages: 0-2 is the 0-2 band (3 years); 3-5 is the 3-5 band (3 years); 6-11 is the 6-8 and 9-11
bands (6 years); 12-17 is the 12-14 and 15-17 bands (6 years). Stage figures are kept unrounded;
the view rounds them for display.

Pinned results (Nov 2026 constants):

| Family | Per child | Family total |
|---|---|---|
| Only child | $421,763 | $421,763 |
| Two children | $332,097 | $664,193 |
| Three children | $252,393 | $757,180 |
| Four children (FRED estimate) | $212,542 | $850,167 |
| Five children (FRED estimate) | $188,631 | $943,155 |
| Six children (FRED estimate) | $172,690 | $1,036,142 |

Monthly, per kid / family: 1 kid $1,950 / $1,950; 2 kids $1,540 / $3,070; 3 kids $1,170 / $3,510;
4 kids $980 / $3,940; 5 kids $870 / $4,370; 6 kids $800 / $4,800.

First-year targets by birth rank: rank 1 $25,700; rank 2 $20,800; rank 3 and later $16,500.

## Everyday figure per kid

The kid card on the family plan page shows what a kid costs this month, as FRED's own estimate (the
inflation math and the family-size extension are never government numbers).

Formula, per kid: USDA band annual for the kid's age x inflation factor x family-size factor for the
kids at home / 12, rounded to the nearest $10 per kid. The family figure is the sum of the per-kid
rounded figures. Kids at home are the kids aged 0 to 17 today. The figure is $0 under one (the baby
fund is year one) and at 18 and up.

Sub line under the Everyday row, by USDA band:

| Age | Sub line |
|---|---|
| under 1 | (no row amount) The baby fund covers year one |
| 1 to 2 | Food, clothes, diapers and childcare |
| 3 to 5 | Food, clothes, preschool and toys |
| 6 to 8 | Food, clothes, school and activities |
| 9 to 11 | Food, clothes, sports and school trips |
| 12 to 14 | Food, clothes, a phone and activities |
| 15 to 17 | Food, clothes, driving and activities |

Row label: "Age N costs" (whole years). Example, one kid aged 2: 12,680 x 1.421586 x 1.27 / 12 = 1,907.6,
so ~$1,910.

Where it is shown: the kid card's Everyday group, the Everyday row's detail sheet, and the "What the
monthly covers" note. The card header and the "Family plan" hero add it to the invested units.

As of 2026-11-10 the baby fund is always cash-planned (the Cash pill, cash rate), never invested.
Design: `docs/superpowers/specs/2026-11-10-family-everyday-plan-design.md`.

## The January gift lump (gift days)

**Removed from the plan page 2026-11-08.** Kids plans are monthly only. `giftDays` is still a field
on the kids config (old rows load) but nothing reads it: no lump posts, live or at the anchor. The
`proposeKidAccountPlan` option is still there and spec'd; every caller passes `giftDays: false`.
The rest of this section describes the feature as it was.

Kids plans no longer have birthday or New Year rituals (the old sized birthday and New Year lumps,
the friendly rounding and the shrink loop are gone). In their place is one opt-in switch, gift
days, stored as `giftDays` on the kids config (missing reads as off; flipping it re-anchors the
plan). When it is on, every kid gets a flat **$1,000 every January** inside that kid's horizon,
posted to the nest egg when the kid has one and to the 529 otherwise, and the monthly is solved net
of the lumps. The offer only exists when the horizon is at least 24 months and the lumps alone fall
short of the target. No birthday is ever asked for.

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
