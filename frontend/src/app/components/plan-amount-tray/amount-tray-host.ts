import { DestroyRef, ElementRef, Signal, computed, signal } from '@angular/core';
import { tickHaptic } from '../quiz-controls/haptics';
import { PlanAmountScale } from '../../utils/plan-amount-scale';
import { AmountUnit, stepLabel } from './plan-amount-tray.component';

/** The house easing: the tray grows and collapses with it. */
export const HOUSE_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
/** The slide tray: grows under the field, collapses back, cross-fades when the field switches. */
export const TRAY_OPEN_MS = 240;
export const TRAY_CLOSE_MS = 200;
export const TRAY_FADE_OUT_MS = 140;
/** A field switch: the card fades out, the slider is swapped at the dip, and the card fades back (220ms in all). */
export const TRAY_SWAP_OUT_MS = 88;
export const TRAY_SWAP_IN_MS = 132;
/** The card lifts this far into place as it fades in. */
export const TRAY_LIFT_PX = 6;
export const DONE_IN_MS = 160;
export const DONE_IN_DELAY_MS = 100;
export const DONE_OUT_MS = 120;
/** The field label's swap between its name and the step size. */
export const LABEL_SWAP_MS = 160;

/** Taps on these never close the tray: the fields, the tray itself and Done. */
export const TRAY_INSIDE_SELECTOR = '.field-tray, .field-value-btn, .field-tray-done';
/** A release this far from its press is a drag that ended, not a tap. */
export const TAP_SLOP_PX = 8;
const LABEL_SELECTOR = '.field-label.active';
const DONE_SELECTOR = '.field-tray-done';

/** Read only while a field is shown; keeps the `scale` signal total in between. */
const NO_SCALE: PlanAmountScale = { min: 0, max: 0, step: 1 };

/** One tray-driven field on a page: its scale, where its value lives, and how the tray speaks it. */
export interface TrayHostField {
  /** The field's name, as its label shows it and the slider speaks it. */
  label: string;
  /** The scale, or a getter (a signal will do) when it changes live, as withdrawable cash does. */
  scale: PlanAmountScale | (() => PlanAmountScale);
  /** The current value: a signal, or a plain getter over the page's model. Read fresh on every render. */
  value: () => number;
  /** Where a detent or a nudge lands. */
  set: (value: number) => void;
  /** Dollars unless said otherwise. */
  unit?: AmountUnit;
  /** Spoken after the value ("/mo"). */
  valueSuffix?: string;
}

export interface AmountTrayHostOptions<F extends string> {
  /** The page's root: the outside-tap test and the label lookup run inside it. */
  root: ElementRef<HTMLElement> | HTMLElement;
  fields: Record<F, TrayHostField>;
  /** Hands the document listener back when the page goes; otherwise the page calls `destroy()`. */
  destroyRef?: DestroyRef;
  /** A tap on anything matching this leaves the tray open. Default TRAY_INSIDE_SELECTOR. */
  insideSelector?: string;
  /** The active field's label, for the swap to the step size. Default '.field-label.active'. */
  labelSelector?: string;
  /** The Done button, for its fades. Default '.field-tray-done'. */
  doneSelector?: string;
  /** Runs when the tray opens on or switches to a field: scroll it into view, say. */
  onOpen?: (field: F) => void;
  /** Overrides the media query (specs). */
  reducedMotion?: () => boolean;
}

/** What the slot component needs from its host: see AmountTraySlotComponent. */
export interface TraySlotHost {
  readonly shown: Signal<string | null>;
  value(): number;
  readonly scale: Signal<PlanAmountScale>;
  readonly unit: Signal<AmountUnit>;
  readonly fieldLabel: Signal<string>;
  readonly valueSuffix: Signal<string | undefined>;
  onValue(value: number): void;
  onDragging(on: boolean): void;
  /** `field` when the page renders a slot per field; the host then moves between slots rather than cross-fading one. */
  attachSlot(box: HTMLElement, inner: HTMLElement, field?: string): void;
  detachSlot(box: HTMLElement): void;
}

