import { CUSTOM_ELEMENTS_SCHEMA, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';

import { GoalPage } from './goal.page';
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
    config: { price: 400_000, downPaymentPct: 20, targetYear: new Date().getFullYear() + 5 },
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

const YEAR = new Date().getFullYear();

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
      createResult?: Observable<Goal>; updateResult?: Observable<Goal>;
    } = {},
  ): Promise<ComponentFixture<GoalPage>> {
    goals = signal<Goal[]>(opts.goals ?? []);
    load = jasmine.createSpy('load').and.callFake(() => {
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
    expect(fixture.nativeElement.querySelector('.gl-cta').textContent).toContain('Show my plan');
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
    expect(text(fixture)).toContain('Undecided plans as if born this year, so you save a little more than you may need');
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
    const logged = localIso(new Date(Date.now() - 90 * 86_400_000));
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
    const fresh = localIso(new Date());
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
});
