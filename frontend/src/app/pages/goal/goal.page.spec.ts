import { CUSTOM_ELEMENTS_SCHEMA, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { GOAL_PAGE_NOW, GoalPage } from './goal.page';
import { AuthService } from '../../services/auth.service';
import { GoalsService } from '../../services/goals.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { CASH_RATE, RateAssumptionsService } from '../../services/rate-assumptions.service';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalRemoveConfirmSheetComponent } from '../../components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component';
import { toCountBand } from '../../services/analytics/analytics-bands';
import { Goal, GoalWrite, KidsConfig, VacationConfig } from '../../models/goal.model';

function houseGoal(id: number, completed = false): Goal {
  return {
    id, clientId: null, type: 'HOUSE', name: 'First home', icon: 'home',
    config: { price: 400_000, downPaymentPct: 20, targetYear: YEAR + 5 },
    currentBalance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0,
    planRate: 0.07, planRateKind: 'market', planAnchoredAt: '2026-01-01',
    completed, sortOrder: 0, createdAt: '', updatedAt: '',
  };
}

/** Local ISO timestamp without a Z suffix, the shape the backend sends. */
function localIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:00`;
}

/** The page's clock in every spec: monthsOut and the fixtures count from the same instant. */
const NOW = new Date();
const YEAR = NOW.getFullYear();

function kidsGoalFixture(id: number, birthYear: number | null): Goal {
  const fresh = { balance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 };
  const config: KidsConfig = {
    plan529Target: 100_000,
    brokerageTarget: 250_000,
    kids: [{
      label: 'Kid 1', birthYear, birthMonth: birthYear == null ? null : 5,
      accounts: { plan529: { ...fresh }, brokerage: { ...fresh } },
    }],
  };
  return { ...houseGoal(id), type: 'KIDS', name: 'Our kids', icon: 'child_care', config };
}

const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** The month `n` months from this one (negative for past months). */
function monthsOut(n: number): { year: number; month: number } {
  const d = NOW;
  const total = d.getFullYear() * 12 + d.getMonth() + n;
  return { year: Math.floor(total / 12), month: (total % 12) + 1 };
}

function monLabel(m: { year: number; month: number }): string {
  return `${MON[m.month - 1]} ${m.year}`;
}

function usd(n: number): string {
  return '$' + Math.round(n).toLocaleString('en-US');
}

interface FundKid {
  label: string;
  birthYear: number | null;
  birthMonth: number | null;
  due?: { year: number; month: number };
  target?: number;
  balance?: number;
  logged?: string | null;
}

/** A stored kids goal anchored now, each kid with a `due` holding a first-year fund. */
function fundKidsGoal(
  id: number,
  kids: FundKid[],
  opts: { plan529Target?: number; brokerageTarget?: number; anchoredAt?: string } = {},
): Goal {
  const fresh = (balance = 0, logged: string | null = null) =>
    ({ balance, balanceUpdatedAt: logged, anchorBalance: balance, anchorMonthly: 0 });
  const config: KidsConfig = {
    plan529Target: opts.plan529Target ?? 0,
    brokerageTarget: opts.brokerageTarget ?? 0,
    firstYearFund: kids.some(k => !!k.due),
    kids: kids.map(k => ({
      label: k.label,
      birthYear: k.birthYear,
      birthMonth: k.birthMonth,
      firstYearTarget: k.due ? (k.target ?? 25_700) : null,
      firstYearDueYear: k.due?.year ?? null,
      firstYearDueMonth: k.due?.month ?? null,
      accounts: {
        plan529: fresh(),
        brokerage: fresh(),
        firstYear: k.due ? fresh(k.balance ?? 0, k.logged ?? null) : null,
      },
    })),
  };
  return {
    ...houseGoal(id), type: 'KIDS', name: 'Our kids', icon: 'child_care', config,
    planAnchoredAt: opts.anchoredAt ?? localIso(NOW), anchorMonthly: 500,
  };
}

function vacationGoalFixture(id: number): Goal {
  const config: VacationConfig = { tripsPerYear: 2, budgetPerTrip: 3_000, savedThisYear: 0, savedYear: YEAR };
  return { ...houseGoal(id), type: 'VACATION', name: 'Trips', icon: 'flight', config, planRateKind: 'cash', planRate: 0.04 };
}

describe('GoalPage', () => {
  let navCtrl: jasmine.SpyObj<NavController>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let router: jasmine.SpyObj<Router>;
  let goals: ReturnType<typeof signal<Goal[]>>;
  let load: jasmine.Spy;
  let createSpy: jasmine.Spy;
  let updateSpy: jasmine.Spy;
  let removeSpy: jasmine.Spy;
  let showToast: jasmine.Spy;
  let trackErrorShown: jasmine.Spy;
  let track: jasmine.Spy;
  let logout: jasmine.Spy;
  let progress: BehaviorSubject<{ selectedTier: string } | null>;

  async function create(
    params: Record<string, string>,
    opts: {
      goals?: Goal[]; loaded?: Goal[]; tier?: string; noProgress?: boolean; dismissRole?: string;
      createResult?: Observable<Goal>; updateResult?: Observable<Goal>; loadError?: unknown;
    } = {},
  ): Promise<ComponentFixture<GoalPage>> {
    goals = signal<Goal[]>(opts.goals ?? []);
    load = jasmine.createSpy('load').and.callFake(() => {
      if (opts.loadError) return throwError(() => opts.loadError);
      if (opts.loaded) goals.set(opts.loaded);
      return of(goals());
    });
    createSpy = jasmine.createSpy('create').and.callFake((body: GoalWrite) =>
      opts.createResult ?? of({ ...houseGoal(99), ...body } as Goal));
    updateSpy = jasmine.createSpy('update').and.callFake((id: number, body: GoalWrite) =>
      opts.updateResult ?? of({ ...(goals().find(g => g.id === id) ?? houseGoal(id)), ...body } as Goal));
    removeSpy = jasmine.createSpy('remove').and.returnValue(of(undefined));
    showToast = jasmine.createSpy('showToast');
    trackErrorShown = jasmine.createSpy('trackErrorShown');
    track = jasmine.createSpy('track');
    logout = jasmine.createSpy('logout');
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    progress = new BehaviorSubject<{ selectedTier: string } | null>(opts.noProgress ? null : { selectedTier: opts.tier ?? 'plus' });
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo({
      present: async () => {},
      onDidDismiss: async () => ({ role: opts.dismissRole }),
    } as unknown as HTMLIonModalElement);

    await TestBed.configureTestingModule({
      imports: [GoalPage],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(params) } } },
        { provide: NavController, useValue: navCtrl },
        { provide: Router, useValue: router },
        { provide: ModalController, useValue: modalCtrl },
        { provide: GoalsService, useValue: { goals, load, create: createSpy, update: updateSpy, remove: removeSpy } },
        { provide: AuthService, useValue: {
            userProgress$: progress,
            getCurrentInvestmentSchedule: () => of({ investmentAmount: 500, frequency: 'BIWEEKLY' }),
            logout,
        } },
        { provide: ToastService, useValue: { showToast } },
        { provide: AnalyticsService, useValue: { track, trackErrorShown } },
        { provide: GOAL_PAGE_NOW, useValue: () => NOW },
      ],
    })
      // Ionic standalone components need their own providers; render their children as plain DOM.
      .overrideComponent(GoalPage, {
        set: { imports: [CommonModule, FormsModule], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
      })
      .compileComponents();

    const fixture = TestBed.createComponent(GoalPage);
    fixture.detectChanges(); // runs ngOnInit once
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('reads the type from the route and starts on step 1', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    expect(page.mode()).toBe('setup');
    expect(page.step()).toBe(1);
    expect(page.form().type).toBe('KIDS');
    expect(page.form().name).toBe('Our kids');
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();
    // KIDS opens the quiz on its first card.
    expect(fixture.nativeElement.querySelector('.gl-q').textContent).toContain('How many kids are you planning for?');
    expect(fixture.nativeElement.querySelector('.gl-cta').textContent).toContain('Next');
  });

  it('navigates back for an unknown type or a non-numeric id', async () => {
    await create({ type: 'BOAT' });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    TestBed.resetTestingModule();

    await create({ id: 'abc' });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    TestBed.resetTestingModule();

    await create({ id: '0' });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('loads goals before rendering a deep-linked goal', async () => {
    const fixture = await create({ id: '7' }, { goals: [], loaded: [houseGoal(7)] });
    const page = fixture.componentInstance;
    expect(load).toHaveBeenCalled();
    expect(page.mode()).toBe('edit');
    expect(page.form().type).toBe('HOUSE');
    expect(page.form().price).toBe(400_000);
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();
    TestBed.resetTestingModule();

    // Still absent after the load: go back.
    await create({ id: '9' }, { goals: [], loaded: [houseGoal(7)] });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('moves to step 2 only when the form validates', async () => {
    const fixture = await create({ type: 'WEDDING' });
    const page = fixture.componentInstance;
    page.showPlan();
    expect(page.step()).toBe(1);
    expect(page.errors()['targetAmount']).toBe('Enter an amount greater than $0.');

    page.patch({ targetAmount: 25_000 });
    expect(page.errors()['targetAmount']).toBeUndefined();
    page.showPlan();
    expect(page.step()).toBe(2);
  });

  it('bounds the kid stepper at 1 and 10', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.removeKid(0);
    expect(page.form().kids.kids.length).toBe(1);
    for (let i = 0; i < 15; i++) page.addKid();
    expect(page.form().kids.kids.length).toBe(10);
    for (let i = 0; i < 15; i++) page.removeKid(page.form().kids.kids.length - 1);
    expect(page.form().kids.kids.length).toBe(1);
  });

  it('lets Something else pick an icon and hides the icon row for other types', async () => {
    const custom = await create({ type: 'CUSTOM' });
    expect(custom.nativeElement.querySelectorAll('.gl-icon-btn').length).toBe(7);
    custom.componentInstance.setIcon('savings');
    expect(custom.componentInstance.form().icon).toBe('savings');
    TestBed.resetTestingModule();

    const house = await create({ type: 'HOUSE' });
    expect(house.nativeElement.querySelector('.gl-icons')).toBeNull();
  });

  it('opens the Plus nudge and skips the form for a core user with one active goal', async () => {
    const fixture = await create({ type: 'HOUSE' }, { goals: [houseGoal(1)], tier: 'core' });
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const args = modalCtrl.create.calls.mostRecent().args[0];
    expect(args.component).toBe(McInfoSheetComponent);
    expect(args.componentProps).toEqual(jasmine.objectContaining({
      mode: 'nudge',
      nudgeTitle: 'Plan more than one goal with Piggy Plus',
      nudgeCtaLabel: 'Upgrade to Piggy Plus',
      targetTier: 'plus',
    }));
    expect(fixture.componentInstance.ready()).toBeFalse();
    expect(fixture.nativeElement.querySelector('.gl-card')).toBeNull();
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('still navigates back when the nudge is dismissed with the upgrade role', async () => {
    spyOn(console, 'log');
    await create({ type: 'HOUSE' }, { goals: [houseGoal(1)], tier: 'core', dismissRole: 'upgrade' });
    expect(console.log).toHaveBeenCalledWith('[GoalPage] Plus upgrade requested');
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('fails open and renders the form when user progress never arrives', async () => {
    const fixture = await create({ type: 'HOUSE' }, { goals: [houseGoal(1)], noProgress: true });
    expect(modalCtrl.create).not.toHaveBeenCalled();
    expect(fixture.componentInstance.ready()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.gl-card')).not.toBeNull();
  }, 10000); // waits the real 3 s progress timeout

  // ── Plan view and save paths ───────────────────────────────────────────────

  /** Set up a WEDDING goal and move to step 2. */
  async function weddingPlan(opts: Parameters<typeof create>[1] = {}): Promise<ComponentFixture<GoalPage>> {
    const fixture = await create({ type: 'WEDDING' }, opts);
    const page = fixture.componentInstance;
    page.patch({ targetAmount: 60_000, targetYear: YEAR + 6 });
    page.showPlan();
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<GoalPage>, selector?: string): string {
    const root: HTMLElement = fixture.nativeElement;
    const el = selector ? root.querySelector(selector) : root;
    return (el?.textContent ?? '').replace(/\s+/g, ' ');
  }

  it('shows the plan hero after step 1 and recomputes on a rate chip tap', async () => {
    const fixture = await weddingPlan();
    const page = fixture.componentInstance;
    expect(page.step()).toBe(2);
    expect(fixture.nativeElement.querySelector('.gl-hero')).not.toBeNull();
    expect(text(fixture, '.gl-hero')).toContain('a paycheck');
    expect(text(fixture)).toContain('Return assumption, shared with your Freedom Date');

    const before = page.view().monthlyTotal;
    const chips = fixture.nativeElement.querySelectorAll('.fd-scenario-chips button');
    expect(chips.length).toBe(3);
    (chips[2] as HTMLButtonElement).click(); // Optimistic (12%)
    fixture.detectChanges();
    expect(page.view().monthlyTotal).toBeLessThan(before);
    expect(text(fixture, '.gl-hero')).toContain(page.view().monthlyTotal.toLocaleString('en-US'));
  });

  it('writes the shared rate only on save', async () => {
    const fixture = await weddingPlan();
    const rates = TestBed.inject(RateAssumptionsService);
    // Stubbed: the real setRates persists to localStorage and could leak 12% into later specs.
    const setRates = spyOn(rates, 'setRates');
    const page = fixture.componentInstance;

    page.selectRate(0.12);
    fixture.detectChanges();
    expect(setRates).not.toHaveBeenCalled();

    await page.save();
    expect(setRates).toHaveBeenCalledOnceWith(0.12, rates.withdrawalRate());
    expect(createSpy).toHaveBeenCalledTimes(1);
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(body.planRate).toBe(0.12);
    expect(body.planRateKind).toBe('market');
    expect(body.anchorMonthly).toBe(page.view().monthlyTotal);
    expect(showToast).toHaveBeenCalledWith('Plan started', 'success');
    expect(router.navigate).toHaveBeenCalledWith(['/my-profile']);
  });

  it('writes each kid account anchorMonthly from the proposed plan on save', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.patchKids({ plan529Target: 100_000, brokerageTarget: 250_000 });
    page.addKid();
    page.patchKid(0, { undecided: false, birthYear: YEAR - 3, birthMonth: 4 });
    page.showPlan();
    fixture.detectChanges();
    expect(page.step()).toBe(2);

    await page.save();
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    const config = body.config as KidsConfig;
    const view = page.view();
    const p529 = view.kids!.find(k => k.account === 'plan529')!;
    const brokerage = view.kids!.find(k => k.account === 'brokerage')!;
    expect(config.kids.length).toBe(2);
    config.kids.forEach((kid, i) => {
      expect(kid.accounts.plan529.anchorMonthly).toBe(p529.perKid[i].plan.monthly);
      expect(kid.accounts.brokerage.anchorMonthly).toBe(brokerage.perKid[i].plan.monthly);
      expect(kid.accounts.plan529.balance).toBe(0);
    });
    expect(p529.perKid[0].plan.monthly).toBeGreaterThan(0);
    expect(body.currentBalance).toBe(0);
    expect(body.anchorMonthly).toBe(view.monthlyTotal);
  });

  it('sends a starting balance typed in setup step 2 with the POST', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    page.patch({ price: 400_000, targetYear: YEAR + 6 });
    page.showPlan();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#gl-bal-current') as HTMLInputElement;
    expect(input).withContext('balance tile shown in setup').not.toBeNull();
    input.value = '12000';
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(updateSpy).not.toHaveBeenCalled(); // no goal yet: draft state only
    expect(page.draftGoal().currentBalance).toBe(12_000);

    await page.save();
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(body.currentBalance).toBe(12_000);
  });

  it('plans a teen account as cash and a newborn as market, and saves planRateKind market', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.patchKids({ plan529Target: 100_000, brokerageTarget: 50_000 });
    page.patchKid(0, { undecided: false, birthYear: YEAR - 16, birthMonth: 6 }); // under 36 months to 18
    page.addKid();                                                                // undecided
    page.selectRate(0.12);
    page.showPlan();
    fixture.detectChanges();

    for (const acct of page.view().kids!) {
      expect(acct.perKid[0].plan.rate).toBe(CASH_RATE);
      expect(acct.perKid[1].plan.rate).toBe(0.12);
    }
    await page.save();
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(body.planRateKind).toBe('market');
  });

  it('keeps the form and opens the nudge on a 403 TIER_LIMIT', async () => {
    const err = new HttpErrorResponse({ status: 403, error: { code: 'TIER_LIMIT', message: 'One active goal on Core.' } });
    const fixture = await weddingPlan({ createResult: throwError(() => err) });
    const page = fixture.componentInstance;
    page.patch({ name: 'Big day' });

    await page.save();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    expect(modalCtrl.create.calls.mostRecent().args[0].component).toBe(McInfoSheetComponent);
    expect(showToast).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();
    expect(page.step()).toBe(2);
    expect(page.form().name).toBe('Big day');
  });

  it('on 401 keeps the form, toasts, and calls logout', async () => {
    const err = new HttpErrorResponse({ status: 401 });
    const fixture = await weddingPlan({ createResult: throwError(() => err) });
    const page = fixture.componentInstance;

    await page.save();
    expect(showToast).toHaveBeenCalledWith('Your session ended. Sign in again to save this plan.', 'danger');
    expect(logout).toHaveBeenCalled();
    expect(page.step()).toBe(2);
    expect(page.form().targetAmount).toBe(60_000);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(trackErrorShown).not.toHaveBeenCalled();
  });

  it('toasts and reports any other save failure', async () => {
    const err = new HttpErrorResponse({ status: 500 });
    const fixture = await weddingPlan({ createResult: throwError(() => err) });
    await fixture.componentInstance.save();
    expect(showToast).toHaveBeenCalledWith('Could not save this goal. Try again.', 'danger');
    expect(trackErrorShown).toHaveBeenCalledWith('save_failed', 'goals', 'toast', 500, true);
  });

  it('renders relative copy for an undecided kid', async () => {
    const fixture = await create({ id: '5' }, { goals: [kidsGoalFixture(5, null)] });
    const page = fixture.componentInstance;
    expect(page.step()).toBe(2);
    page.toggleKidDetails('plan529');
    fixture.detectChanges();

    const card = fixture.nativeElement.querySelector('.gl-kid-card') as HTMLElement;
    expect(card).not.toBeNull();
    const cardText = card.textContent!.replace(/\s+/g, ' ');
    expect(cardText).toContain('At 18');
    expect(cardText).toContain('At 22');
    expect(cardText).toContain('At 30');
    expect(cardText).not.toMatch(/\b\d{4}\b/);
    expect(text(fixture)).toContain('A kid with no date yet is planned as if born this year, so you save a little more than you may need.');
  });

  it('hides Mark completed for a vacation goal and writes savedYear on a balance edit', async () => {
    const goal = vacationGoalFixture(6);
    const fixture = await create({ id: '6' }, { goals: [goal] });
    const page = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('.gl-complete')).toBeNull();
    expect(fixture.nativeElement.querySelector('.gl-remove')).not.toBeNull();

    await page.updateBalance('vacation', 1_200);
    expect(updateSpy).toHaveBeenCalledTimes(1);
    const [id, body] = updateSpy.calls.mostRecent().args as [number, GoalWrite];
    expect(id).toBe(6);
    const config = body.config as VacationConfig;
    expect(config.savedThisYear).toBe(1_200);
    expect(config.savedYear).toBe(YEAR);
    expect(body.anchorMonthly).toBe(goal.anchorMonthly);
  });

  it('prompts to update a balance logged more than 60 days ago', async () => {
    const logged = localIso(new Date(NOW.getTime() - 90 * 86_400_000));
    const goal: Goal = {
      ...houseGoal(8), currentBalance: 10_000, anchorBalance: 10_000, anchorMonthly: 500,
      planAnchoredAt: logged, balanceUpdatedAt: logged,
    };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    expect(['ahead', 'on_plan', 'behind']).toContain(fixture.componentInstance.view().status.kind);
    expect(text(fixture)).toContain('Update your balance');
    expect(fixture.nativeElement.querySelector('.gl-complete')).not.toBeNull();
    TestBed.resetTestingModule();

    // A fresh log has no prompt.
    const fresh = localIso(NOW);
    const fixture2 = await create({ id: '8' }, { goals: [{ ...goal, planAnchoredAt: fresh, balanceUpdatedAt: fresh }] });
    expect(text(fixture2)).not.toContain('Update your balance');
  });

  it('confirms before removing', async () => {
    const kept = await create({ id: '7' }, { goals: [houseGoal(7)] });
    await kept.componentInstance.confirmRemove();
    expect(modalCtrl.create.calls.mostRecent().args[0].component).toBe(GoalRemoveConfirmSheetComponent);
    expect(removeSpy).not.toHaveBeenCalled();
    TestBed.resetTestingModule();

    const fixture = await create({ id: '7' }, { goals: [houseGoal(7)], dismissRole: 'remove' });
    await fixture.componentInstance.confirmRemove();
    expect(modalCtrl.create.calls.mostRecent().args[0].componentProps).toEqual({ goalName: 'First home' });
    expect(removeSpy).toHaveBeenCalledWith(7);
    expect(showToast).toHaveBeenCalledWith('Goal removed', 'success');
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('tracks goal_changed with goal_type and plan_rate and no raw amounts', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    page.patch({ price: 400_000, fullPrice: false, downPaymentPct: 20, targetYear: YEAR + 5 });
    page.showPlan();
    page.selectRate(0.1);
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    // The real service appends the created goal to the signal; the mock does not.
    createSpy.and.callFake((body: GoalWrite) => {
      const created = { ...houseGoal(99), ...body } as Goal;
      goals.update(list => [...list, created]);
      return of(created);
    });
    await page.save();
    const events = track.calls.allArgs().map(a => a[0]).filter(e => e.name === 'goal_changed');
    expect(events).toEqual([{
      name: 'goal_changed',
      props: {
        action: 'created', goal_type: 'HOUSE', icon: 'home', plan_rate: '10',
        target_band: '50k_249k', years_to_target: '3_5', progress: '0_9', goal_count: '1',
      },
    }]);
  });

  it('reports cash as the plan rate for an emergency fund', async () => {
    const fixture = await create({ type: 'EMERGENCY_FUND' });
    const page = fixture.componentInstance;
    page.patch({ monthlyExpenses: 3_000, months: 6, targetYear: YEAR + 2 });
    page.showPlan();
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    await page.save();
    const ev = track.calls.allArgs().map(a => a[0]).find(e => e.name === 'goal_changed');
    expect(ev.props.plan_rate).toBe('cash');
    expect(ev.props.goal_type).toBe('EMERGENCY_FUND');
    expect(ev.props.action).toBe('created');
  });

  it('tracks completed, reopened and removed, with the count the user is left with', async () => {
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7), houseGoal(8)], dismissRole: 'remove' });
    const page = fixture.componentInstance;
    // Patch the signal the way the real service does.
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      const updated = { ...(goals().find(g => g.id === id) as Goal), ...body } as Goal;
      goals.update(list => list.map(g => g.id === id ? updated : g));
      return of(updated);
    });
    removeSpy.and.callFake((id: number) => {
      goals.update(list => list.filter(g => g.id !== id));
      return of(undefined);
    });
    await page.markCompleted();
    await page.markCompleted();
    await page.confirmRemove();
    const props = track.calls.allArgs().map(a => a[0]).filter(e => e.name === 'goal_changed').map(e => e.props);
    expect(props.map(p => p.action)).toEqual(['completed', 'reopened', 'removed']);
    expect(props.map(p => p.goal_count)).toEqual([toCountBand(2), toCountBand(2), toCountBand(1)]);
  });

  it('derives completed bands from the saved goal, not unsaved form edits', async () => {
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7)] });
    const page = fixture.componentInstance;
    page.patch({ price: 4_000_000 }); // unsaved edit, would move the target band
    await page.markCompleted();
    const ev = track.calls.allArgs().map(a => a[0]).find(e => e.name === 'goal_changed');
    expect(ev.props.target_band).toBe('50k_249k');
  });

  it('still navigates and toasts when analytics throws', async () => {
    const fixture = await weddingPlan();
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    track.and.throwError('boom');
    await fixture.componentInstance.save();
    expect(showToast).toHaveBeenCalledWith('Plan started', 'success');
    expect(router.navigate).toHaveBeenCalledWith(['/my-profile']);
    expect(trackErrorShown).not.toHaveBeenCalled();
  });

  it('has no em dash in rendered text', async () => {
    const wedding = await weddingPlan();
    expect(text(wedding)).not.toContain('\u2014');
    TestBed.resetTestingModule();

    const kids = await create({ id: '5' }, { goals: [kidsGoalFixture(5, YEAR - 2)] });
    kids.componentInstance.toggleKidDetails('plan529');
    kids.componentInstance.toggleKidDetails('brokerage');
    kids.detectChanges();
    expect(text(kids)).not.toContain('\u2014');
    TestBed.resetTestingModule();

    const vacation = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    expect(text(vacation)).not.toContain('\u2014');
  });

  // \u2500\u2500 KIDS: quiz, what a kid costs, first-year fund \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  function question(fixture: ComponentFixture<GoalPage>): string {
    return text(fixture, '.gl-q').trim();
  }

  function clickCta(fixture: ComponentFixture<GoalPage>): void {
    (fixture.nativeElement.querySelector('.gl-quiz .gl-cta') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function showCard(fixture: ComponentFixture<GoalPage>, card: 1 | 2 | 3 | 4 | 5): void {
    const page = fixture.componentInstance;
    page.backToInputs();
    page.quizCard.set(card);
    fixture.detectChanges();
  }

  it('walks the five quiz cards in order with the cost card third and blocks Next on an invalid card', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    const root: HTMLElement = fixture.nativeElement;

    expect(question(fixture)).toBe('How many kids are you planning for?');
    expect(text(fixture)).toContain('Count the ones already here too.');
    expect(root.querySelectorAll('.gl-dots span').length).toBe(5);
    expect(root.querySelector('.gl-quiz-back')).toBeNull();

    clickCta(fixture);
    expect(question(fixture)).toBe('When?');
    expect(text(fixture)).toContain('Not sure yet');
    page.patchKid(0, { undecided: false, birthYear: YEAR + 20, birthMonth: 3 });
    clickCta(fixture);
    expect(question(fixture)).withContext('blocked by an out-of-range year').toBe('When?');
    expect(text(fixture)).toContain(`Enter a year from ${YEAR - 17} to ${YEAR + 10}.`);
    page.toggleUndecided(0);
    fixture.detectChanges();

    clickCta(fixture);
    expect(question(fixture)).toBe('What a kid costs');
    expect(root.querySelector('.gl-quiz-back')).not.toBeNull();
    clickCta(fixture);
    expect(question(fixture)).toBe('How much do you want to set aside for college?');
    clickCta(fixture);
    expect(question(fixture)).toBe('How much do you want each kid to start adult life with?');
    expect(text(fixture, '.gl-quiz .gl-cta').trim()).toBe('Show my plan');
    expect(root.querySelector('#gl-name')).not.toBeNull();

    (root.querySelector('.gl-quiz-back') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(question(fixture)).toBe('How much do you want to set aside for college?');

    // A custom amount left empty blocks Next on its own card.
    page.setCollegeChoice('custom');
    page.patchKids({ plan529Target: null });
    clickCta(fixture);
    expect(question(fixture)).toBe('How much do you want to set aside for college?');
    expect(text(fixture)).toContain('Enter an amount greater than $0.');
    page.setCollegeChoice('public_tuition');
    clickCta(fixture);
    clickCta(fixture);
    expect(page.step()).toBe(2);

    // Back from the plan returns to card 1.
    page.backToInputs();
    fixture.detectChanges();
    expect(question(fixture)).toBe('How many kids are you planning for?');
  });

  it('jumps to the first card with an error when the plan is shown or saved', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.quizCard.set(5);
    page.patchKid(0, { undecided: false, birthYear: YEAR + 30, birthMonth: 1 });
    page.showPlan();
    expect(page.step()).toBe(1);
    expect(page.quizCard()).toBe(2);
  });

  it('shows the monthly the fund adds next to the switch and the short-runway line under 12 months', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.selectRate(0.10);
    showCard(fixture, 3);

    expect(text(fixture)).toContain('About $422,000 to raise an only child to 18.');
    expect(text(fixture)).toContain('The first year alone, with birth costs: about $25,700 for a first child.');
    expect(text(fixture)).toContain('National averages for a middle-income, two-parent family. USDA figures from 2015, adjusted to September 2026 dollars by FRED. Birth cost from Peterson-KFF, 2023 dollars, with employer insurance.');
    expect(text(fixture)).toContain('Plan for the first year');
    expect(page.form().kids.firstYearFund).toBeTrue();
    expect(fixture.nativeElement.querySelector('.gl-switch').getAttribute('aria-checked')).toBe('true');
    expect(text(fixture)).toContain(`Adds about $616 a month until ${monLabel(monthsOut(36))}`);
    expect(text(fixture)).not.toContain('That is a lot to save');

    // Six months out: the default turns off, the line still shows what it would add.
    const due = monthsOut(6);
    page.patchKid(0, { undecided: false, birthYear: due.year, birthMonth: due.month });
    fixture.detectChanges();
    expect(page.form().kids.firstYearFund).toBeFalse();
    expect(page.fundShortRunwayMonths()).toBe(6);
    expect(text(fixture)).toContain(`a month until ${monLabel(due)}`);
    expect(text(fixture)).toContain('That is a lot to save in 6 months. Most of the first year is paid from your income as you go, so save what you can.');

    // Once the user touches the switch, timing edits no longer re-default it.
    page.toggleFirstYearFund();
    const later = monthsOut(30);
    page.patchKid(0, { birthYear: later.year, birthMonth: later.month });
    page.patchKid(0, { birthYear: due.year, birthMonth: due.month });
    expect(page.form().kids.firstYearFund).toBeTrue();
  });

  it('sets the 529 target from a college row and the brokerage target from an at-18 row', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    showCard(fixture, 4);
    const rows = () => Array.from(fixture.nativeElement.querySelectorAll('.gl-select-row')) as HTMLButtonElement[];
    // Label left, amount right: read the two spans of each row.
    const rowText = () => rows().map(r => Array.from(r.querySelectorAll('span'))
      .map(s => (s.textContent ?? '').trim()).join(' '));
    expect(rowText()).toEqual([
      "In-state public tuition, at today's prices $47,800",
      "In-state public full cost, at today's prices $123,960",
      "Private college tuition, at today's prices $180,000",
      'Another amount',
      'None for now',
    ]);
    expect(rows()[0].classList).toContain('selected');
    expect(text(fixture)).toContain('This goes in a 529, a state college savings plan you open outside FRED. Per kid, four years at 2025-26 prices from the College Board. College will likely cost more by the time your kid starts: at 3% a year, $47,800 today is about $81,000 in 18 years. Raise the amount any time.');

    rows()[1].click();
    fixture.detectChanges();
    expect(page.form().kids.plan529Target).toBe(123_960);
    expect(page.form().kids.collegeChoice).toBe('public_full');
    rows()[4].click();
    fixture.detectChanges();
    expect(page.form().kids.plan529Target).toBeNull();
    rows()[3].click();
    fixture.detectChanges();
    const custom = fixture.nativeElement.querySelector('#gl-college-custom') as HTMLInputElement;
    expect(custom).not.toBeNull();
    custom.value = '70000';
    custom.dispatchEvent(new Event('input'));
    expect(page.form().kids.plan529Target).toBe(70_000);
    expect(page.form().kids.collegeChoice).toBe('custom');

    page.quizCard.set(5);
    fixture.detectChanges();
    expect(rowText()).toEqual(['$25,000', '$50,000', '$100,000', '$250,000', 'Another amount', 'Nothing extra']);
    expect(rows()[0].classList).toContain('selected');
    rows()[2].click();
    fixture.detectChanges();
    expect(page.form().kids.brokerageTarget).toBe(100_000);
    expect(page.form().kids.at18Choice).toBe(100_000);
    rows()[5].click();
    expect(page.form().kids.brokerageTarget).toBeNull();
    expect(page.form().kids.at18Choice).toBe('none');
  });

  it('orders the plan view: hero, return assumption, inputs, status, costs, fund, accounts', async () => {
    const goal = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due: monthsOut(36) }],
      { plan529Target: 47_800, brokerageTarget: 25_000 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const root: HTMLElement = fixture.nativeElement;
    const order = Array.from(root.querySelectorAll('.gl-hero, .gl-rate, .gl-inputs-row, .gl-status, .gl-costs, .gl-fund, .gl-account'))
      .map(el => ['gl-hero', 'gl-rate', 'gl-inputs-row', 'gl-status', 'gl-costs', 'gl-fund', 'gl-account']
        .find(c => el.classList.contains(c)));
    expect(order).toEqual(['gl-hero', 'gl-rate', 'gl-inputs-row', 'gl-status', 'gl-costs', 'gl-fund', 'gl-account', 'gl-account']);
    expect(text(fixture, '.gl-inputs-row')).toContain('1 kid \u00b7 $48K college \u00b7 $25K at 18 \u00b7 first-year fund');
    const fund = fixture.componentInstance.view().firstYear!;
    expect(text(fixture, '.gl-hero')).toContain(`${usd(fund.totalMonthly)} of this is the first-year fund, which ends ${monLabel(monthsOut(36))}.`);
    expect(text(fixture, '.gl-account')).toContain('Based on 2025-26 college prices, which tend to rise.');
  });

  it('words the per-child headline by family size', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.showPlan();
    fixture.detectChanges();
    expect(text(fixture, '.gl-costs')).toContain('About $422,000 to raise an only child to 18');
    expect(text(fixture, '.gl-costs')).not.toContain('in all');
    // Setup opens the card: rows, birth row and the note with the college ending.
    expect(text(fixture, '.gl-costs')).toContain('Pregnancy and birth, with employer insurance: about $2,700 out of pocket (Peterson-KFF, 2023 dollars)');
    expect(text(fixture, '.gl-costs')).toContain('College is not included, which is what the 529 below is for.');

    page.addKid();
    page.showPlan();
    fixture.detectChanges();
    const two = text(fixture, '.gl-costs');
    expect(two).toContain('About $332,000 per child to 18 in a two-child family');
    expect(two).toContain('For your 2 kids, about $664,000 in all, if they are home at the same time.');
    expect(two).toContain('Baby and toddler, 0 to 2');
    expect(two).toContain('about $18,000 a year, $1,500 a month');
    expect(two).toContain('Preschool, 3 to 5');
    expect(two).toContain('Grade school, 6 to 11');
    expect(two).toContain('about $18,100 a year, $1,510 a month');
    expect(two).toContain('Teen years, 12 to 17');
    expect(two).toContain('about $19,100 a year, $1,600 a month');
    expect(two).toContain('Yearly costs are national averages for a middle-income, two-parent family: USDA figures from 2015, adjusted to September 2026 dollars by FRED. Families with more children spend less on each one, and an only child costs more (USDA). Your costs will differ.');

    page.addKid();
    page.setCollegeChoice('none');
    page.showPlan();
    fixture.detectChanges();
    const three = text(fixture, '.gl-costs');
    expect(three).toContain('About $252,000 per child to 18 in a family of three or more');
    expect(three).toContain('For your 3 kids, about $757,000 in all, if they are home at the same time.');
    expect(three).toContain('College is not included. You can add a college plan from Edit.');

    // Sources list the three cost sources and leave the College Board to the college question.
    (fixture.nativeElement.querySelector('.gl-costs-sources-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    const sources = text(fixture, '.gl-costs');
    expect(sources).toContain('USDA, Expenditures on Children by Families');
    expect(sources).toContain('U.S. Bureau of Labor Statistics, Consumer Price Index');
    expect(sources).toContain('Peterson-KFF Health System Tracker');
    expect(sources).not.toContain('College Board');
  });

  it('collapses the cost card in edit mode behind Show costs by age', async () => {
    const fixture = await create({ id: '5' }, { goals: [kidsGoalFixture(5, null)] });
    expect(text(fixture, '.gl-costs')).toContain('About $422,000 to raise an only child to 18');
    expect(text(fixture, '.gl-costs')).not.toContain('Baby and toddler, 0 to 2');
    const toggle = fixture.nativeElement.querySelector('.gl-costs-toggle') as HTMLButtonElement;
    expect(toggle.textContent).toContain('Show costs by age');
    toggle.click();
    fixture.detectChanges();
    expect(text(fixture, '.gl-costs')).toContain('Baby and toddler, 0 to 2');
  });

  it('lists a decided fund until its month, an undecided fund with its date and the invested note, and a finished fund as saved of target', async () => {
    const ava = monthsOut(48);
    const born = monthsOut(-2);
    const goal = fundKidsGoal(5, [
      { label: 'Ava', birthYear: ava.year, birthMonth: ava.month, due: ava },
      { label: 'Ben', birthYear: null, birthMonth: null, due: monthsOut(36), target: 20_800 },
      { label: 'Cy', birthYear: born.year, birthMonth: born.month, due: born, target: 16_500, balance: 9_000 },
      { label: 'Dee', birthYear: null, birthMonth: null, due: monthsOut(-1), target: 20_800, balance: 20_000 },
    ], { plan529Target: 47_800 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const rows = fixture.componentInstance.view().firstYear!.rows;
    const card = text(fixture, '.gl-fund');

    expect(card).toContain('Ava: about $25,700 for birth costs and the first year.');
    expect(card).toContain(`${usd(rows[0].plan.monthly)} a month until ${monLabel(ava)}.`);
    expect(rows[0].plan.rateKind).toBe('market');
    expect(card).not.toContain('since you need it within 3 years');

    expect(card).toContain('Ben: about $20,800 for birth costs and the first year.');
    expect(card).toContain(`${usd(rows[1].plan.monthly)} a month until ${monLabel(monthsOut(36))}. No date yet, so this one is invested at your return assumption.`);

    expect(card).toContain('Cy: $9,000 saved of $16,500 for the first year.');
    expect(card).not.toContain('Cy: about');
    expect(card).toContain('Dee: $20,000 saved of $20,800. Three years are up, so set a date to keep planning.');

    expect(fixture.nativeElement.querySelectorAll('.gl-fund input').length).toBe(4);
    expect(text(fixture, '.gl-fund')).toContain('Saved so far');
    expect(text(fixture)).toContain('Markets can be down when the day comes, so treat this as a head start. Once you pick a date less than three years away, FRED plans this fund as cash.');
    expect(card).toContain('FRED estimate: first-year average (USDA, adjusted to September 2026 dollars) plus birth costs (Peterson-KFF, 2023 dollars), rounded up. Keep this in your own savings or brokerage account. FRED does not hold or move goal money.');
  });

  it('adds the cash line for a decided fund under three years away', async () => {
    const due = monthsOut(20);
    const goal = fundKidsGoal(5, [{ label: 'Ava', birthYear: due.year, birthMonth: due.month, due }], { plan529Target: 47_800 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const row = fixture.componentInstance.view().firstYear!.rows[0];
    expect(text(fixture, '.gl-fund')).toContain(`${usd(row.plan.monthly)} a month until ${monLabel(due)}. Planned as cash at about 4%, since you need it within 3 years.`);
  });

  it('hides the fund switch and card when every kid is already born and no fund is stored', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.patchKid(0, { undecided: false, birthYear: YEAR - 2, birthMonth: 4 });
    showCard(fixture, 3);
    expect(page.anyKidEligible()).toBeFalse();
    expect(page.form().kids.firstYearFund).toBeFalse();
    expect(fixture.nativeElement.querySelector('.gl-switch')).toBeNull();
    expect(text(fixture)).toContain('Your kids are already here, so there is no first-year fund to plan.');
    page.showPlan();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.gl-fund')).toBeNull();
    expect(text(fixture, '.gl-inputs-row')).not.toContain('first-year fund');
  });

  it('sends firstYearFund, per-kid targets, due dates and first-year anchors on save', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    page.addKid();
    page.showPlan();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#gl-bal-firstYear-0') as HTMLInputElement;
    expect(input).withContext('first-year balance tile in setup').not.toBeNull();
    input.value = '1000';
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    await page.save();
    const config = (createSpy.calls.mostRecent().args[0] as GoalWrite).config as KidsConfig;
    const rows = page.view().firstYear!.rows;
    const due = monthsOut(36);
    expect(config.firstYearFund).toBeTrue();
    expect(config.kids.map(k => k.firstYearTarget)).toEqual([25_700, 20_800]);
    config.kids.forEach((kid, i) => {
      expect([kid.firstYearDueYear, kid.firstYearDueMonth]).toEqual([due.year, due.month]);
      expect(kid.accounts.firstYear!.anchorMonthly).toBe(rows[i].plan.monthly);
      expect(kid.accounts.firstYear!.anchorMonthly).toBeGreaterThan(0);
    });
    expect(config.kids[0].accounts.firstYear!.balance).toBe(1_000);
    expect(config.kids[1].accounts.firstYear!.balance).toBe(0);
  });

  it('logs a first-year balance in edit mode', async () => {
    const goal = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due: monthsOut(36) }], { plan529Target: 47_800 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    await fixture.componentInstance.updateBalance({ kid: 0, account: 'firstYear' }, 2_500);
    const config = (updateSpy.calls.mostRecent().args[1] as GoalWrite).config as KidsConfig;
    expect(config.kids[0].accounts.firstYear!.balance).toBe(2_500);
  });

  it('shows the same first-year monthly before and after a re-plan save', async () => {
    const goal = kidsGoalFixture(5, null);
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const page = fixture.componentInstance;
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    page.toggleFirstYearFund();
    const before = page.view();
    expect(before.firstYear!.totalMonthly).toBeGreaterThan(0);
    await page.save();
    const body = updateSpy.calls.mostRecent().args[1] as GoalWrite;
    TestBed.resetTestingModule();

    // What the server stores on a re-anchor: anchored now, no logged-balance date.
    const saved: Goal = {
      ...goal, ...body, planAnchoredAt: localIso(NOW), balanceUpdatedAt: null, anchorBalance: body.currentBalance,
    };
    const after = await create({ id: '5' }, { goals: [saved] });
    expect(after.componentInstance.view().firstYear!.totalMonthly).toBe(before.firstYear!.totalMonthly);
    expect(after.componentInstance.view().monthlyTotal).toBe(before.monthlyTotal);
  });

  it('reads Plan started, not Ahead or Behind, while a changed plan is unsaved', async () => {
    const logged = localIso(new Date(NOW.getTime() - 90 * 86_400_000));
    const kids = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due: monthsOut(40), balance: 9_000, logged }],
      { plan529Target: 47_800, anchoredAt: localIso(new Date(NOW.getTime() - 200 * 86_400_000)) });
    const fixture = await create({ id: '5' }, { goals: [kids] });
    const page = fixture.componentInstance;
    expect(['ahead', 'on_plan', 'behind']).toContain(page.view().status.kind);
    page.setCollegeChoice('public_full');
    fixture.detectChanges();
    expect(page.statusLine()).toBe('Plan started');
    expect(text(fixture, '.gl-status')).toContain('Plan started');
    TestBed.resetTestingModule();

    const house: Goal = {
      ...houseGoal(8), currentBalance: 10_000, anchorBalance: 1_000, anchorMonthly: 500,
      planAnchoredAt: localIso(new Date(NOW.getTime() - 200 * 86_400_000)), balanceUpdatedAt: logged,
    };
    const houseFixture = await create({ id: '8' }, { goals: [house] });
    const housePage = houseFixture.componentInstance;
    expect(housePage.statusLine()).toContain('Ahead by');
    housePage.patch({ price: 500_000 });
    expect(housePage.statusLine()).toBe('Plan started');
    expect(housePage.draftGoal().anchorBalance).toBe(10_000);
  });

  it('keeps the stored fund switch in edit mode when a kid is added or a date moves', async () => {
    const fixture = await create({ id: '5' }, { goals: [kidsGoalFixture(5, null)] });
    const page = fixture.componentInstance;
    expect(page.form().kids.firstYearFund).toBeFalse();
    page.addKid();
    const due = monthsOut(20);
    page.patchKid(0, { undecided: false, birthYear: due.year, birthMonth: due.month });
    expect(page.form().kids.firstYearFund).toBeFalse();
    TestBed.resetTestingModule();

    const on = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due: monthsOut(36) }], { plan529Target: 47_800 });
    const onFixture = await create({ id: '5' }, { goals: [on] });
    const onPage = onFixture.componentInstance;
    const soon = monthsOut(3);
    onPage.patchKid(0, { undecided: false, birthYear: soon.year, birthMonth: soon.month });
    expect(onPage.form().kids.firstYearFund).toBeTrue();
  });

  it('shows $616 a month next to the switch when the fund is turned on for a stored goal', async () => {
    const fixture = await create({ id: '5' }, { goals: [kidsGoalFixture(5, null)] });
    const page = fixture.componentInstance;
    page.selectRate(0.10);
    showCard(fixture, 3);
    expect(page.form().kids.firstYearFund).toBeFalse();
    expect(text(fixture)).toContain(`Adds about $616 a month until ${monLabel(monthsOut(36))}`);
    page.toggleFirstYearFund();
    fixture.detectChanges();
    expect(page.view().firstYear!.totalMonthly).toBe(616);
  });

  it('uses the cash line, not the invested line, for an undecided fund planned as cash', async () => {
    const due = monthsOut(20);
    const goal = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due }], { plan529Target: 47_800 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const row = fixture.componentInstance.view().firstYear!.rows[0];
    expect(row.plan.rateKind).toBe('cash');
    expect(text(fixture, '.gl-fund')).toContain(`${usd(row.plan.monthly)} a month until ${monLabel(due)}. No date yet. Planned as cash at about 4%, since this runway ends within 3 years.`);
    expect(text(fixture)).not.toContain('invested at your return assumption');
    expect(text(fixture)).not.toContain('Markets can be down');
  });

  it('drops the fund from the hero total when it is switched off and warns about logged balances', async () => {
    const goal = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due: monthsOut(36), balance: 1_200, logged: localIso(NOW) }],
      { plan529Target: 47_800 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const page = fixture.componentInstance;
    const before = page.view();
    const fundMonthly = before.firstYear!.totalMonthly;
    expect(fundMonthly).toBeGreaterThan(0);
    expect(page.fundLoggedBalance()).toBe(1_200);

    // The warning shows while the switch is on, before it is turned off.
    showCard(fixture, 3);
    expect(text(fixture)).toContain('Turning this off removes the $1,200 you logged for it from this plan.');
    page.step.set(2);

    page.toggleFirstYearFund();
    fixture.detectChanges();
    expect(page.view().firstYear).toBeUndefined();
    expect(page.view().monthlyTotal).toBe(before.monthlyTotal - fundMonthly);
    expect(text(fixture, '.gl-hero')).toContain(usd(before.monthlyTotal - fundMonthly));
    expect(fixture.nativeElement.querySelector('.gl-fund')).toBeNull();

    showCard(fixture, 3);
    expect(text(fixture)).not.toContain('Turning this off removes');
  });

  it('says the accounts and the fund live outside FRED', async () => {
    const fixture = await create({ type: 'KIDS' });
    showCard(fixture, 4);
    expect(text(fixture)).toContain('a state college savings plan you open outside FRED');
    showCard(fixture, 5);
    expect(text(fixture)).toContain('A custodial or regular brokerage account you open outside FRED.');
    fixture.componentInstance.showPlan();
    fixture.detectChanges();
    expect(text(fixture, '.gl-fund')).toContain('Keep this in your own savings or brokerage account. FRED does not hold or move goal money.');
  });

  it('has no em dash in the quiz, the cost card or the fund card', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.addKid();
    const soon = monthsOut(4);
    page.patchKid(0, { undecided: false, birthYear: soon.year, birthMonth: soon.month });
    for (const card of [1, 2, 3, 4, 5] as const) {
      showCard(fixture, card);
      expect(text(fixture)).withContext(`card ${card}`).not.toContain('\u2014');
    }
    page.toggleFirstYearFund();
    page.showPlan();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.gl-costs-sources-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.gl-fund')).not.toBeNull();
    expect(text(fixture)).not.toContain('\u2014');
  });

  // \u2500\u2500 Final review fixes \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /** A stored house goal whose PUTs echo the body, patching the goals signal like the real service. */
  function echoUpdates(): void {
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      const updated = { ...(goals().find(g => g.id === id) as Goal), ...body } as Goal;
      goals.update(list => list.map(g => g.id === id ? updated : g));
      return of(updated);
    });
  }

  function blurWith(fixture: ComponentFixture<GoalPage>, selector: string, value: string): void {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    expect(input).withContext(selector).not.toBeNull();
    input.value = value;
    input.dispatchEvent(new Event('blur'));
  }

  it('waits for a pending balance write before Save builds its body', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_000, anchorBalance: 1_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    const pending = new Subject<Goal>();
    let calls = 0;
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      calls++;
      return calls === 1 ? pending.asObservable() : of({ ...goal, ...body } as Goal);
    });

    blurWith(fixture, '#gl-bal-current', '12000');
    const saving = page.save();
    await Promise.resolve();
    expect(updateSpy).toHaveBeenCalledTimes(1); // Save has not sent yet

    pending.next({ ...goal, currentBalance: 12_000 });
    pending.complete();
    await saving;

    expect(updateSpy).toHaveBeenCalledTimes(2);
    const body = updateSpy.calls.mostRecent().args[1] as GoalWrite;
    expect(body.currentBalance).toBe(12_000);
    expect(page.saving()).toBeFalse();
  });

  it('blurs a focused balance field on Save so the typed amount is written first', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_000, anchorBalance: 1_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    echoUpdates();
    const input = fixture.nativeElement.querySelector('#gl-bal-current') as HTMLInputElement;
    input.focus();
    input.value = '7000';
    const blurred = spyOn(input, 'blur').and.callFake(() => input.dispatchEvent(new Event('blur')));
    spyOnProperty(document, 'activeElement', 'get').and.returnValue(input);

    await page.save();

    expect(blurred).toHaveBeenCalled();
    expect(updateSpy).toHaveBeenCalledTimes(2);
    expect((updateSpy.calls.argsFor(0)[1] as GoalWrite).currentBalance).toBe(7_000);
    expect((updateSpy.calls.argsFor(1)[1] as GoalWrite).currentBalance).toBe(7_000);
  });

  it('builds the second of two quick tile blurs from the first write', async () => {
    const goal = kidsGoalFixture(5, YEAR - 2);
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const page = fixture.componentInstance;
    const pending = new Subject<Goal>();
    let calls = 0;
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      calls++;
      return calls === 1 ? pending.asObservable() : of({ ...goal, ...body } as Goal);
    });
    page.toggleKidDetails('plan529');
    page.toggleKidDetails('brokerage');
    fixture.detectChanges();

    blurWith(fixture, '#gl-bal-plan529-0', '300');
    const second = page.updateBalance({ kid: 0, account: 'brokerage' }, 900);
    await Promise.resolve();
    expect(updateSpy).toHaveBeenCalledTimes(1);

    const first = (updateSpy.calls.argsFor(0)[1] as GoalWrite).config as KidsConfig;
    pending.next({ ...goal, config: first });
    pending.complete();
    await second;

    const config = (updateSpy.calls.mostRecent().args[1] as GoalWrite).config as KidsConfig;
    expect(config.kids[0].accounts.plan529.balance).toBe(300);
    expect(config.kids[0].accounts.brokerage.balance).toBe(900);
  });

  it('keeps writing after a rejected balance write', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_000 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    updateSpy.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    await page.updateBalance('current', 2_000);
    expect(showToast).toHaveBeenCalledWith('Could not save this goal. Try again.', 'danger');

    echoUpdates();
    await page.markCompleted();
    expect(updateSpy).toHaveBeenCalledTimes(2);
    expect(page.existing()!.completed).toBeTrue();
  });

  it('opens the Plus nudge when Reopen is refused with TIER_LIMIT, and tracks it', async () => {
    const err = new HttpErrorResponse({ status: 403, error: { code: 'TIER_LIMIT' } });
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7, true)], tier: 'core', updateResult: throwError(() => err) });
    expect(text(fixture, '.gl-complete')).toContain('Reopen goal');
    await fixture.componentInstance.markCompleted();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    expect(modalCtrl.create.calls.mostRecent().args[0].component).toBe(McInfoSheetComponent);
    expect(showToast).not.toHaveBeenCalled();
    const nudges = track.calls.allArgs().map(a => a[0]).filter(e => e.name === 'paywall_nudge_shown');
    expect(nudges).toEqual([
      { name: 'paywall_nudge_shown', props: { feature: 'goals', tier: 'core', action: 'shown' } },
      { name: 'paywall_nudge_shown', props: { feature: 'goals', tier: 'core', action: 'dismissed' } },
    ]);
  });

  it('counts an unfinished first-year fund in the kids target band', async () => {
    const goal = fundKidsGoal(5, [{ label: 'Kid 1', birthYear: null, birthMonth: null, due: monthsOut(36), target: 25_700 }],
      { plan529Target: 47_800 });
    const fixture = await create({ id: '5' }, { goals: [goal] });
    await fixture.componentInstance.markCompleted();
    const ev = track.calls.allArgs().map(a => a[0]).find(e => e.name === 'goal_changed');
    // 47,800 alone is 10k_49k; with the 25,700 fund it is 73,500.
    expect(ev.props.target_band).toBe('50k_249k');
  });

  it('reports a network failure as network, not save_failed', async () => {
    const fixture = await weddingPlan({ createResult: throwError(() => new HttpErrorResponse({ status: 0 })) });
    await fixture.componentInstance.save();
    // httpStatusOf reports Angular's status 0 as no status.
    expect(trackErrorShown).toHaveBeenCalledWith('network', 'goals', 'toast', null, true);
  });

  it('shows what is due now for a past-year target with a shortfall', async () => {
    const goal: Goal = {
      ...houseGoal(8), type: 'CUSTOM', icon: 'flag', name: 'Boat',
      config: { targetAmount: 30_000, targetYear: YEAR - 1 },
      currentBalance: 24_000, anchorBalance: 1_000, anchorMonthly: 500,
      planAnchoredAt: localIso(new Date(YEAR - 2, 0, 15)), balanceUpdatedAt: localIso(new Date(YEAR - 1, 5, 15)),
    };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    expect(text(fixture, '.gl-hero')).toContain('$6,000 due now');
    expect(text(fixture, '.gl-hero')).not.toContain('/mo');
    expect(text(fixture, '.gl-hero')).not.toContain('a paycheck');
    expect(text(fixture, '.gl-hero')).toContain('Target month reached');
    expect(text(fixture)).not.toContain('from growth');
    expect(page.catchUpLine()).toBeNull();
    await page.markCompleted();
    expect((updateSpy.calls.mostRecent().args[1] as GoalWrite).completed).toBeTrue();
    // The stored past year is valid for the page too: Save is not bounced to step 1.
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    await page.save();
    expect(page.step()).toBe(2);
    expect(page.errors()).toEqual({});
    expect(updateSpy).toHaveBeenCalledTimes(2);
  });

  it('never shows a catch-up sentence for a vacation goal', async () => {
    const goal: Goal = {
      ...vacationGoalFixture(6),
      config: { tripsPerYear: 2, budgetPerTrip: 30_000, savedThisYear: 100, savedYear: YEAR },
      planAnchoredAt: localIso(new Date(YEAR, 0, 1)),
    };
    const fixture = await create({ id: '6' }, { goals: [goal] });
    const page = fixture.componentInstance;
    if (NOW.getMonth() > 0) expect(page.view().status.kind).toBe('behind');
    expect(page.catchUpLine()).toBeNull();
    expect(text(fixture)).not.toContain('gets you back on plan');
  });

  it('plans a never-planned import 13 months out as cash and saves cash', async () => {
    const goal: Goal = {
      ...houseGoal(9), clientId: 'v2-a', type: 'CUSTOM', icon: 'flag', name: 'Old goal',
      config: { targetAmount: 20_000, targetYear: YEAR + 1 }, currentBalance: 2_000, anchorBalance: 2_000,
      anchorMonthly: 0, planRate: 0.1, planRateKind: 'market',
    };
    const fixture = await create({ id: '9' }, { goals: [goal] });
    const page = fixture.componentInstance;
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    expect(page.planChanged()).toBeTrue();
    expect(page.view().rateKind).toBe('cash');
    expect(fixture.nativeElement.querySelector('.gl-cash-note')).not.toBeNull();
    expect(page.ctaLabel()).toBe('Start this plan');
    await page.save();
    const body = updateSpy.calls.mostRecent().args[1] as GoalWrite;
    expect(body.planRateKind).toBe('cash');
    expect(body.planRate).toBe(CASH_RATE);
  });

  it('carries a balance typed for a kid added in edit mode into the save', async () => {
    const fixture = await create({ id: '5' }, { goals: [kidsGoalFixture(5, YEAR - 2)] });
    const page = fixture.componentInstance;
    spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    page.addKid();
    page.toggleKidDetails('plan529');
    fixture.detectChanges();

    blurWith(fixture, '#gl-bal-plan529-1', '4500');
    fixture.detectChanges();
    expect(updateSpy).not.toHaveBeenCalled(); // not stored yet: draft only
    expect(page.view().kids!.find(a => a.account === 'plan529')!.perKid[1].balance).toBe(4_500);

    await page.save();
    const config = (updateSpy.calls.mostRecent().args[1] as GoalWrite).config as KidsConfig;
    expect(config.kids.length).toBe(2);
    expect(config.kids[1].accounts.plan529.balance).toBe(4_500);
    expect(config.kids[0].accounts.plan529.balance).toBe(0);
  });

  it('shows whole dollars and never rewrites a stored balance with cents on focus and blur', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_234.56 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const input = fixture.nativeElement.querySelector('#gl-bal-current') as HTMLInputElement;
    expect(input.value).toBe('1235');
    input.dispatchEvent(new Event('blur'));
    await fixture.componentInstance.updateBalance('current', 1_235);
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('toasts and goes back when a deep-linked goal cannot load, and signs out on 401', async () => {
    const failing = await create({ id: '7' }, { loadError: new HttpErrorResponse({ status: 500 }) });
    expect(showToast).toHaveBeenCalledWith('Could not load this goal. Try again.', 'danger');
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    expect(failing.componentInstance.ready()).toBeFalse();
    TestBed.resetTestingModule();

    await create({ id: '7' }, { loadError: new HttpErrorResponse({ status: 401 }) });
    expect(logout).toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('Your session ended. Sign in again to save this plan.', 'danger');
    expect(showToast).not.toHaveBeenCalledWith('Could not load this goal. Try again.', 'danger');
  });

  it('warns on card 1 before a lower kid count drops a kid with logged balances', async () => {
    const goal = kidsGoalFixture(5, YEAR - 2);
    const config = goal.config as KidsConfig;
    const second = { ...config.kids[0], label: 'Ben', accounts: {
      plan529: { balance: 800, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 },
      brokerage: { balance: 400, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 },
    } };
    const fixture = await create({ id: '5' }, { goals: [{ ...goal, config: { ...config, kids: [config.kids[0], second] } }] });
    const page = fixture.componentInstance;
    showCard(fixture, 1);
    expect(text(fixture)).not.toContain('Saving removes');
    page.removeKid(1);
    fixture.detectChanges();
    expect(text(fixture)).toContain('Saving removes Ben and the $1,200 you logged for them.');
  });

  it('exposes selection state and errors to assistive technology', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    showCard(fixture, 4);
    const rows = Array.from(fixture.nativeElement.querySelectorAll('.gl-select-row')) as HTMLElement[];
    expect(rows[0].getAttribute('aria-pressed')).toBe('true');
    expect(rows[1].getAttribute('aria-pressed')).toBe('false');
    page.setCollegeChoice('custom');
    page.patchKids({ plan529Target: null });
    page.nextQuizCard();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.gl-error').getAttribute('role')).toBe('alert');
    showCard(fixture, 2);
    page.patchKid(0, { label: 'Ava' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.gl-year').getAttribute('aria-label')).toBe('Birth year for Ava');
  });
});
