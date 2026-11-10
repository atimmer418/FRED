/**
 * freedom-plan.page.spec.ts
 *
 * The Freedom page owns the editable plan tiles (ported from the Freedom detail sheet's spec when
 * the sheet became a pushed page). These specs carry the input-driven live-math coverage: the hero
 * date must move on every keystroke and every assumption step. Math fixture matches the my-profile
 * spec header: income $5,000/mo is a $1.5M target; $2,500/mo from $0 is 18 years at 10%.
 *
 * The page seeds itself from the same sources my-profile reads (userProgress$, the schedule, the
 * portfolio dashboard), and its Save writes the plan itself, then navigates back to my-profile.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { NavController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
import { FreedomPlanPage } from './freedom-plan.page';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { AuthService } from '../../services/auth.service';
import { PortfolioService } from '../../services/portfolio.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AnalyticsAdapter } from '../../services/analytics/analytics-adapter';

const CURRENT_YEAR = new Date().getFullYear();

interface SetupOptions {
  /** Investment schedule the fallback seed reads when no monthly goal is saved. */
  schedule?: unknown;
  /** What updateUserProfile returns (throwError for the failure paths). */
  saveResult?: Observable<unknown>;
  /** Replaces the userProgress$ emission built from income/invest. */
  progress?: Record<string, unknown>;
  /** Leave the hero roll-up's frames queued instead of running them in setup. */
  keepRolling?: boolean;
  /** The OS reduce-motion setting is on. */
  reducedMotion?: boolean;
  /** The user's birth year the stats service reports (0 = unknown). */
  birthYear?: number;
}

