/**
 * my-profile.page.spec.ts
 *
 * Direct-construction unit tests (no TestBed) for the freedom-plan math, plus
 * DOM-level TestBed tests for live rendering and seeding resilience.
 *
 * Freedom year source: computed LIVE on the page from the two editable tiles
 * (income goal → target portfolio, investing → monthly contribution) plus the
 * current portfolio equity, using the same PV-inclusive 10%-return formula as
 * FreedomStatsService._calculateMonthsToTarget. The tiles are a what-if
 * calculator: edits move the projection immediately, before any save.
 *
 * All plan state is SIGNALS (2026-08-03): plain fields relied on zone-driven
 * change detection, which intermittently failed to flush on WKWebView
 * keystrokes; signal writes schedule CD themselves via the hybrid scheduler.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { NavController, AlertController, ModalController } from '@ionic/angular/standalone';
import { BehaviorSubject, of } from 'rxjs';
import { MyProfilePage } from './my-profile.page';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { PortfolioService } from '../../services/portfolio.service';
import { AccountStatusService } from '../../services/account-status.service';
import { MonthlyFreedomUpdateService } from '../../services/monthly-freedom-update.service';
import { GoalsService } from '../../services/goals.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

const CURRENT_YEAR = new Date().getFullYear();

/**
 * Analytics is fire-and-forget on this page, so these specs only need it not to throw. The
 * payload assertions live in my-profile.analytics.spec.ts, which asserts the exact props.
 */
function analyticsStub(): AnalyticsService {
  return { track: () => {}, trackErrorShown: () => {} } as unknown as AnalyticsService;
}

function makePage(birthYear: number = 0): MyProfilePage {
  const accountStatusMock = { actionRequired$: of(false) } as any;
  const freedomStatsMock = {
    birthYear: () => birthYear,
    stats: () => ({ freedomYear: '—', freedomAge: '—', dollarsAway: '—' })
  } as any;

  return new MyProfilePage(
    {} as any,            // AuthService
    {} as any,            // ToastService
    {} as any,            // PortfolioService
    accountStatusMock,    // AccountStatusService
    {} as any,            // MonthlyFreedomUpdateService
    {} as any,            // Router
    {} as any,            // AlertController
    {} as any,            // NavController
    {} as any,            // ModalController
    {} as any,            // GoalsService (FRED-216)
    freedomStatsMock,     // FreedomStatsService
    analyticsStub()       // AnalyticsService
  );
}

/** Set the two tiles + equity; all projections are computed() and follow. */
function setPlan(page: MyProfilePage, incomeMonthly: number, investMonthly: number, equity: number): void {
  page.retirementIncomeGoal.set(incomeMonthly);
  page.monthlyInvestGoal.set(investMonthly);
  page.currentPortfolioValue.set(equity);
}

/** Build a native input event carrying the given raw value. */
function inputEvent(value: string): { event: Event; input: HTMLInputElement } {
  const input = document.createElement('input');
  input.value = value;
  const event = { target: input } as unknown as Event;
  return { event, input };
}

