import { Component, DestroyRef, ElementRef, OnInit, ViewChild, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { IonContent, IonHeader, NavController, ViewWillLeave } from '@ionic/angular/standalone';
import { filter, take } from 'rxjs';
import { PlanAmountTrayComponent, stepLabel } from '../../components/plan-amount-tray/plan-amount-tray.component';
import { tickHaptic } from '../../components/quiz-controls/haptics';
import { AuthService } from '../../services/auth.service';
import { PortfolioService } from '../../services/portfolio.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { RETURN_SCENARIOS, RateAssumptionsService } from '../../services/rate-assumptions.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { monthsToTarget } from '../../utils/freedom-projection.utils';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { PLAN_AMOUNT_SCALES, PlanField } from '../../utils/plan-amount-scale';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { ErrorCategory, ReturnPreset } from '../../services/analytics/analytics-events';
import { toAmountBand, toYearsBand } from '../../services/analytics/analytics-bands';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';

/** The hero roll-up's length (the goal page's hero count-up uses the same). */
const HERO_ROLL_MS = 600;
/** With no birth year on file, the hero roll starts this many years below the date. */
const HERO_ROLL_FALLBACK_YEARS = 30;
/** The house easing: the Save plan CTA fades up and in with it, and down and out. */
const CTA_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
/** Unhurried enough to read as a reveal (Andy, 2026-11-10: 260ms was too quick). */
const CTA_IN_MS = 480;
const CTA_OUT_MS = 360;

/** The slide tray: grows under the tiles, collapses back, cross-fades when the field switches. */
const TRAY_OPEN_MS = 240;
const TRAY_CLOSE_MS = 200;
const TRAY_FADE_OUT_MS = 140;
/** A field switch: the card fades out, the slider is swapped at the dip, and the card fades back (220ms in all). */
const TRAY_SWAP_OUT_MS = 88;
const TRAY_SWAP_IN_MS = 132;
/** The card lifts this far into place as it fades in. */
const TRAY_LIFT_PX = 6;
const DONE_IN_MS = 160;
const DONE_OUT_MS = 120;
/** The tile label's swap between the field name and the step size. */
const TILE_LABEL_SWAP_MS = 160;
/** With the page scrolled, a tile tap brings the hero back into view this quickly. */
const SCROLL_TO_HERO_MS = 220;

/** One end of a Save plan fade: opacity plus the vertical offset in px. */
interface CtaFrame { o: number; y: number; }
const CTA_SHOWN: CtaFrame = { o: 1, y: 0 };
const CTA_HIDDEN: CtaFrame = { o: 0, y: 12 };
const ctaKeyframe = (f: CtaFrame): Keyframe => ({ opacity: f.o, transform: `translateY(${f.y}px)` });

/** A running fade with the frames it is between, so a flip mid-fade can pick up where it is. */
interface CtaFade { anim: Animation; from: CtaFrame; to: CtaFrame; }

/**
 * Where a fade is right now, read off its eased progress (not computed style, which is empty in a
 * document without layout). Null once it has run its course: the caller then starts from rest.
 */
function ctaLiveFrame(fade: CtaFade): CtaFrame | null {
  const p = fade.anim.effect?.getComputedTiming().progress;
  if (p == null) return null;
  return { o: fade.from.o + (fade.to.o - fade.from.o) * p, y: fade.from.y + (fade.to.y - fade.from.y) * p };
}

/**
 * The Freedom Plan page (/freedom): the full story behind the freedom date.
 *
 * Pushed from the my-profile Freedom Date row. It replaced the Freedom detail sheet and keeps its
 * content and behavior; only the container changed. Everything is a live what-if: edits to the
 * plan tiles or the return chips re-derive the hero date instantly; nothing persists until
 * "Save plan", which writes income and investing through updateUserProfile, moves the shared
 * rate, and goes back to my-profile.
 *
 * The tiles are buttons: tapping one grows the slide tray (components/plan-amount-tray) under the
 * tile row, so the page never needs the keyboard. The tray drives incomeSig / investSig exactly as
 * typing did, so dirty, canSave, analytics bands and the hero all keep working.
 *
 * The page seeds itself from the same sources my-profile reads (userProgress$, the investment
 * schedule, the portfolio dashboard), so a reload of /freedom still opens a complete page.
 */
@Component({
  selector: 'app-freedom-plan',
  templateUrl: './freedom-plan.page.html',
  styleUrls: ['./freedom-plan.page.scss'],
  standalone: true,
  imports: [CommonModule, IonContent, IonHeader, PlanAmountTrayComponent],
})
export class FreedomPlanPage implements OnInit, ViewWillLeave {
  private navCtrl = inject(NavController);
  private authService = inject(AuthService);
  private portfolioService = inject(PortfolioService);
  private toastService = inject(ToastService);
  private rates = inject(RateAssumptionsService);
  private freedomStats = inject(FreedomStatsService);
  private tour = inject(FirstTimeTourService);
  private analytics = inject(AnalyticsService);
  private destroyRef = inject(DestroyRef);

  /**
   * The plan the page opened with (monthly dollars, and the return after the snap-to-nearest in
   * ngOnInit): the baseline isDirty, the `*_changed` flags and the "did the amounts move" check
   * compare against. A signal, because the seeds land asynchronously and the Save plan CTA has to
   * react when they do. Snapping a legacy free-form rate is not the user choosing a preset, so the
   * rate is captured AFTER the snap; only selectReturn changes the signal after that, so the
   * comparison is exact.
   */
  private readonly baseline = signal({ income: 0, invest: 0, rate: 0.10 });
  /** The stored annual income, kept exact when the income tile is left alone (no /12 x 12 drift). */
  private exactAnnualIncome: number | null = null;

  // ── Live what-if state ─────────────────────────────────────────────────────
  readonly incomeSig = signal(0);
  readonly investSig = signal(0);
  /** Current portfolio value; a signal so the hero moves when the dashboard lands. */
  readonly equity = signal(0);
  readonly annualReturn = signal(0.10);
  /** Fixed at the industry-standard 4% rule (customization removed 2026-09-12). */
  readonly withdrawalRate = signal(0.04);
  readonly saving = signal(false);

  /** True once the user moved a tile; a late seed then leaves that tile alone. */
  private incomeTouched = false;
  private investTouched = false;

  ngOnInit(): void {
    this.rates.ensureUser();
    // Snap any legacy free-form return (steppers allowed e.g. 9.5%) to the
    // nearest scenario preset: the chips are the only control now.
    const stored = this.rates.annualReturn();
    const nearest = this.RETURN_SCENARIOS.reduce((best, s) =>
      Math.abs(s.rate - stored) < Math.abs(best.rate - stored) ? s : best);
    this.annualReturn.set(nearest.rate);
    this.baseline.update(b => ({ ...b, rate: nearest.rate }));
    // Withdrawal stays 4% regardless of any legacy stored customization.

    this.seedPlan();
    this.portfolioService.getPortfolioDashboard()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.equity.set(data?.summary?.equity || 0),
        error: () => this.equity.set(0),
      });
  }

  /**
   * The tiles start from the saved plan, read the way my-profile reads it: the first real
   * (non-fallback) progress emission, income stored annually and shown monthly, and the
   * schedule's monthly equivalent when no monthly goal is saved.
   */
  private seedPlan(): void {
    this.authService.userProgress$
      .pipe(
        filter((p): p is NonNullable<typeof p> => !!p && (p as any)._source !== 'localStorageFallback'),
        take(1),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(progress => {
        if (progress.retirementIncome) {
          this.exactAnnualIncome = progress.retirementIncome;
          const income = Math.round(progress.retirementIncome / 12);
          this.baseline.update(b => ({ ...b, income }));
          if (!this.incomeTouched) this.incomeSig.set(income);
        }
        if (progress.monthlyInvestment) {
          const invest = progress.monthlyInvestment;
          this.baseline.update(b => ({ ...b, invest }));
          if (!this.investTouched) this.investSig.set(invest);
        } else {
          this.seedInvestFromSchedule();
        }
      });
  }

  private seedInvestFromSchedule(): void {
    this.authService.getCurrentInvestmentSchedule()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (schedule) => {
          if (!schedule || this.baseline().invest > 0) return;
          const frequency = schedule.frequency ? String(schedule.frequency).toLowerCase() : '';
          const monthly = Math.round(InvestmentFrequencyUtils.toMonthlyEquivalent(schedule.investmentAmount, frequency));
          if (monthly <= 0) return;
          this.baseline.update(b => ({ ...b, invest: monthly }));
          if (!this.investTouched) this.investSig.set(monthly);
        },
        error: () => {},
      });
  }

  // ── Navigation ─────────────────────────────────────────────────────────────
  goBack(): void {
    void this.navCtrl.navigateBack('/my-profile');
  }

  /** Tour: leaving this page is the profileFreedom gate (my-profile reports it too; idempotent). */
  ionViewWillLeave(): void {
    this.closeTray(true);
    this.tour.freedomPlanLeft();
  }

  // ── Derived projection ─────────────────────────────────────────────────────
  readonly portfolioGoal = computed(() => {
    const income = this.incomeSig();
    return income > 0 ? (income * 12) / this.withdrawalRate() : 0;
  });

  private readonly months = computed(() => {
    const goal = this.portfolioGoal();
    if (goal <= 0) return null;
    if (this.equity() <= 0 && this.investSig() <= 0) return null;
    return monthsToTarget(this.equity(), this.investSig(), goal, this.annualReturn());
  });

  readonly freedomYear = computed(() => {
    const m = this.months();
    if (m === null) return null;
    const currentYear = new Date().getFullYear();
    return Math.min(currentYear + Math.ceil(m / 12), currentYear + 100);
  });

  readonly freedomAge = computed(() => {
    const year = this.freedomYear();
    const birthYear = this.freedomStats.birthYear();
    if (year === null || !birthYear || birthYear <= 0) return null;
    const age = year - birthYear;
    return age > 0 ? age : null;
  });

  /**
   * Months-granular distance label. The hero year alone hides small
   * adjustments (a chip tap often moves the projection by a few months,
   * which rounds away in the year); this line moves on every change.
   */
  readonly timeAwayLabel = computed(() => {
    const m = this.months();
    if (m === null) return null;
    const total = Math.max(0, Math.round(m));
    if (total === 0) return 'you’re already there';
    const yr = Math.floor(total / 12);
    const mo = total % 12;
    if (yr === 0) return `${mo} mo away`;
    return mo === 0 ? `${yr} yr away` : `${yr} yr ${mo} mo away`;
  });

  readonly progressPct = computed(() => {
    const goal = this.portfolioGoal();
    return goal > 0 ? Math.min(100, Math.round((this.equity() / goal) * 100)) : 0;
  });

  /** Months sooner if the user invested $100/mo more: the nudge insight. */
  readonly plus100Months = computed(() => {
    const m = this.months();
    const goal = this.portfolioGoal();
    if (m === null || goal <= 0) return null;
    const faster = monthsToTarget(this.equity(), this.investSig() + 100, goal, this.annualReturn());
    const delta = Math.round(m - faster);
    return delta > 0 ? delta : null;
  });

  // ── Hero roll-up ───────────────────────────────────────────────────────────
  // The first time a date is there, the year rolls from the user's birth year up to it over 600ms,
  // the age from 0 alongside: the whole life sweeps up to the date, so even a date one year out
  // rolls (Andy, 2026-11-10; from this year it showed one tick). Afterwards the hero tracks the
  // live projection. Skipped under reduced motion. The same rAF shape as the goal page.

  /** The year and age the hero shows mid roll; null once it tracks the live projection. */
  private readonly heroRolling = signal<{ year: number; age: number | null } | null>(null);
  /** The roll runs once per page, on the first date. */
  private heroRolled = false;

  readonly heroYear = computed(() => this.heroRolling()?.year ?? this.freedomYear());
  readonly heroAge = computed(() => {
    const rolling = this.heroRolling();
    return rolling ? rolling.age : this.freedomAge();
  });

  private _rollFx = effect(() => {
    if (this.freedomYear() === null || this.heroRolled) return;
    this.heroRolled = true;
    untracked(() => this.startRollUp());
  });

  private startRollUp(): void {
    if (this.reducedMotion()) return;
    const first = this.freedomYear();
    if (first === null) return;
    // Where the year starts: the birth year, read live each frame (it can land during the roll),
    // else a fixed span below the date. The age starts at 0 whenever there is one.
    const fromYear = (year: number): number => {
      const birthYear = this.freedomStats.birthYear() ?? 0;
      return birthYear > 0 && birthYear < year ? birthYear : year - HERO_ROLL_FALLBACK_YEARS;
    };
    const start = performance.now();
    const step = (t: number) => {
      const year = this.freedomYear();
      const p = Math.min(1, (t - start) / HERO_ROLL_MS);
      // Done, or the projection went away mid-roll: follow the live value again.
      if (year === null || p >= 1) { this.heroRolling.set(null); return; }
      const eased = 1 - (1 - p) ** 3;
      const age = this.freedomAge();
      const from = fromYear(year);
      this.heroRolling.set({
        year: Math.round(from + (year - from) * eased),
        age: age !== null ? Math.round(age * eased) : null,
      });
      requestAnimationFrame(step);
    };
    this.heroRolling.set({ year: fromYear(first), age: this.freedomAge() !== null ? 0 : null });
    requestAnimationFrame(step);
  }

  private reducedMotion(): boolean {
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch {
      return false;
    }
  }

  // Micro-feedback: pulse the hero whenever the projection moves so every chip tap and detent
  // visibly registers. WAAPI (element.animate), not CSS transitions: the global reduced-motion
  // rule zeroes those. While a finger is down on the tray's slider the pulse waits: the detents
  // land several a second, and one pulse on release reads better than a flutter.
  private elementRef = inject(ElementRef<HTMLElement>);
  /** The projection the hero last pulsed for; null before the opening render has set it. */
  private _pulsedFor: string | null = null;
  private _pulseFx = effect(() => {
    const projection = `${this.months()}|${this.portfolioGoal()}`;
    if (this.trayDragging()) return;
    if (this._pulsedFor === null) { this._pulsedFor = projection; return; }
    if (projection === this._pulsedFor) return;
    this._pulsedFor = projection;
    if (this.reducedMotion()) return;
    const hero = this.elementRef.nativeElement.querySelector('.fd-hero-year') as HTMLElement | null;
    hero?.animate?.(
      [
        { transform: 'scale(1)' },
        { transform: 'scale(1.018)' },
        { transform: 'scale(1)' },
      ],
      { duration: 220, easing: 'ease-out' }
    );
  });

  // ── Formatting ─────────────────────────────────────────────────────────────
  pct(fraction: number): string {
    // Two decimals so quarter-point steps (4.25%) display exactly what the
    // math uses; trailing zeros trimmed (10.00 to "10", 4.50 to "4.5").
    return (fraction * 100).toFixed(2).replace(/\.?0+$/, '');
  }

  // ── Slide tray ─────────────────────────────────────────────────────────────
  // The page owns the open state. `trayField` is the field the tray is open on (the lit tile);
  // `trayShown` is the field the tray is rendered for, which lags on close by the collapse so the
  // card has something to collapse. The two agree except during that collapse.

  /** The field the tray is open on; null when closed. */
  readonly trayField = signal<PlanField | null>(null);
  /** The field the tray is rendered for; stays set through the collapse animation. */
  readonly trayShown = signal<PlanField | null>(null);
  /** True while a finger is down on the tray's slider. */
  readonly trayDragging = signal(false);

  readonly trayValue = computed(() => this.trayShown() === 'invest' ? this.investSig() : this.incomeSig());
  readonly trayScale = computed(() => PLAN_AMOUNT_SCALES[this.trayShown() ?? 'income']);

  @ViewChild(IonContent) private content?: IonContent;

  private trayBoxEl: HTMLElement | null = null;
  private trayInEl: HTMLElement | null = null;
  private doneEl: HTMLElement | null = null;
  /** The running collapse, so a re-open mid-collapse can take over from where it is. */
  private trayCollapse: Animation | null = null;
  /** The running field switch's fade-out; the swap lands when it does, unless the tray moved on. */
  private traySwap: Animation | null = null;

  /** The tray's wrapper arrives with the tray: grow it out of nothing the moment it renders. */
  @ViewChild('trayBox') private set trayBoxRef(ref: ElementRef<HTMLElement> | undefined) {
    const el = ref?.nativeElement ?? null;
    if (el === this.trayBoxEl) return;
    this.trayBoxEl = el;
    if (el) this.growTray(el, 0, 0);
  }

  @ViewChild('trayIn') private set trayInRef(ref: ElementRef<HTMLElement> | undefined) {
    this.trayInEl = ref?.nativeElement ?? null;
  }

  @ViewChild('doneBtn') private set doneRef(ref: ElementRef<HTMLElement> | undefined) {
    const el = ref?.nativeElement ?? null;
    if (el === this.doneEl) return;
    this.doneEl = el;
    if (el && !this.reducedMotion()) {
      el.animate?.([{ opacity: 0 }, { opacity: 1 }], { duration: DONE_IN_MS, delay: 100, fill: 'backwards' });
    }
  }

  /**
   * A tile tap: open on that field, switch to it, or close when it is the open one. A switch waits
   * while a finger is down on the slider: the drag would carry on into the other field's scale.
   */
  toggleTray(field: PlanField): void {
    const open = this.trayField();
    if (open === field) { this.closeTray(); return; }
    if (open !== null && this.trayDragging()) return;
    void tickHaptic();
    if (open !== null) { this.switchTray(field); return; }
    this.openTray(field);
  }

  private openTray(field: PlanField): void {
    this.trayField.set(field);
    const collapsing = this.trayCollapse;
    this.trayCollapse = null;
    if (collapsing && this.trayBoxEl && this.trayInEl) {
      // Caught mid-collapse: pick the grow up from the height and opacity the card is at.
      const fromHeight = this.trayBoxEl.getBoundingClientRect().height;
      const fromOpacity = Number(getComputedStyle(this.trayInEl).opacity);
      this.trayBoxEl.getAnimations().forEach(a => a.cancel());
      this.trayInEl.getAnimations().forEach(a => a.cancel());
      this.growTray(this.trayBoxEl, fromHeight, fromOpacity);
      this.restoreDone();
    }
    this.trayShown.set(field);
    this.watchOutside(true);
    this.scrollHeroIntoView();
  }

  /**
   * The tray stays open and cross-fades to the other field; no height change. The tile lights at
   * once; the slider is swapped only when the card has faded out, so the new thumb, fill and end
   * labels never show at full opacity before the dip. A second switch mid-fade starts from the
   * opacity the card is at, and the first one's swap is dropped.
   */
  private switchTray(field: PlanField): void {
    this.trayField.set(field);
    this.scrollHeroIntoView();
    const inner = this.trayInEl;
    if (!inner || this.reducedMotion() || typeof inner.animate !== 'function') {
      this.trayShown.set(field);
      return;
    }
    const from = Number(getComputedStyle(inner).opacity);
    const out = inner.animate([{ opacity: from }, { opacity: 0 }], { duration: TRAY_SWAP_OUT_MS, easing: 'ease-out' });
    out.onfinish = () => {
      if (this.traySwap !== out) return;
      this.traySwap = null;
      this.trayShown.set(field);
      inner.animate([{ opacity: 0 }, { opacity: 1 }], { duration: TRAY_SWAP_IN_MS, easing: 'ease-out' });
    };
    this.traySwap = out;
  }

  /**
   * Done never left the DOM through a collapse (trayShown stayed set), so its fade-out's held last
   * frame is still on it when the tray is re-opened mid-collapse; drop it and fade Done back in
   * from wherever it is.
   */
  private restoreDone(): void {
    const done = this.doneEl;
    if (!done) return;
    const from = Number(getComputedStyle(done).opacity);
    done.getAnimations().forEach(a => a.cancel());
    if (!this.reducedMotion()) done.animate?.([{ opacity: from }, { opacity: 1 }], { duration: DONE_IN_MS });
  }

  /**
   * Done, the open tile again, a tap outside, Save, or leaving the page. The card fades as the
   * wrapper collapses; the tray leaves the DOM when the collapse lands. `instant` skips the motion
   * (leaving the page, reduced motion).
   */
  closeTray(instant = false): void {
    // Already closed, or a collapse is in flight (Done stays tappable through it): a second tap
    // must not restart the motion from the card's half-collapsed height and full opacity.
    if (this.trayField() === null && (this.trayShown() === null || (this.trayCollapse !== null && !instant))) return;
    this.trayField.set(null);
    this.traySwap = null;
    this.trayDragging.set(false);
    this.watchOutside(false);
    const box = this.trayBoxEl;
    const inner = this.trayInEl;
    if (instant || this.reducedMotion() || !box || !inner || typeof box.animate !== 'function') {
      this.trayCollapse = null;
      this.trayShown.set(null);
      return;
    }
    // A grow or swap still running hands over its live height and opacity (Done's too: its fade-in
    // may not have started); the collapse and the fades start from there.
    const fromHeight = box.getBoundingClientRect().height;
    const fromOpacity = Number(getComputedStyle(inner).opacity);
    const done = this.doneEl;
    const doneFrom = done ? Number(getComputedStyle(done).opacity) : 1;
    box.getAnimations().forEach(a => a.cancel());
    inner.getAnimations().forEach(a => a.cancel());
    done?.getAnimations().forEach(a => a.cancel());
    const collapse = box.animate(
      [{ height: `${fromHeight}px` }, { height: '0px' }],
      { duration: TRAY_CLOSE_MS, easing: CTA_EASING, fill: 'forwards' },
    );
    inner.animate([{ opacity: fromOpacity }, { opacity: 0 }], { duration: TRAY_FADE_OUT_MS, fill: 'forwards' });
    done?.animate?.([{ opacity: doneFrom }, { opacity: 0 }], { duration: DONE_OUT_MS, fill: 'forwards' });
    collapse.onfinish = () => {
      if (this.trayCollapse !== collapse) return;
      this.trayCollapse = null;
      this.trayShown.set(null);
      // A finger that went down on the card mid-collapse leaves with it, and never reports its release.
      this.trayDragging.set(false);
    };
    this.trayCollapse = collapse;
  }

  /** Height 0 to the measured height, the card fading in with a small lift, together. */
  private growTray(box: HTMLElement, fromHeight: number, fromOpacity: number): void {
    const inner = this.trayInEl ?? (box.firstElementChild as HTMLElement | null);
    if (this.reducedMotion() || typeof box.animate !== 'function') return;
    const toHeight = box.scrollHeight;
    box.animate(
      [{ height: `${fromHeight}px` }, { height: `${toHeight}px` }],
      { duration: TRAY_OPEN_MS, easing: CTA_EASING },
    );
    inner?.animate(
      [
        { opacity: fromOpacity, transform: `translateY(${-TRAY_LIFT_PX * (1 - fromOpacity)}px)` },
        { opacity: 1, transform: 'translateY(0px)' },
      ],
      { duration: TRAY_OPEN_MS, easing: CTA_EASING },
    );
  }

  /**
   * A tap anywhere but the tiles, the tray and Done closes the tray. The close waits for the
   * release: the collapse moves everything below the tray up, and a return chip that moved out
   * from under the finger mid-tap would lose its click. A cancel (the page scrolled) is not a tap.
   * Capture, so nothing swallows it.
   */
  private outsidePointer: number | null = null;
  private readonly onOutsidePointer = (event: Event): void => {
    const e = event as PointerEvent;
    if (e.type === 'pointerdown') {
      const target = e.target as Element | null;
      if (!target?.closest?.('.fd-plan, .fp-done')) this.outsidePointer = e.pointerId;
      return;
    }
    if (e.pointerId !== this.outsidePointer) return;
    this.outsidePointer = null;
    if (e.type === 'pointerup') this.closeTray();
  };

  private watchOutside(on: boolean): void {
    this.outsidePointer = null;
    for (const type of ['pointerdown', 'pointerup', 'pointercancel']) {
      if (on) document.addEventListener(type, this.onOutsidePointer, true);
      else document.removeEventListener(type, this.onOutsidePointer, true);
    }
  }

  /** With the tray open, Assumptions and Save drop below the fold; the hero must not. */
  private scrollHeroIntoView(): void {
    const content = this.content;
    if (!content) return;
    content.getScrollElement()
      .then(el => (el.scrollTop > 0 ? content.scrollToTop(SCROLL_TO_HERO_MS) : undefined))
      .catch(() => {});
  }

  /**
   * The tray's value lands on the field the card shows (not the lit tile: the card is still live
   * through a collapse, and lags a field switch by the fade-out); the user has now moved that tile.
   */
  onTrayValue(value: number): void {
    const field = this.trayShown();
    if (field === 'income') { this.incomeTouched = true; this.incomeSig.set(value); }
    else if (field === 'invest') { this.investTouched = true; this.investSig.set(value); }
  }

  /** While the finger is down the active tile's label reads the step size; a quick fade sells the swap. */
  onTrayDragging(on: boolean): void {
    this.trayDragging.set(on);
    if (this.reducedMotion()) return;
    const label = this.elementRef.nativeElement.querySelector('.fp-tile.active .fd-tile-label') as HTMLElement | null;
    label?.animate?.([{ opacity: 0 }, { opacity: 1 }], { duration: TILE_LABEL_SWAP_MS, easing: 'ease-out' });
  }

  /** True while the finger is down on this field's slider. */
  showsStep(field: PlanField): boolean {
    return this.trayDragging() && this.trayField() === field;
  }

  tileLabel(field: PlanField): string {
    if (this.showsStep(field)) return stepLabel(PLAN_AMOUNT_SCALES[field]);
    return field === 'income' ? 'Income goal' : 'Investing';
  }

  constructor() {
    this.destroyRef.onDestroy(() => this.watchOutside(false));
  }

  // ── Assumption controls ────────────────────────────────────────────────────

  /**
   * Return-rate presets, mirroring the Monte Carlo scenarios exactly (same
   * labels, same rates) so the freedom date and tab2's simulations always
   * speak the same language. Three choices by design: free-form return
   * customization invited over-tuning. The shared list, so the goal page's
   * chips and these cannot drift apart; each carries its analytics `preset`.
   */
  readonly RETURN_SCENARIOS = RETURN_SCENARIOS;

  /** Peeking at a chip never moves the shared rate (or the Freedom Date elsewhere); Save does. */
  selectReturn(rate: number): void {
    this.annualReturn.set(rate);
  }

  /** Save requires a complete plan: zeroed tiles can't be saved. */
  readonly canSave = computed(() => this.incomeSig() > 0 && this.investSig() > 0);

  /**
   * True once anything editable (the two tiles, the return chip) differs from the saved plan.
   * Before the first seed lands the baseline is 0/0 and so are the tiles, so an untouched page is
   * clean and nothing flashes.
   */
  readonly isDirty = computed(() => {
    const base = this.baseline();
    return this.incomeSig() !== base.income
      || this.investSig() !== base.invest
      || this.annualReturn() !== base.rate;
  });

  // Save plan is there only while the plan is dirty: it fades up and in on the first edit and
  // fades out when the user puts things back. WAAPI like the pulse above, armed on the first run
  // so the opening render only sets the state. The slot stays reserved (visibility, not display)
  // so nothing above it jumps; the template starts hidden so the first paint cannot flash.
  private _ctaArmed = false;
  private _ctaFade: CtaFade | null = null;
  private _ctaFx = effect(() => {
    const dirty = this.isDirty();
    const cta = this.elementRef.nativeElement.querySelector('.fp-cta') as HTMLElement | null;
    if (!cta) return;
    const animate = this._ctaArmed && !this.reducedMotion() && typeof cta.animate === 'function';
    this._ctaArmed = true;
    // A flip mid-fade picks up from the frame the running fade is on, so neither direction pops to
    // full or zero opacity first. cancel never fires onfinish, so a dropped fade-out cannot hide a
    // button that just came back.
    const live = this._ctaFade ? ctaLiveFrame(this._ctaFade) : null;
    this._ctaFade?.anim.cancel();
    this._ctaFade = null;
    cta.setAttribute('aria-hidden', String(!dirty));
    cta.style.pointerEvents = dirty ? '' : 'none';
    if (dirty) {
      cta.removeAttribute('tabindex');
      cta.classList.remove('fp-cta--hidden');
      if (animate) {
        const from = live ?? CTA_HIDDEN;
        const anim = cta.animate([ctaKeyframe(from), ctaKeyframe(CTA_SHOWN)], { duration: CTA_IN_MS, easing: CTA_EASING });
        this._ctaFade = { anim, from, to: CTA_SHOWN };
      }
      return;
    }
    cta.setAttribute('tabindex', '-1');
    if (!animate) { cta.classList.add('fp-cta--hidden'); return; }
    // Fades down and out, the mirror of the way in. fill: 'forwards' holds the last frame until the
    // hidden class is on (no one-frame flash back to opaque); the cancel then drops the held fill.
    const from = live ?? CTA_SHOWN;
    const out = cta.animate([ctaKeyframe(from), ctaKeyframe(CTA_HIDDEN)], { duration: CTA_OUT_MS, easing: CTA_EASING, fill: 'forwards' });
    out.onfinish = () => { cta.classList.add('fp-cta--hidden'); out.cancel(); this._ctaFade = null; };
    this._ctaFade = { anim: out, from, to: CTA_HIDDEN };
  });

  // ── Save ───────────────────────────────────────────────────────────────────

  /**
   * The plan's one save point. The rates apply at once (a rates-only save writes nothing to the
   * server); moved amounts are written through updateUserProfile, and the page goes back to
   * my-profile when the write lands. A failed write keeps the user here with Save still there.
   */
  save(): void {
    if (!this.canSave() || this.saving()) return;
    this.closeTray(true);
    const income = this.incomeSig();
    const invest = this.investSig();
    const base = this.baseline();
    this.trackPlanChanged();
    this.rates.setRates(this.annualReturn(), this.withdrawalRate());

    if (income === base.income && invest === base.invest) {
      // Only the assumptions moved: no server write needed.
      this.toastService.showToast('Plan updated', 'success');
      this.goBack();
      return;
    }

    // Preserve the exact stored annual income while the rounded monthly tile is untouched.
    const retirementIncome = income === base.income && this.exactAnnualIncome !== null
      ? this.exactAnnualIncome
      : income * 12;
    this.saving.set(true);
    // Tied to the page: a back tap mid-save must not later run goBack from another page.
    this.authService.updateUserProfile({ monthlyInvestment: invest, retirementIncome })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.toastService.showToast('Profile updated!', 'success');
          this.authService.loadUserProgress();
          this.goBack();
        },
        error: (error) => {
          this.saving.set(false);
          this.toastService.showToast('Failed to save changes.', 'danger');
          // Split by analytics-error.utils' rule rather than by message: a request that never
          // reached a server is a network failure, anything with a status is a failed save.
          const category: ErrorCategory = isNetworkFailure(error) ? 'network' : 'save_failed';
          this.analytics.trackErrorShown(category, 'freedom_sheet', 'toast', httpStatusOf(error), true);
        },
      });
  }

  /**
   * Fires at the save point and nowhere else: the tray fires on every detent and the scenario
   * chips on every tap, so anything earlier would emit per step.
   *
   * Amounts are banded, never raw. `*_changed` compares against the plan the page was opened
   * with, which is what the user actually moved in this session. `preset_changed` is what makes
   * the rates-only save legible: without it, a save that moved only the chips looks identical to
   * a save that moved nothing, since both carry the same bands. The surface value stays
   * 'freedom_sheet', the declared name for this plan surface.
   */
  private trackPlanChanged(): void {
    const income = this.incomeSig();
    const invest = this.investSig();
    const base = this.baseline();
    this.analytics.track({
      name: 'freedom_plan_changed',
      props: {
        surface: 'freedom_sheet',
        income_changed: income !== base.income,
        invest_changed: invest !== base.invest,
        preset_changed: this.annualReturn() !== base.rate,
        monthly_income_band: income > 0 ? toAmountBand(income) : null,
        monthly_invest_band: invest > 0 ? toAmountBand(invest) : null,
        return_preset: this.returnPreset(),
        years_to_freedom: toYearsBand(this.yearsToFreedom()),
      },
    });
  }

  /** The chosen return as the taxonomy names it. Null if the rate is not one of the three chips. */
  private returnPreset(): ReturnPreset | null {
    return this.RETURN_SCENARIOS.find((s) => s.rate === this.annualReturn())?.preset ?? null;
  }

  /** Years until the projected freedom year; null when there is no reachable date. */
  private yearsToFreedom(): number | null {
    const year = this.freedomYear();
    return year === null ? null : year - new Date().getFullYear();
  }
}