/**
 * The page side of a slide tray on a form screen, in one place: which field is open, which is
 * shown, the grow, collapse and cross-fade (WAAPI, since the global reduced-motion rule zeroes
 * CSS transitions), the outside-tap listener, Done's fades, and the label's swap to "± $step"
 * while a finger is down. The choreography is the Freedom Plan page's.
 *
 * A page builds one with its ElementRef and its fields, renders a `.field-value-btn` per field
 * that calls `toggle(field)`, a Done button (`.field-tray-done`, `@if (tray.shown())`) that
 * calls `close()`, and `<app-amount-tray-slot [tray]="tray" />` under `@if (tray.shown())` right
 * below the field's row. `open()` lights the field; `shown()` keeps the slot through the collapse.
 *
 * A page whose fields sit in separate cards or rows renders a slot per field instead, under
 * `@if (tray.shown() === 'k401')` with `field="k401"` on the slot. A switch then collapses the
 * open slot as a close does and grows the other field's when the collapse lands, so nothing below
 * the tray ever snaps; one shared slot cross-fades in place.
 */
export class AmountTrayHost<F extends string> implements TraySlotHost {
  /** The field the tray is open on; null when closed. */
  readonly open = signal<F | null>(null);
  /** The field the tray is rendered for; stays set through the collapse animation. */
  readonly shown = signal<F | null>(null);
  /** True while a finger is down on the tray's slider. */
  readonly dragging = signal(false);

  private readonly shownField = computed(() => {
    const field = this.shown();
    return field === null ? null : this.fields[field];
  });
  readonly scale = computed(() => {
    const field = this.shownField();
    return field ? this.scaleOf(field) : NO_SCALE;
  });
  readonly unit = computed<AmountUnit>(() => this.shownField()?.unit ?? 'dollars');
  readonly fieldLabel = computed(() => this.shownField()?.label ?? '');
  readonly valueSuffix = computed(() => this.shownField()?.valueSuffix);

  /**
   * The shown field's value, read on every render. Not a computed: a field whose `value` is a
   * plain getter over the page's model has no signal to invalidate one, and the tray would keep
   * the figure it opened on after every `set`, and after a load that landed while it was open.
   */
  value(): number {
    return this.shownField()?.value() ?? 0;
  }

  private readonly root: HTMLElement;
  private readonly fields: Record<F, TrayHostField>;
  private readonly insideSelector: string;
  private readonly labelSelector: string;
  private readonly doneSelector: string;
  private readonly onOpen?: (field: F) => void;
  private readonly reducedMotion: () => boolean;

  private box: HTMLElement | null = null;
  private inner: HTMLElement | null = null;
  private done: HTMLElement | null = null;
  /** The field the mounted slot is for, when the page renders one per field; null for a shared slot. */
  private slotField: string | null = null;
  /** The running collapse, so a re-open mid-collapse can take over from where it is. */
  private collapse: Animation | null = null;
  /** Where the collapse lands: another field (its slot grows next) or nothing. Re-aimed by taps mid-collapse. */
  private landOn: F | null = null;
  /** The running field switch's fade-out; the swap lands when it does, unless the tray moved on. */
  private swap: Animation | null = null;

  constructor(options: AmountTrayHostOptions<F>) {
    this.root = options.root instanceof ElementRef ? options.root.nativeElement : options.root;
    this.fields = options.fields;
    this.insideSelector = options.insideSelector ?? TRAY_INSIDE_SELECTOR;
    this.labelSelector = options.labelSelector ?? LABEL_SELECTOR;
    this.doneSelector = options.doneSelector ?? DONE_SELECTOR;
    this.onOpen = options.onOpen;
    this.reducedMotion = options.reducedMotion ?? prefersReducedMotion;
    options.destroyRef?.onDestroy(() => this.destroy());
  }

