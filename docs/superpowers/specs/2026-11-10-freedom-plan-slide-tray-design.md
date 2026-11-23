# Freedom Plan: slide tray for the two plan tiles

Date: 2026-11-10. Decided with Andy from the clickable mockup (https://claude.ai/artifact/MqCQtT88W3UmfhtjSZxQLM, concept A).

## Intent

Users should never need the keyboard on the Freedom Plan page. Tapping the Income goal or Investing tile
opens a slider directly under the tile row; dragging it moves the amount and the hero (freedom year,
"X yr Y mo away", bar, insights) re-derives live, exactly as typing does today. The control must read as
part of the page (the tile recipe grown into a third row), not a widget on top of it, and it appears and
disappears simply. A design panel scored four concepts; all three judges ranked this one first.

## Goals

- No keyboard anywhere on /freedom. The two `<input>`s become tappable tiles.
- Tap a tile: a full-width tray grows under the tile row holding a custom slider, minus and plus buttons.
- Hero stays fully visible and lit; nothing modal, no backdrop.
- Tap the other tile while open: the tray stays open and switches to that field.
- Done (in the "Your plan" zone row), tapping the active tile again, tapping anywhere outside the tiles and
  tray, Save, or leaving the page closes it.
- Premium motion: WAAPI only (the global reduced-motion rule zeroes CSS transitions), house easing
  `cubic-bezier(0.32, 0.72, 0, 1)`, light haptic per detent (`tickHaptic()` in
  components/quiz-controls/haptics.ts).

## Non-goals and anti-goals

- No change to the goal page's balance tile in this story (it keeps its keyboard input; follow-up).
- No new colours, no gauge or dial motifs, no bottom sheet.
- No "Reset to standard" (removed earlier today) and no reset-to-saved link.
- Do not break the analytics event `freedom_plan_changed` or the Save-plan dirty fade shipped earlier today.

## Values (Andy's decisions)

| Field | Floor | Cap | Step (slider and buttons) |
|---|---|---|---|
| Income goal | $500 | $30,000 | $250 |
| Investing | $0 | $10,000 | $100 |

- Uniform steps across the whole track. On a 390px phone that is about 2px of track per step: the slider is
  for the ballpark, minus and plus settle the exact figure. Known risk: if it feels twitchy on device, the
  fallback is coarser slider detents with the fine step kept on the buttons.
- A detent commits only when the finger is 65% of the way to it (hysteresis); the thumb follows the finger
  while dragging and settles onto the detent on release.
- Minus and plus move one step per tap; hold to repeat: after 400ms at 8/s, x5 step after 1.6s, x25 after
  3s, one haptic per gear change. Cancel on pointerup, pointercancel and pointerleave; suppress the iOS
  callout (`-webkit-touch-callout: none`, preventDefault on contextmenu). Dim to .35 and disable at the
  floor and cap.
- A seeded off-grid value (for example $4,333 from a biweekly schedule, or a legacy $99,999) is shown
  exactly and drawn at its interpolated position; the first drag snaps to the nearest detent; a tap on
  minus or plus rounds onto the grid in the tap's direction. Values above the cap clamp to the cap on
  first touch.
- Unset income (0) draws the thumb at the left end without changing the value; canSave stays false until
  the user moves it, as today.

## Layout and visual

- Tiles: the `<input>` is replaced by a `<button type="button">` tile (whole tile tappable, `appearance:
  none`, text left) showing the value with the existing `$` and `/mo` affixes in the existing type.
  Empty value shows the placeholder ("5,000" / "500") in #cbd5e1 600. Active tile: `border-color:
  #2563EB`, background #ffffff, label #2563EB. No outer glow, no layout shift (border width unchanged).
- While the finger is down on the slider, the active tile's label cross-fades to the step size ("± $250")
  in #2563EB and returns to the field name on release.
- Tray: a wrapper (animated height, `overflow: hidden`) placed right after `.fd-tiles`, inside a new
  `.fd-plan` container so the page's 10px flex gap is untouched. Inner card uses the tile recipe:
  background #f8fafc, border 1px #eef1f6, radius 13px. Row: [track, flex 1] [minus 28px] [plus 28px],
  gap 4px; the buttons sit to the RIGHT of the track so neither sits inside Ionic's 50px swipe-back edge.
  Under the track: two end labels (9.5px / 700 / #9aa6ba / tabular-nums), e.g. "$500" and "$30,000".
- Track: 6px, radius 3, #e5e7eb; fill #2563EB from the left to the thumb; thumb 28px white circle, border
  1px rgba(15,23,42,.06), shadow `0 .5px 1.5px rgba(15,23,42,.24), 0 3px 8px rgba(15,23,42,.14)`, blue
  10px dot centre; dragging scales 1.18 with a blue-tinted shadow. A faint masked repeating hairline strip
  under the track (camera-dial tick texture, decorative). 44px tall hit band.
- Zone row: "Your plan" gains a right-aligned "Done" button only while the tray is open (the `.fd-reset`
  recipe: 10px / 800 / #2563EB).
- Hero: add `<p class="fd-hero-label">Freedom date</p>` as the first child of `.fd-hero`, the way the
  goal page's hero carries its label (`.fd-hero-label` already exists in theme/_plan-page.scss). Remove the
  two-line `.fp-sub` paragraph ("Your path to work-optional. / Adjust the plan and watch the date move.")
  and its SCSS. Keep the hero roll-up and pulse.
- Open: wrapper height 0 to measured height, 240ms, house easing; inner opacity 0 to 1 with a 6px lift,
  concurrently; Done fades in 160ms. Close: inner fades out 140ms while height collapses 200ms. Switching
  tiles: inner cross-fades (about 220ms), no height change. Reduced motion: snap, no animation.
- With the tray open on a 390x844 phone, Assumptions and Save plan drop below the fold; Done lives above
  the tiles so closing never needs a scroll. If the page is scrolled when a tile is tapped, scroll to top
  (220ms) so the hero is visible.
- Remove `appKeyboardAvoid` from this page's ion-content (no keyboard left on it); the directive stays
  for other pages.

## Gesture and page interplay

- Pointer events with `setPointerCapture` on the track; `touch-action: pan-y` so vertical page scroll
  still works; horizontal drags on the track are the slider's. Guard `startX >= 50` is unnecessary given
  the geometry but harmless.
- The hero pulse (`_pulseFx`) fires per detent today; while a drag is in progress suppress it and fire
  once on release.
- First-time tour: the `profileFreedom` gate (leaving /freedom) is unchanged. Nothing on this page is a
  tour spotlight target.
- Save plan: the earlier "only when dirty" fade is untouched; tray value changes drive `incomeSig` /
  `investSig` exactly as typing did, so dirty, canSave, analytics bands and the hero all keep working.

## Build shape (the builder's call where not stated)

- New standalone component `components/plan-amount-tray/plan-amount-tray.component.ts` (+ html, scss,
  spec): inputs `field` ('income' | 'invest'), `value`, `scale`; output `valueChange` (per detent) and
  `dragging` (boolean) for the label swap and pulse suppression. Pure scale module
  `utils/plan-amount-scale.ts` (detent list, nearest index, snap in direction, clamp) with its own spec.
- The page owns open/close state as a signal (`trayField: 'income' | 'invest' | null`), the Done button,
  the outside-tap listener (document pointerdown, capture), and `ionViewWillLeave` close.
- Karma specs: scale math (detents, hysteresis commit, snap direction, clamps), tray emits per detent and
  on nudge, hold gears, page opens and closes the tray, switches fields, tile label swaps, Done appears
  only while open, Save-plan dirty behaviour still passes, analytics spec still passes.
- Run `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=<specs>` and `npx ng
  build --configuration dev`. Confirm "Executed N of N SUCCESS".
