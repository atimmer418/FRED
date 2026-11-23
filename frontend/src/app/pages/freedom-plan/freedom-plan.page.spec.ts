/**
 * freedom-plan.page.spec.ts
 *
 * The Freedom page owns the editable plan tiles (ported from the Freedom detail sheet's spec when
 * the sheet became a pushed page). These specs carry the tray-driven live-math coverage: the hero
 * date must move on every detent and every assumption step. Math fixture matches the my-profile
 * spec header: income $5,000/mo is a $1.5M target; $2,500/mo from $0 is 18 years at 10%.
 *
 * The tiles are buttons that open the slide tray (components/plan-amount-tray); `slideTo` below
 * drives a tile the way the tray does, one emitted value at a time.
 *
 * The page seeds itself from the same sources my-profile reads (userProgress$, the schedule, the
 * portfolio dashboard), and its Save writes the plan itself, then navigates back to my-profile.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { By } from '@angular/platform-browser';
import { NavController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
import { FreedomPlanPage } from './freedom-plan.page';
import { PlanAmountTrayComponent } from '../../components/plan-amount-tray/plan-amount-tray.component';
import { PlanField } from '../../utils/plan-amount-scale';
import { AuthService } from '../../services/auth.service';
import { PortfolioService } from '../../services/portfolio.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AnalyticsAdapter } from '../../services/analytics/analytics-adapter';

const CURRENT_YEAR = new Date().getFullYear();
const HOUSE_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';

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

  function tile(field: PlanField): HTMLButtonElement {
    const nth = field === 'income' ? 1 : 2;
    return fixture.nativeElement.querySelector(`.fd-tiles .fd-tile:nth-child(${nth})`) as HTMLButtonElement;
  }

  function tileValue(field: PlanField): string {
    return (tile(field).querySelector('.fd-tile-value')?.textContent ?? '').trim();
  }

  function tileLabel(field: PlanField): HTMLElement {
    return tile(field).querySelector('.fd-tile-label') as HTMLElement;
  }

  function tray(): PlanAmountTrayComponent | null {
    return fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance ?? null;
  }

  function doneBtn(): HTMLButtonElement | null {
    return fixture.nativeElement.querySelector('.fp-done');
  }

  /**
   * Opens a tile's tray if it is not the open one (a tap on the open tile would close it). A tray
   * open on the other tile is closed first: a switch swaps the slider only once the card has faded
   * out, and these are synchronous specs.
   */
  function openTile(field: PlanField): void {
    const page = fixture.componentInstance;
    if (page.trayField() === field) return;
    if (page.trayField() !== null) page.closeTray(true);
    tile(field).click();
    fixture.detectChanges();
  }

  /** Lets an animation land and its onfinish run. */
  async function finish(anim: Animation): Promise<void> {
    const landed = new Promise<void>(resolve => anim.addEventListener('finish', () => resolve()));
    anim.finish();
    await landed;
    fixture.detectChanges();
  }

  /** The field switch's fade-out: the slider is swapped when it lands. */
  async function settleSwap(): Promise<void> {
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    for (const anim of inner.getAnimations()) await finish(anim);
  }

  /** Drives a tile the way the tray does: open it, then one emitted value. */
  function slideTo(field: PlanField, value: number): void {
    openTile(field);
    tray()!.valueChange.emit(value);
    fixture.detectChanges();
  }

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
    slideTo('invest', 5000);
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
    slideTo('income', 5000);
    slideTo('invest', 2500);
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

  it('moves the hero date live as the invest tile is slid', () => {
    setup();
    const before = heroYear();
    slideTo('invest', 5000);
    const after = heroYear();
    expect(Number(after)).toBeLessThan(Number(before));
    expect(tileValue('invest')).toBe('5,000');
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
    slideTo('income', 6000);
    slideTo('invest', 3000);
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
    slideTo('invest', 3000);
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
    slideTo('invest', 3000);
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
    slideTo('invest', 3000);
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

  it('sends nothing while a tile is being slid', () => {
    setup();
    slideTo('income', 6000);
    slideTo('income', 6250);
    slideTo('income', 6500);
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

  it('counts a chip tap back to the standard rate as a preset change when the page opened off it', () => {
    setup(5000, 2500, 0, 0.08);

    fixture.componentInstance.selectReturn(0.10);          // Conservative back to Moderate
    fixture.detectChanges();
    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.preset_changed).toBeTrue();
    expect(props.return_preset).toBe('moderate');
  });

  it('reports both amounts as changed when both tiles were moved', () => {
    setup();
    slideTo('income', 12000);
    slideTo('invest', 400);
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

  it('has no reset-to-standard affordance, on or off the default rate', () => {
    setup();
    expect(fixture.nativeElement.querySelector('.fd-reset')).toBeNull();
    fixture.componentInstance.selectReturn(0.12);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fd-reset')).toBeNull();
    expect((fixture.componentInstance as any).resetRates).toBeUndefined();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Save plan shows only while the plan differs from what is saved.
  // ───────────────────────────────────────────────────────────────────────────

  function cta(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('.fp-cta') as HTMLButtonElement;
  }

  /** Hidden = out of the accessibility tree and untappable; the fade-out may still be running. */
  function expectCtaHidden(): void {
    expect(cta().getAttribute('aria-hidden')).toBe('true');
    expect(cta().getAttribute('tabindex')).toBe('-1');
    expect(cta().style.pointerEvents).toBe('none');
  }

  /** Shown = back in the tree, tappable, and out of the visibility-hidden slot state. */
  function expectCtaShown(): void {
    expect(cta().getAttribute('aria-hidden')).toBe('false');
    expect(cta().hasAttribute('tabindex')).toBeFalse();
    expect(cta().style.pointerEvents).toBe('');
    expect(cta().classList.contains('fp-cta--hidden')).toBeFalse();
  }

  it('hides Save plan on an untouched seeded page', () => {
    setup();
    expect(fixture.componentInstance.isDirty()).toBeFalse();
    expectCtaHidden();
    expect(cta().classList.contains('fp-cta--hidden')).toBeTrue();
  });

  it('shows Save plan after an income move', () => {
    setup();
    slideTo('income', 6000);
    expect(fixture.componentInstance.isDirty()).toBeTrue();
    expectCtaShown();
  });

  it('shows Save plan after an invest move', () => {
    setup();
    slideTo('invest', 3000);
    expectCtaShown();
  });

  it('shows Save plan after a preset change', () => {
    setup();
    fixture.componentInstance.selectReturn(0.12);
    fixture.detectChanges();
    expectCtaShown();
  });

  it('shows Save plan, disabled, when a move zeroes a tile: dirty but not saveable', () => {
    setup();
    slideTo('invest', 0);
    expectCtaShown();
    expect(cta().disabled).toBeTrue();
  });

  it('hides Save plan again when the value is put back to the saved one', () => {
    setup();
    slideTo('invest', 3000);
    expectCtaShown();
    slideTo('invest', 2500);
    expect(fixture.componentInstance.isDirty()).toBeFalse();
    expectCtaHidden();

    fixture.componentInstance.selectReturn(0.08);
    fixture.detectChanges();
    expectCtaShown();
    fixture.componentInstance.selectReturn(0.10);
    fixture.detectChanges();
    expectCtaHidden();
  });

  it('fades Save plan up and in with WAAPI, not on the opening render', async () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    const ctaFades = () => animate.calls.all().filter(c => c.object === cta());
    expect(ctaFades().length).toBe(0);

    slideTo('income', 6000);
    expect(ctaFades().length).toBe(1);
    const [frames, options] = ctaFades()[0].args as [Keyframe[], KeyframeAnimationOptions];
    expect(frames[0]).toEqual({ opacity: 0, transform: 'translateY(12px)' });
    expect(frames[1]).toEqual({ opacity: 1, transform: 'translateY(0px)' });
    expect(options.duration).toBe(480);
    expect(options.easing).toBe(HOUSE_EASING);

    // Back to the saved plan: a shorter fade down and out; the slot is released only when it finishes.
    slideTo('income', 5000);
    expect(ctaFades().length).toBe(2);
    const [outFrames, outOptions] = ctaFades()[1].args as [Keyframe[], KeyframeAnimationOptions];
    expect(outFrames[1]).toEqual({ opacity: 0, transform: 'translateY(12px)' });
    expect(outOptions.duration).toBe(360);
    expectCtaHidden();
    expect(cta().classList.contains('fp-cta--hidden')).toBeFalse();
    const fadeOut = ctaFades()[1].returnValue as Animation;
    // The page's onfinish was set first, so it has run by the time this listener fires.
    const finished = new Promise<void>(resolve => fadeOut.addEventListener('finish', () => resolve()));
    fadeOut.finish();
    await finished;
    expect(cta().classList.contains('fp-cta--hidden')).toBeTrue();
  });

  it('a flip mid-fade starts the opposite fade from the live frame, not from full or zero opacity', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    const ctaFades = () => animate.calls.all().filter(c => c.object === cta());
    // Eased progress of a fade, the same figure the page reads to pick up mid-fade.
    const progressOf = (a: Animation) => a.effect!.getComputedTiming().progress as number;

    // Half-way through the fade-in, the user slides the saved value back.
    slideTo('income', 6000);
    const fadeIn = ctaFades()[0].returnValue as Animation;
    fadeIn.currentTime = 240;
    const midIn = progressOf(fadeIn);
    expect(midIn).toBeGreaterThan(0);
    expect(midIn).toBeLessThan(1);
    slideTo('income', 5000);
    expect(ctaFades().length).toBe(2);
    const outFrames = ctaFades()[1].args[0] as Keyframe[];
    expect(outFrames[0]['opacity']).toBeCloseTo(midIn, 6);
    expect(outFrames[0]['transform']).toBe(`translateY(${12 - 12 * midIn}px)`);
    expect(outFrames[1]).toEqual({ opacity: 0, transform: 'translateY(12px)' });

    // And the mirror: dirty again while the fade-out is still running.
    const fadeOut = ctaFades()[1].returnValue as Animation;
    fadeOut.currentTime = 180;
    const midOut = progressOf(fadeOut);
    expect(midOut).toBeGreaterThan(0);
    expect(midOut).toBeLessThan(1);
    slideTo('income', 6000);
    expect(ctaFades().length).toBe(3);
    const inFrames = ctaFades()[2].args[0] as Keyframe[];
    expect(inFrames[0]['opacity']).toBeCloseTo(midIn * (1 - midOut), 6);
    expect(inFrames[1]).toEqual({ opacity: 1, transform: 'translateY(0px)' });
    expect(cta().classList.contains('fp-cta--hidden')).toBeFalse();
  });

  it('under reduced motion Save plan toggles visibility at once, with no fade', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup(5000, 2500, 0, undefined, { reducedMotion: true });
    slideTo('invest', 3000);
    expectCtaShown();
    slideTo('invest', 2500);
    expectCtaHidden();
    expect(cta().classList.contains('fp-cta--hidden')).toBeTrue();
    expect(animate.calls.all().some(c => c.object === cta())).toBeFalse();
  });

  it('reacts to a seed that lands after the user moved a tile: dirty is judged against the saved plan', () => {
    // Only the fallback so far: baseline 0/0 and tiles 0/0, so the page opens clean.
    setup(5000, 2500, 0, undefined, { progress: { _source: 'localStorageFallback' } });
    expect(fixture.componentInstance.isDirty()).toBeFalse();
    expectCtaHidden();

    slideTo('invest', 3000);
    expectCtaShown();

    // The saved plan arrives and matches what was moved: the income tile fills in, the page is clean.
    const progress$ = TestBed.inject(AuthService).userProgress$ as BehaviorSubject<any>;
    progress$.next({ monthlyInvestment: 3000, retirementIncome: 60_000, _source: 'db' });
    fixture.detectChanges();
    expect(fixture.componentInstance.incomeSig()).toBe(5000);
    expect(fixture.componentInstance.isDirty()).toBeFalse();
    expectCtaHidden();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Slide tray: the tiles are buttons; a tap grows the tray under the tile row.
  // ───────────────────────────────────────────────────────────────────────────

  it('the hero carries its label, the subtitle is gone, and nothing on the page takes a keyboard', () => {
    setup();
    const hero = fixture.nativeElement.querySelector('.fd-hero') as HTMLElement;
    expect(hero.firstElementChild?.classList.contains('fd-hero-label')).toBeTrue();
    expect(hero.firstElementChild?.textContent?.trim()).toBe('Freedom date');
    expect(fixture.nativeElement.querySelector('.fp-sub')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('input').length).toBe(0);
    const content = fixture.nativeElement.querySelector('ion-content') as HTMLElement;
    expect(content.hasAttribute('appkeyboardavoid')).toBeFalse();
  });

  it('the tiles are buttons showing the plan, with placeholders while a value is unset', () => {
    setup();
    expect(tile('income').tagName).toBe('BUTTON');
    expect(tile('invest').tagName).toBe('BUTTON');
    expect(tileValue('income')).toBe('5,000');
    expect(tileValue('invest')).toBe('2,500');
    expect(tile('income').querySelector('.fd-tile-value')?.classList.contains('fp-empty')).toBeFalse();
    expect(tray()).toBeNull();
  });

  it('an unset plan shows the placeholders in the placeholder style', () => {
    setup(0, 0);
    expect(tileValue('income')).toBe('5,000');
    expect(tileValue('invest')).toBe('500');
    expect(tile('income').querySelector('.fd-tile-value')?.classList.contains('fp-empty')).toBeTrue();
    expect(tile('invest').querySelector('.fd-tile-value')?.classList.contains('fp-empty')).toBeTrue();
  });

  it('tapping a tile opens the tray on that field, lights the tile and shows Done', () => {
    setup();
    expect(doneBtn()).toBeNull();
    tile('income').click();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.trayField()).toBe('income');
    expect(tray()).not.toBeNull();
    expect(tray()!.field()).toBe('income');
    expect(tray()!.value()).toBe(5000);
    expect(tray()!.scale()).toEqual({ min: 500, max: 30_000, step: 250 });
    expect(tile('income').classList.contains('active')).toBeTrue();
    expect(tile('income').getAttribute('aria-expanded')).toBe('true');
    expect(tile('invest').classList.contains('active')).toBeFalse();
    expect(doneBtn()).not.toBeNull();
    // The tray sits right after the tile row, inside the plan block.
    const tiles = fixture.nativeElement.querySelector('.fd-plan > .fd-tiles') as HTMLElement;
    expect(tiles.nextElementSibling?.classList.contains('fp-tray')).toBeTrue();
  });

  it('tapping the other tile switches the tray to that field without closing it', async () => {
    setup();
    tile('income').click();
    fixture.detectChanges();
    tile('invest').click();
    fixture.detectChanges();
    await settleSwap();
    expect(fixture.debugElement.queryAll(By.directive(PlanAmountTrayComponent)).length).toBe(1);
    expect(tray()!.field()).toBe('invest');
    expect(tray()!.value()).toBe(2500);
    expect(tray()!.scale()).toEqual({ min: 0, max: 10_000, step: 100 });
    expect(tile('invest').classList.contains('active')).toBeTrue();
    expect(tile('income').classList.contains('active')).toBeFalse();
  });

  it('tapping the open tile again closes the tray', () => {
    setup(5000, 2500, 0, undefined, { reducedMotion: true });
    tile('invest').click();
    fixture.detectChanges();
    expect(tray()).not.toBeNull();
    tile('invest').click();
    fixture.detectChanges();
    expect(fixture.componentInstance.trayField()).toBeNull();
    expect(fixture.componentInstance.trayShown()).toBeNull();
    expect(tray()).toBeNull();
    expect(doneBtn()).toBeNull();
    expect(tile('invest').classList.contains('active')).toBeFalse();
  });

  it('Done closes the tray', () => {
    setup(5000, 2500, 0, undefined, { reducedMotion: true });
    tile('income').click();
    fixture.detectChanges();
    doneBtn()!.click();
    fixture.detectChanges();
    expect(tray()).toBeNull();
    expect(doneBtn()).toBeNull();
  });

  it('a tap outside the tiles and the tray closes it on release; a tap on them does not', () => {
    setup(5000, 2500, 0, undefined, { reducedMotion: true });
    const pointer = (type: string, target: Element, pointerId = 1) => {
      target.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId }));
      fixture.detectChanges();
    };
    const tap = (target: Element) => { pointer('pointerdown', target); pointer('pointerup', target); };
    const hero = () => fixture.nativeElement.querySelector('.fd-hero') as HTMLElement;
    tile('income').click();
    fixture.detectChanges();

    tap(fixture.nativeElement.querySelector('.ps'));            // on the slider
    expect(tray()).not.toBeNull();
    tap(tile('invest'));                                        // on the other tile
    expect(tray()).not.toBeNull();
    tap(doneBtn()!);                                            // on Done itself (its click closes)
    expect(tray()).not.toBeNull();
    // A touch outside that the page turns into a scroll is not a tap; a release the finger did not
    // start outside (it went down on the slider and lifted elsewhere) is not one either.
    pointer('pointerdown', hero());
    pointer('pointercancel', hero());
    expect(tray()).not.toBeNull();
    pointer('pointerdown', fixture.nativeElement.querySelector('.ps'), 2);
    pointer('pointerup', hero(), 2);
    expect(tray()).not.toBeNull();
    // The tray stays through the touch and closes when the finger lifts, so what is under it has
    // not moved before its click lands.
    pointer('pointerdown', hero());
    expect(tray()).not.toBeNull();
    pointer('pointerup', hero());
    expect(tray()).toBeNull();
    expect(fixture.componentInstance.trayField()).toBeNull();

    // Closed: the listeners are gone, so a later outside tap is nothing to the page.
    tap(hero());
    expect(fixture.componentInstance.trayField()).toBeNull();
  });

  it('a tile tap with the page scrolled brings the hero back into view, on open and on switch', async () => {
    setup();
    const content = fixture.componentInstance['content']!;
    spyOn(content, 'getScrollElement').and.resolveTo({ scrollTop: 120 } as HTMLElement);
    const scrollToTop = spyOn(content, 'scrollToTop').and.resolveTo();
    tile('income').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(scrollToTop).toHaveBeenCalledOnceWith(220);
    tile('invest').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(scrollToTop).toHaveBeenCalledTimes(2);
  });

  it('a tap on the other tile waits while a finger is down on the slider', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    tray()!.dragging.emit(true);
    fixture.detectChanges();
    const before = animate.calls.count();
    tile('invest').click();
    fixture.detectChanges();
    expect(fixture.componentInstance.trayField()).toBe('income');
    expect(tray()!.field()).toBe('income');
    expect(tile('invest').classList.contains('active')).toBeFalse();
    expect(animate.calls.all().slice(before).filter(c => c.object === inner).length).toBe(0);
    // Released: the next tap switches as usual.
    tray()!.dragging.emit(false);
    fixture.detectChanges();
    tile('invest').click();
    fixture.detectChanges();
    expect(fixture.componentInstance.trayField()).toBe('invest');
  });

  it('leaving the page closes the tray at once', () => {
    setup();
    tile('income').click();
    fixture.detectChanges();
    fixture.componentInstance.ionViewWillLeave();
    fixture.detectChanges();
    expect(fixture.componentInstance.trayShown()).toBeNull();
    expect(tray()).toBeNull();
    expect(freedomPlanLeft).toHaveBeenCalledTimes(1);
  });

  it('Save closes the tray', () => {
    setup();
    slideTo('invest', 3000);
    expect(tray()).not.toBeNull();
    fixture.componentInstance.save();
    fixture.detectChanges();
    expect(fixture.componentInstance.trayField()).toBeNull();
    expect(fixture.componentInstance.trayShown()).toBeNull();
    expect(tray()).toBeNull();
  });

  it('a value from the tray lands on the open tile and counts as the user moving it', () => {
    // The seed lands after the move: the moved tile is left alone, the other fills in.
    setup(5000, 2500, 0, undefined, { progress: { _source: 'localStorageFallback' } });
    slideTo('income', 7500);
    expect(fixture.componentInstance.incomeSig()).toBe(7500);
    expect(tileValue('income')).toBe('7,500');
    const progress$ = TestBed.inject(AuthService).userProgress$ as BehaviorSubject<any>;
    progress$.next({ monthlyInvestment: 1000, retirementIncome: 60_000, _source: 'db' });
    fixture.detectChanges();
    expect(fixture.componentInstance.incomeSig()).toBe(7500);
    expect(fixture.componentInstance.investSig()).toBe(1000);
  });

  it('the active tile label reads the step size while the finger is down, and the field name again on release', () => {
    setup();
    tile('income').click();
    fixture.detectChanges();
    expect(tileLabel('income').textContent?.trim()).toBe('Income goal');
    tray()!.dragging.emit(true);
    fixture.detectChanges();
    expect(tileLabel('income').textContent?.trim()).toBe('± $250');
    expect(tileLabel('income').classList.contains('fp-step')).toBeTrue();
    expect(tileLabel('invest').textContent?.trim()).toBe('Investing');
    tray()!.dragging.emit(false);
    fixture.detectChanges();
    expect(tileLabel('income').textContent?.trim()).toBe('Income goal');
    expect(tileLabel('income').classList.contains('fp-step')).toBeFalse();

    tile('invest').click();
    fixture.detectChanges();
    tray()!.dragging.emit(true);
    fixture.detectChanges();
    expect(tileLabel('invest').textContent?.trim()).toBe('± $100');
    expect(tileLabel('income').textContent?.trim()).toBe('Income goal');
  });

  it('the hero pulse waits while the finger is down and fires once on release', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    const hero = () => fixture.nativeElement.querySelector('.fd-hero-year') as HTMLElement;
    const pulses = () => animate.calls.all().filter(c => c.object === hero()).length;
    expect(pulses()).toBe(0);

    // A move with no finger down (a chip, a nudge): one pulse per change, as before.
    slideTo('invest', 3000);
    expect(pulses()).toBe(1);

    tray()!.dragging.emit(true);
    fixture.detectChanges();
    slideTo('invest', 3100);
    slideTo('invest', 3200);
    slideTo('invest', 3300);
    expect(pulses()).toBe(1);
    tray()!.dragging.emit(false);
    fixture.detectChanges();
    expect(pulses()).toBe(2);

    // A drag that moved nothing pulses nothing.
    tray()!.dragging.emit(true);
    fixture.detectChanges();
    tray()!.dragging.emit(false);
    fixture.detectChanges();
    expect(pulses()).toBe(2);
  });

  it('grows the tray with WAAPI: the wrapper from 0 to its measured height, the card fading in with a lift, Done fading in', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    const boxCalls = animate.calls.all().filter(c => c.object === box);
    expect(boxCalls.length).toBe(1);
    const [frames, options] = boxCalls[0].args as [Keyframe[], KeyframeAnimationOptions];
    expect(frames[0]).toEqual({ height: '0px' });
    // Karma's context document has no layout, so the measured height is whatever scrollHeight says.
    expect(frames[1]).toEqual({ height: `${box.scrollHeight}px` });
    expect(options.duration).toBe(240);
    expect(options.easing).toBe(HOUSE_EASING);

    const innerCalls = animate.calls.all().filter(c => c.object === inner);
    expect(innerCalls.length).toBe(1);
    const [innerFrames, innerOptions] = innerCalls[0].args as [Keyframe[], KeyframeAnimationOptions];
    expect(innerFrames[0]).toEqual({ opacity: 0, transform: 'translateY(-6px)' });
    expect(innerFrames[1]).toEqual({ opacity: 1, transform: 'translateY(0px)' });
    expect(innerOptions.duration).toBe(240);

    const doneCalls = animate.calls.all().filter(c => c.object === doneBtn());
    expect(doneCalls.length).toBe(1);
    expect((doneCalls[0].args[1] as KeyframeAnimationOptions).duration).toBe(160);
  });

  it('collapses the tray on close and removes it only when the collapse lands', async () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    const done = doneBtn()!;

    tile('income').click();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    // The tile unlights at once; the card stays to collapse.
    expect(page.trayField()).toBeNull();
    expect(page.trayShown()).toBe('income');
    expect(tile('income').classList.contains('active')).toBeFalse();
    expect(tray()).not.toBeNull();

    const collapse = animate.calls.all().filter(c => c.object === box)[1];
    const [frames, options] = collapse.args as [Keyframe[], KeyframeAnimationOptions];
    expect(frames[1]).toEqual({ height: '0px' });
    expect(options.duration).toBe(200);
    expect(options.easing).toBe(HOUSE_EASING);
    const fadeOut = animate.calls.all().filter(c => c.object === inner)[1];
    expect((fadeOut.args[0] as Keyframe[])[1]).toEqual({ opacity: 0 });
    expect((fadeOut.args[1] as KeyframeAnimationOptions).duration).toBe(140);
    expect(animate.calls.all().filter(c => c.object === done).length).toBe(2);

    await finish(collapse.returnValue as Animation);
    expect(page.trayShown()).toBeNull();
    expect(tray()).toBeNull();
    expect(doneBtn()).toBeNull();
  });

  it('a close that lands mid-open fades the card and Done out from where they are', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    const done = doneBtn()!;
    // The grow has just begun and Done's fade-in is still in its delay: neither is at full opacity.
    const innerLive = Number(getComputedStyle(inner).opacity);
    const doneLive = Number(getComputedStyle(done).opacity);
    expect(innerLive).toBeLessThan(1);
    expect(doneLive).toBeLessThan(1);
    const before = animate.calls.count();

    tile('income').click();
    fixture.detectChanges();
    const since = animate.calls.all().slice(before);
    const fadeOut = since.filter(c => c.object === inner);
    expect(fadeOut.length).toBe(1);
    expect((fadeOut[0].args[0] as Keyframe[])[0]).toEqual({ opacity: innerLive });
    const doneOut = since.filter(c => c.object === done);
    expect(doneOut.length).toBe(1);
    expect((doneOut[0].args[0] as Keyframe[])[0]).toEqual({ opacity: doneLive });
    // The fade-in is gone: the fade-out is Done's only animation.
    expect(done.getAnimations().length).toBe(1);
    expect(done.getAnimations()[0]).toBe(doneOut[0].returnValue as Animation);
  });

  it('a drag that begins mid-collapse does not leave the page thinking a finger is down', async () => {
    setup();
    tile('income').click();
    fixture.detectChanges();
    doneBtn()!.click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    const page = fixture.componentInstance;
    // The card is live through the collapse; the finger goes down and the tray leaves before it lifts.
    tray()!.dragging.emit(true);
    fixture.detectChanges();
    expect(page.trayDragging()).toBeTrue();
    await finish(box.getAnimations()[0]);
    expect(tray()).toBeNull();
    expect(page.trayDragging()).toBeFalse();
    tile('invest').click();
    fixture.detectChanges();
    expect(tileLabel('invest').textContent?.trim()).toBe('Investing');
  });

  it('a second Done mid-collapse changes nothing', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    doneBtn()!.click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    const collapse = box.getAnimations()[0];
    const before = animate.calls.count();
    doneBtn()!.click();
    fixture.detectChanges();
    expect(animate.calls.count()).toBe(before);
    expect(box.getAnimations()[0]).toBe(collapse);
    expect(fixture.componentInstance.trayShown()).toBe('income');
  });

  it('a value from the card while it collapses still lands on the field it shows', () => {
    setup();
    tile('income').click();
    fixture.detectChanges();
    doneBtn()!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.trayField()).toBeNull();
    tray()!.valueChange.emit(5250);
    fixture.detectChanges();
    expect(fixture.componentInstance.incomeSig()).toBe(5250);
  });

  it('re-opening mid-collapse grows the tray back from where it is and brings Done back', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    const done = doneBtn()!;
    tile('income').click();
    fixture.detectChanges();
    const before = animate.calls.count();
    tile('income').click();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.trayField()).toBe('income');
    expect(page.trayShown()).toBe('income');
    // The same tray and the same Done: nothing left the DOM. The collapse's held frame is gone,
    // the grow is the box's only animation, and Done fades back in instead of holding opacity 0.
    expect(fixture.nativeElement.querySelector('.fp-tray')).toBe(box);
    expect(doneBtn()).toBe(done);
    expect(box.getAnimations().length).toBe(1);
    const grow = animate.calls.all().slice(before).filter(c => c.object === box);
    expect(grow.length).toBe(1);
    expect((grow[0].args[0] as Keyframe[])[1]).toEqual({ height: `${box.scrollHeight}px` });
    const doneIn = animate.calls.all().slice(before).filter(c => c.object === done);
    expect(doneIn.length).toBe(1);
    expect((doneIn[0].args[0] as Keyframe[])[1]).toEqual({ opacity: 1 });
    expect((doneIn[0].args[1] as KeyframeAnimationOptions).duration).toBe(160);
    expect(done.getAnimations().length).toBe(1);
    expect(done.getAnimations()[0]).toBe(doneIn[0].returnValue as Animation);
  });

  it('switching fields cross-fades the card with no height change, swapping the slider inside the dip', async () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tile('income').click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    const before = animate.calls.count();

    tile('invest').click();
    fixture.detectChanges();
    // The tile lights at once; the card still shows the income slider while it fades out.
    expect(tile('invest').classList.contains('active')).toBeTrue();
    expect(tray()!.field()).toBe('income');
    const since = () => animate.calls.all().slice(before);
    expect(since().filter(c => c.object === box).length).toBe(0);
    const out = since().filter(c => c.object === inner);
    expect(out.length).toBe(1);
    expect((out[0].args[0] as Keyframe[])[1]).toEqual({ opacity: 0 });
    expect((out[0].args[1] as KeyframeAnimationOptions).duration).toBe(88);

    await finish(out[0].returnValue as Animation);
    expect(tray()!.field()).toBe('invest');
    const swap = since().filter(c => c.object === inner);
    expect(swap.length).toBe(2);
    expect(swap[1].args[0] as Keyframe[]).toEqual([{ opacity: 0 }, { opacity: 1 }]);
    expect((swap[1].args[1] as KeyframeAnimationOptions).duration).toBe(132);
    expect(since().filter(c => c.object === box).length).toBe(0);
    expect(fixture.nativeElement.querySelector('.fp-tray')).toBe(box);
  });

  it('closing during a field switch drops the swap: the collapsing card keeps the slider it shows', async () => {
    setup();
    tile('income').click();
    fixture.detectChanges();
    tile('invest').click();
    fixture.detectChanges();
    const inner = fixture.nativeElement.querySelector('.fp-tray-in') as HTMLElement;
    const out = inner.getAnimations()[0];
    fixture.componentInstance.closeTray(true);
    fixture.detectChanges();
    expect(tray()).toBeNull();
    await finish(out);
    expect(fixture.componentInstance.trayShown()).toBeNull();
    expect(tray()).toBeNull();
  });

  it('under reduced motion the tray snaps open and shut, with no animation', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup(5000, 2500, 0, undefined, { reducedMotion: true });
    tile('income').click();
    fixture.detectChanges();
    const box = fixture.nativeElement.querySelector('.fp-tray') as HTMLElement;
    expect(animate.calls.all().some(c => c.object === box)).toBeFalse();
    expect(animate.calls.all().some(c => c.object === doneBtn())).toBeFalse();
    tile('invest').click();
    fixture.detectChanges();
    expect(animate.calls.all().some(c => c.object === box.firstElementChild)).toBeFalse();
    doneBtn()!.click();
    fixture.detectChanges();
    expect(tray()).toBeNull();
    expect(animate.calls.all().some(c => c.object === box)).toBeFalse();
  });
});
