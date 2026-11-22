import { ChangeDetectorRef, Component, ElementRef, NgZone, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ToastItem, ToastService, ToastSeverity } from '../../services/toast.service';
import { thumpHaptic, tickHaptic } from '../quiz-controls/haptics';

/** Cards on screen at once. Showing a fourth dismisses the oldest. */
export const MAX_VISIBLE_TOASTS = 3;
/** Vertical gap between stacked cards. */
export const TOAST_GAP_PX = 6;
/** A second toast with the same message inside this window is ignored. */
export const DEDUPE_WINDOW_MS = 1000;
/** How far a finger has to travel upward to throw a card away. */
export const SWIPE_DISMISS_PX = 24;
/** Least time a card stays after a finger lets go, so it never vanishes under the thumb. */
export const RESUME_GRACE_MS = 500;

export const ENTER_MS = 320;
export const LEAVE_MS = 240;
export const RESTACK_MS = 240;
const SNAP_BACK_MS = 160;
const ENTER_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
/** Opacity of every card below the newest. */
const STACKED_OPACITY = 0.92;
/** The small lift a card enters from and leaves toward. */
const LIFT_PX = 12;

/** One card on screen: the request plus what the host needs to run it. */
interface ToastEntry extends ToastItem {
  id: number;
  /** Set once the leave animation starts; the card no longer counts or reacts. */
  leaving: boolean;
  /** When the auto-dismiss timer fires, as a Date.now() value. */
  deadline: number;
  timer: ReturnType<typeof setTimeout> | null;
  /** Time left when a finger paused the timer. */
  remaining: number;
  slotAnimation: Animation | null;
  cardAnimation: Animation | null;
}

interface Drag {
  entry: ToastEntry;
  card: HTMLElement;
  startY: number;
  dy: number;
}

function translateY(px: number): string {
  return `translateY(${px}px)`;
}

/** The element's current vertical offset, mid-animation included. */
function currentTranslateY(el: HTMLElement): number {
  const transform = getComputedStyle(el).transform;
  if (!transform || transform === 'none') return 0;
  return new DOMMatrixReadOnly(transform).m42;
}

function currentOpacity(el: HTMLElement): number {
  const opacity = parseFloat(getComputedStyle(el).opacity);
  return Number.isFinite(opacity) ? opacity : 1;
}

/**
 * FredToastHostComponent: the one place toasts are drawn.
 *
 * Mounted once inside ion-app (app.component.html), the way the first-time
 * tour overlay is, so it outlives page pushes and sits above every Ionic
 * overlay. It listens to ToastService and keeps a stack of cards, newest on
 * top, older ones pushed down by their measured height.
 *
 * Every move is a Web Animation, not a CSS transition: the global
 * reduced-motion rule zeroes transition durations outright, and WAAPI gives
 * each card its own timing. When the user prefers reduced motion the cards
 * simply appear and disappear in place.
 */
@Component({
  selector: 'app-fred-toast-host',
  templateUrl: './fred-toast-host.component.html',
  styleUrls: ['./fred-toast-host.component.scss'],
  standalone: true,
})
export class FredToastHostComponent {

  /** Newest first. The template draws them in this order. */
  readonly toasts = signal<ToastEntry[]>([]);

  private nextId = 1;
  private lastShown: { message: string; at: number } | null = null;
  private drag: Drag | null = null;

  private readonly onPointerMove = (event: PointerEvent) => this.moveDrag(event);
  private readonly onPointerEnd = (event: PointerEvent) => this.endDrag(event);

  constructor(
    toastService: ToastService,
    private host: ElementRef<HTMLElement>,
    private cdr: ChangeDetectorRef,
    private zone: NgZone,
  ) {
    toastService.items$.pipe(takeUntilDestroyed()).subscribe(item => this.add(item));
  }

  /** True while an error card is still up, which makes the host region assertive. */
  hasLiveError(): boolean {
    return this.live().some(t => t.severity === 'error');
  }