  /** True while the tray is open on this field (the lit state). */
  isOpen(field: F): boolean {
    return this.open() === field;
  }

  /** True while the finger is down on this field's slider. */
  showsStep(field: F): boolean {
    return this.dragging() && this.open() === field;
  }

  /** The field's label text: its name, or the step in force while the finger is down on its slider. */
  label(field: F): string {
    const f = this.fields[field];
    if (!this.showsStep(field)) return f.label;
    return stepLabel(this.scaleOf(f), f.value(), f.unit);
  }

  /**
   * A field tap: open on that field, switch to it, or close when it is the open one. A switch
   * waits while a finger is down on the slider: the drag would carry on into the other field's
   * scale.
   */
  toggle(field: F): void {
    const open = this.open();
    if (open === field) { this.close(); return; }
    if (open !== null && this.dragging()) return;
    void tickHaptic();
    if (open !== null) { this.switchTo(field); return; }
    this.openOn(field);
  }

  /**
   * Done, the open field again, a tap outside, submit, or leaving the page. The card fades as the
   * wrapper collapses; the slot leaves the DOM when the collapse lands. `instant` skips the
   * motion (leaving the page, reduced motion).
   */
  close(instant = false): void {
    // Already closed, or a collapse is in flight (Done stays tappable through it): a second tap
    // must not restart the motion from the card's half-collapsed height and full opacity.
    if (this.open() === null && (this.shown() === null || (this.collapse !== null && !instant))) return;
    this.open.set(null);
    this.swap = null;
    this.dragging.set(false);
    this.watchOutside(false);
    if (instant || !this.canAnimate()) {
      this.collapse = null;
      this.shown.set(null);
      return;
    }
    this.collapseTo(null);
  }

  /**
   * Height to 0 and the card and Done faded, from wherever they are: a grow or swap still running
   * hands over its live height and opacity (Done's fade-in may not have started). When it lands
   * the slot leaves, and `field`'s slot takes its place (a page with a slot per field) or nothing.
   */
  private collapseTo(field: F | null): void {
    const box = this.box!;
    const inner = this.inner!;
    this.swap = null;
    this.landOn = field;
    const fromHeight = box.getBoundingClientRect().height;
    const fromOpacity = Number(getComputedStyle(inner).opacity);
    const done = this.doneEl();
    const doneFrom = done ? Number(getComputedStyle(done).opacity) : 1;
    box.getAnimations().forEach(a => a.cancel());
    inner.getAnimations().forEach(a => a.cancel());
    done?.getAnimations().forEach(a => a.cancel());
    const collapse = box.animate(
      [{ height: `${fromHeight}px` }, { height: '0px' }],
      { duration: TRAY_CLOSE_MS, easing: HOUSE_EASING, fill: 'forwards' },
    );
    inner.animate([{ opacity: fromOpacity }, { opacity: 0 }], { duration: TRAY_FADE_OUT_MS, fill: 'forwards' });
    done?.animate([{ opacity: doneFrom }, { opacity: 0 }], { duration: DONE_OUT_MS, fill: 'forwards' });
    collapse.onfinish = () => {
      if (this.collapse !== collapse) return;
      this.collapse = null;
      this.shown.set(this.landOn);
      // A finger that went down on the card mid-collapse leaves with it, and never reports its release.
      this.dragging.set(false);
    };
    this.collapse = collapse;
  }

  /** Hands the document listener back. The page's DestroyRef does this when one was given. */
  destroy(): void {
    this.watchOutside(false);
  }

  // ── Slot and tray callbacks ────────────────────────────────────────────────