describe('FreedomPlanPage', () => {
  let fixture: ComponentFixture<FreedomPlanPage>;
  let trackSpy: jasmine.Spy;
  let updateUserProfile: jasmine.Spy;
  let loadUserProgress: jasmine.Spy;
  let navigateBack: jasmine.Spy;
  let showToast: jasmine.Spy;
  let freedomPlanLeft: jasmine.Spy;
  let equity$: BehaviorSubject<{ summary: { equity: number } }>;
  /** rAF callbacks the hero roll-up queued; finishRollUp() runs them to the end. */
  let rafCallbacks: FrameRequestCallback[];

  function setup(income = 5000, invest = 2500, equity = 0, storedReturn?: number, opts: SetupOptions = {}): void {
    rafCallbacks = [];
    spyOn(window, 'requestAnimationFrame').and.callFake((cb: FrameRequestCallback) => {
      rafCallbacks.push(cb);
      return rafCallbacks.length;
    });
    if (opts.reducedMotion) {
      spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);
    }
    // The real service with a spied track(): the payload assertions then cover the exact event
    // object the service is handed, with no vendor and no second implementation of the helpers.
    const analytics = new AnalyticsService({} as unknown as AnalyticsAdapter);
    trackSpy = spyOn(analytics, 'track');
    updateUserProfile = jasmine.createSpy('updateUserProfile').and.returnValue(opts.saveResult ?? of({}));
    loadUserProgress = jasmine.createSpy('loadUserProgress');
    navigateBack = jasmine.createSpy('navigateBack').and.returnValue(Promise.resolve(true));
    showToast = jasmine.createSpy('showToast');
    freedomPlanLeft = jasmine.createSpy('freedomPlanLeft');
    equity$ = new BehaviorSubject({ summary: { equity } });

    TestBed.configureTestingModule({
      imports: [FreedomPlanPage],
      providers: [
        { provide: AuthService, useValue: {
            // retirementIncome is stored annually; the tile shows monthly.
            userProgress$: new BehaviorSubject<any>(opts.progress ?? {
              monthlyInvestment: invest || undefined,
              retirementIncome: income * 12,
              _source: 'db',
            }),
            getCurrentInvestmentSchedule: () => of(opts.schedule ?? null),
            updateUserProfile,
            loadUserProgress,
        } },
        { provide: PortfolioService, useValue: { getPortfolioDashboard: () => equity$.asObservable() } },
        { provide: NavController, useValue: { navigateBack } },
        { provide: ToastService, useValue: { showToast } },
        { provide: FreedomStatsService, useValue: { birthYear: () => opts.birthYear ?? 0 } },
        { provide: FirstTimeTourService, useValue: { freedomPlanLeft } },
        { provide: AnalyticsService, useValue: analytics },
        RateAssumptionsService,
      ],
    });
    // The keyboard directive is native-only and its Capacitor listeners reject on web; those
    // unhandled rejections are the suite's known DISCONNECT noise, so it stays out of these specs.
    TestBed.overrideComponent(FreedomPlanPage, { remove: { imports: [KeyboardAvoidDirective] } });
    if (storedReturn !== undefined) {
      // A legacy free-form rate, set before the page reads it on init.
      TestBed.inject(RateAssumptionsService).annualReturn.set(storedReturn);
    }
    fixture = TestBed.createComponent(FreedomPlanPage);
    fixture.detectChanges();
    if (!opts.keepRolling) finishRollUp();
  }

  /** Runs the queued roll-up frames to the end, so the hero shows the live projection. */
  function finishRollUp(): void {
    const far = performance.now() + 10_000;
    while (rafCallbacks.length) rafCallbacks.shift()!(far);
    fixture.detectChanges();
  }

  function heroYear(): string {
    return (fixture.nativeElement.querySelector('.fd-hero-year')?.textContent ?? '').trim();
  }

  function typeInto(selector: string, value: string): void {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  const INCOME_TILE = '.fd-tiles .fd-tile:nth-child(1) .fd-tile-input';
  const INVEST_TILE = '.fd-tiles .fd-tile:nth-child(2) .fd-tile-input';

  afterEach(() => localStorage.removeItem('userId'));

  it('renders the hero freedom year from the seeded plan', () => {
    setup();
    expect(heroYear()).toBe(String(CURRENT_YEAR + 18));
  });

  it('rolls the hero from the birth year up to the freedom date on first render, the age from 0 alongside, then tracks the live date', () => {
    // One clock for the roll's start and the frame below, so a slow run cannot land past the end.
    const clock = spyOn(performance, 'now').and.returnValue(1_000);
    const birthYear = CURRENT_YEAR - 30;
    setup(5000, 2500, 0, undefined, { keepRolling: true, birthYear });
    const page = fixture.componentInstance;
    const target = page.freedomYear()!;
    expect(target).toBe(CURRENT_YEAR + 18);
    // First frame: born, age 0 (shown, not hidden as a falsy value).
    expect(heroYear()).toBe(String(birthYear));
    expect(page.heroAge()).toBe(0);
    expect(fixture.nativeElement.querySelector('.fd-hero-meta')?.textContent).toContain('Age 0');

    // Mid-roll (300 of 600ms): strictly between the two, year and age moving together.
    rafCallbacks.splice(0).forEach(cb => cb(1_300));
    fixture.detectChanges();
    const mid = Number(heroYear());
    expect(mid).toBeGreaterThan(birthYear);
    expect(mid).toBeLessThan(target);
    expect(page.heroAge()!).toBeGreaterThan(0);
    expect(page.heroAge()!).toBeLessThan(48);
    // Year and age keep step: the year shown is the birth year plus the age shown.
    expect(mid - birthYear).toBe(page.heroAge()!);

    clock.and.callThrough();
    finishRollUp();
    expect(heroYear()).toBe(String(target));
    expect(page.heroAge()).toBe(48);
    expect(fixture.nativeElement.querySelector('.fd-hero-meta')?.textContent).toContain('Age 48');

    // Rolled once: a later edit moves the hero at once, and running any queued frames changes nothing.
    typeInto(INVEST_TILE, '5000');
    expect(heroYear()).toBe(String(page.freedomYear()));
    expect(Number(heroYear())).toBeLessThan(target);
    const settled = heroYear();
    finishRollUp();
    expect(heroYear()).toBe(settled);
  });

  it('skips the roll-up under reduced motion: the hero shows the date at once', () => {
    setup(5000, 2500, 0, undefined, { keepRolling: true, reducedMotion: true });
    expect(heroYear()).toBe(String(CURRENT_YEAR + 18));
  });

  it('rolls when the date first arrives later, not before: an empty plan shows the dash until the tiles are filled', () => {
    setup(0, 0, 0, undefined, { keepRolling: true });
    expect(heroYear()).toBe('—');
    typeInto(INCOME_TILE, '5000');
    typeInto(INVEST_TILE, '2500');
    // No birth year on file: the roll starts 30 years below the date the moment a date exists,
    // and there is no age line to roll.
    expect(heroYear()).toBe(String(CURRENT_YEAR + 18 - 30));
    expect(fixture.componentInstance.heroAge()).toBeNull();
    expect(fixture.nativeElement.querySelector('.fd-hero-meta')?.textContent).not.toContain('Age');
    finishRollUp();
    expect(heroYear()).toBe(String(CURRENT_YEAR + 18));
  });

  it('rolls a date one year out all the same: from the birth year, not from this year', () => {
    // A near date (big invest against a small income) used to roll one tick from this year.
    const birthYear = CURRENT_YEAR - 40;
    setup(1000, 20000, 0, undefined, { keepRolling: true, birthYear });
    const page = fixture.componentInstance;
    const target = page.freedomYear()!;
    expect(target).toBeGreaterThan(CURRENT_YEAR);
    expect(target).toBeLessThanOrEqual(CURRENT_YEAR + 3);
    expect(heroYear()).toBe(String(birthYear));
    expect(page.heroAge()).toBe(0);
    finishRollUp();
    expect(heroYear()).toBe(String(target));
    expect(page.heroAge()).toBe(target - birthYear);
  });

  it('renders as a page: the Freedom Plan header and a back button, no sheet handle or Cancel', () => {
    setup();
    expect(fixture.nativeElement.querySelector('.header-title')?.textContent?.trim()).toBe('Freedom Plan');
    expect(fixture.nativeElement.querySelector('.fd-handle')).toBeNull();
    expect(fixture.nativeElement.querySelector('.fd-cancel')).toBeNull();

    (fixture.nativeElement.querySelector('.back-btn') as HTMLButtonElement).click();
    expect(navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('moves the hero date live as the invest tile is edited', () => {
    setup();
    const before = heroYear();
    typeInto(INVEST_TILE, '5000');
    const after = heroYear();
    expect(Number(after)).toBeLessThan(Number(before));
  });

  it('moves the hero when the portfolio value arrives after the page opened', () => {
    setup();
    const before = heroYear();
    equity$.next({ summary: { equity: 500_000 } });
    fixture.detectChanges();
    expect(Number(heroYear())).toBeLessThan(Number(before));
  });

  it('seeds Investing from the schedule equivalent when no monthly goal is saved', () => {
    setup(5000, 0, 0, undefined, { schedule: { investmentAmount: 100, frequency: 'WEEKLY' } });
    expect(fixture.componentInstance.investSig()).toBe(433);  // $100 weekly x 4.33
  });

  it('holds the withdrawal rate at the fixed 4% rule (customization removed)', () => {
    setup();
    expect(fixture.componentInstance.withdrawalRate()).toBe(0.04);
    expect(fixture.componentInstance.portfolioGoal()).toBe(1_500_000);
    expect((fixture.componentInstance as any).bumpWithdrawal).toBeUndefined();
    expect(fixture.nativeElement.querySelector('.fd-note')?.textContent)
      .toContain('Withdrawals follow the industry-standard 4% rule.');
  });

  it('registers a single rate step in the months-granular hero label', () => {
    setup();
    const before = fixture.componentInstance.timeAwayLabel();
    fixture.componentInstance.selectReturn(0.12);  // Moderate to Optimistic: moves months, maybe not the year
    fixture.detectChanges();
    expect(fixture.componentInstance.timeAwayLabel()).not.toBe(before);
    expect(fixture.nativeElement.querySelector('.fd-hero-meta').textContent).toContain('away');
  });

  it('shows the +$100/mo insight and a valid months delta', () => {
    setup();
    expect(fixture.componentInstance.plus100Months()).toBeGreaterThan(0);
    const text = fixture.nativeElement.querySelector('.fd-insight')?.textContent ?? '';
    expect(text).toContain('+$100/mo');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Save: the page writes the plan itself, then goes back to my-profile.
  // ───────────────────────────────────────────────────────────────────────────

  it('save writes income and investing through updateUserProfile and navigates back', () => {
    setup();
    typeInto(INCOME_TILE, '6000');
    typeInto(INVEST_TILE, '3000');
    fixture.componentInstance.selectReturn(0.08);        // Moderate to Conservative
    fixture.componentInstance.save();

    expect(updateUserProfile).toHaveBeenCalledOnceWith({ monthlyInvestment: 3000, retirementIncome: 72000 });
    expect(loadUserProgress).toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('Profile updated!', 'success');
    expect(navigateBack).toHaveBeenCalledWith('/my-profile');
    // The shared rate moves on Save only.
    expect(TestBed.inject(RateAssumptionsService).annualReturn()).toBe(0.08);
  });

  it('keeps the exact stored annual income when the income tile was not touched', () => {
    // An annual figure that does not divide evenly by 12: the tile shows the rounded monthly.
    setup(5000, 2500, 0, undefined, { progress: { monthlyInvestment: 2500, retirementIncome: 60_005, _source: 'db' } });
    expect(fixture.componentInstance.incomeSig()).toBe(5000);
    typeInto(INVEST_TILE, '3000');
    fixture.componentInstance.save();
    expect(updateUserProfile).toHaveBeenCalledOnceWith({ monthlyInvestment: 3000, retirementIncome: 60_005 });
  });

  it('peeking at a preset never moves the shared rate; only Save does', () => {
    setup();
    const rates = TestBed.inject(RateAssumptionsService);
    fixture.componentInstance.selectReturn(0.12);
    expect(rates.annualReturn()).toBe(0.10);
    fixture.componentInstance.save();
    expect(rates.annualReturn()).toBe(0.12);
  });

  it('a rates-only save writes nothing to the server and still goes back', () => {
    setup();
    fixture.componentInstance.selectReturn(0.12);
    fixture.componentInstance.save();
    expect(updateUserProfile).not.toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('Plan updated', 'success');
    expect(navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('a failed save stays on the page and reports error_shown with a retry', () => {
    setup(5000, 2500, 0, undefined, {
      saveResult: throwError(() => new HttpErrorResponse({ status: 500 })),
    });
    typeInto(INVEST_TILE, '3000');
    fixture.componentInstance.save();

    expect(navigateBack).not.toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('Failed to save changes.', 'danger');
    expect(trackSpy).toHaveBeenCalledWith({
      name: 'error_shown',
      props: {
        category: 'save_failed',
        surface: 'freedom_sheet',
        display: 'toast',
        http_status: 500,
        retry_available: true,
      },
    });
    expect(fixture.componentInstance.saving()).toBeFalse();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Tour handoff
  // ───────────────────────────────────────────────────────────────────────────

  it('leaving the page sets the tour handoff so my-profile completes profileFreedom on enter', () => {
    setup();
    expect(freedomPlanLeft).not.toHaveBeenCalled();
    fixture.componentInstance.ionViewWillLeave();
    expect(freedomPlanLeft).toHaveBeenCalledTimes(1);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Analytics: freedom_plan_changed fires at the save point and nowhere else.
  // ───────────────────────────────────────────────────────────────────────────

  /** The page's real CTA: the save point the event is pinned to. */
  function tapSavePlan(): void {
    (fixture.nativeElement.querySelector('.fd-cta') as HTMLButtonElement).click();
  }

  it('fires freedom_plan_changed once, with banded amounts, when Save plan is tapped', () => {
    setup();                                              // seeded $5,000 / $2,500
    typeInto(INVEST_TILE, '3000');
    fixture.componentInstance.selectReturn(0.08);         // Moderate to Conservative
    fixture.detectChanges();

    tapSavePlan();

    // $3,000/mo from $0 to a $1.5M target at 8% = 221 months, so 19 years.
    expect(trackSpy).toHaveBeenCalledTimes(1);
    expect(trackSpy).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'freedom_sheet',
        income_changed: false,
        invest_changed: true,
        preset_changed: true,
        monthly_income_band: '5k_9999',
        monthly_invest_band: '2500_4999',
        return_preset: 'conservative',
        years_to_freedom: '11_20',
      },
    });
  });

  it('sends nothing while the tiles are being typed into', () => {
    setup();
    typeInto(INCOME_TILE, '6');
    typeInto(INCOME_TILE, '60');
    typeInto(INCOME_TILE, '6000');
    fixture.componentInstance.selectReturn(0.12);

    expect(trackSpy).not.toHaveBeenCalled();
  });

  it('covers the rates-only save, where no amount moved and nothing is written to the server', () => {
    setup();
    fixture.componentInstance.selectReturn(0.12);         // only the preset moved
    fixture.detectChanges();

    tapSavePlan();

    // $2,500/mo from $0 to a $1.5M target at 12% = 196 months, so 17 years.
    expect(trackSpy).toHaveBeenCalledTimes(1);
    expect(trackSpy).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'freedom_sheet',
        income_changed: false,
        invest_changed: false,
        preset_changed: true,
        monthly_income_band: '5k_9999',
        monthly_invest_band: '2500_4999',
        return_preset: 'optimistic',
        years_to_freedom: '11_20',
      },
    });
  });

  it('tells a rates-only save apart from a save that moved nothing', () => {
    // Both carry identical bands, so without preset_changed these two are indistinguishable.
    setup();

    tapSavePlan();

    expect(trackSpy).toHaveBeenCalledTimes(1);
    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.income_changed).toBeFalse();
    expect(props.invest_changed).toBeFalse();
    expect(props.preset_changed).toBeFalse();
    expect(props.return_preset).toBe('moderate');
  });

  it('does not count the snap-to-nearest of a legacy free-form rate as a user change', () => {
    // A stored 9.5% snaps to the Moderate chip on open. The user did not touch anything.
    setup(5000, 2500, 0, 0.095);

    tapSavePlan();

    expect(trackSpy.calls.mostRecent().args[0].props.preset_changed).toBeFalse();
  });

  it('reads a non-default stored preset as unchanged when the user leaves it alone', () => {
    // Pins preset_changed against the rate the page OPENED with, not against the 0.10 default:
    // comparing to the default would call this a change and be wrong for every Conservative user.
    setup(5000, 2500, 0, 0.08);

    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.preset_changed).toBeFalse();
    expect(props.return_preset).toBe('conservative');
  });

  it('counts the reset-to-default chip as a preset change', () => {
    setup(5000, 2500, 0, 0.08);

    fixture.componentInstance.resetRates();                // the reset only renders off-default
    fixture.detectChanges();
    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.preset_changed).toBeTrue();
    expect(props.return_preset).toBe('moderate');
  });

  it('reports both amounts as changed when both tiles were edited', () => {
    setup();
    typeInto(INCOME_TILE, '12000');
    typeInto(INVEST_TILE, '400');
    fixture.detectChanges();

    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.income_changed).toBeTrue();
    expect(props.invest_changed).toBeTrue();
    expect(props.monthly_income_band).toBe('10k_49k');
    expect(props.monthly_invest_band).toBe('250_499');
    expect(props.return_preset).toBe('moderate');
  });

  it('sends nothing when an incomplete plan cannot be saved', () => {
    setup(0, 2500);                                       // no income goal, so canSave is false
    fixture.componentInstance.save();

    expect(trackSpy).not.toHaveBeenCalled();
    expect(updateUserProfile).not.toHaveBeenCalled();
    expect(navigateBack).not.toHaveBeenCalled();
  });

  it('reset restores standard rates and hides the reset affordance', () => {
    setup();
    fixture.componentInstance.selectReturn(0.12);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fd-reset')).toBeTruthy();
    fixture.componentInstance.resetRates();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fd-reset')).toBeFalsy();
    expect(fixture.componentInstance.isDefaultRates()).toBeTrue();
  });
});