  /** Close tap. Also the fallback for anything that wants a card gone by id. */
  dismiss(id: number): void {
    const entry = this.toasts().find(t => t.id === id);
    if (!entry || entry.leaving) return;

    entry.leaving = true;
    this.clearTimer(entry);
    // The leaving class is bound in the template; paint it before the fade starts.
    this.cdr.detectChanges();

    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      this.toasts.update(list => list.filter(t => t.id !== id));
      this.cdr.detectChanges();
      this.restack();
    };

    const card = this.cardOf(id);
    if (!card) {
      finish();
      return;
    }

    // Read where the card is before cancelling: a cancelled enter reverts the
    // element to its inline style, and the fade should start where the eye left it.
    const fromY = currentTranslateY(card);
    const fromOpacity = currentOpacity(card);
    entry.cardAnimation?.cancel();
    const animation = this.play(card, [
      { transform: translateY(fromY), opacity: fromOpacity },
      { transform: translateY(fromY - LIFT_PX), opacity: 0 },
    ], LEAVE_MS, 'ease-in', finish);
    if (animation) {
      // onfinish can be swallowed when the webview is backgrounded mid-fade;
      // the card must come down either way.
      setTimeout(finish, LEAVE_MS + 100);
    }
    // The cards below slide up under the leaving one rather than waiting for it.
    this.restack();
  }

  /** The card's action button: runs once, then the card goes like a close tap. */
  runAction(entry: ToastEntry): void {
    if (entry.leaving) return;
    const run = entry.action?.run;
    this.dismiss(entry.id);
    run?.();
  }

  /**
   * A finger on the card pauses its timer and may start a swipe up. Move and
   * release are tracked on the window so a drag that strays off the card
   * still ends cleanly.
   */
  onPointerDown(event: PointerEvent, entry: ToastEntry): void {
    if (entry.leaving || this.drag) return;
    if (event.button !== 0) return;
    const card = this.cardOf(entry.id);
    if (!card) return;

    this.pauseTimer(entry);
    // A drag takes over the card's transform, so an in-flight enter stops here.
    entry.cardAnimation?.cancel();
    entry.cardAnimation = null;
    card.style.transform = translateY(0);
    card.style.opacity = '1';

    this.drag = { entry, card, startY: event.clientY, dy: 0 };
    this.zone.runOutsideAngular(() => {
      window.addEventListener('pointermove', this.onPointerMove);
      window.addEventListener('pointerup', this.onPointerEnd);
      window.addEventListener('pointercancel', this.onPointerEnd);
    });
  }

  private add(item: ToastItem): void {
    const now = Date.now();
    if (this.lastShown && this.lastShown.message === item.message && now - this.lastShown.at < DEDUPE_WINDOW_MS) {
      return;
    }
    this.lastShown = { message: item.message, at: now };

    const entry: ToastEntry = {
      ...item,
      id: this.nextId++,
      leaving: false,
      deadline: 0,
      timer: null,
      remaining: item.duration,
      slotAnimation: null,
      cardAnimation: null,
    };
    this.toasts.update(list => [entry, ...list]);
    this.cdr.detectChanges();

    const card = this.cardOf(entry.id);
    if (card) {
      entry.cardAnimation = this.play(card, [
        { transform: translateY(-LIFT_PX), opacity: 0 },
        { transform: translateY(0), opacity: 1 },
      ], ENTER_MS, ENTER_EASING, () => { entry.cardAnimation = null; });
    }
    this.restack();
    this.startTimer(entry, entry.duration);

    this.live().slice(MAX_VISIBLE_TOASTS).forEach(t => this.dismiss(t.id));
    this.haptic(entry.severity);
  }

  /** Cards that still count: on screen and not on their way out. */
  private live(): ToastEntry[] {
    return this.toasts().filter(t => !t.leaving);
  }

  /**
   * Lay the stack out again: the newest card at the top, each older card
   * pushed down by the measured height of everything above it, plus the gap.
   */
  private restack(): void {
    let offset = 0;
    this.live().forEach((entry, index) => {
      const slot = this.slotOf(entry.id);
      if (!slot) return;
      const opacity = index === 0 ? 1 : STACKED_OPACITY;
      const fromY = currentTranslateY(slot);
      const fromOpacity = currentOpacity(slot);
      entry.slotAnimation?.cancel();
      entry.slotAnimation = null;
      if (fromY !== offset || fromOpacity !== opacity) {
        entry.slotAnimation = this.play(slot, [
          { transform: translateY(fromY), opacity: fromOpacity },
          { transform: translateY(offset), opacity },
        ], RESTACK_MS, 'ease-out', () => { entry.slotAnimation = null; });
      }
      offset += slot.offsetHeight + TOAST_GAP_PX;
    });
  }

  private moveDrag(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag) return;
    drag.dy = Math.min(0, event.clientY - drag.startY);
    drag.card.style.transform = translateY(drag.dy);
    drag.card.style.opacity = String(Math.max(0, 1 + drag.dy / 120));
  }

  private endDrag(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag) return;
    this.drag = null;
    window.removeEventListener('pointermove', this.onPointerMove);
    window.removeEventListener('pointerup', this.onPointerEnd);
    window.removeEventListener('pointercancel', this.onPointerEnd);

    this.zone.run(() => {
      const { entry, card, dy } = drag;
      // The card may have been sent away under the finger (a fourth toast
      // arrived); a snap-back would override its leave and pop it back opaque.
      if (entry.leaving) return;
      if (event.type !== 'pointercancel' && -dy >= SWIPE_DISMISS_PX) {
        this.dismiss(entry.id);
        return;
      }
      if (dy !== 0) {
        entry.cardAnimation = this.play(card, [
          { transform: translateY(dy), opacity: currentOpacity(card) },
          { transform: translateY(0), opacity: 1 },
        ], SNAP_BACK_MS, 'ease-out', () => { entry.cardAnimation = null; });
      }
      this.resumeTimer(entry);
    });
  }

  private startTimer(entry: ToastEntry, ms: number): void {
    this.clearTimer(entry);
    entry.deadline = Date.now() + ms;
    entry.timer = setTimeout(() => this.dismiss(entry.id), ms);
  }

  private pauseTimer(entry: ToastEntry): void {
    if (!entry.timer) return;
    entry.remaining = Math.max(0, entry.deadline - Date.now());
    this.clearTimer(entry);
  }

  private resumeTimer(entry: ToastEntry): void {
    if (entry.leaving || entry.timer) return;
    this.startTimer(entry, Math.max(entry.remaining, RESUME_GRACE_MS));
  }

  private clearTimer(entry: ToastEntry): void {
    if (entry.timer) clearTimeout(entry.timer);
    entry.timer = null;
  }

  /**
   * Run a two-frame animation, or under reduced motion jump straight to the
   * last frame. The final values are written to inline style when the
   * animation ends so nothing depends on a lingering fill.
   */
  private play(
    el: HTMLElement,
    frames: [Keyframe, Keyframe],
    duration: number,
    easing: string,
    onDone?: () => void,
  ): Animation | null {
    const last = frames[1];
    const commit = () => {
      el.style.transform = String(last['transform']);
      el.style.opacity = String(last['opacity']);
    };
    if (this.prefersReducedMotion() || typeof el.animate !== 'function') {
      commit();
      onDone?.();
      return null;
    }
    const animation = el.animate(frames, { duration, easing, fill: 'forwards' });
    animation.onfinish = () => {
      commit();
      animation.cancel();
      onDone?.();
    };
    return animation;
  }

  private prefersReducedMotion(): boolean {
    return typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  private haptic(severity: ToastSeverity): void {
    if (severity === 'success') void tickHaptic();
    if (severity === 'error') void thumpHaptic();
  }

  private cardOf(id: number): HTMLElement | null {
    return this.host.nativeElement.querySelector(`[data-toast-id="${id}"]`);
  }

  private slotOf(id: number): HTMLElement | null {
    return this.host.nativeElement.querySelector(`[data-toast-slot="${id}"]`);
  }
}
