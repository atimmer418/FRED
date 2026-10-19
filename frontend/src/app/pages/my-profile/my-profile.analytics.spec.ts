/**
 * my-profile.analytics.spec.ts
 *
 * Payload specs for every event this page fires. Each one drives the real UI path (a tapped chip,
 * the header Save button, a sheet dismiss role) and asserts the EXACT props object, so any change
 * to a payload fails here rather than silently changing what lands in the vendor.
 *
 * What is deliberately asserted as ABSENT matters as much as what is present:
 *   - no goal name and no goal id (the only two free-text/identifier fields a goal has),
 *   - no raw dollar amount anywhere (everything is banded),
 *   - no second `freedom_plan_changed`: the sheet owns that event, this page must not double it.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController, AlertController, ModalController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
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
import { AnalyticsAdapter } from '../../services/analytics/analytics-adapter';

const CURRENT_YEAR = new Date().getFullYear();
const USER_ID = '40';

const EXISTING_GOAL: Goal = {
  id: 'g-1',
  name: 'House down payment',
  icon: 'home',
  targetAmount: 60_000,
  targetYear: CURRENT_YEAR + 5,
  startingValue: 5_000,
  perPaycheckOutsideFRED: 275,
  completed: false,
};

/** Plan tiles seed to $7,000 invest / $5,000 income per month. */
const PROGRESS = {
  firstName: 'Andy',
  monthlyInvestment: 7000,
  retirementIncome: 60000,
  investmentConfirmationCompleted: true,
  _source: 'db',
};

interface Options {
  tier?: string;
  goals?: Goal[];
  /** What the next presented modal dismisses with. */
  dismiss?: { role?: string; data?: unknown };
  /** What updateUserProfile returns (use throwError for the failure paths). */
  saveResult?: Observable<unknown>;
}

interface Harness {
  fixture: ComponentFixture<MyProfilePage>;
  page: MyProfilePage;
  track: jasmine.Spy;
  savePrefs: jasmine.Spy;
  deleteGoal: jasmine.Spy;
  updateUserProfile: jasmine.Spy;
}

