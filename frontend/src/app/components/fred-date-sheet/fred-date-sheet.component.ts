import {
  AfterViewInit,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  Injector,
  Input,
  NgZone,
  OnInit,
  ViewChild,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import {
  addDays,
  ageOn,
  dayOfWeek,
  daysInMonth,
  formatFull,
  formatLong,
  formatMonthDay,
  formatShort,
  isWeekend,
  parseYmd,
  todayYmd,
  ymd,
} from '../../utils/local-date';
import { PlanAmountScale } from '../../utils/plan-amount-scale';
import { QuickPick } from '../../utils/quick-picks';
import { HoldGear, PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';
import { thumpHaptic, tickHaptic } from '../quiz-controls/haptics';

/** upcoming = a transfer or pay date ahead of today; birthday = a date of birth, with the year rail. */
export type FredDateSheetMode = 'upcoming' | 'birthday';

export interface FredDateSheetOptions {
  /** The zone label over the readout, e.g. 'Transfer date', 'Date of birth'. */
  title: string;
  mode: FredDateSheetMode;
  /** YYYY-MM-DD. A value outside the range (or on a weekend under businessDaysOnly) opens its month but is not chosen. */
  value?: string | null;
  /** YYYY-MM-DD, inclusive. */
  min?: string;
  /** YYYY-MM-DD, inclusive. */
  max?: string;
  /** upcoming: weekends drawn disabled and unreachable, with the caption under the grid. */
  businessDaysOnly?: boolean;
  /** upcoming: up to four chips, already filtered to the range (utils/quick-picks). */
  quickPicks?: QuickPick[];
  /** The readout's second line for a chosen date, e.g. 'Trades land Nov 23 to Nov 25'. Birthday defaults to the age. */
  subline?: (date: string) => string;
  /** Done's label for a chosen date. Default 'Use Fri, Nov 20' (upcoming) or 'Use Mar 14, 1991' (birthday). */
  doneLabel?: (date: string) => string;
}

/** The dismiss role when the user tapped Done; the data is `{ value }` as YYYY-MM-DD. */
export const FRED_DATE_SHEET_ROLE = 'done';

/** The year rail's hold-to-repeat gears: a year a tick, then five, then ten. */
export const YEAR_GEARS: ReadonlyArray<HoldGear> = [
  { afterMs: 3000, steps: 10 },
  { afterMs: 1600, steps: 5 },
  { afterMs: 0, steps: 1 },
];
/** The rail runs from this many years before today's year up to it. */
export const YEAR_SPAN = 110;
/** An empty birthday sheet opens this many years back, where most dates of birth sit. */
export const BIRTHDAY_OPEN_YEARS_BACK = 30;

export const EASE = 'cubic-bezier(0.32, 0.72, 0, 1)';
export const RING_GLIDE_MS = 180;
/** The chosen cell's number turns white this far into the glide, as the ring arrives under it. */
export const RING_PAINT_MS = 90;
export const READOUT_FADE_MS = 120;
export const PANE_OUT_MS = 88;
export const PANE_IN_MS = 132;
export const SHAKE_MS = 180;
export const PAGE_SLIDE_PX = 24;
const RING_PX = 36;
/** The ring sits this far below the cell's top: a 36px ring centred in a 40px row. */
const RING_TOP_PX = 2;

const SHAKE_FRAMES: Keyframe[] = [
  { transform: 'translateX(0)' },
  { transform: 'translateX(-6px)' },
  { transform: 'translateX(6px)' },
  { transform: 'translateX(-3px)' },
  { transform: 'translateX(0)' },
];

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const MONTHS_SHORT = MONTHS.map(name => name.slice(0, 3));
const WEEKDAY_LETTERS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const GRID_COLS = 7;
/** Six rows, always: the sheet never changes height between months. */
const GRID_ROWS = 6;
/** How far each arrow key moves the focused day. */
const ARROW_STEPS: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -GRID_COLS, ArrowDown: GRID_COLS };

/** One of the 42 grid cells; `date` is null for the blanks before the 1st and after the last day. */
interface DayCell {
  key: number;
  date: string | null;
  day: number;
  disabled: boolean;
  weekend: boolean;
  today: boolean;
  label: string;
}

interface MonthTile {
  m: number;
  name: string;
  label: string;
  disabled: boolean;
}

interface Chip extends QuickPick {
  monthDay: string;
  ariaLabel: string;
}

interface Pane {
  y: number;
  m: number;
}

/** Where the ring sits, in the grid pane's pixels. Hidden rings keep their last place to grow back from. */
interface RingAt {
  x: number;
  y: number;
  shown: boolean;
}

function ringFrame(at: RingAt): { transform: string; opacity: string } {
  return {
    transform: `translate(${at.x}px, ${at.y}px) scale(${at.shown ? 1 : 0.6})`,
    opacity: at.shown ? '1' : '0',
  };
}

function ageText(years: number): string {
  return `${years} ${years === 1 ? 'year' : 'years'} old`;
}

/**
 * The FRED date sheet: one bottom sheet for every date in the app. A big live readout, the month
 * grid with one blue ring that glides between days, quick-pick chips for an upcoming date or the
 * year rail (the slide tray with unit 'year') for a birthday, and a Done that says what it commits.
 * Present with presentDateSheet() below, or with BOTTOM_SHEET_MODAL_OPTS and FredDateSheetOptions
 * as componentProps.
 *
 * Nothing reaches the page until Done: dismiss({ value }, 'done'). Cancel, a drag down or a tap
 * outside: no role. Motion is WAAPI only (the global reduced-motion rule zeroes CSS transitions),
 * and under reduced motion every change snaps with no animate() call at all.
 */
@Component({
  selector: 'app-fred-date-sheet',
  templateUrl: './fred-date-sheet.component.html',
  styleUrls: ['./fred-date-sheet.component.scss'],
  standalone: true,
  imports: [PlanAmountTrayComponent],
  host: {
    class: 'ion-content-scroll-host',
    role: 'dialog',
    'aria-modal': 'true',
    '[attr.aria-label]': 'title',
  },
  hostDirectives: [SheetDragDismissDirective],
})
export class FredDateSheetComponent implements OnInit, AfterViewInit {
  @Input({ required: true }) title = '';
  @Input({ required: true }) mode: FredDateSheetMode = 'upcoming';
  @Input() value?: string | null;
  @Input() min?: string;
  @Input() max?: string;
  @Input() businessDaysOnly?: boolean;
  @Input() quickPicks?: QuickPick[];
  @Input() subline?: (date: string) => string;
  @Input() doneLabel?: (date: string) => string;
  /** Today as YYYY-MM-DD: the dot, the readout's year rule, the rail's top. Specs pin it. */
  @Input() today = todayYmd();

  readonly YEAR_GEARS = YEAR_GEARS;
  readonly weekdays = WEEKDAY_LETTERS;
  /** Built once in ngOnInit (not an inline literal) so the tray's detent cache is not rebuilt per change detection. */
  yearScale!: PlanAmountScale;
  chips: Chip[] = [];

  /** The chosen day, or null. Only a day tap, a chip or the year rail move it. */
  readonly selected = signal<string | null>(null);
  /** The cell drawn white: lags `selected` by RING_PAINT_MS so the number turns as the ring arrives. */
  readonly paint = signal<string | null>(null);
  /** The month on view: the header, the chevrons, the tiles and the rail read it the moment it changes. */
  readonly view = signal<Pane>({ y: 1970, m: 1 });
  /** The month the day grid is drawing: follows `view` once the outgoing grid has slid out. */
  readonly gridView = signal<Pane>({ y: 1970, m: 1 });
  /** What the month button says is up; `visiblePane` follows it once the outgoing pane has faded. */
  readonly pane = signal<'days' | 'months'>('days');
  readonly visiblePane = signal<'days' | 'months'>('days');
  readonly railDragging = signal(false);
  /** 'Feb 29 moved to Feb 28 in 1991.' after the rail clamped the day; cleared by the next tap. */
  readonly clampNote = signal('');

  /** Held on the instance so a spec can watch them: module functions cannot be spied on. */
  readonly haptics = { tick: tickHaptic, thump: thumpHaptic };

  @ViewChild('ring', { static: true }) private ring!: ElementRef<HTMLElement>;
  @ViewChild('days', { static: true }) private days!: ElementRef<HTMLElement>;
  @ViewChild('months', { static: true }) private months!: ElementRef<HTMLElement>;
  @ViewChild('readout', { static: true }) private readout!: ElementRef<HTMLElement>;

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly modalCtrl = inject(ModalController);
  private readonly injector = inject(Injector);
  private readonly zone = inject(NgZone);
  /** Null until the first placement, which always writes the ring's frame. */
  private ringAt: RingAt | null = null;
  private paintTimer: ReturnType<typeof setTimeout> | null = null;
  /** The `after` callbacks owed by the grid's out-slide in flight; null while no out-slide is running. */
  private outSlideAfters: (() => void)[] | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearPaintTimer());
  }

  /** The rail's year: the chosen day's, else the month on view. */
  readonly year = computed(() => {
    const selected = this.selected();
    return selected ? parseYmd(selected).y : this.view().y;
  });

  readonly headerText = computed(() => `${MONTHS[this.view().m - 1]} ${this.view().y}`);

  readonly rows = computed<DayCell[][]>(() => {
    const { y, m } = this.gridView();
    const lead = dayOfWeek(ymd(y, m, 1));
    const count = daysInMonth(y, m);
    const cells: DayCell[] = [];
    for (let i = 0; i < GRID_COLS * GRID_ROWS; i++) {
      const d = i - lead + 1;
      if (d < 1 || d > count) {
        cells.push({ key: i, date: null, day: 0, disabled: true, weekend: false, today: false, label: '' });
        continue;
      }
      const date = ymd(y, m, d);
      const ok = this.selectable(date);
      cells.push({
        key: i,
        date,
        day: d,
        disabled: !ok,
        weekend: this.mode === 'upcoming' && isWeekend(date),
        today: date === this.today,
        label: formatFull(date) + (ok ? '' : ', unavailable'),
      });
    }
    const rows: DayCell[][] = [];
    for (let r = 0; r < GRID_ROWS; r++) rows.push(cells.slice(r * GRID_COLS, (r + 1) * GRID_COLS));
    return rows;
  });

  readonly monthTiles = computed<MonthTile[]>(() => {
    const { y } = this.view();
    return MONTHS_SHORT.map((name, i) => ({
      m: i + 1,
      name,
      label: `${MONTHS[i]} ${y}`,
      disabled: !this.monthInRange(y, i + 1),
    }));
  });

  readonly prevDisabled = computed(() => !this.reachable(this.neighbour(-1)));

  readonly nextDisabled = computed(() => !this.reachable(this.neighbour(1)));

  readonly readoutText = computed(() => {
    const selected = this.selected();
    if (!selected) return 'Pick a day';
    return this.mode === 'birthday' ? formatLong(selected) : formatShort(selected, this.today);
  });

  readonly sublineText = computed(() => {
    const selected = this.selected();
    if (!selected) return this.mode === 'birthday' ? 'Slide the year, then tap a day' : 'Tap a quick pick or a day';
    if (this.subline) return this.subline(selected);
    return this.mode === 'birthday' ? ageText(ageOn(selected, this.today)) : '';
  });

  readonly doneText = computed(() => {
    const selected = this.selected();
    if (!selected) return this.mode === 'birthday' ? 'Done' : 'Use this date';
    return this.doneLabel ? this.doneLabel(selected) : `Use ${this.readoutText()}`;
  });

  readonly noteText = computed(() => this.clampNote() || (this.businessDaysOnly ? 'Transfers run on business days.' : ''));

  ngOnInit(): void {
    // Ionic copies componentProps with Object.assign, so an explicit undefined lands over a default.
    this.businessDaysOnly ??= false;
    this.chips = (this.quickPicks ?? []).map(pick => ({
      ...pick,
      monthDay: formatMonthDay(pick.date),
      ariaLabel: `${pick.label}, ${formatFull(pick.date)}`,
    }));

    const todayParts = parseYmd(this.today);
    this.yearScale = { min: todayParts.y - YEAR_SPAN, max: todayParts.y, step: 1, fine: 1 };

    // An incoming value that cannot be chosen (a passed transfer date, a weekend seed) opens its
    // month but is not selected: the page keeps showing it until Done commits a valid day.
    const seed = this.value ?? null;
    if (seed && this.selectable(seed)) {
      this.selected.set(seed);
      this.paint.set(seed);
    }
    const open = seed
      ?? (this.mode === 'birthday' ? ymd(todayParts.y - BIRTHDAY_OPEN_YEARS_BACK, todayParts.m, 1) : (this.min ?? this.today));
    const { y, m } = parseYmd(open);
    this.view.set({ y, m });
    this.gridView.set({ y, m });
  }

  ngAfterViewInit(): void {
    this.positionRing(false);
    // Ionic keeps the modal display: none until present() starts its animation, so inside one the
    // placement above measured a 0x0 cell. Measure again on the first frame the modal is shown.
    this.host.nativeElement.closest('ion-modal')?.addEventListener('ionModalWillPresent', () => {
      requestAnimationFrame(() => this.zone.run(() => this.positionRing(false)));
    }, { once: true });
  }

  /** A rotation moves every cell; the ring follows with no glide. */
  @HostListener('window:resize')
  onResize(): void {
    this.positionRing(false);
  }

  // ── Grid ────────────────────────────────────────────────────────────────────

  onCell(cell: DayCell, event: Event): void {
    if (!cell.date) return;
    if (cell.disabled) {
      void this.haptics.thump();
      if (!this.reducedMotion()) (event.currentTarget as HTMLElement).animate(SHAKE_FRAMES, { duration: SHAKE_MS });
      return;
    }
    this.select(cell.date);
  }

  /**
   * Arrows move the focused day; a step past the month's edge pages the grid and lands on the new
   * cell, but never into a month the chevrons would refuse.
   */
  onGridKeydown(event: KeyboardEvent): void {
    const step = ARROW_STEPS[event.key];
    const current = (event.target as HTMLElement | null)?.closest<HTMLElement>('.cell')?.dataset['date'];
    if (!step || !current) return;
    event.preventDefault();
    const next = addDays(current, step);
    const { y, m } = parseYmd(next);
    const view = this.view();
    if ((y !== view.y || m !== view.m) && !this.reachable({ y, m })) return;
    this.setView(y, m, step > 0 ? 1 : -1, () => this.cellOf(next)?.focus());
  }

  onChip(chip: Chip): void {
    this.select(chip.date);
  }

  /** The month button: swaps the day grid for the 3 x 4 month tiles and back. */
  flip(): void {
    const toMonths = this.pane() === 'days';
    this.pane.set(toMonths ? 'months' : 'days');
    const outEl = toMonths ? this.days.nativeElement : this.months.nativeElement;
    const inEl = toMonths ? this.months.nativeElement : this.days.nativeElement;
    if (toMonths) this.positionRing(false);
    const swap = () => {
      this.visiblePane.set(this.pane());
      this.afterRender(() => {
        if (!toMonths) this.positionRing(false);
        if (!this.reducedMotion()) {
          this.play(inEl, [{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'translateY(0)' }], PANE_IN_MS);
        }
      });
    };
    if (this.reducedMotion()) {
      swap();
      return;
    }
    this.play(outEl, [{ opacity: 1 }, { opacity: 0 }], PANE_OUT_MS, swap);
  }

  /** A tile sets the month on view and flips back; the selection stays where it was. */
  onMonthTile(tile: MonthTile): void {
    if (tile.disabled) return;
    void this.haptics.tick();
    this.setView(this.view().y, tile.m, 0);
    this.flip();
  }

  /** The chevrons: one month either way, never past the range (see `reachable`). */
  page(dir: 1 | -1): void {
    const next = this.neighbour(dir);
    if (!this.reachable(next)) return;
    void this.haptics.tick();
    this.setView(next.y, next.m, dir);
  }

  // ── Year rail ───────────────────────────────────────────────────────────────

  /**
   * The rail moved. The chosen day keeps its month and day, clamped to the new year's month
   * length (Feb 29 to Feb 28) and to the range, with the note saying so; with nothing chosen the
   * grid just shows the same month of the new year. No haptic here: the tray ticks per detent.
   */
  onYear(year: number): void {
    const y = Math.max(this.yearScale.min, Math.min(this.yearScale.max, year));
    const selected = this.selected();
    if (!selected) {
      if (this.view().y !== y) this.setView(y, this.view().m, 0);
      return;
    }
    const { m, d } = parseYmd(selected);
    let next = ymd(y, m, Math.min(d, daysInMonth(y, m)));
    if (this.max && next > this.max) next = this.max;
    if (this.min && next < this.min) next = this.min;
    if (next === selected) return;
    const moved = formatMonthDay(next) !== formatMonthDay(selected);
    this.clampNote.set(moved ? `${formatMonthDay(selected)} moved to ${formatMonthDay(next)} in ${y}.` : '');
    this.select(next, true);
  }

  /** Home and End jump the rail to its ends; the tray handles the arrows itself. */
  onRailKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    this.onYear(event.key === 'Home' ? this.yearScale.min : this.yearScale.max);
  }

  // ── Done and Cancel ─────────────────────────────────────────────────────────

  done(): void {
    const value = this.selected();
    if (!value) return;
    void this.modalCtrl.dismiss({ value }, FRED_DATE_SHEET_ROLE);
  }

  close(): void {
    void this.modalCtrl.dismiss();
  }

  // ── Selection ───────────────────────────────────────────────────────────────

  /**
   * Adopts a day. In the month on view the ring glides to it; in another month the grid pages
   * there (a slide for a tap, a plain redraw for the rail) and the ring appears with it.
   */
  private select(date: string, viaRail = false): void {
    const was = this.selected();
    if (date === was) return;
    this.selected.set(date);
    this.clearPaintTimer();
    // A tap ticks and cross-fades the readout; the rail ticks per detent itself, and a readout
    // fading on every year of a drag would flicker.
    if (!viaRail) {
      this.clampNote.set('');
      void this.haptics.tick();
      if (!this.reducedMotion()) {
        this.play(this.readout.nativeElement, [{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'translateY(0)' }], READOUT_FADE_MS);
      }
    }
    const { y, m } = parseYmd(date);
    const view = this.view();
    if (y !== view.y || m !== view.m) {
      this.paint.set(date);
      this.setView(y, m, viaRail ? 0 : date > (was ?? this.today) ? 1 : -1);
    } else {
      this.paint.set(null);
      this.afterRender(() => this.positionRing(true));
      this.later(RING_PAINT_MS, () => this.paint.set(date));
    }
  }

  /**
   * Shows another month. `view` moves at once (the header and the chevrons never lag a tap), the
   * grid follows: with a direction it slides PAGE_SLIDE_PX out, redraws, and slides in from the
   * other side; without one (the rail, a month tile) it redraws in place. `after` runs once the
   * new cells are in the DOM.
   */
  private setView(y: number, m: number, dir: 1 | 0 | -1, after?: () => void): void {
    const view = this.view();
    if (view.y === y && view.m === m) {
      if (after) this.afterRender(after);
      return;
    }
    this.view.set({ y, m });
    if (this.outSlideAfters) {
      // A second tap inside the out-slide: the running one's apply reads the latest `view`, so a
      // fresh out-slide would only snap the fading grid back to opaque and stack two slide-ins.
      if (after) this.outSlideAfters.push(after);
      return;
    }
    const afters = after ? [after] : [];
    const apply = () => {
      this.outSlideAfters = null;
      // The latest target, not this call's: a second tap inside the out-slide must not drag the grid back.
      this.gridView.set(this.view());
      this.afterRender(() => {
        this.positionRing(false);
        if (dir !== 0 && !this.reducedMotion()) {
          this.play(this.days.nativeElement, [
            { transform: `translateX(${PAGE_SLIDE_PX * dir}px)`, opacity: 0 },
            { transform: 'translateX(0)', opacity: 1 },
          ], PANE_IN_MS);
        }
        afters.forEach(fn => fn());
      });
    };
    if (dir === 0 || this.visiblePane() !== 'days' || this.reducedMotion()) {
      apply();
      return;
    }
    this.outSlideAfters = afters;
    this.play(this.days.nativeElement, [
      { transform: 'translateX(0)', opacity: 1 },
      { transform: `translateX(${-PAGE_SLIDE_PX * dir}px)`, opacity: 0 },
    ], PANE_OUT_MS, apply);
  }

  /**
   * Moves the one ring to the chosen cell, measured from the cell's offsets at this moment. With
   * `glide` it animates there; a ring that was hidden grows in place instead of flying in from
   * wherever it last sat. No chosen cell on view (none chosen, or the month pane is up) hides it.
   */
  private positionRing(glide: boolean): void {
    const selected = this.selected();
    const cell = selected && this.pane() === 'days' && this.visiblePane() === 'days' ? this.cellOf(selected) : null;
    const was = this.ringAt;
    const next: RingAt = cell
      ? { x: cell.offsetLeft + (cell.offsetWidth - RING_PX) / 2, y: cell.offsetTop + RING_TOP_PX, shown: true }
      : { x: was?.x ?? 0, y: was?.y ?? 0, shown: false };
    if (was && next.x === was.x && next.y === was.y && next.shown === was.shown) return;
    this.ringAt = next;
    const ring = this.ring.nativeElement;
    const to = ringFrame(next);
    if (!glide || this.reducedMotion()) {
      Object.assign(ring.style, to);
      return;
    }
    const from = ringFrame(was?.shown ? was : { ...next, shown: false });
    this.play(ring, [from, to], RING_GLIDE_MS, () => Object.assign(ring.style, to));
  }

  private cellOf(date: string): HTMLElement | null {
    return this.days.nativeElement.querySelector<HTMLElement>(`[data-date="${date}"]`);
  }

  // ── Rules ───────────────────────────────────────────────────────────────────

  private inRange(date: string): boolean {
    return (!this.min || date >= this.min) && (!this.max || date <= this.max);
  }

  private selectable(date: string): boolean {
    return this.inRange(date) && !(this.businessDaysOnly && isWeekend(date));
  }

  private monthInRange(y: number, m: number): boolean {
    const first = ymd(y, m, 1);
    const last = ymd(y, m, daysInMonth(y, m));
    return (!this.min || last >= this.min) && (!this.max || first <= this.max);
  }

  /** The month `dir` steps from the one on view. */
  private neighbour(dir: 1 | -1): Pane {
    const { y, m } = this.view();
    return m + dir < 1 ? { y: y - 1, m: 12 } : m + dir > 12 ? { y: y + 1, m: 1 } : { y, m: m + dir };
  }

  /**
   * Whether the grid may move to `next`: a month in the range, or any step back toward it while
   * the view sits outside (an old seed opened its own month; the chevron that faces the range stays
   * live so the user is never stranded on a month of disabled days).
   */
  private reachable(next: Pane): boolean {
    if (this.monthInRange(next.y, next.m)) return true;
    const view = this.view();
    const ahead = next.y * 12 + next.m > view.y * 12 + view.m;
    const viewBeforeMin = !!this.min && ymd(view.y, view.m, daysInMonth(view.y, view.m)) < this.min;
    const viewAfterMax = !!this.max && ymd(view.y, view.m, 1) > this.max;
    return (viewBeforeMin && ahead) || (viewAfterMax && !ahead);
  }

  // ── Motion plumbing ─────────────────────────────────────────────────────────

  /**
   * One WAAPI run with the house easing, held at its end until `onDone` has committed the new
   * state, then dropped. `onDone` runs inside the zone: it writes signals.
   */
  private play(el: HTMLElement, frames: Keyframe[], duration: number, onDone?: () => void): void {
    const animation = el.animate(frames, { duration, easing: EASE, fill: 'forwards' });
    animation.onfinish = () => this.zone.run(() => {
      onDone?.();
      animation.cancel();
    });
  }

  private afterRender(fn: () => void): void {
    afterNextRender(fn, { injector: this.injector });
  }

  /** A short delay that collapses to now under reduced motion. */
  private later(ms: number, fn: () => void): void {
    if (this.reducedMotion()) {
      fn();
      return;
    }
    this.paintTimer = setTimeout(() => {
      this.paintTimer = null;
      fn();
    }, ms);
  }

  private clearPaintTimer(): void {
    if (this.paintTimer !== null) {
      clearTimeout(this.paintTimer);
      this.paintTimer = null;
    }
  }

  private reducedMotion(): boolean {
    return typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }
}

/**
 * Opens the sheet and resolves with the chosen date as a local YYYY-MM-DD string on Done, or null
 * when it was dismissed any other way (Cancel, a drag down, the backdrop). One call per field:
 *
 *   const date = await presentDateSheet(this.modalCtrl, {
 *     title: 'Transfer date', mode: 'upcoming', value: this.transferDate, min: todayYmd(),
 *     businessDaysOnly: true, quickPicks: quickPicks(frequency, todayYmd(), todayYmd(), true),
 *     subline: d => `Trades land ${tradeWindow(d)}`,
 *   });
 *   if (date === null) return;
 */
export async function presentDateSheet(
  modalCtrl: ModalController,
  opts: FredDateSheetOptions,
): Promise<string | null> {
  const modal = await modalCtrl.create({
    component: FredDateSheetComponent,
    componentProps: opts,
    ...BOTTOM_SHEET_MODAL_OPTS,
  });
  await modal.present();
  const { role, data } = await modal.onDidDismiss<{ value?: string }>();
  return role === FRED_DATE_SHEET_ROLE && typeof data?.value === 'string' ? data.value : null;
}
