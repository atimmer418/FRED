/**
 * my-profile.goals-render.spec.ts — freeze-hunt harness.
 *
 * Renders MyProfilePage with a persisted v2 goal (Plus tier) and asserts the
 * page renders, stays stable across CD cycles, and remains interactive.
 * Written while debugging the on-device "my-profile dead on arrival with a
 * saved goal" freeze (2026-08-14).
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
import { GoalsService, Goal } from '../../services/goals.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

const V2_GOAL: Goal = {
  id: 'g-1',
  name: 'Test goal',
  icon: 'home',
  targetAmount: 20000,
  targetYear: new Date().getFullYear() + 4,
  startingValue: 500,
  perPaycheckOutsideFRED: 150,
  completed: false,
};

const PRO_PROGRESS = {
  monthlyInvestment: 7000,
  retirementIncome: 60000,
  selectedTier: 'pro',
  investmentConfirmationCompleted: true,
};

function setup(goals: Goal[]): ComponentFixture<MyProfilePage> {
  localStorage.setItem('userId', '40');
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
      { provide: Router, useValue: { navigateByUrl: jasmine.createSpy() } },
      { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
      { provide: AlertController, useValue: {} },
      { provide: ModalController, useValue: {} },
      { provide: FirstTimeTourService, useValue: { suspend: jasmine.createSpy('suspend'), freedomSheetClosed: jasmine.createSpy('freedomSheetClosed') } },
      { provide: GoalsService, useValue: {
          getGoals: () => goals,
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

describe('MyProfilePage — goals rendering (freeze hunt)', () => {
  afterEach(() => localStorage.removeItem('userId'));

  it('renders a saved v2 goal row without throwing', () => {
    const fixture = setup([V2_GOAL]);
    const rows = fixture.nativeElement.querySelectorAll('.goal-row');
    expect(rows.length).toBe(2); // Freedom Date row + the goal
    const sub = fixture.nativeElement.querySelectorAll('.goal-sub')[1]?.textContent ?? '';
    expect(sub).toContain('by ' + V2_GOAL.targetYear);
  });

  it('survives 25 change-detection cycles without exploding or churning', () => {
    const fixture = setup([V2_GOAL]);
    const rowBefore = fixture.nativeElement.querySelectorAll('.goal-row')[1];
    for (let i = 0; i < 25; i++) fixture.detectChanges();
    const rowAfter = fixture.nativeElement.querySelectorAll('.goal-row')[1];
    expect(rowAfter).toBe(rowBefore); // same DOM node — no ngFor identity churn
  });

  it('renders a completed goal and a no-pace goal without throwing', () => {
    const fixture = setup([
      { ...V2_GOAL, id: 'g-2', completed: true },
      { ...V2_GOAL, id: 'g-3', perPaycheckOutsideFRED: 0 },
    ]);
    const projTexts = Array.from(
      fixture.nativeElement.querySelectorAll('.goal-proj')
    ).map(el => (el as HTMLElement).textContent!.trim());
    expect(projTexts.some(t => t.includes('Done'))).toBeTrue();
    expect(projTexts.some(t => t.includes('no pace set'))).toBeTrue();
  });

  it('keeps buttons clickable after rendering goals', () => {
    const fixture = setup([V2_GOAL]);
    const page = fixture.componentInstance;
    spyOn(page, 'openAddGoalSheet').and.resolveTo();
    const addBtn = fixture.nativeElement.querySelector('.add-goal-btn') as HTMLButtonElement;
    expect(addBtn).withContext('add-goal button rendered').toBeTruthy();
    addBtn.click();
    expect(page.openAddGoalSheet).toHaveBeenCalled();
  });
});