  /** The slot has rendered: grow it out of nothing, and fade Done in beside the field's label. */
  attachSlot(box: HTMLElement, inner: HTMLElement, field?: string): void {
    this.box = box;
    this.inner = inner;
    this.slotField = field ?? null;
    // A slot per field: this one may mount before the leaving one is destroyed, so its Done is looked up afresh.
    this.done = null;
    this.grow(0, 0);
    const done = this.doneEl();
    if (done && !this.reducedMotion() && typeof done.animate === 'function') {
      // One Done shared by every field's slot (portfolio-customize) survives a switch: the held
      // last frame of its fade-out would win back once this fade-in ended, leaving it invisible.
      done.getAnimations().forEach(a => a.cancel());
      done.animate([{ opacity: 0 }, { opacity: 1 }], { duration: DONE_IN_MS, delay: DONE_IN_DELAY_MS, fill: 'backwards' });
    }
  }

  detachSlot(box: HTMLElement): void {
    if (this.box !== box) return;
    this.box = null;
    this.inner = null;
    this.done = null;
    this.slotField = null;
  }

  /**
   * The tray's value lands on the field the card shows (not the lit one: the card is still live
   * through a collapse, and lags a field switch by the fade-out).
   */
  onValue(value: number): void {
    this.shownField()?.set(value);
  }

  /** While the finger is down the active field's label reads the step size; a quick fade sells the swap. */
  onDragging(on: boolean): void {
    this.dragging.set(on);
    if (this.reducedMotion()) return;
    const label = this.root.querySelector(this.labelSelector) as HTMLElement | null;
    label?.animate?.([{ opacity: 0 }, { opacity: 1 }], { duration: LABEL_SWAP_MS, easing: 'ease-out' });
  }

  // ── Open, switch ───────────────────────────────────────────────────────────

  private openOn(field: F): void {
    this.open.set(field);
    if (this.collapse !== null) {
      // Caught mid-collapse. Under another field's slot, that one lands first and this field's
      // grows in its place; otherwise the grow picks up from where the card is.
      if (this.slotLeaves(field)) this.landOn = field;
      else this.regrow();
    }
    if (this.collapse === null) this.shown.set(field);
    this.watchOutside(true);
    this.onOpen?.(field);
  }

  /**
   * The tray stays open and moves to the other field. One shared slot cross-fades, no height
   * change: the field lights at once; the slider is swapped only when the card has faded out, so
   * the new thumb, fill and end labels never show at full opacity before the dip. A second switch
   * mid-fade starts from the opacity the card is at, and the first one's swap is dropped. A slot
   * per field collapses the open one as a close does; the other field's grows when it lands.
   */
  private switchTo(field: F): void {
    this.open.set(field);
    this.onOpen?.(field);
    const inner = this.inner;
    if (!inner || !this.canAnimate()) {
      this.shown.set(field);
      return;
    }
    if (this.slotLeaves(field)) {
      if (this.collapse !== null) this.landOn = field;
      else this.collapseTo(field);
      return;
    }
    if (this.collapse !== null) {
      // Back to the field whose slot is still leaving: grow it back from where it is.
      this.regrow();
      return;
    }
    const from = Number(getComputedStyle(inner).opacity);
    const out = inner.animate([{ opacity: from }, { opacity: 0 }], { duration: TRAY_SWAP_OUT_MS, easing: 'ease-out' });
    out.onfinish = () => {
      if (this.swap !== out) return;
      this.swap = null;
      this.shown.set(field);
      inner.animate([{ opacity: 0 }, { opacity: 1 }], { duration: TRAY_SWAP_IN_MS, easing: 'ease-out' });
    };
    this.swap = out;
  }

  /** A collapse in flight is dropped and the grow picks up from the height and opacity the card is at. */
  private regrow(): void {
    this.collapse = null;
    const box = this.box;
    const inner = this.inner;
    if (!box || !inner) return;
    const fromHeight = box.getBoundingClientRect().height;
    const fromOpacity = Number(getComputedStyle(inner).opacity);
    box.getAnimations().forEach(a => a.cancel());
    inner.getAnimations().forEach(a => a.cancel());
    this.grow(fromHeight, fromOpacity);
    this.restoreDone();
  }