describe('MyProfilePage — freedom plan calculator', () => {

  // Hand-computed expectations at r = 0.10/12 (see spec header):
  //   income $5,000/mo → target $1.5M; invest $2,500/mo; equity $0
  //   months = ln((1.5M + 300k)/300k) / ln(1 + 0.10/12) = 215.9 → ceil(/12) = 18
  it('projects the freedom year from the tiles at a 10% annual return', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 0);
    expect(page.freedomYear()).toBe(CURRENT_YEAR + 18);
  });

  it('decreases the time to freedom when the invest amount increases', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 0);
    const before = page.freedomYear()!;
    setPlan(page, 5000, 5000, 0);          // ln(3.5) path → 150.9 months → 13 yrs
    expect(page.freedomYear()).toBe(CURRENT_YEAR + 13);
    expect(page.freedomYear()!).toBeLessThan(before);
  });

  it('increases the portfolio goal and time to freedom when the income goal increases', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 0);
    const before = page.freedomYear()!;
    setPlan(page, 6000, 2500, 0);          // target $1.8M → ln(7) → 234.5 months → 20 yrs
    expect(page.portfolioGoal()).toBe(1_800_000);
    expect(page.freedomYear()).toBe(CURRENT_YEAR + 20);
    expect(page.freedomYear()!).toBeGreaterThan(before);
  });

  it('counts current portfolio equity toward the goal (favorable PV-inclusive math)', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 100_000);    // ln(4.5) → 181.2 months → 16 yrs
    expect(page.freedomYear()).toBe(CURRENT_YEAR + 16);
  });

  it('projects growth-only when investing is 0 but equity exists', () => {
    const page = makePage();
    setPlan(page, 5000, 0, 100_000);       // ln(15) → 326.3 months → 28 yrs
    expect(page.freedomYear()).toBe(CURRENT_YEAR + 28);
  });

  it('shows the current year when equity already meets the goal', () => {
    const page = makePage();
    setPlan(page, 1000, 0, 400_000);       // target $300k, equity $400k
    expect(page.freedomYear()).toBe(CURRENT_YEAR);
  });

  it('returns null while the income goal is empty', () => {
    const page = makePage();
    setPlan(page, 0, 2500, 10_000);
    expect(page.freedomYear()).toBeNull();
    expect(page.yearsToFreedom()).toBeNull();
  });

  it('returns null when there is no equity and no contribution', () => {
    const page = makePage();
    setPlan(page, 5000, 0, 0);
    expect(page.freedomYear()).toBeNull();
  });

  it('computes yearsToFreedom from the projected year', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 0);
    expect(page.yearsToFreedom()).toBe(18);
  });

  it('derives freedom age from the KYC birth year', () => {
    const page = makePage(1990);
    setPlan(page, 5000, 2500, 0);
    expect(page.freedomAge()).toBe(String(CURRENT_YEAR + 18 - 1990));
  });

  it('shows a dash for freedom age when the birth year is unknown', () => {
    const page = makePage(0);
    setPlan(page, 5000, 2500, 0);
    expect(page.freedomAge()).toBe('—');
  });
});

describe('MyProfilePage — tile input sanitizing (5-digit cap, digits only)', () => {

  it('caps the income input at 5 digits and syncs input and model', () => {
    const page = makePage();
    const { event, input } = inputEvent('123456');
    page.onIncomeInput(event);
    expect(page.retirementIncomeGoal()).toBe(12345);
    expect(input.value).toBe('12345');
  });

  it('caps the invest input at 5 digits and syncs input and model', () => {
    const page = makePage();
    const { event, input } = inputEvent('999999');
    page.onInvestInput(event);
    expect(page.monthlyInvestGoal()).toBe(99999);
    expect(input.value).toBe('99999');
  });

  it('strips non-digit characters', () => {
    const page = makePage();
    const { event, input } = inputEvent('$1,000');
    page.onIncomeInput(event);
    expect(page.retirementIncomeGoal()).toBe(1000);
    expect(input.value).toBe('1000');
  });

  it('keeps all 5 digits of a comma-formatted paste', () => {
    const page = makePage();
    const { event, input } = inputEvent('10,000');
    page.onInvestInput(event);
    expect(page.monthlyInvestGoal()).toBe(10000);
    expect(input.value).toBe('10000');
  });

  it('treats an emptied input as 0 and clears the projection', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 0);
    const { event, input } = inputEvent('');
    page.onIncomeInput(event);
    expect(page.retirementIncomeGoal()).toBe(0);
    expect(input.value).toBe('');
    expect(page.portfolioGoal()).toBe(0);
    expect(page.freedomYear()).toBeNull();
  });

  it('recalculates the projection directly from the input event', () => {
    const page = makePage();
    setPlan(page, 5000, 2500, 0);
    const { event } = inputEvent('5000');
    page.onInvestInput(event);
    expect(page.freedomYear()).toBe(CURRENT_YEAR + 13);
    expect(page.isDirty()).toBeTrue();
  });
});

// ---------------------------------------------------------------------------
// DOM-level rendering tests (TestBed, real template)
// ---------------------------------------------------------------------------

const SAVED_PROGRESS = {
  firstName: 'Andy',
  monthlyInvestment: 7000,
  retirementIncome: 60000, // annual → $5,000/mo tile
  selectedTier: 'pro',
  investmentConfirmationCompleted: true
};

