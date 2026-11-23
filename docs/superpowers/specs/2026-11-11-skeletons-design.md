# Skeleton loading states

Date: 2026-11-11. Decided with Andy. Runs AFTER the amount tray rollout (shared files).

## Intent

Where data loads today behind a spinner, a dash, or a blank, show a skeleton of the final layout so
the screen reads as "almost there" rather than empty. Calm and quiet: light grey blocks in the shapes
of the real cards and rows, with a slow shimmer.

## Primitive

`components/skeleton/skeleton.component.ts` (`<app-skeleton>`): inputs width (css length), height,
radius (default 8px; `pill` for 999px; `circle`), optional `lines` (n stacked text lines, last one
shorter). Block fill #eef1f6 with a shimmer sweep (linear-gradient #eef1f6 to #f8fafc to #eef1f6,
translateX via element.animate, 1.4s, infinite; static under reduced motion). `aria-hidden="true"`,
and the loading region carries `aria-busy="true"`. Standalone, no Ionic dependency. Spec it.

## Screens, in order

1. Portfolio dashboard (components/portfolio-dashboard): replace the spinner + "Loading your
   portfolio..." block with skeleton hero card (eyebrow, big number, badge pill), performance card (5
   chips, readout, chart rect, return row), holdings card (3 rows: 40px tile, two lines, right value).
   Keep the `data-fresh` gating and the snapshot-hydrate path exactly as they are (the Lottie launch
   cover depends on them).
2. Goal page (pages/goal): `@else` of `ready()`: hero (label, amount, band), one content card, the rate
   card, two text lines, CTA bar.
3. My profile (pages/my-profile): add a page-level "loaded" signal across the goals, schedule, dashboard
   and MFU checks; until then skeleton the percentile pill, the freedom row sub line, 2 goal rows (tile,
   name, sub, chip) and the rebalance rows. Nothing may pop in late once loaded is true.
4. Recurring investments: `isLoading` skeleton: status banner, streak row, two field rows, next-date
   row, CTA.
5. Sell / Withdraw: Sell card header + chip + 3 holding rows; Withdraw card header + balance line +
   amount field.
6. Tax documents: two section cards, each header, subtitle, two document rows. Keep LoadingController
   for the PDF open.
7. Tab 3 stat strip: three shimmer bars in the `.stat-value` slots while freedomStats is loading (the
   service's '—' sentinel stays; only the template swaps).
8. Portfolio customize: the in-card spinner becomes 5 list-row shapes.

Do not skeleton: the onboarding veil screens (that wait is for fonts, not data), button spinners, the
AI chat thinking row, MFU.

## Cleanup allowed (dead code only)

Remove the unused `.mfu-skeleton-*` CSS in monthly-freedom-update.component.scss, the unused
LoadingController injection in portfolio-dashboard, and the unused ion-spinner rules in
retirement-planning.component.scss.

## Gates

Each touched screen's spec asserts the skeleton renders while loading and is gone once data lands; the
primitive's spec covers inputs and the reduced-motion path. `npx ng build --configuration dev` passes.
