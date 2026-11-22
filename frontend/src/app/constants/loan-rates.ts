// Loan rate figures. The default rate the house and car loan sliders start from comes from this one file.
//
// Each default is a published figure quoted exactly, with the series and the as-of week or quarter
// in the comment beside it. The monthly payment FRED shows from it is FRED's own arithmetic
// (utils/loan.utils.ts) and must never be labeled as a Freddie Mac or Federal Reserve number.
//
// Refresh steps live in FREDdocs/loan-rate-figures.md.

/**
 * Freddie Mac Primary Mortgage Market Survey (PMMS), 30-year fixed-rate mortgage, U.S. weekly
 * average: 7.28% as of November 19, 2026 (read from https://www.freddiemac.com/pmms on 2026-11-21).
 */
export const MORTGAGE_RATE_DEFAULT_PCT = 7.28;

/**
 * Federal Reserve G.19 Consumer Credit, Terms of Credit, commercial bank interest rates, new car
 * loans, 60-month: 7.54% for 2026 Q3 (preliminary), release of November 6, 2026 (read from
 * https://www.federalreserve.gov/releases/g19/current/ on 2026-11-21). Not seasonally adjusted, APR.
 */
export const CAR_LOAN_RATE_DEFAULT_PCT = 7.54;

/** FRED's mortgage term: 30 years (matches the PMMS series). */
export const MORTGAGE_TERM_MONTHS = 360;

/** FRED's car loan term: 60 months (matches the G.19 series). */
export const CAR_LOAN_TERM_MONTHS = 60;

/** Where each default rate comes from. `usedIn` names the goal whose slider starts from it. */
export const LOAN_RATE_SOURCES: ReadonlyArray<{
  name: string;
  period: string;
  url: string;
  usedIn: 'HOUSE' | 'CAR';
}> = [
  {
    name: 'Freddie Mac, Primary Mortgage Market Survey, 30-year fixed-rate mortgage',
    period: 'Week of November 19, 2026',
    url: 'https://www.freddiemac.com/pmms',
    usedIn: 'HOUSE',
  },
  {
    name: 'Federal Reserve, G.19 Consumer Credit, new car loans at commercial banks, 60-month',
    period: '2026 Q3 (preliminary)',
    url: 'https://www.federalreserve.gov/releases/g19/current/',
    usedIn: 'CAR',
  },
];
