import { createAnimation } from '@ionic/angular/standalone';

/**
 * FRED bottom sheets are PLAIN modals (no Ionic sheet mode / breakpoints) —
 * the sheet look comes from the mc-bottom-sheet-modal chrome in global.scss
 * (bottom-anchored auto-height wrapper), and drag-to-dismiss comes from
 * SheetDragDismissDirective, hostDirective'd on each sheet component.
 *
 * History: sheets previously used breakpoints [0, 1] purely to get Ionic's
 * built-in drag gesture. That gesture settles at a HARD-CODED 500ms with no
 * velocity input (modal.js progressEnd(1, 0, 500)) — the "clunky close" — and
 * its held scrub animation caused the backdrop-snap bug buildSheetLeave works
 * around. Dropping sheet mode removes the gesture conflict entirely; our own
 * gesture springs with the finger's release velocity instead.
 */

const SHEET_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';

/**
 * Enter: 320ms (Ionic's default is a sluggish 500ms; Spotify sheets open in
 * ~300ms). Wrapper slides its own height (translateY 100% -> 0), backdrop
 * fades to --backdrop-opacity — same shape as Ionic's sheet enter, minus the
 * breakpoint math (FRED sheets always open fully).
 */
export function buildSheetEnter(baseEl: HTMLElement) {
  const duration = 320;
  // Zero-duration fallback: any failure degrades to an instant present.
  const fallback = () => createAnimation().addElement(baseEl).duration(0);

  try {
    const root: ParentNode = baseEl.shadowRoot ?? baseEl;
    const backdropEl = root.querySelector('ion-backdrop') as HTMLElement | null;
    const wrapperEl = root.querySelector('.modal-wrapper') as HTMLElement | null;
    if (!wrapperEl) return fallback();

    const wrapperAnimation = createAnimation()
      .addElement(wrapperEl)
      .beforeStyles({ opacity: 1 })
      .keyframes([
        { offset: 0, transform: 'translateY(100%)' },
        { offset: 1, transform: 'translateY(0%)' },
      ]);

    const animations = [wrapperAnimation];
    if (backdropEl) {
      animations.push(
        createAnimation()
          .addElement(backdropEl)
          .fromTo('opacity', 0.01, 'var(--backdrop-opacity)')
          .beforeStyles({ 'pointer-events': 'none' })
          .afterClearStyles(['pointer-events']),
      );
    }

    return createAnimation()
      .addElement(baseEl)
      .easing(SHEET_EASING)
      .duration(duration)
      .addAnimation(animations);
  } catch {
    return fallback();
  }
}

/**
 * Leave for PROGRAMMATIC dismissal only (Cancel / Save buttons, backdrop tap,
 * Esc) — swipe dismissal never runs this: the drag gesture's exit spring is
 * the close animation, and Ionic skips leaveAnimation for role 'gesture'.
 *
 * Why not the stock leave: Ionic keeps the finished ENTER animation alive
 * (WAAPI, fill both) until teardown — it is what holds the wrapper on-screen
 * over its off-screen resting CSS — and its var()-based backdrop keyframes
 * left the backdrop held dark on WKWebView (backdrop-snap bug). This builder
 * reads CURRENT computed values (literals, no var()), cancels the held
 * animations, and runs a single fresh fade/slide — which also makes it
 * correct from ANY state: mid-drag, mid-spring, or at rest.
 */
export function buildSheetLeave(baseEl: HTMLElement) {
  const duration = 360;
  // Zero-duration, keyframe-less animation: Ionic resolves it immediately, so
  // any failure below degrades to an instant dismiss — never a stuck modal.
  const fallback = () => createAnimation().addElement(baseEl).duration(0);

  try {
    const root: ParentNode = baseEl.shadowRoot ?? baseEl;
    const backdropEl = root.querySelector('ion-backdrop') as HTMLElement | null;
    const wrapperEl = root.querySelector('.modal-wrapper') as HTMLElement | null;
    if (!wrapperEl) return fallback();

    // Backdrop: neutralize its tap-blocking immediately, then fade via raw
    // WAAPI — deliberately OUTSIDE the returned Ionic animation. Ionic's
    // engine counts child animations to detect completion (child + 1), and a
    // child that fails to initialize strands that counter and hangs dismiss
    // (froze the whole page behind an invisible backdrop). Raw WAAPI here is
    // cosmetic-only: if it throws, we still dismiss.
    if (backdropEl) {
      const startOpacity = getComputedStyle(backdropEl).opacity;
      backdropEl.style.pointerEvents = 'none';
      backdropEl.getAnimations?.().forEach(a => a.cancel());
      backdropEl.animate(
        [{ opacity: startOpacity }, { opacity: '0' }],
        { duration, easing: SHEET_EASING, fill: 'both' }
      );
    }

    // Wrapper: a single flat Ionic animation (no children) — one WAAPI
    // animation whose finish directly resolves the dismiss transition.
    const computed = getComputedStyle(wrapperEl).transform;
    const startTransform = computed === 'none' ? 'translateY(0%)' : computed;
    wrapperEl.getAnimations?.().forEach(a => a.cancel());
    return createAnimation()
      .addElement(wrapperEl)
      .easing(SHEET_EASING)
      .duration(duration)
      .keyframes([
        { offset: 0, transform: startTransform },
        { offset: 1, transform: 'translateY(100%)' },
      ]);
  } catch {
    return fallback();
  }
}

/**
 * Shared ModalController options for FRED bottom sheets
 * (ion-modal.mc-bottom-sheet-modal chrome in global.scss).
 *
 * Deliberately NOT a sheet modal (no breakpoints): see the module doc above.
 * Drag-to-dismiss is provided by SheetDragDismissDirective on each sheet
 * component; each component renders its own handle div (.gs-handle /
 * .sheet-handle) and the drag works anywhere on the sheet.
 */
export const BOTTOM_SHEET_MODAL_OPTS = {
  cssClass: 'mc-bottom-sheet-modal',
  enterAnimation: buildSheetEnter,
  leaveAnimation: buildSheetLeave,
};