  /** True when the mounted slot is another field's own (a slot per field), so it must leave before this one's grows. */
  private slotLeaves(field: F): boolean {
    return this.slotField !== null && this.slotField !== field;
  }

  /** A slot is mounted, motion is wanted, and the browser has WAAPI. */
  private canAnimate(): boolean {
    return !!this.box && !!this.inner && !this.reducedMotion() && typeof this.box.animate === 'function';
  }

  /** Height 0 to the measured height, the card fading in with a small lift, together. */
  private grow(fromHeight: number, fromOpacity: number): void {
    const box = this.box;
    const inner = this.inner;
    if (!box || this.reducedMotion() || typeof box.animate !== 'function') return;
    const toHeight = box.scrollHeight;
    box.animate(
      [{ height: `${fromHeight}px` }, { height: `${toHeight}px` }],
      { duration: TRAY_OPEN_MS, easing: HOUSE_EASING },
    );
    inner?.animate(
      [
        { opacity: fromOpacity, transform: `translateY(${-TRAY_LIFT_PX * (1 - fromOpacity)}px)` },
        { opacity: 1, transform: 'translateY(0px)' },
      ],
      { duration: TRAY_OPEN_MS, easing: HOUSE_EASING },
    );
  }

  /**
   * Done never left the DOM through a collapse (`shown` stayed set), so its fade-out's held last
   * frame is still on it when the tray is re-opened mid-collapse; drop it and fade Done back in
   * from wherever it is.
   */
  private restoreDone(): void {
    const done = this.doneEl();
    if (!done) return;
    const from = Number(getComputedStyle(done).opacity);
    done.getAnimations().forEach(a => a.cancel());
    if (!this.reducedMotion()) done.animate?.([{ opacity: from }, { opacity: 1 }], { duration: DONE_IN_MS });
  }

  /** Done, looked up once per slot: it renders in the same pass as the slot and leaves with it. */
  private doneEl(): HTMLElement | null {
    if (!this.done || !this.done.isConnected) this.done = this.root.querySelector(this.doneSelector) as HTMLElement | null;
    return this.done;
  }

  private scaleOf(field: TrayHostField): PlanAmountScale {
    return typeof field.scale === 'function' ? field.scale() : field.scale;
  }

  // ── Outside tap ────────────────────────────────────────────────────────────

  /**
   * A tap anywhere but the fields, the tray and Done closes the tray. The close waits for the
   * release: the collapse moves everything below the tray up, and a control that moved out from
   * under the finger mid-tap would lose its click. A cancel (the page scrolled) is not a tap, and
   * nor is a release that travelled: a JS gesture (a sheet pulled and let go, a row swiped) ends
   * in a pointerup, not the cancel a native scroll sends. Capture, so nothing swallows it.
   */
  private outsidePointer: number | null = null;
  private outsideDownX = 0;
  private outsideDownY = 0;
  private readonly onOutsidePointer = (event: Event): void => {
    const e = event as PointerEvent;
    if (e.type === 'pointerdown') {
      const target = e.target as Element | null;
      if (target?.closest?.(this.insideSelector)) return;
      this.outsidePointer = e.pointerId;
      this.outsideDownX = e.clientX;
      this.outsideDownY = e.clientY;
      return;
    }
    if (e.pointerId !== this.outsidePointer) return;
    this.outsidePointer = null;
    if (e.type !== 'pointerup') return;
    if (Math.hypot(e.clientX - this.outsideDownX, e.clientY - this.outsideDownY) > TAP_SLOP_PX) return;
    this.close();
  };

  private watchOutside(on: boolean): void {
    this.outsidePointer = null;
    for (const type of ['pointerdown', 'pointerup', 'pointercancel']) {
      if (on) document.addEventListener(type, this.onOutsidePointer, true);
      else document.removeEventListener(type, this.onOutsidePointer, true);
    }
  }
}

function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}
