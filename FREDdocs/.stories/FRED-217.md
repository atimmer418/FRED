# FRED-217 — Retire button in MFU when freedom date is this year

## Before (from backlog.md)
```
## FRED-217 — Retire button in MFU when freedom date is this year
Add a "Retire" button to the Monthly Freedom Update instead of the "What if you invested more" option when the freedom date is the current year.
```

## After

**Summary**
When the user's projected freedom year has arrived (`projectedFreedomYear` equals the current year), the Monthly Freedom Update should stop pitching a contribution increase. The "What If You Invested More?" CTA is replaced by a "Retire" CTA celebrating that they have reached their freedom date.

**Label:** `[code]`
**Estimate:** `1-3hr` (frontend only, assuming the Retire tap target lands on a simple celebration/dismiss; larger if it opens a new flow)

**Files / systems**
- `frontend/src/app/components/monthly-freedom-update/monthly-freedom-update.component.html` — the CTA block at lines ~122-138 (`.mfu-cta`, currently gated on `*ngIf="!data.showQuarterlyCompare"`)
- `frontend/src/app/components/monthly-freedom-update/monthly-freedom-update.component.ts` — add the gating getter (e.g. `isFreedomYearReached`) alongside `bestNextMoveTitle` / `bestNextMoveDescription` (~lines 271-280) and the new tap handler next to `updateContribution()` (~line 176)
- `frontend/src/app/components/monthly-freedom-update/monthly-freedom-update.component.scss` — variant styling for the retire CTA
- `frontend/src/app/components/monthly-freedom-update/monthly-freedom-update.component.spec.ts` — specs for both branches
- `frontend/src/app/services/dev/mfu-mock.ts` — add a mock case where `projectedFreedomYear` is the current year, so the state is reachable via `FORCE_MFU` without touching real data
- Backend `MonthlyFreedomUpdateService` / `MonthlyFreedomUpdateDTO` — **read-only for this story**; `projectedFreedomYear` already exists on the DTO. Only touch the backend if it turns out `projectedFreedomYear` can be `0`/unset in the shipped payload.

**Docs**
- `FREDdocs/FRED_UI_STYLE_GUIDE.md` — CTA styling, Manrope, `material-symbols-outlined`, motion
- `.claude/CONTEXT.md` — copy tone (calm, direct, never salesy) and unhappy-path bar

**Acceptance Criteria**
1. When `data.projectedFreedomYear === new Date().getFullYear()`, the MFU renders a "Retire" CTA in place of the "What If You Invested More?" CTA. The boost title, description, `Update Contribution` button, and the contribution disclaimer are not rendered at all in this state.
2. When `projectedFreedomYear` is any future year, the existing "What If You Invested More?" CTA renders exactly as it does today, with no visual or copy change.
3. The retire branch is a pure display swap: it does not change `commitMfuSeen` timing, dismissal, swipe-to-dismiss, or the `requestReviewIfFirstMFU()` path.
4. The quarterly-review gate still wins. On `showQuarterlyCompare` months neither CTA renders, in the freedom year or otherwise.
5. Edge values do not produce a false positive: `projectedFreedomYear` of `0`, `null`, or `undefined` (see the `0` default in `services/dev/mfu-mock.ts`) falls through to the existing behavior, never to the Retire CTA.
6. A past year (`projectedFreedomYear < currentYear`, possible for an over-target user) also shows the Retire CTA, not the contribution pitch.
7. Copy is calm and specific in FRED's voice, no em dashes, and reuses the existing `.mfu-cta` structure and design-system tokens rather than a new ad-hoc card. Icon comes from `material-symbols-outlined`.
8. Specs cover both branches (freedom year reached / not reached) plus the null/zero guard; `ng build` (plain or `--configuration dev`), lint, and the existing MFU specs pass with no new debt.

**Resolved decisions (Andy, 2026-09-05)**
- **On tap → Option B: route to the tab2 Education tab.** The Retire button dismisses the MFU and navigates to tab2's Education section (the retirement-strategies content, "Make your money last."), NOT the simulator landing and NOT the Pro-gated Optimal Drawdown deck (avoids a paywall on a celebratory moment). Same dismiss-then-navigate pattern as the existing "Update Contribution" button. Reuses an existing feature, so this stays frontend-only (no new flow, no story split).
- **Design — the "sky card":** the Retire CTA replaces the invest-more `.mfu-cta` with a sky-themed celebratory card:
  - Hero = the cartoon flying pig `assets/images/whenPiggybanksFly.png` ("when pigs fly" = achieving the impossible; a pig in flight = freedom), gently bobbing. Its sky blends into a blue-gradient card.
  - A ONE-TIME confetti burst behind the pig on first reveal, gated on `prefers-reduced-motion` (no confetti when motion is reduced).
  - Title: **"You've reached your Freedom Date."**
  - Supporting line: **"Your portfolio is now ready to support the retirement income you set."**
  - Button: e.g. **"Explore retirement strategies"** (final label TBD) → tab2 Education tab.
  - Copy is calm / house voice, no em dashes.
- **Art tradeoff (noted):** the flying pig is a different style from the flat blue crown mascot, an intentional one-off hero for this rare moment. Future option: a dedicated flat-blue "free FRED" mascot (crown pig with wings, or lounging) that drops into the same slot.
- **Stateless re-evaluation:** the Retire CTA is re-evaluated each month from `projectedFreedomYear` (no permanent "once shown" suppression), as the acceptance criteria encode.

**Estimate update:** still frontend-only (route to the existing tab2 Education tab), but the confetti + sky-card + image hero push it a bit above the original 1-3hr, call it ~2-4hr. No backend, no story split.