interface DomHarness {
  fixture: ComponentFixture<MyProfilePage>;
  progress$: BehaviorSubject<any>;
  updateUserProfileSpy: jasmine.Spy;
}

function setupDom(initialProgress: any, schedule$ = of(null as any)): DomHarness {
  localStorage.removeItem('userId');
  const progress$ = new BehaviorSubject<any>(initialProgress);
  const updateUserProfileSpy = jasmine.createSpy('updateUserProfile').and.returnValue(of({}));

  TestBed.configureTestingModule({
    imports: [MyProfilePage],
    providers: [
      { provide: AuthService, useValue: {
          userProgress$: progress$,
          getCurrentInvestmentSchedule: () => schedule$,
          updateUserProfile: updateUserProfileSpy,
          loadUserProgress: jasmine.createSpy('loadUserProgress')
      } },
      { provide: ToastService, useValue: { showToast: jasmine.createSpy() } },
      { provide: PortfolioService, useValue: { getPortfolioDashboard: () => of({ summary: { equity: 0 } }) } },
      { provide: AccountStatusService, useValue: { actionRequired$: of(false) } },
      { provide: MonthlyFreedomUpdateService, useValue: { checkShouldShow: () => of(null) } },
      { provide: Router, useValue: { navigateByUrl: jasmine.createSpy() } },
      { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
      { provide: AlertController, useValue: {} },
      { provide: ModalController, useValue: {
          create: jasmine.createSpy('create').and.returnValue(Promise.resolve({
            present: () => Promise.resolve(),
            onDidDismiss: () => Promise.resolve({ role: 'cancel' }),
          }))
      } },
      { provide: FirstTimeTourService, useValue: { suspend: jasmine.createSpy('suspend'), freedomSheetClosed: jasmine.createSpy('freedomSheetClosed') } },
      { provide: GoalsService, useValue: { getGoals: () => [], getPrefs: () => ({ rebalanceSchedule: 'Quarterly' }) } },
      { provide: FreedomStatsService, useValue: {
          birthYear: () => 0,
          stats: () => ({ freedomYear: '—', freedomAge: '—', dollarsAway: '—' })
      } },
      { provide: AnalyticsService, useValue: analyticsStub() }
    ]
  });

  const fixture = TestBed.createComponent(MyProfilePage);
  fixture.detectChanges(); // ngOnInit → seeds tiles from progress
  return { fixture, progress$, updateUserProfileSpy };
}

describe('MyProfilePage — live plan rendering (DOM)', () => {
  let harness: DomHarness;

  beforeEach(() => {
    harness = setupDom(SAVED_PROGRESS);
  });

  /** Tiles moved into the Freedom detail sheet (2026-08-15); plan edits now
   *  arrive through the same sanitizing handlers, driven directly. */
  function editInvest(value: string): void {
    harness.fixture.componentInstance.onInvestInput(inputEvent(value).event);
    harness.fixture.detectChanges();
  }

  it('seeds the plan from saved progress and renders the Freedom Date row line', () => {
    expect(harness.fixture.componentInstance.monthlyInvestGoal()).toBe(7000);
    expect(harness.fixture.componentInstance.freedomGoalSub).toContain('$7,000/mo through FRED');
  });

  it('updates the Freedom Date row line on every plan edit', () => {
    editInvest('700');
    expect(harness.fixture.componentInstance.freedomGoalSub).toContain('$700/mo');
    expect(harness.fixture.componentInstance.freedomGoalSub).not.toContain('$7,000/mo');
    editInvest('70');
    expect(harness.fixture.componentInstance.freedomGoalSub).toContain('$70/mo');
    editInvest('7005');
    expect(harness.fixture.componentInstance.freedomGoalSub).toContain('$7,005/mo');
  });

  it('does not clobber unsaved plan edits when userProgress$ re-emits in the background', () => {
    editInvest('700');                                 // user mid-edit, dirty
    harness.progress$.next({ ...SAVED_PROGRESS });     // token-refresh reload lands
    harness.fixture.detectChanges();
    expect(harness.fixture.componentInstance.monthlyInvestGoal()).toBe(700);
    expect(harness.fixture.componentInstance.freedomGoalSub).toContain('$700/mo');
  });

  it('re-seeds the plan from a post-save emission once no longer dirty', () => {
    editInvest('700');
    harness.fixture.componentInstance.saveChanges();   // clears dirt via originalValues
    expect(harness.updateUserProfileSpy).toHaveBeenCalledWith({
      monthlyInvestment: 700, retirementIncome: 60000
    });
    harness.progress$.next({ ...SAVED_PROGRESS, monthlyInvestment: 700 });
    harness.fixture.detectChanges();
    expect(harness.fixture.componentInstance.monthlyInvestGoal()).toBe(700);
  });

  it('openFreedomSheet suspends the tour before presenting and reports the close after dismiss', async () => {
    const page = harness.fixture.componentInstance;
    const tour = TestBed.inject(FirstTimeTourService) as any;

    await page.openFreedomSheet();

    expect(tour.suspend).toHaveBeenCalledTimes(1);
    expect(tour.freedomSheetClosed).toHaveBeenCalledTimes(1);
    expect(tour.suspend).toHaveBeenCalledBefore(tour.freedomSheetClosed);
  });
});

/**
 * Seeding resilience (DOM). The dirty-guard must never block the FIRST real
 * DB emission: userProgress$ starts null, so pre-seed keystrokes would
 * otherwise pin the empty defaults as the baseline forever — and Save
 * could write fabricated values over the user's real DB row.
 */
describe('MyProfilePage — plan seeding resilience (DOM)', () => {

  const DB_PROGRESS = {
    firstName: 'Andy',
    monthlyInvestment: 800,
    retirementIncome: 48000, // → $4,000/mo plan
    selectedTier: 'pro',
    investmentConfirmationCompleted: true,
    _source: 'db'
  };

  function editInvest(fixture: ComponentFixture<MyProfilePage>, value: string): void {
    fixture.componentInstance.onInvestInput(inputEvent(value).event);
    fixture.detectChanges();
  }

  it('starts with an empty plan (no fabricated defaults) before any progress arrives', () => {
    const { fixture } = setupDom(null);
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(0);
    expect(fixture.componentInstance.retirementIncomeGoal()).toBe(0);
    expect(fixture.componentInstance.freedomGoalSub).toContain('—');  // — placeholders
  });

  it('always applies the FIRST real DB emission even if the user typed pre-seed', () => {
    const { fixture, progress$ } = setupDom(null);
    editInvest(fixture, '700');             // pre-seed edit → dirty vs empty baseline
    expect(fixture.componentInstance.isDirty()).toBeTrue();

    progress$.next(DB_PROGRESS);            // real load lands late
    fixture.detectChanges();
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(800);
    expect(fixture.componentInstance.retirementIncomeGoal()).toBe(4000);
    expect(fixture.componentInstance.isDirty()).toBeFalse();  // baseline is now the DB row
  });

  it('ignores a localStorageFallback emission and still seeds from the later DB one', () => {
    const { fixture, progress$ } = setupDom({ _source: 'localStorageFallback' });
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(0);

    editInvest(fixture, '700');             // edit while only the fallback has emitted

    progress$.next(DB_PROGRESS);
    fixture.detectChanges();
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(800);
    expect(fixture.componentInstance.isDirty()).toBeFalse();
  });

  it('keeps protecting mid-edit values after the first DB seed', () => {
    const { fixture, progress$ } = setupDom(DB_PROGRESS);
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(800);
    editInvest(fixture, '80');              // dirty against a REAL baseline
    progress$.next({ ...DB_PROGRESS });     // background refresh
    fixture.detectChanges();
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(80);
  });

  it('seeds the plan from the schedule equivalent when no goal is saved (flipped-rule fallback)', () => {
    const noGoal = { ...DB_PROGRESS, monthlyInvestment: undefined };
    const { fixture } = setupDom(noGoal, of({ investmentAmount: 100, frequency: 'WEEKLY' }));
    expect(fixture.componentInstance.monthlyInvestGoal()).toBe(433);  // $100 weekly × 4.33
    expect(fixture.componentInstance.isDirty()).toBeFalse();          // seeded, not an edit
  });
});
