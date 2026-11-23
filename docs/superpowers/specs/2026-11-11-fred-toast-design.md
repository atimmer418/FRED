# FRED toast: a modern, severity-coloured notification

Date: 2026-11-11. Decided with Andy.

## Intent

Replace the stock Ionic toast (Ionic blue, system font, full-width coloured bar, hard-coded stacking)
with a FRED-styled notification card. Severity decides the colour so the user can tell at a glance what
kind of thing happened. No call site has to change: `ToastService.showToast(message, color, duration)`
keeps its signature and maps colours to severities.

## Severities and colours (pills/chips palette from the style guide)

| Severity | Mapped from today's colour | Icon (material-symbols-outlined) | Icon chip |
|---|---|---|---|
| success | 'success' | check_circle | #16A34A on #DCFCE7 |
| error | 'danger' | error | #DC2626 on #FEE2E2 |
| warning | 'warning' | warning | #D97706 on #FEF3C7 |
| info | 'primary' and the default | info | #2563EB on #DBEAFE |
| neutral | 'medium' | none | none |

The card itself stays white for every severity; the chip and a 3px left accent in the chip's colour carry
the meaning. Never a fully coloured card.

## Visual

White card (#ffffff), 1px #e5e7eb border, 16px radius, shadow `0 10px 30px -12px rgba(15,23,42,.25)`,
width calc(100% - 32px) max 420px centered, padding 12px 14px. Row: 28px round icon chip, then text,
then a 28px close target (material `close`, #9aa6ba). Text in Manrope: optional title 14px/700 #0f172a,
message 14px/500 #111827 line-height 1.35, up to 2 lines then ellipsis. Light only.

## Behaviour

- Fixed at the top: `top: calc(env(safe-area-inset-top, 0px) + 8px)`. Above every Ionic overlay
  (z-index 60000, as Ionic's own toasts). Mounted ONCE as `<app-fred-toast-host>` inside `<ion-app>` in
  app.component.html, the way the first-time tour overlay is.
- Stacking: newest on top; older cards push down by their MEASURED height + 6px, fade to .92, max 3
  visible (the oldest beyond that is dismissed). Same-message dedupe within 1s.
- Enter: slide down from -12px + fade, 320ms, cubic-bezier(0.32, 0.72, 0, 1), via element.animate (the
  global reduced-motion rule zeroes CSS transitions; under reduced motion just appear). Leave: 240ms up
  + fade. Restack moves are WAAPI too.
- Dismiss: auto after `duration` (default 3000ms; errors 4000ms), paused while a finger is on the card;
  tap the close target; swipe up (pointer events, threshold 24px, spring-free simple ease-out).
- Accessibility: host `role="status" aria-live="polite"`; errors use `aria-live="assertive"`.
- Haptics: none for info/neutral; `tickHaptic()` for success; a Medium impact for error (via a small
  helper beside quiz-controls/haptics.ts). Native only, never throws.

## Service

`ToastService` keeps `showToast(message, color = 'primary', duration = 3000)` and adds
`show({ severity, message, title?, duration? })`. Internally it pushes to the host via a signal/subject.
Delete the ToastController-based stacking code and the `ion-toast.stacked-toast` rule in global.scss.
Fix along the way: recovery.page.ts failure toasts that pass no colour must pass 'danger';
document-upload and kyc-verification stop using a raw ToastController and call the service; the dead
ToastService injection in strategy-deck is removed. No message text changes beyond that.

## Gates

Specs: service mapping (5 colours to 5 severities, default info, custom durations), host (stacks, caps
at 3, dedupes, auto-dismiss timing with fakeAsync, close tap, swipe dismiss, aria-live per severity,
reduced motion path). Existing specs that spy on `showToast` keep passing. `npx ng build
--configuration dev` passes.
