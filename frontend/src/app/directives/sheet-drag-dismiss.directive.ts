import { AfterViewInit, Directive, ElementRef, NgZone, OnDestroy } from '@angular/core';
import { GestureController } from '@ionic/angular/standalone';
import type { Gesture, GestureDetail } from '@ionic/core';
import {
  SHEET_EXIT_SPRING,
  SHEET_SNAPBACK_SPRING,
  rubberband,
  sheetShouldDismiss,
  springKeyframes,
} from '../utils/sheet-spring.utils';

/**
 * Spotify-feel drag-to-dismiss for FRED bottom sheets. Attached as a
 * hostDirective on each sheet component (whose ion-modal is created with
 * BOTTOM_SHEET_MODAL_OPTS — plain modal, NOT Ionic sheet mode; this directive
 * replaces the sheet gesture Ionic would otherwise provide, because Ionic's
 * settles at a hard-coded 500ms with no velocity input).
 *
 * What it does differently from Ionic's sheet gesture:
 * - Release runs a SPRING seeded with the finger's velocity (see
 *   sheet-spring.utils.ts) instead of a fixed-duration ease curve.
 * - The sheet is grabbable mid-settle (pointerdown cancels the in-flight
 *   animation at its current position and the drag continues from there).
 * - Upward overdrag rubber-bands slightly instead of hard-stopping.
 * - The backdrop fades to 0 (Ionic's gesture parks it at 1% opacity).
 *
 * Mechanics worth knowing:
 * - The wrapper's RESTING CSS is translate3d(0, 100%, 0) (off-screen); its
 *   open position is held by the finished enter animation (WAAPI, fill both)
 *   that Ionic keeps until teardown. So taking manual control = read computed
 *   transform → pin it as an inline style → cancel the element's animations.
 *   After that, resting-open must stay pinned inline (translateY(0)).
 * - Dismissal calls modal.dismiss(undefined, 'gesture'): Ionic skips the
 *   leave animation for that role, so our exit spring IS the close animation.
 * - While the keyboard is open, KeyboardAvoidDirective stops touch/pointer
 *   propagation at the sheet component host, which sits BELOW the modal
 *   wrapper in the event path — so this gesture is automatically suspended,
 *   same as Ionic's was.
 * - The .ion-page scroller (auto-height recipe in global.scss): the gesture
 *   only arms at scrollTop 0, upward drags hand a scrollable sheet back to
 *   native scrolling, and the scroller is locked while a drag owns the sheet.
 */
@Directive({
  selector: '[appSheetDragDismiss]',
  standalone: true,
})
export class SheetDragDismissDirective implements AfterViewInit, OnDestroy {
  private modalEl: HTMLIonModalElement | null = null;
  private wrapperEl: HTMLElement | null = null;
  private backdropEl: HTMLElement | null = null;
  private scrollerEl: HTMLElement | null = null;
  private gesture?: Gesture;

  private sheetHeight = 0;
  private startY = 0;
  private currentY = 0;
  private backdropOpacity = 0.4;
  /** Set when a drag begins upward on a scrollable sheet — hand it to scroll. */
  private dead = false;
  private dismissing = false;

  constructor(
    private elementRef: ElementRef<HTMLElement>,
    private gestureCtrl: GestureController,
    private zone: NgZone,
  ) {}

  ngAfterViewInit(): void {
    const host = this.elementRef.nativeElement;
    const modalEl = host.closest('ion-modal');
    if (!modalEl) {
      return; // not presented in a modal (unit tests) — stay inert
    }
    this.modalEl = modalEl;
    const root: ParentNode = modalEl.shadowRoot ?? modalEl;
    this.wrapperEl = root.querySelector('.modal-wrapper');
    this.backdropEl = root.querySelector('ion-backdrop');
    this.scrollerEl = host.closest('.ion-page');
    if (!this.wrapperEl) {
      return;
    }

    const raw = parseFloat(getComputedStyle(modalEl).getPropertyValue('--backdrop-opacity'));
    if (!Number.isNaN(raw)) {
      this.backdropOpacity = raw;
    }

    // Pin both animated surfaces to compositor layers for the modal's
    // lifetime: WebKit does not guarantee layerization for a plain inline 2D
    // transform, and an un-layered drag repaints the sheet (30px-blur shadow
    // included) on every move frame — reads as a sticky drag. The WAAPI
    // settles layerize on their own; this makes the finger-down phase match.
    this.wrapperEl.style.willChange = 'transform';
    if (this.backdropEl) {
      this.backdropEl.style.willChange = 'opacity';
    }

    // runInsideAngularZone=false: drag frames must not trigger change detection.
    this.gesture = this.gestureCtrl.create(
      {
        el: this.wrapperEl,
        gestureName: 'fred-sheet-drag',
        gesturePriority: 40,
        direction: 'y',
        threshold: 4,
        canStart: () => this.canStart(),
        onStart: (d) => this.onStart(d),
        onMove: (d) => this.onMove(d),
        onEnd: (d) => this.onEnd(d),
      },
      false,
    );
    // Arm only once presentation has finished: a grab during the enter
    // animation would cancel it, stranding present()'s promise — and with it
    // the overlay lock that dismiss() must acquire (a permanently stuck
    // modal). This directive attaches while the modal mounts (before present
    // runs), so ionModalDidPresent is the normal arming path; the duck-typed
    // `presented` check (internal state, not in the element typings) covers
    // any already-presented edge.
    modalEl.addEventListener('ionModalDidPresent', () => this.gesture?.enable(true), { once: true });
    if ((modalEl as unknown as { presented?: boolean }).presented === true) {
      this.gesture.enable(true);
    }
  }

