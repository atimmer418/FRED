import { Component, DestroyRef, ElementRef, ViewChild, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { tickHaptic } from '../quiz-controls/haptics';
import {
  PlanAmountScale,
  PlanField,
  breakpoints,
  clampToScale,
  commitIndex,
  detentCount,
  detentValue,
  nearestIndex,
  nudge,
  positionOf,
  stepAt,
} from '../../utils/plan-amount-scale';

/** The thumb's radius: the track is inset by it on both sides so the thumb never leaves the card. */
const THUMB_INSET_PX = 14;
/**
 * Ionic's swipe-back gesture claims any touch that starts this close to the screen's left edge
 * (createSwipeBackGesture: startX <= 50), and the track's floor end sits inside it. A touch on
 * bare track there is the page's, or the value would climb while the page slid away. A touch on
 * the thumb is the slider's even there: at the floor the whole thumb sits inside the edge, and a
 * finger on it means to drag.
 */
const SWIPE_BACK_EDGE_PX = 50;

/** Hold-to-repeat: a tap repeats after this long, then at 8 per second. */
export const HOLD_DELAY_MS = 400;
export const HOLD_INTERVAL_MS = 125;
/** One gear of a hold: how many steps each repeat moves once the button has been held `afterMs`. */
export interface HoldGear {
  afterMs: number;
  steps: number;
}
/**
 * The gears: single steps, then x5 after 1.6s of holding, x25 after 3s. Checked top down, so a
 * list runs from the longest hold to the `afterMs: 0` gear that a fresh hold starts in.
 */
export const HOLD_GEARS: ReadonlyArray<HoldGear> = [
  { afterMs: 3000, steps: 25 },
  { afterMs: 1600, steps: 5 },
  { afterMs: 0, steps: 1 },
];

/** What the value is: dollars ("$5,000"), a percentage ("35%") or a year ("1991"). */
export type AmountUnit = 'dollars' | 'percent' | 'year';

/**
 * The Freedom Plan's two tiles, which pass no `label` or `valueSuffix` of their own: their spoken
 * name and the "/mo" after the value live here. Every other host names its field itself.
 */
const FREEDOM_FIELDS: Readonly<Record<PlanField, { label: string; valueSuffix: string }>> = {
  income: { label: 'Income goal', valueSuffix: '/mo' },
  invest: { label: 'Investing', valueSuffix: '/mo' },
};
const freedomField = (field: string) => (FREEDOM_FIELDS as Record<string, { label: string; valueSuffix: string } | undefined>)[field];

/** "$30,000", or "$1,234.56" when there are cents: the end labels and the slider's spoken value. */
export function formatAmount(value: number): string {
  const cents = Math.round(value * 100) / 100;
  return '$' + cents.toLocaleString('en-US', Number.isInteger(cents) ? undefined : { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** "35%": trailing zeros trimmed. */
export function formatPercent(value: number): string {
  return Number(value.toFixed(2)) + '%';
}

/** "1991": a plain integer, no separators (a year is not a quantity). */
export function formatYear(value: number): string {
  return String(Math.round(value));
}

export function formatValue(value: number, unit: AmountUnit = 'dollars'): string {
  if (unit === 'percent') return formatPercent(value);
  if (unit === 'year') return formatYear(value);
  return formatAmount(value);
}

/** "± $250": what the active field's label reads while the finger is down, for the step in force at `value`. */
export function stepLabel(scale: PlanAmountScale, value: number = scale.min, unit: AmountUnit = 'dollars'): string {
  return '± ' + formatValue(stepAt(scale, value), unit);
}

/**
 * The slide tray under an amount field: a custom slider with minus and plus to its right (clear
 * of Ionic's swipe-back edge). The host owns the value; this emits `valueChange` per detent and
 * per nudge, and `dragging` while the finger is down so the host can swap the field's label and
 * hold any live motion until release.
 *
 * The scale may be uniform or piecewise (utils/plan-amount-scale): the track is index space, a
 * hairline tick marks each breakpoint, and the minus and plus buttons move by the scale's `fine`
 * step when it has one. Colours come from `--tray-*` custom properties on any ancestor (see the
 * SCSS header), with the white-page values as defaults.
 *
 * Pointer events with capture on the hit band. The band has `touch-action: none`: it is a thin
 * strip, so the page loses no scrolling it needs, and a finger that drifts up or down mid-drag
 * stays the slider's instead of handing the pointer to a page scroll (which cancelled the drag and
 * moved the page under the thumb). iOS does not always honour touch-action once a drag is under
 * way, so while a pointer is down a non-passive touchmove listener on the document holds the page
 * still too; it is removed on release, cancel, a field switch and destroy. The thumb follows the
 * finger while dragging and settles onto the detent on release; a detent commits only once the
 * finger is 65% of the way to it.
 */
@Component({
  selector: 'app-plan-amount-tray',
  standalone: true,
  templateUrl: './plan-amount-tray.component.html',
  styleUrls: ['./plan-amount-tray.component.scss'],
})
export class PlanAmountTrayComponent {
  /** Which field this serves; the host's own name for it. A switch between fields ends any gesture in flight. */
  readonly field = input.required<string>();
  readonly value = input.required<number>();
  readonly scale = input.required<PlanAmountScale>();
  readonly unit = input<AmountUnit>('dollars');
  /** The slider's spoken name. */
  readonly label = input<string>();
  /** Spoken after the value ("/mo"). */
  readonly valueSuffix = input<string>();
  /** The two nudge buttons' spoken names. The date sheet's year rail passes "Earlier year" / "Later year". */
  readonly lessLabel = input<string>('Less');
  readonly moreLabel = input<string>('More');
  /** The hold-to-repeat gears, longest hold first, ending in the `afterMs: 0` gear. The date sheet's year rail runs x1 / x5 / x10. */
  readonly gears = input<ReadonlyArray<HoldGear>>(HOLD_GEARS);
  /**
   * Whether a touch on bare track inside Ionic's swipe-back edge is left to the page (see
   * SWIPE_BACK_EDGE_PX). Inside a modal there is no swipe-back, and the guard would only swallow
   * taps on the first few pixels of track, so a sheet passes false.
   */
  readonly edgeGuard = input<boolean>(true);

  readonly valueChange = output<number>();
  readonly dragging = output<boolean>();

  @ViewChild('band', { static: true }) private band!: ElementRef<HTMLElement>;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.stopHold();
      this.unlockScroll();
    });
  }

  readonly isDragging = signal(false);
  /** Where the finger is along the track (0 to 1) while dragging; null once released. */
  private readonly fingerPos = signal<number | null>(null);
  /** The detent the drag holds, for the hysteresis; the host's value is the source of truth otherwise. */
  private dragIndex = 0;
  private activePointer: number | null = null;
  /** Where the finger went down, until a horizontal move arms the drag or a release makes it a tap. */
  private pendingDown: { x: number; y: number } | null = null;
  /**
   * The value last emitted, with the field and input it was emitted against. Events can land
   * before the parent has rendered the new value back into `value`, so until the input moves on,
   * the control reasons from what it emitted, not from the input it has already superseded. The
   * field is part of the key: the other field's value can equal this one by chance.
   */
  private emitted: { field: string; against: number; value: number } | null = null;

  /**
   * The field this instance last served. A host keeps one tray across a field switch, so a
   * finger still down on the slider, or a held minus or plus, would carry straight on into the
   * other field's value. A switch ends whatever gesture was running instead.
   */
  private servedField: string | null = null;
  private _fieldFx = effect(() => {
    const field = this.field();
    if (this.servedField !== null && this.servedField !== field) untracked(() => this.endGestures());
    this.servedField = field;
  });

  readonly ariaLabel = computed(() => this.label() ?? freedomField(this.field())?.label ?? this.field());
  readonly minLabel = computed(() => formatValue(this.scale().min, this.unit()));
  readonly maxLabel = computed(() => formatValue(this.scale().max, this.unit()));
  readonly valueText = computed(() =>
    formatValue(this.value(), this.unit()) + (this.valueSuffix() ?? freedomField(this.field())?.valueSuffix ?? ''));
  /** A seed over the cap reads as the cap until the first touch clamps it. */
  readonly ariaValue = computed(() => clampToScale(this.scale(), this.value()));
  readonly atFloor = computed(() => this.value() <= this.scale().min);
  readonly atCap = computed(() => this.value() >= this.scale().max);

  /** The fill ends at the value's position; an off-grid seed draws where it interpolates. */
  readonly fillPct = computed(() => positionOf(this.scale(), this.value()) * 100);
  /** The thumb rides the finger while dragging, else it sits with the fill. */
  readonly thumbLeft = computed(() => this.trackLeft(this.fingerPos() ?? positionOf(this.scale(), this.value())));
  /** Where each step change sits along the track: a hairline tick at each. */
  readonly markLefts = computed(() => breakpoints(this.scale()).map(v => this.trackLeft(positionOf(this.scale(), v))));

  /** The CSS left of a place along the track (0 to 1), inside the thumb insets. */
  private trackLeft(pos: number): string {
    return `calc(${THUMB_INSET_PX}px + (100% - ${THUMB_INSET_PX * 2}px) * ${pos})`;
  }

  // ── Slider ─────────────────────────────────────────────────────────────────

  onPointerDown(event: PointerEvent): void {
    if (this.activePointer !== null || this.leftToPage(event.clientX)) return;
    this.activePointer = event.pointerId;
    try {
      this.band.nativeElement.setPointerCapture(event.pointerId);
    } catch {
      // A synthetic pointer has nothing to capture; pointermove still reaches the band.
    }
    event.preventDefault();
    this.lockScroll();
    this.isDragging.set(true);
    this.dragging.emit(true);
    // Nothing commits yet: the first horizontal move arms the drag, and a release with no move is
    // a tap. A finger drifting straight up or down moves nothing until it turns sideways.
    this.pendingDown = { x: event.clientX, y: event.clientY };
  }

  onPointerMove(event: PointerEvent): void {
    if (event.pointerId !== this.activePointer) return;
    const down = this.pendingDown;
    if (down) {
      if (Math.abs(event.clientX - down.x) <= Math.abs(event.clientY - down.y)) return;
      this.armDrag(down.x);
    }
    const pos = this.positionFrom(event.clientX);
    this.fingerPos.set(pos);
    const raw = pos * (detentCount(this.scale()) - 1);
    this.commit(commitIndex(this.scale(), raw, this.dragIndex));
  }

  onPointerEnd(event: PointerEvent): void {
    if (event.pointerId !== this.activePointer) return;
    // A release with no move is a tap onto the detent under the finger; a cancel is the page scrolling.
    if (this.pendingDown && event.type === 'pointerup') this.armDrag(event.clientX);
    this.pendingDown = null;
    this.activePointer = null;
    this.unlockScroll();
    this.fingerPos.set(null);
    this.isDragging.set(false);
    this.dragging.emit(false);
  }

  /**
   * The page must not scroll while a finger is down on the slider. The band's `touch-action: none`
   * asks for that, but iOS can still start a scroll from a touch that began on the band, so the
   * document refuses every touchmove for as long as the pointer is held. Non-passive on purpose:
   * a passive listener cannot prevent anything.
   */
  private readonly scrollLock = (event: TouchEvent): void => {
    event.preventDefault();
  };
  private scrollLocked = false;

  private lockScroll(): void {
    if (this.scrollLocked) return;
    this.scrollLocked = true;
    document.addEventListener('touchmove', this.scrollLock, { passive: false });
  }

  private unlockScroll(): void {
    if (!this.scrollLocked) return;
    this.scrollLocked = false;
    document.removeEventListener('touchmove', this.scrollLock);
  }

  /**
   * The swipe-back gesture listens for touchstart on the router outlet, above this component, and
   * pointer capture does nothing to touch events. A touch the slider takes stops here, so the page
   * cannot start sliding away under a thumb dragged from the floor.
   */
  onTouchStart(event: TouchEvent): void {
    const touch = event.changedTouches[0];
    if (touch && !this.leftToPage(touch.clientX)) event.stopPropagation();
  }

  onKeydown(event: KeyboardEvent): void {
    const dir = event.key === 'ArrowRight' || event.key === 'ArrowUp' ? 1
      : event.key === 'ArrowLeft' || event.key === 'ArrowDown' ? -1 : 0;
    if (dir === 0) return;
    event.preventDefault();
    this.nudgeBy(dir);
  }

  /** Ends a drag or a hold in flight; the pointer's later events then belong to nobody. */
  private endGestures(): void {
    this.stopHold();
    if (this.activePointer === null) return;
    try {
      this.band.nativeElement.releasePointerCapture(this.activePointer);
    } catch {
      // Nothing captured (a synthetic pointer): nothing to release.
    }
    this.pendingDown = null;
    this.activePointer = null;
    this.unlockScroll();
    this.fingerPos.set(null);
    this.isDragging.set(false);
    this.dragging.emit(false);
  }

  /** The first touch lands on the detent under the finger (a value over the cap clamps here too). */
  private armDrag(clientX: number): void {
    this.pendingDown = null;
    const pos = this.positionFrom(clientX);
    this.fingerPos.set(pos);
    this.dragIndex = nearestIndex(this.scale(), this.current());
    this.commit(Math.round(pos * (detentCount(this.scale()) - 1)));
  }

  /** The value as this control knows it: see `emitted`. */
  private current(): number {
    const input = this.value();
    const e = this.emitted;
    if (e && e.field === this.field() && e.against === input) return e.value;
    this.emitted = null;
    return input;
  }

  /** The finger's place along the track, 0 to 1, from its x in the viewport. */
  private positionFrom(clientX: number): number {
    const rect = this.band.nativeElement.getBoundingClientRect();
    const span = rect.width - THUMB_INSET_PX * 2;
    if (span <= 0) return 0;
    return Math.max(0, Math.min(1, (clientX - rect.left - THUMB_INSET_PX) / span));
  }

  /** True for a touch inside the swipe-back edge that misses the thumb: see SWIPE_BACK_EDGE_PX. */
  private leftToPage(clientX: number): boolean {
    if (!this.edgeGuard() || clientX > SWIPE_BACK_EDGE_PX) return false;
    const rect = this.band.nativeElement.getBoundingClientRect();
    const span = Math.max(0, rect.width - THUMB_INSET_PX * 2);
    const thumbX = rect.left + THUMB_INSET_PX + span * positionOf(this.scale(), this.value());
    return Math.abs(clientX - thumbX) > THUMB_INSET_PX;
  }

  /** Adopts a detent. Nothing happens when the value already sits there (an off-grid value never does). */
  private commit(index: number): void {
    const next = detentValue(this.scale(), index);
    if (index === this.dragIndex && next === this.current()) return;
    this.dragIndex = index;
    this.emitValue(next);
  }

  private emitValue(next: number): void {
    if (next === this.current()) return;
    this.emitted = { field: this.field(), against: this.value(), value: next };
    this.valueChange.emit(next);
    void tickHaptic();
  }

  // ── Minus and plus ─────────────────────────────────────────────────────────

  /** True once a hold has repeated: the click that follows the release is then not a tap. */
  private holdFired = false;
  private holdTimer: ReturnType<typeof setTimeout> | null = null;
  private holdInterval: ReturnType<typeof setInterval> | null = null;

  /** A tap (pointer or keyboard): one step in the button's direction. */
  onNudgeClick(direction: 1 | -1): void {
    if (this.holdFired) { this.holdFired = false; return; }
    this.nudgeBy(direction);
  }

  /** Press and hold: repeats after HOLD_DELAY_MS, shifting gears the longer it is held. */
  onNudgeDown(event: PointerEvent, direction: 1 | -1): void {
    event.preventDefault();
    this.stopHold();
    this.holdFired = false;
    let ticks = 0;
    let gear = 0;
    this.holdTimer = setTimeout(() => {
      this.holdTimer = null;
      this.holdInterval = setInterval(() => {
        // This tick fires one interval after the last: the time held when it fires counts it.
        ticks++;
        const held = HOLD_DELAY_MS + ticks * HOLD_INTERVAL_MS;
        // A gear list without an `afterMs: 0` entry still single-steps until its first gear engages.
        const steps = this.gears().find(g => held >= g.afterMs)?.steps ?? 1;
        if (steps !== gear) { gear = steps; void tickHaptic(); }
        this.holdFired = true;
        this.nudgeBy(direction, steps);
        // At the end of the scale the button disables under the finger, so no click can follow:
        // the hold ends here, and its flag with it.
        const end = direction > 0 ? this.scale().max : this.scale().min;
        if (this.current() === end) this.stopHold();
      }, HOLD_INTERVAL_MS);
    }, HOLD_DELAY_MS);
  }

  onNudgeUp(): void {
    this.stopHold(true);
  }

  /** The finger left the button, or the pointer was cancelled: the hold ends with no click behind it. */
  onNudgeLeave(): void {
    this.stopHold();
  }

  /**
   * A touch on a button is the button's, like one on the band (onTouchStart). Inside a bottom
   * sheet it would otherwise reach the modal wrapper and arm the drag-to-dismiss gesture: the
   * button has `touch-action: none`, so no pointercancel ever ends the hold, and the sheet moves
   * with the finger, so no pointerleave does either; the value would climb while the sheet left.
   */
  onNudgeTouchStart(event: TouchEvent): void {
    event.stopPropagation();
  }

  /** The iOS long-press callout would otherwise open over a held button. */
  onNudgeContextMenu(event: Event): void {
    event.preventDefault();
  }

  /**
   * Ends a hold. Only a release has a click behind it to consume `holdFired`; any other end of a
   * running hold (the finger leaving, a cancel, the scale's end) has none, and the flag left set
   * would swallow the next keyboard tap on either button.
   */
  private stopHold(releasing = false): void {
    const running = this.holdTimer !== null || this.holdInterval !== null;
    if (this.holdTimer !== null) { clearTimeout(this.holdTimer); this.holdTimer = null; }
    if (this.holdInterval !== null) { clearInterval(this.holdInterval); this.holdInterval = null; }
    if (running && !releasing) this.holdFired = false;
  }

  /** One nudge (the scale's fine step, or a detent); nothing when the value cannot move (already at the end). */
  private nudgeBy(direction: 1 | -1, steps = 1): void {
    const current = this.current();
    const next = nudge(this.scale(), current, direction, steps);
    if (next === current) return;
    this.dragIndex = nearestIndex(this.scale(), next);
    this.emitValue(next);
  }
}
