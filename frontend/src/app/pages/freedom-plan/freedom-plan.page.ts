import { Component, DestroyRef, ElementRef, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { IonContent, IonHeader, NavController, ViewWillLeave } from '@ionic/angular/standalone';
import { filter, take } from 'rxjs';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { AuthService } from '../../services/auth.service';
import { PortfolioService } from '../../services/portfolio.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { RETURN_SCENARIOS, RateAssumptionsService } from '../../services/rate-assumptions.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { monthsToTarget } from '../../utils/freedom-projection.utils';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { ErrorCategory, ReturnPreset } from '../../services/analytics/analytics-events';
import { toAmountBand, toYearsBand } from '../../services/analytics/analytics-bands';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';

/** The hero roll-up's length (the goal page's hero count-up uses the same). */
const HERO_ROLL_MS = 600;
/** With no birth year on file, the hero roll starts this many years below the date. */
const HERO_ROLL_FALLBACK_YEARS = 30;

/**
 * The Freedom Plan page (/freedom): the full story behind the freedom date.
 *
 * Pushed from the my-profile Freedom Date row. It replaced the Freedom detail sheet and keeps its
 * content and behavior; only the container changed. Everything is a live what-if: edits to the
 * plan tiles or the return chips re-derive the hero date instantly; nothing persists until
 * "Save plan", which writes income and investing through updateUserProfile, moves the shared
 * rate, and goes back to my-profile.
 *
 * The page seeds itself from the same sources my-profile reads (userProgress$, the investment
 * schedule, the portfolio dashboard), so a reload of /freedom still opens a complete page.
 */
@Component({
  selector: 'app-freedom-plan',
  templateUrl: './freedom-plan.page.html',
  styleUrls: ['./freedom-plan.page.scss'],
  standalone: true,
  imports: [CommonModule, IonContent, IonHeader, KeyboardAvoidDirective],
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
   * The plan the page opened with (monthly dollars): the baseline the `*_changed` flags and the
   * "did the amounts move" check compare against. Set by the seed below.
   */
  income = 0;
  invest = 0;
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

  /** True once the user typed into a tile; a late seed then leaves that tile alone. */
  private incomeTouched = false;
  private investTouched = false;

  /**
   * The return the page opened with, captured AFTER the snap-to-nearest below so that snapping a
   * legacy free-form rate is not read as the user choosing a preset. Only selectReturn/resetRates
   * change the signal after that, so the comparison is exact.
   */
  private openedReturn = 0.10;

  ngOnInit(): void {
    this.rates.ensureUser();
    // Snap any legacy free-form return (steppers allowed e.g. 9.5%) to the
    // nearest scenario preset: the chips are the only control now.
    const stored = this.rates.annualReturn();
    const nearest = this.RETURN_SCENARIOS.reduce((best, s) =>
      Math.abs(s.rate - stored) < Math.abs(best.rate - stored) ? s : best);
    this.annualReturn.set(nearest.rate);
    this.openedReturn = nearest.rate;
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
          this.income = Math.round(progress.retirementIncome / 12);
          if (!this.incomeTouched) this.incomeSig.set(this.income);
        }
        if (progress.monthlyInvestment) {
          this.invest = progress.monthlyInvestment;
          if (!this.investTouched) this.investSig.set(this.invest);
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
          if (!schedule || this.invest > 0) return;
          const frequency = schedule.frequency ? String(schedule.frequency).toLowerCase() : '';
          const monthly = Math.round(InvestmentFrequencyUtils.toMonthlyEquivalent(schedule.investmentAmount, frequency));
          if (monthly <= 0) return;
          this.invest = monthly;
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

  readonly isDefaultRates = computed(() =>
    this.annualReturn() === 0.10 && this.withdrawalRate() === 0.04);

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

  // Micro-feedback: pulse the hero whenever the projection moves so every
  // chip tap and keystroke visibly registers. WAAPI (element.animate), not
  // CSS transitions: the global reduced-motion rule zeroes those.
  private elementRef = inject(ElementRef<HTMLElement>);
  private _pulseArmed = false;
  private _pulseFx = effect(() => {
    this.months();
    this.portfolioGoal();
    if (!this._pulseArmed) { this._pulseArmed = true; return; }
    if (typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches) return;
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

  // ── Tile inputs (same sanitizing discipline as the my-profile tiles) ──────
  onIncomeInput(event: Event): void {
    this.incomeTouched = true;
    this.incomeSig.set(this._sanitize(event));
  }

  onInvestInput(event: Event): void {
    this.investTouched = true;
    this.investSig.set(this._sanitize(event));
  }

  /** Digits only, capped at 5 digits, input element rewritten in place. */
  private _sanitize(event: Event): number {
    const input = event.target as HTMLInputElement;
    const digits = (input.value || '').replace(/\D+/g, '').slice(0, 5);
    if (input.value !== digits) input.value = digits;
    return digits ? parseInt(digits, 10) : 0;
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

  resetRates(): void {
    this.annualReturn.set(0.10);
  }

  /** Save requires a complete plan: zeroed tiles can't be saved. */
  readonly canSave = computed(() => this.incomeSig() > 0 && this.investSig() > 0);

  // ── Save ───────────────────────────────────────────────────────────────────

  /**
   * The plan's one save point. The rates apply at once (a rates-only save writes nothing to the
   * server); moved amounts are written through updateUserProfile, and the page goes back to
   * my-profile when the write lands. A failed write keeps the user here with Save still there.
   */
  save(): void {
    if (!this.canSave() || this.saving()) return;
    const income = this.incomeSig();
    const invest = this.investSig();
    this.trackPlanChanged();
    this.rates.setRates(this.annualReturn(), this.withdrawalRate());

    if (income === this.income && invest === this.invest) {
      // Only the assumptions moved: no server write needed.
      this.toastService.showToast('Plan updated', 'success');
      this.goBack();
      return;
    }

    // Preserve the exact stored annual income while the rounded monthly tile is untouched.
    const retirementIncome = income === this.income && this.exactAnnualIncome !== null
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
   * Fires at the save point and nowhere else: the tiles fire on every keystroke and the scenario
   * chips on every tap, so anything earlier would emit per character.
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
    this.analytics.track({
      name: 'freedom_plan_changed',
      props: {
        surface: 'freedom_sheet',
        income_changed: income !== (this.income || 0),
        invest_changed: invest !== (this.invest || 0),
        preset_changed: this.annualReturn() !== this.openedReturn,
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