/** Lets every queued promise (modal create → present → onDidDismiss) settle. */
function flush(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function setup(opts: Options = {}): Harness {
  localStorage.setItem('userId', USER_ID);
  const goals = opts.goals ?? [];
  const dismiss = opts.dismiss ?? { role: 'cancel' };

  // The real service with a spied track(): the helpers (trackErrorShown) are exercised for real,
  // and the spy records the exact event object they build.
  const analytics = new AnalyticsService({} as unknown as AnalyticsAdapter);
  const track = spyOn(analytics, 'track');

  const updateUserProfile = jasmine.createSpy('updateUserProfile')
    .and.returnValue(opts.saveResult ?? of({}));
  const savePrefs = jasmine.createSpy('savePrefs');
  const deleteGoal = jasmine.createSpy('deleteGoal');

  TestBed.configureTestingModule({
    imports: [MyProfilePage],
    providers: [
      { provide: AuthService, useValue: {
          userProgress$: new BehaviorSubject<any>({ ...PROGRESS, selectedTier: opts.tier ?? 'pro' }),
          getCurrentInvestmentSchedule: () => of(null),
          updateUserProfile,
          loadUserProgress: jasmine.createSpy('loadUserProgress'),
      } },
      { provide: ToastService, useValue: { showToast: jasmine.createSpy('showToast') } },
      { provide: PortfolioService, useValue: { getPortfolioDashboard: () => of({ summary: { equity: 0 } }) } },
      { provide: AccountStatusService, useValue: { actionRequired$: of(false) } },
      { provide: MonthlyFreedomUpdateService, useValue: { checkShouldShow: () => of(null) } },
      { provide: Router, useValue: { navigateByUrl: jasmine.createSpy() } },
      { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
      { provide: AlertController, useValue: {} },
      { provide: ModalController, useValue: {
          create: jasmine.createSpy('create').and.returnValue(Promise.resolve({
            present: () => Promise.resolve(),
            onDidDismiss: () => Promise.resolve(dismiss),
          })),
      } },
      { provide: FirstTimeTourService, useValue: {
          suspend: jasmine.createSpy('suspend'),
          freedomSheetClosed: jasmine.createSpy('freedomSheetClosed'),
      } },
      { provide: GoalsService, useValue: {
          getGoals: () => goals,
          getPrefs: () => ({ rebalanceSchedule: 'Quarterly' }),
          addGoal: (_uid: string, goal: Omit<Goal, 'id'>) => ({ ...goal, id: 'g-new' }),
          updateGoal: jasmine.createSpy('updateGoal'),
          deleteGoal,
          savePrefs,
      } },
      { provide: FreedomStatsService, useValue: {
          birthYear: () => 0,
          stats: () => ({ freedomYear: '—', freedomAge: '—', dollarsAway: '—' }),
      } },
      { provide: AnalyticsService, useValue: analytics },
    ],
  });

  const fixture = TestBed.createComponent(MyProfilePage);
  fixture.detectChanges();
  return { fixture, page: fixture.componentInstance, track, savePrefs, deleteGoal, updateUserProfile };
}

/** A native input event carrying a raw value, as the tile handlers receive it. */
function inputEvent(value: string): Event {
  const input = document.createElement('input');
  input.value = value;
  return { target: input } as unknown as Event;
}

/** Every props object the page sent, for the "nothing identifying, ever" assertions. */
function allProps(track: jasmine.Spy): Record<string, unknown>[] {
  return track.calls.allArgs().map((args) => args[0].props);
}

/**
 * Registered per suite rather than at the file root: a root-level afterEach would run after every
 * spec in the whole Karma run and clear state other specs own.
 */
function cleanup(): void {
  localStorage.removeItem('userId');
  localStorage.removeItem(`fred.rateAssumptions.v1.${USER_ID}`);
}

// ─────────────────────────────────────────────────────────────────────────────
// goal_changed — the five actions, all reported from the sheet's dismiss role.
// ─────────────────────────────────────────────────────────────────────────────

describe('MyProfilePage analytics — goal_changed', () => {
  afterEach(cleanup);

  it('reports a created goal with bands and the resulting count, never the name', async () => {
    const newGoal: Omit<Goal, 'id'> = {
      name: 'Dream vacation',
      icon: 'flag',
      targetAmount: 20_000,
      targetYear: CURRENT_YEAR + 4,
      startingValue: 5_000,
      perPaycheckOutsideFRED: 150,
      completed: false,
    };
    const h = setup({ dismiss: { role: 'saved', data: newGoal } });

    await h.page.openAddGoalSheet();

    expect(h.track).toHaveBeenCalledTimes(1);
    expect(h.track).toHaveBeenCalledWith({
      name: 'goal_changed',
      props: {
        action: 'created',
        icon: 'flag',
        target_band: '10k_49k',
        years_to_target: '3_5',
        progress: '25_49',       // $5,000 of $20,000
        goal_count: '1',         // counted AFTER the change
      },
    });
    const sent = JSON.stringify(allProps(h.track));
    expect(sent).not.toContain('Dream vacation');
    expect(sent).not.toContain('g-new');
  });

  it('reports an edited goal, with the id left on the device', async () => {
    const edited: Goal = { ...EXISTING_GOAL, targetAmount: 120_000, startingValue: 90_000 };
    const h = setup({ goals: [EXISTING_GOAL], dismiss: { role: 'updated', data: edited } });

    await h.page.openAddGoalSheet(EXISTING_GOAL);

    expect(h.track).toHaveBeenCalledWith({
      name: 'goal_changed',
      props: {
        action: 'updated',
        icon: 'home',
        target_band: '50k_249k',
        years_to_target: '3_5',
        progress: '75_99',       // $90,000 of $120,000
        goal_count: '1',
      },
    });
    expect(JSON.stringify(allProps(h.track))).not.toContain('g-1');
  });

  // One TestBed per spec: setup() configures the testing module, so calling it twice in a single
  // spec throws "Cannot configure the test module when the test module has already been
  // instantiated". The two sides of the toggle are therefore two specs.
  it('reports the completed side of the toggle', async () => {
    const h = setup({
      goals: [EXISTING_GOAL],
      dismiss: { role: 'completed', data: { ...EXISTING_GOAL, completed: true } },
    });

    await h.page.openAddGoalSheet(EXISTING_GOAL);

    expect(h.track).toHaveBeenCalledWith({
      name: 'goal_changed',
      props: {
        action: 'completed',
        icon: 'home',
        target_band: '50k_249k',
        years_to_target: '3_5',
        progress: '0_9',         // $5,000 of $60,000
        goal_count: '1',
      },
    });
  });

  it('reports the reopened side of the same toggle', async () => {
    const done: Goal = { ...EXISTING_GOAL, completed: true };
    const h = setup({
      goals: [done],
      dismiss: { role: 'completed', data: { ...done, completed: false } },
    });

    await h.page.openAddGoalSheet(done);

    expect(h.track).toHaveBeenCalledWith({
      name: 'goal_changed',
      props: {
        action: 'reopened',
        icon: 'home',
        target_band: '50k_249k',
        years_to_target: '3_5',
        progress: '0_9',
        goal_count: '1',
      },
    });
  });

  it('reports a removed goal from the goal it read before deleting it', async () => {
    const h = setup({ goals: [EXISTING_GOAL], dismiss: { role: 'deleted', data: EXISTING_GOAL.id } });

    await h.page.openAddGoalSheet(EXISTING_GOAL);

    expect(h.deleteGoal).toHaveBeenCalledWith(USER_ID, 'g-1');
    expect(h.track).toHaveBeenCalledWith({
      name: 'goal_changed',
      props: {
        action: 'removed',
        icon: 'home',
        target_band: '50k_249k',
        years_to_target: '3_5',
        progress: '0_9',
        goal_count: '0',         // the count the user is left with
      },
    });
  });

  it('sends nothing when the goal sheet is cancelled', async () => {
    const h = setup({ goals: [EXISTING_GOAL], dismiss: { role: 'cancel' } });

    await h.page.openAddGoalSheet(EXISTING_GOAL);

    expect(h.track).not.toHaveBeenCalled();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// paywall_nudge_shown — presented, upgraded, dismissed. Billing is untouched.
// ─────────────────────────────────────────────────────────────────────────────

describe('MyProfilePage analytics — paywall_nudge_shown', () => {
  afterEach(cleanup);

  it('reports the goals veil nudge as shown, then dismissed', async () => {
    const h = setup({ tier: 'core', dismiss: { role: undefined } });

    await h.page.openGoalsVeilNudge();

    expect(h.track.calls.allArgs().map((a) => a[0])).toEqual([
      { name: 'paywall_nudge_shown', props: { feature: 'goals', tier: 'core', action: 'shown' } },
      { name: 'paywall_nudge_shown', props: { feature: 'goals', tier: 'core', action: 'dismissed' } },
    ]);
  });

  it('reports the rebalancing gate from a tapped chip, and upgrade_tapped on the CTA', async () => {
    const h = setup({ tier: 'core', dismiss: { role: 'upgrade' } });

    const chips = h.fixture.nativeElement.querySelectorAll('.rb-chips button');
    (chips[2] as HTMLButtonElement).click();              // "Annual" on a gated tier
    await flush();

    expect(h.track.calls.allArgs().map((a) => a[0])).toEqual([
      { name: 'paywall_nudge_shown', props: { feature: 'rebalancing', tier: 'core', action: 'shown' } },
      { name: 'paywall_nudge_shown', props: { feature: 'rebalancing', tier: 'core', action: 'upgrade_tapped' } },
    ]);
    // Analytics only: the gate still refuses the change, and no setting was saved.
    expect(h.page.selectedSchedule).toBe('Quarterly');
    expect(h.savePrefs).not.toHaveBeenCalled();
  });

  it('reports the tax-loss-harvesting nudge with the tier the user actually has', async () => {
    const h = setup({ tier: 'plus', dismiss: { role: undefined } });

    await h.page.openTlhProNudge();

    expect(h.track.calls.allArgs().map((a) => a[0])).toEqual([
      { name: 'paywall_nudge_shown', props: { feature: 'tax_loss_harvesting', tier: 'plus', action: 'shown' } },
      { name: 'paywall_nudge_shown', props: { feature: 'tax_loss_harvesting', tier: 'plus', action: 'dismissed' } },
    ]);
  });

  it('sends a null tier when the user has none', async () => {
    const h = setup({ tier: '', dismiss: { role: undefined } });

    await h.page.openGoalsVeilNudge();

    expect(h.track.calls.first().args[0].props.tier).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// setting_changed — the rebalance schedule chips.
// ─────────────────────────────────────────────────────────────────────────────

describe('MyProfilePage analytics — setting_changed', () => {
  afterEach(cleanup);

  it('reports the chosen schedule as a slug when a chip is tapped', () => {
    const h = setup({ tier: 'pro' });

    const chips = h.fixture.nativeElement.querySelectorAll('.rb-chips button');
    (chips[1] as HTMLButtonElement).click();             // "Semi-annual"

    expect(h.savePrefs).toHaveBeenCalledWith(USER_ID, { rebalanceSchedule: 'Semi-annual' });
    expect(h.track).toHaveBeenCalledTimes(1);
    expect(h.track).toHaveBeenCalledWith({
      name: 'setting_changed',
      props: {
        setting: 'rebalance_schedule',
        enabled: null,
        value: 'semi_annual',
        outcome: 'applied',
      },
    });
  });

  it('slugs the single-word schedules too', () => {
    const h = setup({ tier: 'pro' });

    const chips = h.fixture.nativeElement.querySelectorAll('.rb-chips button');
    (chips[2] as HTMLButtonElement).click();             // "Annual"

    expect(h.track.calls.mostRecent().args[0].props.value).toBe('annual');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// error_shown — the page's two visible failures. Never the message.
// ─────────────────────────────────────────────────────────────────────────────

describe('MyProfilePage analytics — error_shown', () => {
  afterEach(cleanup);

  /** The header Save button only exists while the plan is dirty. */
  function tapSave(h: Harness): void {
    h.fixture.detectChanges();
    (h.fixture.nativeElement.querySelector('.hero-action-btn') as HTMLButtonElement).click();
  }

  it('reports the "enter valid amounts" warning as a validation toast', () => {
    const h = setup({ tier: 'pro' });
    h.page.onIncomeInput(inputEvent(''));                // emptied → dirty but incomplete

    tapSave(h);

    expect(h.updateUserProfile).not.toHaveBeenCalled();
    expect(h.track).toHaveBeenCalledTimes(1);
    expect(h.track).toHaveBeenCalledWith({
      name: 'error_shown',
      props: {
        category: 'validation',
        surface: 'my_profile',
        display: 'toast',
        http_status: null,
        retry_available: false,
      },
    });
  });

  it('reports a rejected save as save_failed, with the status and nothing else', () => {
    const h = setup({
      tier: 'pro',
      saveResult: throwError(() => new HttpErrorResponse({
        status: 500,
        error: { message: 'Andy Timmer: constraint violation on users.retirement_income' },
      })),
    });
    h.page.onInvestInput(inputEvent('700'));             // dirty and valid

    tapSave(h);

    expect(h.track).toHaveBeenCalledWith({
      name: 'error_shown',
      props: {
        category: 'save_failed',
        surface: 'my_profile',
        display: 'toast',
        http_status: 500,
        retry_available: true,                           // the Save button is still there
      },
    });
    expect(JSON.stringify(allProps(h.track))).not.toContain('constraint');
  });

  it('calls a request that never reached a server a network failure, with no status', () => {
    const h = setup({
      tier: 'pro',
      saveResult: throwError(() => new HttpErrorResponse({ status: 0 })),
    });
    h.page.onInvestInput(inputEvent('700'));

    tapSave(h);

    expect(h.track).toHaveBeenCalledWith({
      name: 'error_shown',
      props: {
        category: 'network',
        surface: 'my_profile',
        display: 'toast',
        http_status: null,
        retry_available: true,
      },
    });
  });

  it('sends nothing when the save succeeds', () => {
    const h = setup({ tier: 'pro' });
    h.page.onInvestInput(inputEvent('700'));

    tapSave(h);

    expect(h.updateUserProfile).toHaveBeenCalled();
    expect(h.track).not.toHaveBeenCalled();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// freedom_plan_changed belongs to the sheet. This page must not double it.
// ─────────────────────────────────────────────────────────────────────────────

describe('MyProfilePage analytics — the freedom sheet is counted once', () => {
  afterEach(cleanup);

  it('persists a saved plan without firing a second freedom_plan_changed', async () => {
    const h = setup({
      tier: 'pro',
      dismiss: { role: 'saved', data: { income: 4000, invest: 3000, annualReturn: 0.08, withdrawalRate: 0.04 } },
    });

    await h.page.openFreedomSheet();

    expect(h.updateUserProfile).toHaveBeenCalledWith({ monthlyInvestment: 3000, retirementIncome: 48000 });
    expect(h.track.calls.allArgs().map((a) => a[0].name)).not.toContain('freedom_plan_changed');
  });

  it('applies a rates-only change without firing anything from the page', async () => {
    const h = setup({
      tier: 'pro',
      // The plan the page already holds ($5,000 income / $7,000 invest) with a new preset.
      dismiss: { role: 'saved', data: { income: 5000, invest: 7000, annualReturn: 0.12, withdrawalRate: 0.04 } },
    });

    await h.page.openFreedomSheet();

    expect(h.updateUserProfile).not.toHaveBeenCalled();   // nothing dirty, no server write
    expect(h.track).not.toHaveBeenCalled();
  });
});
