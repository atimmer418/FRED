import { CUSTOM_ELEMENTS_SCHEMA, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ActionSheetController, AlertController, ModalController, NavController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { GoalPage } from './goal.page';
import { GOAL_PAGE_NOW } from './goal-clock';
import { AuthService } from '../../services/auth.service';
import { GoalsService } from '../../services/goals.service';
import { GoalDraftService } from '../../services/goal-draft.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalRemoveConfirmSheetComponent } from '../../components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component';
import { GoalUnitDetailSheetComponent } from '../../components/goal-unit-detail-sheet/goal-unit-detail-sheet.component';
import { KidTimelineComponent } from '../../components/kid-timeline/kid-timeline.component';
import { toCountBand } from '../../services/analytics/analytics-bands';
import { Goal, GoalWrite, KidsConfig, VacationConfig } from '../../models/goal.model';
import { GoalForm, defaultForm, formFromGoal } from './goal-form.model';
import { buildGoalPlanView, fmtMoney } from './goal-plan.view-model';

/** The page's clock in every spec: fixtures and horizons count from the same instant. */
const NOW = new Date();
const YEAR = NOW.getFullYear();

/** Local ISO timestamp without a Z suffix, the shape the backend sends. */
function localIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:00`;
}

function houseGoal(id: number, completed = false): Goal {
  return {
    id, clientId: null, type: 'HOUSE', name: 'First home', icon: 'home',
    config: { price: 400_000, downPaymentPct: 20, targetYear: YEAR + 5 },
    currentBalance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0,
    planRate: 0.07, planRateKind: 'market', planAnchoredAt: '2026-01-01',
    completed, sortOrder: 0, createdAt: '', updatedAt: '',
  };
}

const fresh = (balance = 0, logged: string | null = null) =>
  ({ balance, balanceUpdatedAt: logged, anchorBalance: balance, anchorMonthly: 0 });

/** One kid, born two years ago, with a 529 and a nest egg. */
function kidsGoalFixture(id: number, birthYear: number | null = YEAR - 2, label = 'Kid 1'): Goal {
  const config: KidsConfig = {
    plan529Target: 47_800,
    brokerageTarget: 100_000,
    kids: [{ label, birthYear, birthMonth: birthYear == null ? null : 5, accounts: { plan529: fresh(), brokerage: fresh() } }],
  };
  return { ...houseGoal(id), type: 'KIDS', name: 'Our kids', icon: 'child_care', config, planAnchoredAt: localIso(NOW), planRate: 0.10 };
}

/** The spec's sample family: Ellie born two years ago, Theo due in 19 months with a baby fund, a third kid not dated. */
function sampleFamily(id: number): Goal {
  const due = new Date(NOW.getFullYear(), NOW.getMonth() + 19, 1);
  const undatedDue = new Date(NOW.getFullYear(), NOW.getMonth() + 36, 1);
  const config: KidsConfig = {
    plan529Target: 47_800,
    brokerageTarget: 100_000,
    firstYearFund: true,
    kids: [
      { label: 'Ellie', birthYear: YEAR - 2, birthMonth: null, accounts: { plan529: fresh(), brokerage: fresh() } },
      {
        label: 'Theo', birthYear: due.getFullYear(), birthMonth: due.getMonth() + 1,
        firstYearTarget: 20_800, firstYearDueYear: due.getFullYear(), firstYearDueMonth: due.getMonth() + 1,
        accounts: { plan529: fresh(), brokerage: fresh(), firstYear: fresh() },
      },
      {
        label: 'Kid 3', birthYear: null, birthMonth: null,
        firstYearTarget: 16_500, firstYearDueYear: undatedDue.getFullYear(), firstYearDueMonth: undatedDue.getMonth() + 1,
        accounts: { plan529: fresh(), brokerage: fresh(), firstYear: fresh() },
      },
    ],
  };
  return { ...kidsGoalFixture(id), config };
}

function vacationGoalFixture(id: number): Goal {
  const config: VacationConfig = { tripsPerYear: 2, budgetPerTrip: 3_000, savedThisYear: 0, savedYear: YEAR };
  return { ...houseGoal(id), type: 'VACATION', name: 'Trips', icon: 'flight', config, planRateKind: 'cash', planRate: 0.04 };
}

/** A new-goal draft the quiz would hand over for this type. */
function newDraft(type: GoalForm['type'], patch: Partial<GoalForm> = {}): GoalForm {
  const form = defaultForm(type, NOW);
  if (type === 'WEDDING') Object.assign(form, { targetAmount: 60_000, targetYear: YEAR + 6 });
  if (type === 'HOUSE') Object.assign(form, { price: 400_000, downPaymentPct: 20, fullPrice: false, targetYear: YEAR + 5 });
  if (type === 'EMERGENCY_FUND') Object.assign(form, { monthlyExpenses: 3_000, months: 6, targetYear: YEAR + 2 });
  return { ...form, ...patch };
}

describe('GoalPage', () => {
  let navCtrl: jasmine.SpyObj<NavController>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let actionSheetCtrl: jasmine.SpyObj<ActionSheetController>;
  let alertCtrl: jasmine.SpyObj<AlertController>;
  let router: jasmine.SpyObj<Router>;
  let draftService: GoalDraftService;
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
  let rafCallbacks: FrameRequestCallback[];
  let reducedMotion = false;

  interface CreateOpts {
    goals?: Goal[]; loaded?: Goal[]; tier?: string; dismissRole?: string; alertRole?: string; alertName?: string;
    createResult?: Observable<Goal>; updateResult?: Observable<Goal>; loadError?: unknown;
    /** The quiz's draft, set before the page opens. */
    draft?: { form: GoalForm; existing: Goal | null };
  }

  async function create(params: Record<string, string>, opts: CreateOpts = {}): Promise<ComponentFixture<GoalPage>> {
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
    router.navigate.and.resolveTo(true);
    progress = new BehaviorSubject<{ selectedTier: string } | null>({ selectedTier: opts.tier ?? 'plus' });
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward', 'back']);
    navCtrl.navigateBack.and.resolveTo(true);
    navCtrl.navigateForward.and.resolveTo(true);
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo({
      present: async () => {},
      onDidDismiss: async () => ({ role: opts.dismissRole }),
    } as unknown as HTMLIonModalElement);
    actionSheetCtrl = jasmine.createSpyObj<ActionSheetController>('ActionSheetController', ['create']);
    actionSheetCtrl.create.and.resolveTo({ present: async () => {}, onDidDismiss: async () => ({}) } as unknown as HTMLIonActionSheetElement);
    alertCtrl = jasmine.createSpyObj<AlertController>('AlertController', ['create']);
    alertCtrl.create.and.resolveTo({
      present: async () => {},
      onDidDismiss: async () => ({ role: opts.alertRole, data: { values: { name: opts.alertName } } }),
    } as unknown as HTMLIonAlertElement);

    // The count-up runs on frames the spec drives by hand. A spec that creates two pages reuses the spies.
    rafCallbacks = [];
    if (!jasmine.isSpy(window.requestAnimationFrame)) {
      spyOn(window, 'requestAnimationFrame').and.callFake((cb: FrameRequestCallback) => {
        rafCallbacks.push(cb);
        return rafCallbacks.length;
      });
      spyOn(window, 'matchMedia').and.callFake((q: string) =>
        ({ matches: reducedMotion && q.includes('reduce'), media: q } as MediaQueryList));
    }

    const isPreview = 'preview' in params;
    const paramMap = { ...params };
    delete paramMap['preview'];

    await TestBed.configureTestingModule({
      imports: [GoalPage],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(paramMap), data: isPreview ? { preview: true } : {} } } },
        { provide: NavController, useValue: navCtrl },
        { provide: Router, useValue: router },
        { provide: ModalController, useValue: modalCtrl },
        { provide: ActionSheetController, useValue: actionSheetCtrl },
        { provide: AlertController, useValue: alertCtrl },
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
        set: { imports: [CommonModule, KidTimelineComponent], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
      })
      .compileComponents();

    draftService = TestBed.inject(GoalDraftService);
    draftService.clear();
    if (opts.draft) draftService.set(opts.draft.form, opts.draft.existing);

    const fixture = TestBed.createComponent(GoalPage);
    fixture.detectChanges(); // runs ngOnInit once
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    reducedMotion = false;
  });

  function text(fixture: ComponentFixture<GoalPage>, selector?: string): string {
    const root: HTMLElement = fixture.nativeElement;
    const el = selector ? root.querySelector(selector) : root;
    return (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  }

  function finishCountUp(fixture: ComponentFixture<GoalPage>): void {
    const far = performance.now() + 10_000;
    while (rafCallbacks.length) rafCallbacks.shift()!(far);
    fixture.detectChanges();
  }

  function blurWith(fixture: ComponentFixture<GoalPage>, selector: string, value: string): void {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    expect(input).withContext(selector).not.toBeNull();
    input.value = value;
    input.dispatchEvent(new Event('blur'));
  }

  /** The real service patches its signal on every write; the mock has to do the same. */
  function echoUpdates(): void {
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      const updated = { ...(goals().find(g => g.id === id) as Goal), ...body } as Goal;
      goals.update(list => list.map(g => g.id === id ? updated : g));
      return of(updated);
    });
  }

  function click(fixture: ComponentFixture<GoalPage>, selector: string): void {
    const el = fixture.nativeElement.querySelector(selector) as HTMLElement;
    expect(el).withContext(selector).not.toBeNull();
    el.click();
    fixture.detectChanges();
  }

  // ── Modes and the CTA ──────────────────────────────────────────────────────

  it('preview-new builds the view from the draft and Start this plan POSTs then replaces the URL with /goals/:id', async () => {
    const form = newDraft('WEDDING');
    const fixture = await create({ type: 'WEDDING', preview: '1' }, { draft: { form, existing: null } });
    const page = fixture.componentInstance;
    finishCountUp(fixture);

    expect(page.mode()).toBe('preview-new');
    expect(text(fixture, '.header-title')).toBe('Wedding');
    expect(text(fixture, '.fd-hero-label')).toBe('Save this month');
    expect(text(fixture, '.fd-hero-big')).toBe(`${fmtMoney(page.view().monthlyTotal)}/mo`);
    expect(text(fixture, '.fd-hero-meta')).toContain('for the wedding');
    expect(text(fixture, '.fd-hero-meta')).toContain(`~${fmtMoney(page.view().perPaycheck!)} a paycheck`);
    expect(fixture.nativeElement.querySelector('.gp-menu')).toBeNull();
    expect(text(fixture, '.fd-cta')).toBe('Start this plan');
    expect(text(fixture, '.fd-how')).toContain('How FRED helps.');

    click(fixture, '.fd-cta');
    await fixture.whenStable();
    expect(createSpy).toHaveBeenCalledTimes(1);
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(body.type).toBe('WEDDING');
    expect(body.name).toBe('Wedding');
    expect(body.anchorMonthly).toBe(page.view().monthlyTotal);
    expect(body.planRateKind).toBe('market');
    expect(router.navigate).toHaveBeenCalledWith(['/goals', 99], { replaceUrl: true });
    expect(draftService.draft()).toBeNull();
    expect(showToast).toHaveBeenCalledWith('Plan started', 'success');
  });

  it('preview-edit shows Update my plan and PUTs the draft config, then pops back to the saved page', async () => {
    const goal = houseGoal(4);
    const form = { ...formFromGoal(goal), price: 500_000 };
    const fixture = await create({ id: '4', preview: '1' }, { goals: [goal], draft: { form, existing: goal } });
    const page = fixture.componentInstance;
    finishCountUp(fixture);

    expect(page.mode()).toBe('preview-edit');
    expect(text(fixture, '.fd-cta')).toBe('Update my plan');
    // A changed plan input previews as re-anchored: Plan started, not graded.
    expect(page.view().status.kind).toBe('started');

    click(fixture, '.fd-cta');
    await fixture.whenStable();
    expect(updateSpy).toHaveBeenCalledTimes(1);
    const [id, body] = updateSpy.calls.mostRecent().args as [number, GoalWrite];
    expect(id).toBe(4);
    expect(body.config).toEqual(jasmine.objectContaining({ price: 500_000, downPaymentPct: 20 }));
    expect(body.anchorMonthly).toBe(page.view().monthlyTotal);
    expect(navCtrl.navigateBack).toHaveBeenCalledWith(['/goals', 4]);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(draftService.draft()).toBeNull();
    expect(showToast).toHaveBeenCalledWith('Plan updated', 'success');
  });

  it('back from a preview pops to the quiz and leaves the draft for it; back from a saved page goes to my-profile', async () => {
    const form = newDraft('WEDDING');
    const fixture = await create({ type: 'WEDDING', preview: '1' }, { draft: { form, existing: null } });
    click(fixture, '.back-btn');
    expect(navCtrl.back).toHaveBeenCalledTimes(1);
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();
    // The quiz beneath reads the draft to tell this return from a created plan, and clears it itself.
    expect(draftService.draft()).toEqual({ form, existing: null });
    TestBed.resetTestingModule();

    const saved = await create({ id: '7' }, { goals: [houseGoal(7)] });
    click(saved, '.back-btn');
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    expect(navCtrl.back).not.toHaveBeenCalled();
  });

  it('saved mode shows no CTA; the menu offers Edit plan, Rename, Mark completed, Remove goal', async () => {
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7)] });
    const page = fixture.componentInstance;
    expect(page.mode()).toBe('saved');
    expect(fixture.nativeElement.querySelector('.fd-cta')).toBeNull();
    expect(text(fixture, '.header-title')).toBe('First home');

    click(fixture, '.gp-menu');
    await fixture.whenStable();
    expect(actionSheetCtrl.create).toHaveBeenCalledTimes(1);
    const buttons = actionSheetCtrl.create.calls.mostRecent().args[0]!.buttons as Array<{ text: string; role?: string; handler?: () => void }>;
    expect(buttons.map(b => b.text)).toEqual(['Edit plan', 'Rename', 'Mark completed', 'Remove goal', 'Cancel']);
    expect(buttons.find(b => b.text === 'Remove goal')!.role).toBe('destructive');

    // Each button fires its page method (fire-and-forget, so the sheet closes at once).
    const edit = spyOn(page, 'editPlan').and.callThrough();
    const rename = spyOn(page, 'renameGoal').and.resolveTo();
    const complete = spyOn(page, 'markCompleted').and.resolveTo();
    const remove = spyOn(page, 'confirmRemove').and.resolveTo();
    for (const b of buttons) b.handler?.();
    await fixture.whenStable();
    expect(edit).toHaveBeenCalled();
    expect(rename).toHaveBeenCalled();
    expect(complete).toHaveBeenCalled();
    expect(remove).toHaveBeenCalled();
    expect(navCtrl.navigateForward).toHaveBeenCalledWith(['/goals', 7, 'edit']);
    TestBed.resetTestingModule();

    // A completed goal offers Reopen; a vacation goal offers neither.
    const done = await create({ id: '7' }, { goals: [houseGoal(7, true)] });
    await done.componentInstance.openMenu();
    const doneButtons = actionSheetCtrl.create.calls.mostRecent().args[0]!.buttons as Array<{ text: string }>;
    expect(doneButtons.map(b => b.text)).toContain('Reopen');
    TestBed.resetTestingModule();

    const trips = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    await trips.componentInstance.openMenu();
    const tripButtons = actionSheetCtrl.create.calls.mostRecent().args[0]!.buttons as Array<{ text: string }>;
    expect(tripButtons.map(b => b.text)).toEqual(['Edit plan', 'Rename', 'Remove goal', 'Cancel']);
  });

  it('Rename in the menu prompts for the goal name and PUTs it', async () => {
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7)], alertRole: 'save', alertName: '  Lake house ' });
    echoUpdates();
    await fixture.componentInstance.renameGoal();
    expect(alertCtrl.create).toHaveBeenCalledTimes(1);
    const alertArgs = alertCtrl.create.calls.mostRecent().args[0]!;
    expect(alertArgs.header).toBe('Rename goal');
    expect(alertArgs.inputs![0].value).toBe('First home');
    expect(updateSpy).toHaveBeenCalledTimes(1);
    expect((updateSpy.calls.mostRecent().args[1] as GoalWrite).name).toBe('Lake house');
    fixture.detectChanges();
    expect(text(fixture, '.header-title')).toBe('Lake house');
    TestBed.resetTestingModule();

    // Cancel, or an empty name, writes nothing.
    const kept = await create({ id: '7' }, { goals: [houseGoal(7)], alertRole: 'cancel', alertName: 'Other' });
    await kept.componentInstance.renameGoal();
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('no draft on /goals/new/kids/plan navigates back to /my-profile', async () => {
    const fixture = await create({ type: 'KIDS', preview: '1' });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    expect(fixture.componentInstance.ready()).toBeFalse();
    expect(fixture.nativeElement.querySelector('.fd-hero')).toBeNull();
    TestBed.resetTestingModule();

    // A draft for another type does not render either.
    await create({ type: 'KIDS', preview: '1' }, { draft: { form: newDraft('HOUSE'), existing: null } });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('navigates back for a non-numeric id and loads goals before rendering a deep-linked goal', async () => {
    await create({ id: 'abc' });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    TestBed.resetTestingModule();

    const fixture = await create({ id: '7' }, { goals: [], loaded: [houseGoal(7)] });
    expect(load).toHaveBeenCalled();
    expect(fixture.componentInstance.mode()).toBe('saved');
    expect(fixture.componentInstance.existing()?.id).toBe(7);
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();
    TestBed.resetTestingModule();

    // Still absent after the load: go back.
    await create({ id: '9' }, { goals: [], loaded: [houseGoal(7)] });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
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

  // ── Hero ───────────────────────────────────────────────────────────────────

  it('the hero number counts up on first render unless reduced motion is on', async () => {
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7)] });
    const total = fmtMoney(fixture.componentInstance.view().monthlyTotal);
    expect(text(fixture, '.fd-hero-big')).toBe('$0/mo');
    // Halfway through 600ms the number is strictly between 0 and the total. (Angular's own
    // scheduler also queues frames, so every pending callback runs; only the count-up's matters.)
    const start = performance.now();
    rafCallbacks.splice(0).forEach(cb => cb(start + 300));
    fixture.detectChanges();
    const mid = text(fixture, '.fd-hero-big');
    expect(mid).not.toBe('$0/mo');
    expect(mid).not.toBe(`${total}/mo`);
    finishCountUp(fixture);
    expect(text(fixture, '.fd-hero-big')).toBe(`${total}/mo`);
    TestBed.resetTestingModule();

    // Reduced motion: the total shows at once and no frame ever changes it.
    reducedMotion = true;
    const still = await create({ id: '7' }, { goals: [houseGoal(7)] });
    const stillTotal = `${fmtMoney(still.componentInstance.view().monthlyTotal)}/mo`;
    expect(text(still, '.fd-hero-big')).toBe(stillTotal);
    rafCallbacks.splice(0).forEach(cb => cb(performance.now() + 300));
    still.detectChanges();
    expect(text(still, '.fd-hero-big')).toBe(stillTotal);
  });

  it('shows what is due now for a past-year target with a shortfall and hides the How FRED helps card', async () => {
    const goal: Goal = {
      ...houseGoal(8), type: 'CUSTOM', icon: 'flag', name: 'Boat',
      config: { targetAmount: 30_000, targetYear: YEAR - 1 },
      currentBalance: 24_000, anchorBalance: 1_000, anchorMonthly: 500,
      planAnchoredAt: localIso(new Date(YEAR - 2, 0, 15)), balanceUpdatedAt: localIso(new Date(YEAR - 1, 5, 15)),
    };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    finishCountUp(fixture);
    expect(text(fixture, '.fd-hero-label')).toBe('Due now');
    expect(text(fixture, '.fd-hero-big')).toBe('$6,000 due now');
    expect(text(fixture, '.fd-hero')).not.toContain('/mo');
    expect(text(fixture, '.fd-hero')).not.toContain('a paycheck');
    expect(text(fixture, '.fd-hero-meta')).toBe('Boat');
    expect(fixture.nativeElement.querySelector('.fd-how')).toBeNull();
    await fixture.componentInstance.markCompleted();
    expect((updateSpy.calls.mostRecent().args[1] as GoalWrite).completed).toBeTrue();
  });

  it('the band for a simple goal reads saved, percent and target with the pill and the status chip at its right end; a vacation reads this year and trips', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 8_000, anchorBalance: 8_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    expect(text(fixture, '.gp-band-text')).toBe(`Saved $8,000 · 10% there · Target $80K by Dec ${YEAR + 5}`);
    expect(text(fixture, '.gp-band .gp-band-right .fd-pill')).toBe('Invested');
    expect(text(fixture, '.gp-band .gp-band-right .gp-chip')).toBe('Plan started');
    expect(fixture.nativeElement.querySelector('.gp-zone')).toBeNull();
    expect(text(fixture)).not.toContain('Your plan');
    expect(fixture.nativeElement.querySelector('app-kid-timeline')).toBeNull();
    expect(fixture.nativeElement.querySelector('#gp-bal-current')).not.toBeNull();
    TestBed.resetTestingModule();

    const trips = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    expect(text(trips, '.gp-band-text')).toBe('Saved $0 this year · 0% there · $6,000 for 2 trips');
    expect(text(trips, '.gp-band .gp-band-right .fd-pill')).toBe('Cash');
    expect(text(trips, '.gp-band .gp-band-right .gp-chip')).toBe('Plan started');
    expect(trips.nativeElement.querySelector('#gp-bal-vacation')).not.toBeNull();
    expect(text(trips, '.gp-cash-note')).toContain('Planned as cash at ~4%');
  });

  // ── Kids: cards, pills, detail sheet, rename, gift days ────────────────────

  it('each kid unit row shows its Invested or Cash pill and tapping the label opens the detail sheet', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const page = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('app-kid-timeline')).not.toBeNull();
    expect(text(fixture, '.gp-zone')).toContain('Per kid');
    expect(text(fixture, '.gp-chip')).toBe('Plan started');

    const cards = Array.from(fixture.nativeElement.querySelectorAll('.gp-kcard')) as HTMLElement[];
    expect(cards.length).toBe(3);
    const sub = (i: number) => cards[i].querySelector('.gp-ksub')!.textContent!.trim();
    expect(sub(0)).toBe(`Born ${YEAR - 2}`);
    expect(sub(1)).toMatch(/^Arrives \d{4}$/);
    expect(sub(2)).toBe('Not dated yet');

    const rows = (i: number) => Array.from(cards[i].querySelectorAll('.gp-krow')).map(r => (r as HTMLElement).textContent!.replace(/\s+/g, ' ').trim());
    expect(rows(0).length).toBe(2);
    expect(rows(0)[0]).toContain('College');
    expect(rows(0)[0]).toContain('Invested');
    expect(rows(0)[0]).toContain(`$47,800 by ${YEAR - 2 + 18}`);
    expect(rows(0)[1]).toContain('Nest egg');
    expect(rows(0)[1]).toContain(`$100,000 by ${YEAR - 2 + 18}`);
    // Theo's baby fund is due in 19 months: cash.
    expect(rows(1).length).toBe(3);
    expect(rows(1)[0]).toContain('Baby fund');
    expect(rows(1)[0]).toContain('Cash');
    expect(rows(1)[0]).toContain('$20,800 by ');
    const pills = Array.from(cards[1].querySelectorAll('.fd-pill')).map(p => (p as HTMLElement).textContent!.trim());
    expect(pills).toEqual(['Cash', 'Invested', 'Invested']);
    expect(cards.every(c => c.querySelector('.gp-kmonthly')!.textContent!.includes('/mo'))).toBeTrue();

    (cards[0].querySelector('.gp-klabel') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const args = modalCtrl.create.calls.mostRecent().args[0];
    expect(args.component).toBe(GoalUnitDetailSheetComponent);
    const detail = (args.componentProps as { detail: { title: string; kidLabel: string; milestones: unknown[]; pill: string; perPaycheck: number | null } }).detail;
    expect(detail.title).toBe('College');
    expect(detail.kidLabel).toBe('Ellie');
    expect(detail.pill).toBe('Invested');
    expect(detail.milestones.length).toBe(3);
    expect(detail.perPaycheck).not.toBeNull();
    expect(page.renaming()).toBeNull();
  });

  it('tapping a unit target reveals a Saved so far field that saves on blur through updateBalance', async () => {
    const goal = kidsGoalFixture(5);
    const fixture = await create({ id: '5' }, { goals: [goal] });
    expect(fixture.nativeElement.querySelector('#gp-bal-plan529-0')).toBeNull();
    click(fixture, '.gp-ktarget');
    blurWith(fixture, '#gp-bal-plan529-0', '300');
    await fixture.whenStable();
    expect(updateSpy).toHaveBeenCalledTimes(1);
    const config = (updateSpy.calls.mostRecent().args[1] as GoalWrite).config as KidsConfig;
    expect(config.kids[0].accounts.plan529.balance).toBe(300);
    expect((updateSpy.calls.mostRecent().args[1] as GoalWrite).anchorMonthly).toBe(goal.anchorMonthly);
  });

  it('rename in place: tapping the pen shows an input; check PUTs the label and the kid card and hero audience update', async () => {
    const fixture = await create({ id: '5' }, { goals: [kidsGoalFixture(5)] });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    echoUpdates();
    expect(text(fixture, '.fd-hero-meta')).toContain('for Kid 1');
    expect(fixture.nativeElement.querySelector('.gp-rename')).toBeNull();

    click(fixture, '.gp-pen');
    expect(page.renaming()).toBe(0);
    const input = fixture.nativeElement.querySelector('.gp-rename') as HTMLInputElement;
    expect(input).not.toBeNull();
    expect(input.value).toBe('Kid 1');
    input.value = ' Theo ';
    click(fixture, '.gp-check');
    await fixture.whenStable();

    expect(updateSpy).toHaveBeenCalledTimes(1);
    const body = updateSpy.calls.mostRecent().args[1] as GoalWrite;
    expect((body.config as KidsConfig).kids[0].label).toBe('Theo');
    expect(body.anchorMonthly).toBe(kidsGoalFixture(5).anchorMonthly);
    fixture.detectChanges();
    expect(page.renaming()).toBeNull();
    expect(text(fixture, '.gp-kcard .gp-kname b')).toBe('Theo');
    expect(text(fixture, '.fd-hero-meta')).toContain('for Theo');
    expect(text(fixture, 'app-kid-timeline')).toContain('Theo');
  });

  it('rename during a pending balance save carries the latest balance and the balance PUT does not revert the label', async () => {
    const goal = kidsGoalFixture(5);
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const page = fixture.componentInstance;
    const pending = new Subject<Goal>();
    let calls = 0;
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      calls++;
      const updated = { ...(goals().find(g => g.id === id) as Goal), ...body } as Goal;
      goals.update(list => list.map(g => g.id === id ? updated : g));
      return calls === 1 ? pending.asObservable() : of(updated);
    });

    click(fixture, '.gp-ktarget');
    blurWith(fixture, '#gp-bal-plan529-0', '300');
    const renamed = page.commitRename(0, 'Theo');
    await Promise.resolve();
    expect(updateSpy).toHaveBeenCalledTimes(1); // the rename waits for the balance write

    const first = updateSpy.calls.argsFor(0)[1] as GoalWrite;
    pending.next({ ...goal, config: first.config });
    pending.complete();
    await renamed;

    expect(updateSpy).toHaveBeenCalledTimes(2);
    const second = (updateSpy.calls.argsFor(1)[1] as GoalWrite).config as KidsConfig;
    expect(second.kids[0].accounts.plan529.balance).toBe(300);
    expect(second.kids[0].label).toBe('Theo');

    // A later balance write is built from the renamed goal.
    await page.updateBalance({ kid: 0, account: 'brokerage' }, 900);
    const third = (updateSpy.calls.argsFor(2)[1] as GoalWrite).config as KidsConfig;
    expect(third.kids[0].label).toBe('Theo');
    expect(third.kids[0].accounts.plan529.balance).toBe(300);
    expect(third.kids[0].accounts.brokerage.balance).toBe(900);
  });

  it('kids plans are monthly only: no gift-days row, and a stored giftDays flag changes nothing', async () => {
    const stored = sampleFamily(5);
    const flagged: Goal = { ...stored, config: { ...(stored.config as KidsConfig), giftDays: true } };
    const fixture = await create({ id: '5' }, { goals: [flagged] });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    expect(fixture.nativeElement.querySelector('app-quiz-switch-row')).toBeNull();
    expect(text(fixture)).not.toContain('New Year');
    const plain = buildGoalPlanView(stored, { marketRate: 0.10, frequency: 'biweekly', now: NOW, rateKindOverride: null, reanchorPending: false });
    expect(page.view().monthlyTotal).toBe(plain.monthlyTotal);
    for (const account of page.view().kids!) {
      expect(account.perKid.map(k => k.plan.newYearLump)).toEqual(account.perKid.map(() => 0));
    }
  });

  it('in preview, a rename patches the draft instead of writing', async () => {
    const goal = sampleFamily(5);
    const form = formFromGoal(goal);
    const fixture = await create({ type: 'KIDS', preview: '1' }, { draft: { form, existing: null } });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    page.commitRename(0, 'Ellie Mae');
    fixture.detectChanges();
    expect(updateSpy).not.toHaveBeenCalled();
    expect(createSpy).not.toHaveBeenCalled();
    expect(text(fixture, '.gp-kcard .gp-kname b')).toBe('Ellie Mae');

    await page.startPlan();
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect((body.config as KidsConfig).kids[0].label).toBe('Ellie Mae');
  });

  it('shows Update balance on the chip when the last log is over 60 days old', async () => {
    const logged = localIso(new Date(NOW.getTime() - 90 * 86_400_000));
    const goal: Goal = {
      ...houseGoal(8), currentBalance: 10_000, anchorBalance: 10_000, anchorMonthly: 500,
      planAnchoredAt: logged, balanceUpdatedAt: logged,
    };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    expect(['ahead', 'on_plan', 'behind']).toContain(fixture.componentInstance.view().status.kind);
    expect(text(fixture, '.gp-chip')).toBe('Update balance');
    TestBed.resetTestingModule();

    const fresh = localIso(NOW);
    const fixture2 = await create({ id: '8' }, { goals: [{ ...goal, planAnchoredAt: fresh, balanceUpdatedAt: fresh }] });
    expect(text(fixture2, '.gp-chip')).not.toBe('Update balance');
  });

  // ── Balances: serialization and sanitizing ─────────────────────────────────

  it('Update my plan waits for a pending balance write before building its body', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_000, anchorBalance: 1_000, anchorMonthly: 500 };
    const form = { ...formFromGoal(goal), price: 450_000 };
    const fixture = await create({ id: '8', preview: '1' }, { goals: [goal], draft: { form, existing: goal } });
    const page = fixture.componentInstance;
    const pending = new Subject<Goal>();
    let calls = 0;
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      calls++;
      return calls === 1 ? pending.asObservable() : of({ ...goal, ...body } as Goal);
    });

    blurWith(fixture, '#gp-bal-current', '12000');
    const saving = page.updatePlan();
    await Promise.resolve();
    expect(updateSpy).toHaveBeenCalledTimes(1); // Update has not sent yet

    pending.next({ ...goal, currentBalance: 12_000 });
    pending.complete();
    await saving;

    expect(updateSpy).toHaveBeenCalledTimes(2);
    const body = updateSpy.calls.mostRecent().args[1] as GoalWrite;
    expect(body.currentBalance).toBe(12_000);
    expect((body.config as { price: number }).price).toBe(450_000);
    expect(page.saving()).toBeFalse();
  });

  it('builds the second of two quick balance blurs from the first write', async () => {
    const goal = kidsGoalFixture(5);
    const fixture = await create({ id: '5' }, { goals: [goal] });
    const page = fixture.componentInstance;
    const pending = new Subject<Goal>();
    let calls = 0;
    updateSpy.and.callFake((id: number, body: GoalWrite) => {
      calls++;
      return calls === 1 ? pending.asObservable() : of({ ...goal, ...body } as Goal);
    });

    click(fixture, '.gp-ktarget');
    blurWith(fixture, '#gp-bal-plan529-0', '300');
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

  it('shows whole dollars and never rewrites a stored balance with cents on focus and blur', async () => {
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_234.56 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const input = fixture.nativeElement.querySelector('#gp-bal-current') as HTMLInputElement;
    expect(input.value).toBe('1235');
    input.dispatchEvent(new Event('blur'));
    await fixture.componentInstance.updateBalance('current', 1_235);
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('writes savedYear on a vacation balance edit and records a preview balance in the draft for the POST', async () => {
    const goal = vacationGoalFixture(6);
    const fixture = await create({ id: '6' }, { goals: [goal] });
    await fixture.componentInstance.updateBalance('vacation', 1_200);
    const [id, body] = updateSpy.calls.mostRecent().args as [number, GoalWrite];
    expect(id).toBe(6);
    expect((body.config as VacationConfig).savedThisYear).toBe(1_200);
    expect((body.config as VacationConfig).savedYear).toBe(YEAR);
    TestBed.resetTestingModule();

    const preview = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null } });
    blurWith(preview, '#gp-bal-current', '5000');
    expect(updateSpy).not.toHaveBeenCalled();
    await preview.componentInstance.startPlan();
    expect((createSpy.calls.mostRecent().args[0] as GoalWrite).currentBalance).toBe(5_000);
  });

  // ── Errors, tier nudge, remove, analytics ──────────────────────────────────

  it('keeps the preview and opens the nudge on a 403 TIER_LIMIT', async () => {
    const err = new HttpErrorResponse({ status: 403, error: { code: 'TIER_LIMIT', message: 'One active goal on Core.' } });
    const fixture = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null }, createResult: throwError(() => err), tier: 'core' });
    const page = fixture.componentInstance;
    await page.startPlan();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    expect(modalCtrl.create.calls.mostRecent().args[0].component).toBe(McInfoSheetComponent);
    expect(modalCtrl.create.calls.mostRecent().args[0].componentProps).toEqual(jasmine.objectContaining({
      mode: 'nudge', nudgeTitle: 'Plan more than one goal with Piggy Plus', nudgeCtaLabel: 'Upgrade to Piggy Plus', targetTier: 'plus',
    }));
    expect(showToast).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(page.mode()).toBe('preview-new');
    expect(draftService.draft()).not.toBeNull();
    const nudges = track.calls.allArgs().map(a => a[0]).filter(e => e.name === 'paywall_nudge_shown');
    expect(nudges).toEqual([
      { name: 'paywall_nudge_shown', props: { feature: 'goals', tier: 'core', action: 'shown' } },
      { name: 'paywall_nudge_shown', props: { feature: 'goals', tier: 'core', action: 'dismissed' } },
    ]);
  });

  it('on 401 keeps the preview, toasts, and calls logout', async () => {
    const err = new HttpErrorResponse({ status: 401 });
    const fixture = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null }, createResult: throwError(() => err) });
    await fixture.componentInstance.startPlan();
    expect(showToast).toHaveBeenCalledWith('Your session ended. Sign in again to save this plan.', 'danger');
    expect(logout).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(trackErrorShown).not.toHaveBeenCalled();
    expect(draftService.draft()).not.toBeNull();
  });

  it('toasts and reports any other save failure, and a network failure as network', async () => {
    const fixture = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null }, createResult: throwError(() => new HttpErrorResponse({ status: 500 })) });
    await fixture.componentInstance.startPlan();
    expect(showToast).toHaveBeenCalledWith('Could not save this goal. Try again.', 'danger');
    expect(trackErrorShown).toHaveBeenCalledWith('save_failed', 'goals', 'toast', 500, true);
    TestBed.resetTestingModule();

    const offline = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null }, createResult: throwError(() => new HttpErrorResponse({ status: 0 })) });
    await offline.componentInstance.startPlan();
    // httpStatusOf reports Angular's status 0 as no status.
    expect(trackErrorShown).toHaveBeenCalledWith('network', 'goals', 'toast', null, true);
  });

  it('opens the Plus nudge when Reopen is refused with TIER_LIMIT', async () => {
    const err = new HttpErrorResponse({ status: 403, error: { code: 'TIER_LIMIT' } });
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7, true)], tier: 'core', updateResult: throwError(() => err) });
    await fixture.componentInstance.markCompleted();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    expect(modalCtrl.create.calls.mostRecent().args[0].component).toBe(McInfoSheetComponent);
    expect(showToast).not.toHaveBeenCalled();
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
    const fixture = await create({ type: 'HOUSE', preview: '1' }, { draft: { form: newDraft('HOUSE'), existing: null } });
    const page = fixture.componentInstance;
    const setRates = spyOn(TestBed.inject(RateAssumptionsService), 'setRates');
    // The real service appends the created goal to the signal; the mock does not.
    createSpy.and.callFake((body: GoalWrite) => {
      const created = { ...houseGoal(99), ...body } as Goal;
      goals.update(list => [...list, created]);
      return of(created);
    });
    await page.startPlan();
    const events = track.calls.allArgs().map(a => a[0]).filter(e => e.name === 'goal_changed');
    expect(events).toEqual([{
      name: 'goal_changed',
      props: {
        action: 'created', goal_type: 'HOUSE', icon: 'home', plan_rate: '10',
        target_band: '50k_249k', years_to_target: '3_5', progress: '0_9', goal_count: '1',
      },
    }]);
    // The return is set only on the Freedom Plan page; Start never writes it.
    expect(setRates).not.toHaveBeenCalled();
  });

  it('reports cash as the plan rate for an emergency fund and tracks completed, reopened and removed', async () => {
    const fixture = await create({ type: 'EMERGENCY_FUND', preview: '1' }, { draft: { form: newDraft('EMERGENCY_FUND'), existing: null } });
    await fixture.componentInstance.startPlan();
    const ev = track.calls.allArgs().map(a => a[0]).find(e => e.name === 'goal_changed');
    expect(ev.props.plan_rate).toBe('cash');
    expect(ev.props.goal_type).toBe('EMERGENCY_FUND');
    expect(ev.props.action).toBe('created');
    TestBed.resetTestingModule();

    const saved = await create({ id: '7' }, { goals: [houseGoal(7), houseGoal(8)], dismissRole: 'remove' });
    const page = saved.componentInstance;
    echoUpdates();
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

  it('still navigates and toasts when analytics throws', async () => {
    const fixture = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null } });
    track.and.throwError('boom');
    await fixture.componentInstance.startPlan();
    expect(showToast).toHaveBeenCalledWith('Plan started', 'success');
    expect(router.navigate).toHaveBeenCalledWith(['/goals', 99], { replaceUrl: true });
    expect(trackErrorShown).not.toHaveBeenCalled();
  });

  it('shows the shared return read-only on a market plan: the figure, no chips, no reset, and where to change it', async () => {
    const fixture = await create({ id: '7' }, { goals: [{ ...houseGoal(7), planRate: 0.10 }] });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(page.annualReturn()).toBe(0.10);
    expect(el.querySelector('.fd-assume')).toBeTruthy();
    expect(text(fixture, '.fd-assume .fd-assume-name')).toContain('Avg annual return');
    expect(text(fixture, '.fd-assume .fd-assume-value')).toBe('10%');
    expect(el.querySelectorAll('.fd-assume button').length).toBe(0);
    expect(el.querySelector('.fd-reset')).toBeFalsy();
    // The "where to change it" line lives inside the card, as its sub line (Andy, 2026-11-10).
    expect(text(fixture, '.fd-assume .fd-assume-sub')).toBe('Set on your Freedom Plan page');
    expect(el.querySelector('.gp-assume-note')).toBeFalsy();
    expect((page as any).selectRate).toBeUndefined();
  });

  it('follows the shared return live: a change made on the Freedom Plan page re-solves the hero here', async () => {
    const goal = { ...houseGoal(7), planRate: 0.10 };
    const fixture = await create({ id: '7' }, { goals: [goal] });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    const rates = TestBed.inject(RateAssumptionsService);
    expect(page.annualReturn()).toBe(rates.annualReturn());
    const before = page.view().monthlyTotal;

    rates.setRates(0.12, rates.withdrawalRate());
    fixture.detectChanges();

    expect(page.annualReturn()).toBe(0.12);
    expect(page.view().monthlyTotal).toBeLessThan(before);
    expect(text(fixture, '.fd-hero-big')).toBe(`${fmtMoney(page.view().monthlyTotal)}/mo`);
    expect(text(fixture, '.fd-assume .fd-assume-value')).toBe('12%');
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('re-reads the goal when revealed beneath a popped edit preview', async () => {
    const goal = { ...houseGoal(7), planRate: 0.10 };
    const fixture = await create({ id: '7' }, { goals: [goal] });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    const updated: Goal = { ...goal, anchorMonthly: 777 };
    goals.set([updated]);
    page.ionViewWillEnter();
    fixture.detectChanges();
    expect(page.existing()).toBe(updated);
  });

  it('has no em dash in rendered text', async () => {
    const kids = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    kids.componentInstance.beginRename(0);
    kids.detectChanges();
    expect(text(kids)).not.toContain(String.fromCharCode(0x2014));
    TestBed.resetTestingModule();

    const vacation = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    expect(text(vacation)).not.toContain(String.fromCharCode(0x2014));
    TestBed.resetTestingModule();

    const wedding = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null } });
    expect(text(wedding)).not.toContain(String.fromCharCode(0x2014));
  });
});
