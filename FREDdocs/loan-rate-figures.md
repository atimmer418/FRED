# Loan Rate Figures

The house and car goals plan the down payment, then show the loan that follows as its own line at a
rate the user sets on a slider. The rate each slider starts from comes from one file:
`frontend/src/app/constants/loan-rates.ts`. The payment math lives in
`frontend/src/app/utils/loan.utils.ts`, and `loan.utils.spec.ts` pins it.

## The figures

Both read from the publisher's own page on 2026-11-21. Quoted exactly, in percent, not rounded.

| Constant | Value | Source | Series | Period |
|---|---|---|---|---|
| `MORTGAGE_RATE_DEFAULT_PCT` | 7.28 | Freddie Mac, Primary Mortgage Market Survey (PMMS) | 30-year fixed-rate mortgage, U.S. weekly average | As of November 19, 2026 |
| `CAR_LOAN_RATE_DEFAULT_PCT` | 7.54 | Federal Reserve, G.19 Consumer Credit | Terms of Credit, commercial bank interest rates, new car loans, 60-month (not seasonally adjusted, APR) | 2026 Q3, preliminary (release of November 6, 2026) |

What the pages said:

- PMMS (https://www.freddiemac.com/pmms): "The 30-year fixed-rate mortgage averaged 7.28% as of
  November 19, 2026, up from last week when it averaged 7.11%."
- G.19 (https://www.federalreserve.gov/releases/g19/current/): Terms of Credit table, row "New car
  loans, 60-month", column "2026 Q3 p" reads 7.54. The monthly columns are "n.a." except August
  (also 7.54), because the Fed samples these rates in the first calendar week of the middle month of
  each quarter.

## What FRED derives

FRED's own arithmetic. Never label it as a Freddie Mac or Federal Reserve number.

| Derived value | Formula |
|---|---|
| Loan amount | price minus the down payment |
| Monthly payment | P x r / (1 - (1 + r)^-n), r = rate / 100 / 12, nearest dollar. 0% divides evenly. No principal or no months is $0. |
| Mortgage term | 360 months (30 years), the PMMS series term |
| Car loan term | 60 months, the G.19 series term |

The mortgage line says "before taxes and insurance" because the payment is principal and interest only.

## How to refresh

1. Mortgage: open https://www.freddiemac.com/pmms. PMMS publishes Thursdays at noon ET (Wednesday when
   a holiday falls on Thursday). Copy the 30-year fixed-rate figure and its "as of" date exactly.
2. Car: open https://www.federalreserve.gov/releases/g19/current/. G.19 publishes monthly (dates at
   https://www.federalreserve.gov/releases/g19/default.htm). In the Terms of Credit table, read "New car loans, 60-month" from the latest
   quarter column that has a number (it may carry a "p" for preliminary). Note the release date.
3. Use only the publisher's page. If it cannot be read, leave the old figure in place; never type one
   from memory or copy one from a third-party site.
4. Update the constant, its JSDoc, the matching `LOAN_RATE_SOURCES` period, and the table above.
   The spec does not pin the values, so no spec change is needed.
