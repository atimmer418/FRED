# Amount tray rollout: every typeable amount field goes keyboard-free

Date: 2026-11-11. Decided with Andy. Builds on the Freedom Plan slide tray
(docs/superpowers/specs/2026-11-10-freedom-plan-slide-tray-design.md, components/plan-amount-tray,
utils/plan-amount-scale.ts), which stays exactly as it behaves today.

## Intent

No typeable amount or percentage field anywhere in the app opens the keyboard. Tapping the field opens
the same slide tray under it, the value changes live, Done or a tap outside closes it. Identifier
fields (SSN, phone, ZIP, dates, account numbers, OTP) are NOT part of this and keep the keyboard.

## Shared changes

- `utils/plan-amount-scale.ts`: a scale may now be PIECEWISE: `segments: { to, step }[]` from `min`
  (uniform finger distance per detent, dollar step grows with the amount, as the Freedom mockup did),
  plus an optional `fine` step for the minus and plus buttons (defaults to the slider step at the
  current value). Freedom Plan keeps its uniform scales and identical behaviour (its specs must not
  change). Add `buildAmountScale(min, max, fine)`: picks round-number segments from a step ladder
  (1, 5, 10, 25, 50, 100, 250, 500, 1,000, 2,500, 5,000, 10,000, 25,000, 50,000, 100,000) so the whole
  track has 80 to 160 detents, breakpoints land on round numbers, and the LAST detent is exactly `max`
  (even when max is not round, e.g. withdrawable cash $1,234.56). Pure, with its own spec.
- `components/plan-amount-tray`: supports piecewise scales (detent list from segments; hysteresis,
  thumb-follows-finger, hold gears unchanged); hairline ticks at each segment breakpoint; end labels
  from min and max; a `fine` nudge step. Add a themeable surface through CSS custom properties on the
  host (track, fill, thumb, card surface, border, label colour) with the current white-page values as
  defaults, so the blue onboarding flow can restyle it without a second component.
- Field presentation on form screens (bottom-border `.field-input` recipe from theme/_form-fields.scss):
  the `<input>` becomes a `<button type="button">` that looks identical at rest (same font, size,
  bottom border, `$` or `%` affix), shows the formatted value or the placeholder, and when active turns
  its bottom border and label #2563EB. No new glyphs. The tray renders directly below that field's row,
  full width of the card, grown and collapsed with the same WAAPI choreography as the Freedom page
  (height 240ms in / 200ms out, house easing, Done fades in the field's label row). Tapping another
  tray-enabled field on the same screen switches the tray (cross-fade, no height change). Outside tap,
  Done, submit, and leaving the page close it. Haptics per detent via tickHaptic.
- Put the page-level open/close/switch logic in ONE reusable place rather than copying the Freedom
  page: e.g. a `TrayHostDirective` or a small `useAmountTray()` helper (signals: openField, shownField,
  dragging; WAAPI grow/collapse/swap; outside-tap listener; label swap to "± $step" while dragging).
  The Freedom page may adopt it only if its specs stay green unchanged; otherwise leave Freedom as is.
- `appKeyboardAvoid` is removed from an ion-content only when NO typeable input remains on that screen.
- Existing validation stays (min/max/sum rules, toasts). A seeded value outside the scale is shown
  exactly and drawn at the nearest end; the first touch clamps onto the scale.
- Analytics: no new events; existing events keep firing where they fire today.

## Fields and scales

| Screen (file) | Field | Floor | Cap | Slider segments | Fine |
|---|---|---|---|---|---|
| recurring-investments.page | Investment amount (per period) | $5 | $10,000 | $5 to $500 by $5, to $2,000 by $50, to $5,000 by $250, to $10,000 by $500 | $5 |
| components/investment-schedule (onboarding, white) | Investment amount (per paycheck) | $5 | $10,000 | same as above | $5 |
| lump-sum-investment.page | Investment amount | $25 | $100,000, or the page's existing validated max if lower | $25 to $1,000 by $25, to $5,000 by $100, to $25,000 by $500, to $100,000 by $2,500 | $25 |
| sell-withdraw.page | Withdraw amount | $5 | withdrawable cash (live; rebuild the scale when it changes) | buildAmountScale(5, max, 5); last detent == max so "all" is reachable; quick chips stay and sync | $5 |
| pages/goal (simple + vacation tiles) | Saved so far | $0 | the goal target | buildAmountScale(0, target, 25) | $25 |
| components/goal-unit-detail-sheet | Saved so far | $0 | the unit target (fallback $50,000) | buildAmountScale(0, target, 100); the tray opens inside the sheet (sheet grows) | $100 |
| components/monte-carlo-flow (BLUE flow) | 401(k) balance, Roth IRA balance | $0 | $5,000,000 | $0 to $100k by $5k, to $500k by $10k, to $2M by $50k, to $5M by $100k | $1,000 |
| beneficiaries/add-beneficiary.page | Allocation percentage | 1 % | 100 % | 1 to 100 by 1 | 1 |
| components/portfolio-customize | Per-asset allocation (one per row) | 0 % | 100 % | 0 to 100 by 1; one tray for the active row, rendered under that row; tapping another row switches | 1 |

Monte Carlo: the tray takes the blue-flow look (theme/_blue-flow.scss `--bf-*` tokens: ink-on-blue
track and thumb like the existing `.sim-range`), set through the tray's CSS custom properties from the
component's SCSS. Percent fields show `%` as the suffix and no `$`.

## Non-goals

- Identifier fields, date pickers, the goal quiz, the onboarding survey sliders, Monte Carlo's existing
  range sliders and steppers, and the PIN keypad are untouched.
- No change to backend contracts; values submitted are the same numbers as before.

## Gates

For every touched screen: its spec updated (typing specs become tray-driven), plus the shared scale and
tray specs. `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=<the touched
specs>` must print "Executed N of N SUCCESS"; `npx ng build --configuration dev` must pass. The Freedom
Plan spec must pass unchanged.
