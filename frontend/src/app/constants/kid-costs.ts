// Kid cost figures. Every number shown about what a kid costs comes from this one file.
//
// Government and private figures below are quoted exactly as published, with the table and
// dollar-year in the comment beside each. Anything FRED computes from them (inflation-adjusted
// costs, per-child and family totals, stage averages, first-year targets) is FRED's own
// arithmetic and must never be labeled as a government number.
//
// The Peterson-KFF birth figure is a private-source figure in 2023 dollars. It is left
// unadjusted on purpose and must be labeled with its dollar-year wherever it appears.
//
// Refresh steps live in FREDdocs/kid-cost-figures.md.

/** USDA annual spending on a child by age band: Table 1, middle-income married-couple family, 2015 dollars. */
export const USDA_2015_ANNUAL_BY_BAND: ReadonlyArray<{ fromAge: number; toAge: number; annual: number }> = [
  { fromAge: 0, toAge: 2, annual: 12680 },
  { fromAge: 3, toAge: 5, annual: 12730 },
  { fromAge: 6, toAge: 8, annual: 12350 },
  { fromAge: 9, toAge: 11, annual: 13180 },
  { fromAge: 12, toAge: 14, annual: 13030 },
  { fromAge: 15, toAge: 17, annual: 13900 },
];

/** USDA total spending on a child, birth through age 17: Table 1, middle-income married-couple family, 2015 dollars. */
export const USDA_2015_TOTAL = 233610;

/** USDA per-child family-size factors: Table 1 adjustment, only child, two children, three or more. */
export const USDA_FAMILY_SIZE_FACTORS = { one: 1.27, two: 1, threeOrMore: 0.76 };

/**
 * FRED estimate, not a USDA figure: per-kid factors for four, five and six kids. USDA stops at
 * "three or more", so FRED applies the third child's marginal cost (0.76 x 3 - 1.00 x 2 = 0.28) to
 * the fourth, fifth and sixth: (2.28 + 0.28) / 4 = 0.64, (2.56 + 0.28) / 5 = 0.568 and
 * (2.84 + 0.28) / 6 = 0.52. See FREDdocs/kid-cost-figures.md.
 */
export const FRED_ESTIMATE_FAMILY_SIZE_FACTORS = { four: 0.64, five: 0.568, six: 0.52 };

/** BLS CPI-U, all items, not seasonally adjusted, series CUUR0000SA0: 2015 annual average. */
export const CPI_U_2015_AVERAGE = 237.017;

/** BLS CPI-U, all items, not seasonally adjusted, series CUUR0000SA0: latest month used. */
export const CPI_U_LATEST = 336.94;

/** Month name for CPI_U_LATEST, shown to the user as the "as of" label. */
export const CPI_U_LATEST_LABEL = 'September 2026';

/** Peterson-KFF Health System Tracker (published Sept 9 2025): out-of-pocket pregnancy, birth and postpartum care, employer plans, 2023 dollars. */
export const CHILDBIRTH_OUT_OF_POCKET = 2743;

/** Dollar-year label that must accompany CHILDBIRTH_OUT_OF_POCKET wherever it is shown. */
export const CHILDBIRTH_DOLLAR_YEAR_LABEL = '2023 dollars';

/** College Board, Trends in College Pricing and Student Aid 2025: one year, 2025-26 prices. */
export const COLLEGE_ONE_YEAR = {
  publicTuition: 11950, // in-state public four-year tuition and fees, 2025-26
  publicFullCost: 30990, // in-state public four-year full cost of attendance, 2025-26
  privateTuition: 45000, // private nonprofit four-year tuition and fees, 2025-26
};

/** Price-year label for COLLEGE_ONE_YEAR. */
export const COLLEGE_PRICE_YEAR_LABEL = '2025-26';

/** FRED's own "start adult life with" choices (not a sourced figure). */
export const AT_18_PRESETS: ReadonlyArray<number> = [25000, 50000, 100000, 250000];

/** Where each figure comes from. `usedIn` keeps the College Board off the cost card. */
export const KID_COST_SOURCES: ReadonlyArray<{
  name: string;
  year: string;
  url: string;
  usedIn: 'costs' | 'college';
}> = [
  {
    name: 'USDA, Expenditures on Children by Families',
    year: '2015 (published 2017)',
    url: 'https://www.fns.usda.gov/sites/default/files/resource-files/crc2015-march2017.pdf',
    usedIn: 'costs',
  },
  {
    name: 'U.S. Bureau of Labor Statistics, Consumer Price Index',
    year: 'September 2026',
    url: 'https://www.bls.gov/cpi/',
    usedIn: 'costs',
  },
  {
    name: 'Peterson-KFF Health System Tracker, pregnancy and childbirth costs',
    year: '2025, in 2023 dollars',
    url: 'https://www.healthsystemtracker.org/brief/health-costs-associated-with-pregnancy-childbirth-and-postpartum-care/',
    usedIn: 'costs',
  },
  {
    name: 'College Board, Trends in College Pricing and Student Aid',
    year: '2025',
    url: 'https://research.collegeboard.org/media/pdf/Trends-in-College-Pricing-and-Student-Aid-2025-final_1.pdf',
    usedIn: 'college',
  },
];