  ngOnDestroy(): void {
    this.gesture?.destroy();
    this.unlockScroller();
  }

  private canStart(): boolean {
    if (this.dismissing) {
      return false;
    }
    // Mirror Ionic's rule: the sheet only arms when its content sits at the top.
    return !this.scrollerEl || this.scrollerEl.scrollTop <= 0;
  }

  private onStart(detail: GestureDetail): void {
    const wrapper = this.wrapperEl!;
    this.dead = false;

    // Upward drag on content that can scroll = the user wants to scroll it.
    const scrollable =
      !!this.scrollerEl && this.scrollerEl.scrollHeight > this.scrollerEl.clientHeight + 1;
    if (scrollable && detail.deltaY < 0) {
      this.dead = true;
      return;
    }

    this.sheetHeight = wrapper.clientHeight;
    // Only a scrollable sheet needs its scroller frozen during the drag; on
    // the (majority) non-scrollable sheets, skipping the overflow toggle
    // avoids a layout invalidation right at grab time.
    if (scrollable) {
      this.lockScroller();
    }

    // Take manual control from whatever holds the transform right now — the
    // held enter animation on first grab, or our own in-flight spring when the
    // sheet is caught mid-settle. Pin computed values inline BEFORE cancelling
    // (the resting CSS is off-screen).
    const computed = getComputedStyle(wrapper).transform;
    this.startY = computed === 'none' ? 0 : new DOMMatrix(computed).m42;
    this.currentY = this.startY;
    wrapper.style.transform = `translate3d(0, ${this.startY}px, 0)`;
    wrapper.getAnimations?.().forEach((a) => a.cancel());

    if (this.backdropEl) {
      this.backdropEl.style.opacity = getComputedStyle(this.backdropEl).opacity;
      this.backdropEl.getAnimations?.().forEach((a) => a.cancel());
    }
  }

  private onMove(detail: GestureDetail): void {
    if (this.dead || this.dismissing) {
      return;
    }
    const y = this.startY + detail.deltaY;
    this.currentY = y >= 0 ? y : -rubberband(-y, this.sheetHeight);
    this.wrapperEl!.style.transform = `translate3d(0, ${this.currentY}px, 0)`;

    if (this.backdropEl && this.sheetHeight > 0) {
      const progress = Math.min(1, Math.max(0, this.currentY) / this.sheetHeight);
      this.backdropEl.style.opacity = `${this.backdropOpacity * (1 - progress)}`;
    }
  }

  private onEnd(detail: GestureDetail): void {
    this.unlockScroller();
    if (this.dead || this.dismissing) {
      return;
    }
    const velocity = detail.velocityY; // px/ms, positive = downward
    if (sheetShouldDismiss(Math.max(0, this.currentY), velocity, this.sheetHeight)) {
      this.animateExit(velocity);
    } else {
      this.animateSnapBack(velocity);
    }
  }

  /** Spring off-screen from the release point, then dismiss with no re-animation. */
  private animateExit(velocityPxMs: number): void {
    const wrapper = this.wrapperEl!;
    const { positions, durationMs } = springKeyframes(
      { from: this.currentY, to: this.sheetHeight, velocityPxMs },
      SHEET_EXIT_SPRING,
    );
    if (this.backdropEl) {
      this.backdropEl.style.pointerEvents = 'none';
      this.backdropEl.animate([{ opacity: getComputedStyle(this.backdropEl).opacity }, { opacity: '0' }], {
        duration: durationMs,
        easing: 'ease-out',
        fill: 'both',
      });
    }
    const anim = wrapper.animate(
      positions.map((p) => ({ transform: `translate3d(0, ${p.toFixed(2)}px, 0)` })),
      { duration: durationMs, fill: 'both' },
    );
    // onfinish never fires if a new grab cancels the spring — catching the
    // sheet mid-exit rescues it instead of dismissing under the finger.
    anim.onfinish = () => {
      this.dismissing = true;
      this.zone.run(() => this.modalEl?.dismiss(undefined, 'gesture'));
    };
  }

  /** Spring back to open, absorbing the release velocity (soft catch). */
  private animateSnapBack(velocityPxMs: number): void {
    const wrapper = this.wrapperEl!;
    const { positions, durationMs } = springKeyframes(
      { from: this.currentY, to: 0, velocityPxMs },
      SHEET_SNAPBACK_SPRING,
    );
    let backdropAnim: Animation | undefined;
    if (this.backdropEl) {
      backdropAnim = this.backdropEl.animate(
        [{ opacity: getComputedStyle(this.backdropEl).opacity }, { opacity: `${this.backdropOpacity}` }],
        { duration: durationMs, easing: 'ease-out', fill: 'both' },
      );
    }
    const anim = wrapper.animate(
      positions.map((p) => ({ transform: `translate3d(0, ${p.toFixed(2)}px, 0)` })),
      { duration: durationMs, fill: 'both' },
    );
    anim.onfinish = () => {
      // Pin the open state inline and drop the animations (resting CSS is
      // off-screen; inline transform is what holds the sheet open from now on).
      wrapper.style.transform = 'translate3d(0, 0, 0)';
      anim.cancel();
      if (this.backdropEl) {
        this.backdropEl.style.opacity = `${this.backdropOpacity}`;
        backdropAnim?.cancel();
      }
      this.currentY = 0;
    };
  }

  private lockScroller(): void {
    if (this.scrollerEl) {
      this.scrollerEl.style.overflowY = 'hidden';
    }
  }

  private unlockScroller(): void {
    if (this.scrollerEl) {
      this.scrollerEl.style.overflowY = '';
    }
  }
}
