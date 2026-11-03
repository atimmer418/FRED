/**
 * my-profile.goals-render.spec.ts: freeze-hunt harness.
 *
 * Renders MyProfilePage with one backend goal and asserts the page renders,
 * stays stable across CD cycles, and remains interactive. Written while
 * debugging the on-device "my-profile dead on arrival with a saved goal"
 * freeze (2026-08-14); rewritten for the Goals v3 summary card.
 */
import { signal } from '@angular/core';
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
import { GoalsService, Goal } from '../../services/goals.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

const YEAR = new Date().getFullYear();

const GOAL: Goal = {
  id: 1,
  clientId: null,
  type: 'CUSTOM',
  name: 'Test goal',
  icon: 'home',
  config: { targetAmount: 20_000, targetYear: YEAR + 4 },
  currentBalance: 500,
  balanceUpdatedAt: null,
  anchorBalance: 500,
  anchorMonthly: 380,
  planRate: 0.10,
  planRateKind: 'market',
  planAnchoredAt: `${YEAR}-01-01T00:00:00`,
  completed: false,
  sortOrder: 0,
  createdAt: '',
  updatedAt: '',
};

const PRO_PROGRESS = {
  monthlyInvestment: 7000,
  retirementIncome: 60000,
  selectedTier: 'pro',
  investmentConfirmationCompleted: true,
};

let refresh: jasmine.Spy;

function setup(goals: Goal[]): ComponentFixture<MyProfilePage> {
  localStorage.setItem('userId', '40');
  refresh = jasmine.createSpy('refresh').and.returnValue(of(goals));
  TestBed.configureTestingModule({
    imports: [MyProfilePage],
    providers: [
      { provide: AuthService, useValue: {
          userProgress$: new BehaviorSubject<any>(PRO_PROGRESS),
          getCurrentInvestmentSchedule: () =>
            of({ investmentAmount: 500, frequency: 'BIWEEKLY' }),
          updateUserProfile: () => of({}),
          loadUserProgress: jasmine.createSpy('loadUserProgress'),
      } },
      { provide: ToastService, useValue: { showToast: jasmine.createSpy() } },
      { provide: PortfolioService, useValue: { getPortfolioDashboard: () => of({ summary: { equity: 10000 } }) } },
      { provide: AccountStatusService, useValue: { actionRequired$: of(false) } },
      { provide: MonthlyFreedomUpdateService, useValue: { checkShouldShow: () => of(null) } },
      { provide: Router, useValue: { navigateByUrl: jasmine.createSpy(), navigate: jasmine.createSpy() } },
      { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
      { provide: AlertController, useValue: {} },
      { provide: ModalController, useValue: {} },
      { provide: FirstTimeTourService, useValue: { suspend: jasmine.createSpy('suspend'), freedomSheetClosed: jasmine.createSpy('freedomSheetClosed') } },
      { provide: GoalsService, useValue: {
          goals: signal(goals),
          load: () => of(goals),
          refresh,
          readLegacyV2: () => [],
          getPrefs: () => ({ rebalanceSchedule: 'Quarterly' }),
      } },
      { provide: FreedomStatsService, useValue: {
          birthYear: () => 0,
          stats: () => ({ freedomYear: '—', freedomAge: '—', dollarsAway: '—' }),
      } },
      // Fire-and-forget here; payload assertions live in my-profile.analytics.spec.ts.
      { provide: AnalyticsService, useValue: { track: () => {}, trackErrorShown: () => {} } },
    ],
  });
  const fixture = TestBed.createComponent(MyProfilePage);
  fixture.detectChanges();
  return fixture;
}

function chipTexts(fixture: ComponentFixture<MyProfilePage>): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('.goal-proj'))
    .map(el => (el as HTMLElement).textContent!.trim());
}

describe('MyProfilePage: goals rendering (freeze hunt)', () => {
  afterEach(() => localStorage.removeItem('userId'));

  it('renders a saved goal row without throwing', () => {
    const fixture = setup([GOAL]);
    const rows = fixture.nativeElement.querySelectorAll('.goal-row');
    expect(rows.length).toBe(2); // Freedom Date row + the goal
    const sub = (fixture.nativeElement.querySelectorAll('.goal-sub')[1]?.textContent ?? '').trim();
    expect(sub).toMatch(new RegExp(`^\\$[\\d,]+/mo · \\$20K by ${YEAR + 4}$`));
  });

  it('survives 25 change-detection cycles without exploding or churning', () => {
    const fixture = setup([GOAL]);
    const rowBefore = fixture.nativeElement.querySelectorAll('.goal-row')[1];
    for (let i = 0; i < 25; i++) fixture.detectChanges();
    const rowAfter = fixture.nativeElement.querySelectorAll('.goal-row')[1];
    expect(rowAfter).toBe(rowBefore); // same DOM node, no ngFor identity churn
  });

  it('renders a completed goal as Done', () => {
    const fixture = setup([{ ...GOAL, id: 2, completed: true }]);
    expect(chipTexts(fixture)).toContain('Done');
  });

  it('shows Started, never Behind, for a goal with no logged balance', () => {
    const fixture = setup([{ ...GOAL, currentBalance: 0, anchorBalance: 0, balanceUpdatedAt: null }]);
    const chips = chipTexts(fixture);
    expect(chips).toContain('Started');
    expect(chips).not.toContain('Behind');
  });

  it('shows Plan updated for an imported goal that has not been planned', () => {
    const fixture = setup([{ ...GOAL, clientId: 'v2-1', anchorMonthly: 0 }]);
    expect(chipTexts(fixture)).toContain('Plan updated');
    expect(fixture.nativeElement.querySelectorAll('.goal-sub')[1].textContent).toContain('Plan updated, take a look');
  });

  it('refreshes goals on ionViewWillEnter', () => {
    const fixture = setup([GOAL]);
    refresh.calls.reset();
    fixture.componentInstance.ionViewWillEnter();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('keeps buttons clickable after rendering goals', () => {
    const fixture = setup([GOAL]);
    const page = fixture.componentInstance;
    spyOn(page, 'addGoal').and.resolveTo();
    spyOn(page, 'openGoal');
    const addBtn = fixture.nativeElement.querySelector('.add-goal-btn') as HTMLButtonElement;
    expect(addBtn).withContext('add-goal button rendered').toBeTruthy();
    addBtn.click();
    expect(page.addGoal).toHaveBeenCalled();
    (fixture.nativeElement.querySelectorAll('.goal-row')[1] as HTMLElement).click();
    expect(page.openGoal).toHaveBeenCalledWith(1);
  });
});
