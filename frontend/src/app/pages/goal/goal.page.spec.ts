import { CUSTOM_ELEMENTS_SCHEMA, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { By } from '@angular/platform-browser';
import { ModalController, NavController } from '@ionic/angular/standalone';
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
import {
  FRED_ACTION_SHEET_ROLE, FredActionSheetComponent, FredActionSheetOptions,
} from '../../components/fred-action-sheet/fred-action-sheet.component';
import {
  FRED_TEXT_PROMPT_ROLE, FredTextPromptSheetComponent,
} from '../../components/fred-text-prompt-sheet/fred-text-prompt-sheet.component';
import { GoalUnitDetailSheetComponent, UnitDetail } from '../../components/goal-unit-detail-sheet/goal-unit-detail-sheet.component';
import { InfoNote } from '../../components/goal-plan-info-sheet/info-note';
import { kidCostFigures } from '../../utils/kid-cost.utils';
import { KidTimelineComponent } from '../../components/kid-timeline/kid-timeline.component';
import { AmountTraySlotComponent } from '../../components/plan-amount-tray/amount-tray-slot.component';
import { SkeletonComponent } from '../../components/skeleton/skeleton.component';
import { TallyPillsComponent } from '../../components/tally-pills/tally-pills.component';
import { PlanAmountTrayComponent } from '../../components/plan-amount-tray/plan-amount-tray.component';
import { detents } from '../../utils/plan-amount-scale';
import { toCountBand } from '../../services/analytics/analytics-bands';
import { Goal, GoalWrite, KidsConfig, LedgerKind, UnitTally, VacationConfig } from '../../models/goal.model';
import { GoalForm, defaultForm, formFromGoal } from './goal-form.model';
import { buildGoalPlanView, fmtCompact, fmtMoney } from './goal-plan.view-model';

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

/** A server tally for one unit: credited, on plan, $450 a payday, the next one on Jan 1 next year. */
function tallyOf(patch: Partial<UnitTally> = {}): UnitTally {
  return {
    tally: 900, creditedThrough: `${YEAR}-11-15`, creditsUntil: `${YEAR + 5}-12-31`,
    nextCreditDate: `${YEAR + 1}-01-01`, nextCreditAmount: 450, net: 0, netSince: null, startedCrediting: true,
    ...patch,
  };
}

/** A house anchored at $900 this month with the server tally beside it. */
function talliedHouse(id: number, patch: Partial<UnitTally> = {}): Goal {
  const t = tallyOf(patch);
  return {
    ...houseGoal(id), currentBalance: t.tally, anchorBalance: 900, anchorMonthly: 450,
    planAnchoredAt: localIso(NOW), tally: t,
  };
}

function vacationGoalFixture(id: number): Goal {
  const config: VacationConfig = { tripsPerYear: 2, budgetPerTrip: 3_000, savedThisYear: 0, savedYear: YEAR };
  return { ...houseGoal(id), type: 'VACATION', name: 'Trips', icon: 'flight', config, planRateKind: 'cash', planRate: 0.04 };
}

/** A new-goal draft the quiz would hand over for this type. */
function newDraft(type: GoalForm['type'], patch: Partial<GoalForm> = {}): GoalForm {
  const form = defaultForm(type, NOW);
  if (type === 'WEDDING') Object.assign(form, { targetAmount: 60_000, targetYear: YEAR + 6 });
  if (type === 'HOUSE') Object.assign(form, { price: 400_000, downPaymentPct: 20, targetYear: YEAR + 5 });
  if (type === 'EMERGENCY_FUND') Object.assign(form, { monthlyExpenses: 3_000, months: 6, targetYear: YEAR + 2 });
  return { ...form, ...patch };
}

describe('GoalPage', () => {
  let navCtrl: jasmine.SpyObj<NavController>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  /** The row the mocked action sheet "taps" (null = dismissed with no role). A spec may reassign it between menus. */
  let menuPick: string | null;
  let router: jasmine.SpyObj<Router>;
  let draftService: GoalDraftService;
  let goals: ReturnType<typeof signal<Goal[]>>;
  let load: jasmine.Spy;
  let createSpy: jasmine.Spy;
  let updateSpy: jasmine.Spy;
  let removeSpy: jasmine.Spy;
  let adjustSpy: jasmine.Spy;
  let refreshSpy: jasmine.Spy;
  let showToast: jasmine.Spy;
  let show: jasmine.Spy;
  let trackErrorShown: jasmine.Spy;
  let track: jasmine.Spy;
  let logout: jasmine.Spy;
  let progress: BehaviorSubject<{ selectedTier: string } | null>;
  let rafCallbacks: FrameRequestCallback[];
  let reducedMotion = false;

  interface CreateOpts {
    goals?: Goal[]; loaded?: Goal[]; tier?: string;
    /** How each mocked sheet dismisses: any other modal by role, the action sheet by row id, the text prompt by role + value. */
    dismissRole?: string; menuPick?: string; promptRole?: string; promptValue?: string;
    createResult?: Observable<Goal>; updateResult?: Observable<Goal>; loadError?: unknown;
    /** What a ledger post returns instead of the echo below. */
    adjustResult?: Observable<{ goal: Goal; capped: boolean }>;
    /** What the goals load returns instead of the fixtures, e.g. a Subject the spec completes later. */
    loadResult?: Observable<Goal[]>;
    /** The quiz's draft, set before the page opens. */
    draft?: { form: GoalForm; existing: Goal | null };
  }

  async function create(params: Record<string, string>, opts: CreateOpts = {}): Promise<ComponentFixture<GoalPage>> {
    goals = signal<Goal[]>(opts.goals ?? []);
    load = jasmine.createSpy('load').and.callFake(() => {
      if (opts.loadError) return throwError(() => opts.loadError);
      if (opts.loadResult) return opts.loadResult;
      if (opts.loaded) goals.set(opts.loaded);
      return of(goals());
    });
    createSpy = jasmine.createSpy('create').and.callFake((body: GoalWrite) =>
      opts.createResult ?? of({ ...houseGoal(99), ...body } as Goal));
    updateSpy = jasmine.createSpy('update').and.callFake((id: number, body: GoalWrite) =>
      opts.updateResult ?? of({ ...(goals().find(g => g.id === id) ?? houseGoal(id)), ...body } as Goal));
    removeSpy = jasmine.createSpy('remove').and.returnValue(of(undefined));
    // The real service posts to /ledger and patches its signal with the goal the server returns.
    adjustSpy = jasmine.createSpy('adjust').and.callFake((id: number, unit: string, kind: LedgerKind, amount?: number) => {
      if (opts.adjustResult) return opts.adjustResult;
      const g = goals().find(x => x.id === id) ?? houseGoal(id);
      const t = g.tally ?? null;
      // An Undo sends the exact amount; a tap sends none and moves one share.
      const share = kind === 'set' ? 0 : amount ?? t?.nextCreditAmount ?? 0;
      const before = t?.tally ?? g.currentBalance;
      const raw = kind === 'set' ? amount! : kind === 'missed' ? before - share : before + share;
      const tally = Math.max(0, raw);
      const net = kind === 'missed' ? -share : kind === 'extra' ? share : 0;
      const updated: Goal = { ...g, currentBalance: tally, tally: t ? { ...t, tally, net: t.net + net } : t };
      goals.update(list => list.map(x => (x.id === id ? updated : x)));
      return of({ goal: updated, capped: raw < 0 });
    });
    refreshSpy = jasmine.createSpy('refresh').and.callFake(() => of(goals()));
    showToast = jasmine.createSpy('showToast');
    show = jasmine.createSpy('show');
    trackErrorShown = jasmine.createSpy('trackErrorShown');
    track = jasmine.createSpy('track');
    logout = jasmine.createSpy('logout');
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    progress = new BehaviorSubject<{ selectedTier: string } | null>({ selectedTier: opts.tier ?? 'plus' });
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward', 'back']);
    navCtrl.navigateBack.and.resolveTo(true);
    navCtrl.navigateForward.and.resolveTo(true);
    menuPick = opts.menuPick ?? null;
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    // Every sheet is one ModalController.create; what it dismisses with depends on which sheet it is.
    modalCtrl.create.and.callFake(async ({ component }) => {
      const dismissed = () => {
        if (component === FredActionSheetComponent) {
          return menuPick === null ? { role: undefined } : { role: FRED_ACTION_SHEET_ROLE, data: { id: menuPick } };
        }
        if (component === FredTextPromptSheetComponent) return { role: opts.promptRole, data: { value: opts.promptValue } };
        return { role: opts.dismissRole };
      };
      return { present: async () => {}, onDidDismiss: async () => dismissed() } as unknown as HTMLIonModalElement;
    });

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
        { provide: GoalsService, useValue: { goals, load, create: createSpy, update: updateSpy, remove: removeSpy, adjust: adjustSpy, refresh: refreshSpy } },
        { provide: AuthService, useValue: {
            userProgress$: progress,
            getCurrentInvestmentSchedule: () => of({ investmentAmount: 500, frequency: 'BIWEEKLY' }),
            logout,
        } },
        { provide: ToastService, useValue: { showToast, show } },
        { provide: AnalyticsService, useValue: { track, trackErrorShown } },
        { provide: GOAL_PAGE_NOW, useValue: () => NOW },
      ],
    })
      // Ionic standalone components need their own providers; render their children as plain DOM.
      .overrideComponent(GoalPage, {
        set: { imports: [CommonModule, KidTimelineComponent, AmountTraySlotComponent, SkeletonComponent, TallyPillsComponent], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
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

  const trayOf = (fixture: ComponentFixture<GoalPage>) =>
    fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;

  /** Opens the Saved so far tile's tray (tap the value), under reduced motion so it snaps open. */
  function openTray(fixture: ComponentFixture<GoalPage>, field: 'current' | 'vacation' = 'current'): void {
    click(fixture, `#gp-bal-${field}`);
    expect(fixture.componentInstance.tray.open()).toBe(field);
  }

  /** The slider landing on a value: the tray's output, as a finger or a nudge would raise it. */
  function slideTo(fixture: ComponentFixture<GoalPage>, value: number): void {
    trayOf(fixture)!.valueChange.emit(value);
    fixture.detectChanges();
  }

  /** A tap as the host's outside-tap listener sees it: a press and a release on the same element. */
  function press(fixture: ComponentFixture<GoalPage>, selector: string): void {
    const target = fixture.nativeElement.querySelector(selector) as HTMLElement;
    expect(target).withContext(selector).not.toBeNull();
    target.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    target.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
    fixture.detectChanges();
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
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const args = modalCtrl.create.calls.mostRecent().args[0];
    expect(args.component).toBe(FredActionSheetComponent);
    expect(args.cssClass).toBe('mc-bottom-sheet-modal');
    const props = args.componentProps as FredActionSheetOptions;
    // Header: the goal's icon and name, then the hero's monthly and the status chip.
    expect(props.header.icon).toBe('home');
    expect(props.header.title).toBe('First home');
    expect(props.header.subtitle).toBe(`${page.fmtMoney(page.view().monthlyTotal)}/mo · ${page.statusChip()}`);
    expect(props.header.subtitle).toMatch(/^\$\d[\d,]*\/mo · Plan started$/);
    expect(props.actions.map(a => a.label)).toEqual(['Edit plan', 'Rename', 'Mark completed', 'Remove goal']);
    expect(props.actions.map(a => a.id)).toEqual(['edit', 'rename', 'complete', 'remove']);
    expect(props.actions.map(a => a.helper)).toEqual([
      'Price, down payment and the year', 'First home', 'Keeps it on your profile as done', 'Its plan goes with it',
    ]);
    expect(props.actions.map(a => !!a.destructive)).toEqual([false, false, false, true]);

    // Dismissed with no row: nothing ran (no edit navigation, no second sheet, no write).
    expect(navCtrl.navigateForward).not.toHaveBeenCalled();
    expect(updateSpy).not.toHaveBeenCalled();
    const edit = spyOn(page, 'editPlan').and.callThrough();
    const rename = spyOn(page, 'renameGoal').and.resolveTo();
    const complete = spyOn(page, 'markCompleted').and.resolveTo();
    const remove = spyOn(page, 'confirmRemove').and.resolveTo();

    // Each row id fires its page method (fire-and-forget, so the sheet closes at once).
    for (const id of ['edit', 'rename', 'complete', 'remove']) {
      menuPick = id;
      await page.openMenu();
    }
    await fixture.whenStable();
    expect(edit).toHaveBeenCalledTimes(1);
    expect(rename).toHaveBeenCalledTimes(1);
    expect(complete).toHaveBeenCalledTimes(1);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(navCtrl.navigateForward).toHaveBeenCalledWith(['/goals', 7, 'edit']);
    TestBed.resetTestingModule();

    // A completed goal offers Reopen; a vacation goal offers neither; a kids plan names what its quiz asks.
    const done = await create({ id: '7' }, { goals: [houseGoal(7, true)] });
    await done.componentInstance.openMenu();
    const doneActions = (modalCtrl.create.calls.mostRecent().args[0].componentProps as FredActionSheetOptions).actions;
    expect(doneActions.map(a => a.label)).toEqual(['Edit plan', 'Rename', 'Reopen', 'Remove goal']);
    expect(doneActions[2].icon).toBe('undo');
    TestBed.resetTestingModule();

    const trips = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    await trips.componentInstance.openMenu();
    const tripProps = modalCtrl.create.calls.mostRecent().args[0].componentProps as FredActionSheetOptions;
    expect(tripProps.header.icon).toBe('flight');
    expect(tripProps.actions.map(a => a.label)).toEqual(['Edit plan', 'Rename', 'Remove goal']);
    expect(tripProps.actions[0].helper).toBe('Trips a year and the budget per trip');
    TestBed.resetTestingModule();

    const kids = await create({ id: '5' }, { goals: [kidsGoalFixture(5)] });
    await kids.componentInstance.openMenu();
    const kidProps = modalCtrl.create.calls.mostRecent().args[0].componentProps as FredActionSheetOptions;
    expect(kidProps.header.icon).toBe('child_care');
    expect(kidProps.actions[0].helper).toBe('Kids, costs and the return you assume');
  });

  it('Rename in the menu prompts for the goal name and PUTs it', async () => {
    const fixture = await create({ id: '7' }, { goals: [houseGoal(7)], promptRole: FRED_TEXT_PROMPT_ROLE, promptValue: 'Lake house' });
    echoUpdates();
    await fixture.componentInstance.renameGoal();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const args = modalCtrl.create.calls.mostRecent().args[0];
    expect(args.component).toBe(FredTextPromptSheetComponent);
    expect(args.cssClass).toBe('mc-bottom-sheet-modal');
    expect(args.componentProps).toEqual({ title: 'Rename goal', label: 'Name', value: 'First home', maxlength: 50 });
    expect(updateSpy).toHaveBeenCalledTimes(1);
    expect((updateSpy.calls.mostRecent().args[1] as GoalWrite).name).toBe('Lake house');
    fixture.detectChanges();
    expect(text(fixture, '.header-title')).toBe('Lake house');
    TestBed.resetTestingModule();

    // Dismissed without the save role (Cancel, drag, tap outside): nothing is written.
    const kept = await create({ id: '7' }, { goals: [houseGoal(7)], promptValue: 'Other' });
    await kept.componentInstance.renameGoal();
    expect(updateSpy).not.toHaveBeenCalled();
    TestBed.resetTestingModule();

    // Saved with the name it already had: nothing is written either.
    const same = await create({ id: '7' }, { goals: [houseGoal(7)], promptRole: FRED_TEXT_PROMPT_ROLE, promptValue: 'First home' });
    await same.componentInstance.renameGoal();
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

  it('shows the plan skeleton with aria-busy while a deep-linked goal loads, and the real hero once it lands', async () => {
    const pending = new Subject<Goal[]>();
    const fixture = await create({ id: '7' }, { loadResult: pending });
    const inner = () => fixture.nativeElement.querySelector('.gp-inner') as HTMLElement;
    const skeletons = () => fixture.nativeElement.querySelectorAll('app-skeleton').length;

    expect(fixture.componentInstance.ready()).toBeFalse();
    expect(inner().getAttribute('aria-busy')).toBe('true');
    expect(skeletons()).toBeGreaterThan(0);
    expect(fixture.nativeElement.querySelector('.gp-sk-hero')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.fd-tile app-skeleton')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.fd-assume app-skeleton')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.gp-sk-lines .sk-block').length).toBe(2);
    // Only the saved deep link loads (previews are ready at once), and a saved goal has no CTA,
    // so the skeleton draws none: a bar there would vanish on every swap.
    expect(fixture.nativeElement.querySelector('app-skeleton.gp-cta')).toBeNull();
    // No real content while loading.
    expect(fixture.nativeElement.querySelector('.fd-hero')).toBeNull();
    expect(fixture.nativeElement.querySelector('.fd-hero-big')).toBeNull();
    expect(fixture.nativeElement.querySelector('#gp-bal-current')).toBeNull();
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();

    goals.set([houseGoal(7)]);
    pending.next(goals());
    pending.complete();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.ready()).toBeTrue();
    expect(inner().getAttribute('aria-busy')).toBeNull();
    expect(skeletons()).toBe(0);
    expect(fixture.nativeElement.querySelector('.gp-sk-hero')).toBeNull();
    expect(fixture.nativeElement.querySelector('.fd-hero-big')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#gp-bal-current')).not.toBeNull();
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
      // Re-anchored at $24,000 in the target year's last month: the plan's value is $24,000.
      currentBalance: 24_000, anchorBalance: 24_000, anchorMonthly: 0,
      planAnchoredAt: localIso(new Date(YEAR - 1, 11, 15)), balanceUpdatedAt: localIso(new Date(YEAR - 1, 11, 15)),
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

  it('the band for a simple goal reads saved and its part with the pill and the status chip at its right end; a vacation reads its year of trips', async () => {
    // Anchored this month: your part is $8,000 plus $500 for each month through Dec of the target year.
    const goal: Goal = { ...houseGoal(8), currentBalance: 8_000, anchorBalance: 8_000, anchorMonthly: 500, planAnchoredAt: localIso(NOW) };
    const months = 5 * 12 + (12 - (NOW.getMonth() + 1));
    const part = 8_000 + 500 * months;
    const pct = Math.round((8_000 / part) * 100);
    const fixture = await create({ id: '8' }, { goals: [goal] });
    // No tally read yet: no Next and no Behind by.
    expect(text(fixture, '.gp-band-text')).toBe(`Saved $8,000 · ${pct}% of your ${fmtCompact(part)}`);
    expect(text(fixture, '.gp-band .gp-band-right .fd-pill')).toBe('Invested');
    expect(text(fixture, '.gp-band .gp-band-right .gp-chip')).toBe('Plan started');
    expect(fixture.nativeElement.querySelector('.gp-zone')).toBeNull();
    expect(text(fixture)).not.toContain('Your plan');
    expect(fixture.nativeElement.querySelector('app-kid-timeline')).toBeNull();
    expect(fixture.nativeElement.querySelector('#gp-bal-current')).not.toBeNull();
    TestBed.resetTestingModule();

    const trips = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    expect(text(trips, '.gp-band-text')).toBe('Saved $0 · 0% of this year\'s $6K');
    expect(text(trips, '.gp-band .gp-band-right .fd-pill')).toBe('Cash');
    expect(text(trips, '.gp-band .gp-band-right .gp-chip')).toBe('Plan started');
    expect(trips.nativeElement.querySelector('#gp-bal-vacation')).not.toBeNull();
    expect(text(trips, '.gp-cash-note')).toContain('Planned as cash at ~4%');
  });

  // ── Kids: cards, pills, detail sheet, rename, gift days ────────────────────

  it('each kid unit row shows its Invested or Cash pill and tapping the row opens the detail sheet', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const page = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('app-kid-timeline')).not.toBeNull();
    expect(text(fixture, '.gp-zone')).toContain('Per kid');
    expect(text(fixture, '.gp-chip')).toBe('Plan started');

    const cards = Array.from(fixture.nativeElement.querySelectorAll('.gp-kcard')) as HTMLElement[];
    expect(cards.length).toBe(3);
    const sub = (i: number) => cards[i].querySelector('.gp-ksub')!.textContent!.trim();
    expect(sub(0)).toBe(`Born ${YEAR - 2} · age 2`);
    expect(sub(1)).toMatch(/^Arrives \d{4}$/);
    expect(sub(2)).toBe('Not dated yet');

    const rows = (i: number) => Array.from(cards[i].querySelectorAll('.gp-krow:not(.everyday)')).map(r => (r as HTMLElement).textContent!.replace(/\s+/g, ' ').trim());
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

    (cards[0].querySelector('button.gp-krow') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const args = modalCtrl.create.calls.mostRecent().args[0];
    expect(args.component).toBe(GoalUnitDetailSheetComponent);
    const detail = (args.componentProps as { detail: () => { title: string; kidLabel: string; milestones: unknown[]; pill: string; perPaycheck: number | null } }).detail();
    expect(detail.title).toBe('College');
    expect(detail.kidLabel).toBe('Ellie');
    expect(detail.pill).toBe('Invested');
    expect(detail.milestones.length).toBe(3);
    expect(detail.perPaycheck).not.toBeNull();
    expect(page.renaming()).toBeNull();
  });

  it('each kid card header is the kid\'s whole month and the cards add up to the hero', async () => {
    const fixture = await create({ id: '3' }, { goals: [kidsGoalFixture(3)] });
    finishCountUp(fixture);
    const page = fixture.componentInstance;
    const cards = page.kidCards();
    expect(cards.length).toBe(1);
    // One kid at home, so the everyday figure is real money, not a zero that hides a bug.
    expect(cards[0].everyday.monthly).toBeGreaterThan(0);
    expect(cards[0].everyday).toBe(page.view().everyday![0]);
    expect(cards[0].investMonthly).toBe(page.view().investMonthly);
    cards.forEach(c => expect(c.monthly).toBe(c.investMonthly + c.everyday.monthly));
    expect(cards.reduce((s, c) => s + c.monthly, 0)).toBe(page.view().monthlyTotal);
    expect(text(fixture, '.fd-hero-label')).toBe('Family plan');
  });

  it('each kid card header is the kid\'s whole month and the cards add up to the hero, across three cards (priced, unborn, undated)', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    finishCountUp(fixture);
    const page = fixture.componentInstance;
    const cards = page.kidCards();
    expect(cards.length).toBe(3);
    expect(cards.map(c => c.everyday.state)).toEqual(['priced', 'unborn', 'unborn']);
    expect(cards[0].everyday.monthly).toBeGreaterThan(0);
    cards.forEach((c, i) => expect(c.everyday).toBe(page.view().everyday![i]));
    cards.forEach(c => expect(c.monthly).toBe(c.investMonthly + c.everyday.monthly));
    expect(cards.reduce((s, c) => s + c.investMonthly, 0)).toBe(page.view().investMonthly);
    expect(cards.reduce((s, c) => s + c.monthly, 0)).toBe(page.view().monthlyTotal);
    expect(text(fixture, '.fd-hero-label')).toBe('Family plan');
  });

  it('a card whose kid is 18 or older has no Everyday group and no everyday row, but keeps its Invest group', async () => {
    const base = kidsGoalFixture(6);
    const config: KidsConfig = {
      ...(base.config as KidsConfig),
      kids: [
        { label: 'Ellie', birthYear: YEAR - 2, birthMonth: 5, accounts: { plan529: fresh(), brokerage: fresh() } },
        { label: 'Sam', birthYear: YEAR - 19, birthMonth: 1, accounts: { plan529: fresh(), brokerage: fresh() } },
      ],
    };
    const fixture = await create({ id: '6' }, { goals: [{ ...base, config }] });
    const page = fixture.componentInstance;
    expect(page.kidCards()[1].everyday.state).toBe('grown');
    const card = (fixture.nativeElement as HTMLElement).querySelectorAll('.gp-kcard')[1];
    expect(card.querySelector('.gp-group.second')).toBeNull();
    expect(card.querySelector('.gp-krow.everyday')).toBeNull();
    expect(card.querySelector('.gp-from')).toBeNull();
    const groups = Array.from(card.querySelectorAll('.gp-group')) as HTMLElement[];
    expect(groups.length).toBe(1);
    expect(groups[0].textContent).toContain('Invest');
  });

  it('the sub line carries the age: born kids "Born 2024 · age 2", under a year "under 1", unborn unchanged', async () => {
    const kid = (label: string, birthYear: number | null, birthMonth: number | null) =>
      ({ label, birthYear, birthMonth, accounts: { plan529: fresh(), brokerage: fresh() } });
    const base = kidsGoalFixture(6);
    const config: KidsConfig = {
      ...(base.config as KidsConfig),
      kids: [
        kid('Ellie', YEAR - 2, null),
        kid('Baby', YEAR, NOW.getMonth() + 1),
        kid('Soon', YEAR, null),
        kid('Later', YEAR + 2, 3),
        kid('Someday', null, null),
      ],
    };
    const fixture = await create({ id: '6' }, { goals: [{ ...base, config }] });
    expect(fixture.componentInstance.kidCards().map(c => c.subLine)).toEqual([
      `Born ${YEAR - 2} · age 2`,
      `Born ${YEAR} · under 1`,
      'This year',
      `Arrives ${YEAR + 2}`,
      'Not dated yet',
    ]);
  });

  it('a new family plan posts anchorMonthly as the invest figure, not the whole month', async () => {
    const form = formFromGoal(sampleFamily(5));
    const fixture = await create({ type: 'KIDS', preview: '1' }, { draft: { form, existing: null } });
    const page = fixture.componentInstance;
    finishCountUp(fixture);
    expect(page.mode()).toBe('preview-new');
    const { investMonthly, monthlyTotal } = page.view();

    await page.startPlan();
    expect(createSpy).toHaveBeenCalledTimes(1);
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(body.anchorMonthly).toBe(investMonthly);
    // Ellie is two and at home, so the whole month carries everyday cash on top of the invest figure.
    expect(body.anchorMonthly).toBeLessThan(monthlyTotal);
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

    void page.updateBalance({ kid: 0, account: 'plan529' }, 300);
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

  // ── Kids: groups, rows open the sheet, the hint ────────────────────────────

  /** The ledger fake for a kids goal: each entry moves that unit's tally, as the server would. */
  function kidAdjust(): void {
    adjustSpy.and.callFake((id: number, unit: string, kind: LedgerKind, amount?: number) => {
      const g = goals().find(x => x.id === id) as Goal;
      const t = g.unitTallies?.[unit] ?? tallyOf({ tally: 0, nextCreditAmount: 0 });
      const share = amount ?? t.nextCreditAmount;
      const tally = Math.max(0, kind === 'set' ? amount! : kind === 'missed' ? t.tally - share : t.tally + share);
      const net = kind === 'missed' ? -share : kind === 'extra' ? share : 0;
      const updated: Goal = { ...g, unitTallies: { ...g.unitTallies, [unit]: { ...t, tally, net: t.net + net } } };
      goals.update(list => list.map(x => (x.id === id ? updated : x)));
      return of({ goal: updated, capped: false });
    });
  }

  /** The sample family with a server tally on every saving unit: $900 each, $450 a payday. */
  function talliedFamily(id: number): Goal {
    const g = sampleFamily(id);
    const unitTallies: Record<string, UnitTally> = {};
    (g.config as KidsConfig).kids.forEach((k, i) => {
      for (const account of Object.keys(k.accounts)) unitTallies[`kid:${i}:${account}`] = tallyOf();
    });
    return { ...g, anchorBalance: 0, anchorMonthly: 900, unitTallies };
  }

  /** The sheet's props from the last modalCtrl.create call. */
  function sheetProps(): {
    detail: () => UnitDetail | null; onBalance: ((v: number) => Promise<void>) | null;
    onTally: ((kind: 'missed' | 'extra') => Promise<void>) | null; notes: InfoNote[];
  } {
    const args = modalCtrl.create.calls.mostRecent().args[0];
    expect(args.component).toBe(GoalUnitDetailSheetComponent);
    return args.componentProps as ReturnType<typeof sheetProps>;
  }

  it('a house plan shows the loan card: lead, loan line, then the default-rate source, not the shown rate', async () => {
    const fixture = await create({ id: '5' }, { goals: [{ ...houseGoal(5), config: { price: 500_000, downPaymentPct: 20, targetYear: YEAR + 5, loanRate: 6.5 } }] });
    const cards = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.fd-how')) as HTMLElement[];
    const loan = cards.find(c => c.textContent!.includes('The loan that follows.'));
    expect(loan).withContext('loan card').toBeDefined();
    expect(loan!.querySelector('b')!.textContent).toBe('The loan that follows.');
    expect(loan!.querySelector('p')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      'The loan that follows. Then the mortgage: ~$2,528 a month at 6.5% over 30 years, before taxes and insurance. Default rate: Freddie Mac PMMS, 7.28% as of November 19, 2026.');
    expect(loan!.textContent).not.toContain('What the monthly covers.');
  });

  it('a car plan shows the Federal Reserve default-rate source', async () => {
    const car: Goal = { ...houseGoal(6), type: 'CAR', name: 'Car', icon: 'directions_car', config: { price: 40_000, downPaymentPct: null, downPaymentAmount: 5_000, loanRate: 7.5, targetYear: YEAR + 4 } };
    const fixture = await create({ id: '6' }, { goals: [car] });
    const t = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(t).toContain('Then the loan: ~$701 a month at 7.5% over 60 months. Default rate: Federal Reserve G.19, 7.54% for 2026 Q3 (preliminary).');
  });

  it('kids and vacation plans have no loan card', async () => {
    const kids = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    expect(kids.nativeElement.textContent).not.toContain('The loan that follows.');
    TestBed.resetTestingModule();
    const trips = await create({ id: '8' }, { goals: [vacationGoalFixture(8)] });
    expect(trips.nativeElement.textContent).not.toContain('The loan that follows.');
  });

  it('renders Invest and Everyday group labels with no totals (Andy, 2026-11-11), plain unit amounts, and the Tap for details hint', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const card = fixture.componentInstance.kidCards()[0];
    const el = (fixture.nativeElement as HTMLElement).querySelectorAll('.gp-kcard')[0];
    const groups = Array.from(el.querySelectorAll('.gp-group')) as HTMLElement[];
    expect(groups.length).toBe(2);
    expect(groups[0].textContent!.trim()).toBe('Invest');
    expect(groups[1].classList).toContain('second');
    expect(groups[1].textContent!.trim()).toBe('Everyday');
    expect(card.everyday.monthly).toBeGreaterThan(0);
    // The group rows carry only their label; the header figure is the one blue number on the card.
    expect(el.querySelector('.gp-group-total')).toBeNull();
    const amounts = Array.from(el.querySelectorAll('.gp-krow > em')) as HTMLElement[];
    expect(amounts.length).toBe(3);
    expect(amounts[2].textContent!.trim()).toBe(fmtMoney(card.everyday.monthly));
    expect(text(fixture, '.gp-kcard .gp-kmonthly')).toBe(`${fmtMoney(card.monthly)}/mo`);
    expect(text(fixture, '.gp-zone .gp-hint')).toBe('Tap for details');
    expect(text(fixture, '.gp-zone')).toContain('Per kid');
  });

  it('tapping a unit row opens its sheet with a live detail and a balance hook; no inline Saved so far field exists', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const page = fixture.componentInstance;
    const card = (fixture.nativeElement as HTMLElement).querySelectorAll('.gp-kcard')[0];
    const rows = Array.from(card.querySelectorAll('button.gp-krow')) as HTMLButtonElement[];
    rows[1].click();
    await fixture.whenStable();
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const props = sheetProps();
    const detail = props.detail()!;
    expect(detail.title).toBe('Nest egg');
    expect(detail.kind).toBe('account');
    expect(detail.kidLabel).toBe('Ellie');
    expect(detail.balance).toBe(0);
    expect(detail.status).toEqual({ text: 'Plan started', tone: 'muted' });
    expect(props.notes.map(n => [n.icon, n.lead])).toEqual([
      ['account_balance_wallet', 'How FRED helps.'],
      ['checklist', 'What the monthly covers.'],
      ['trending_up', 'Why invest it.'],
    ]);
    expect(props.notes[0].text).toBe(page.view().howFredHelps);
    expect(props.notes[1].text).toBe(page.view().coversLine!);

    // The hook writes the balance through the page's queue, and the live detail follows.
    kidAdjust();
    expect(props.onBalance).toEqual(jasmine.any(Function));
    await props.onBalance!(4_200);
    // A set entry on the unit's tally, not a PUT of the goal.
    expect(updateSpy).not.toHaveBeenCalled();
    expect(adjustSpy).toHaveBeenCalledOnceWith(5, 'kid:0:brokerage', 'set', 4_200);
    expect(props.detail()!.balance).toBe(4_200);

    expect(fixture.nativeElement.querySelector('.gp-bal-input')).toBeNull();
    expect(fixture.nativeElement.querySelector('.gp-bal')).toBeNull();
    expect((page as any).toggleBalance).toBeUndefined();
    expect((page as any).balanceOpen).toBeUndefined();
  });

  it('page passes onTally that posts for kid:1:brokerage', async () => {
    const fixture = await create({ id: '5' }, { goals: [talliedFamily(5)] });
    const page = fixture.componentInstance;
    kidAdjust();
    await page.openUnitDetail(1, 'brokerage');
    const props = sheetProps();
    expect(props.detail()!.share).toBe(450);
    expect(props.detail()!.status).toEqual({ text: 'On plan', tone: 'good' });
    expect(props.onTally).toEqual(jasmine.any(Function));

    await props.onTally!('missed');
    expect(adjustSpy).toHaveBeenCalledOnceWith(5, 'kid:1:brokerage', 'missed');
    expect(updateSpy).not.toHaveBeenCalled();
    // The sheet's live detail follows, and the page owns the toast with its Undo.
    expect(props.detail()!.balance).toBe(450);
    expect(props.detail()!.status).toEqual({ text: 'Behind by $450', tone: 'warn' });
    expect(lastToast().message).toBe('Saved so far is now $450.');
    expect(lastToast().action!.label).toBe('Undo');
    await props.onTally!('extra');
    expect(adjustSpy.calls.mostRecent().args).toEqual([5, 'kid:1:brokerage', 'extra']);
    expect(props.detail()!.status).toEqual({ text: 'On plan', tone: 'good' });
  });

  const tallyEvents = () => track.calls.allArgs().map(a => a[0]).filter(e => e.name === 'goal_tally_adjusted');

  it('a missed tap emits goal_tally_adjusted with goal_type and kind only', async () => {
    const fixture = await create({ id: '5' }, { goals: [talliedFamily(5)] });
    kidAdjust();
    await fixture.componentInstance.onTally({ kid: 1, account: 'brokerage' }, 'missed');
    expect(tallyEvents()).toEqual([{ name: 'goal_tally_adjusted', props: { goal_type: 'KIDS', kind: 'missed' } }]);
  });

  it('an Undo emits the kind it actually posted', async () => {
    const fixture = await create({ id: '5' }, { goals: [talliedFamily(5)] });
    kidAdjust();
    await fixture.componentInstance.onTally({ kid: 1, account: 'brokerage' }, 'missed');
    lastToast().action!.run();
    await fixture.whenStable();
    expect(tallyEvents().map(e => e.props.kind)).toEqual(['missed', 'extra']);
  });

  it('a set from the tray emits goal_tally_adjusted with kind set', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 90_000, anchorBalance: 90_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    openTray(fixture);
    slideTo(fixture, 95_000);
    click(fixture, '.gp-done');
    await fixture.whenStable();
    expect(tallyEvents()).toEqual([{ name: 'goal_tally_adjusted', props: { goal_type: 'HOUSE', kind: 'set' } }]);
  });

  it('a failed adjustment emits nothing', async () => {
    const fixture = await create({ id: '5' }, { goals: [talliedFamily(5)] });
    adjustSpy.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    await fixture.componentInstance.onTally({ kid: 1, account: 'brokerage' }, 'missed');
    adjustSpy.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'UNIT_FINISHED' } })));
    await fixture.componentInstance.onTally({ kid: 1, account: 'brokerage' }, 'extra');
    expect(tallyEvents()).toEqual([]);
  });

  it('the kid sheet detail carries the unit growth line (none for the baby fund) and no share for everyday or a finished unit', async () => {
    const fixture = await create({ id: '5' }, { goals: [talliedFamily(5)] });
    const page = fixture.componentInstance;
    await page.openUnitDetail(0, 'plan529');
    expect(sheetProps().detail()!.growthLine).toBe(page.view().kids![0].perKid[0].growthLine);
    expect(sheetProps().onTally).not.toBeNull();
    await page.openUnitDetail(1, 'firstYear');
    expect(sheetProps().detail()!.growthLine).toBeNull();
    expect(sheetProps().detail()!.share).toBe(450);
    await page.openUnitDetail(0, 'everyday');
    expect(sheetProps().onTally).toBeNull();
    expect(sheetProps().detail()!.share).toBe(0);
  });

  it('maps the status chip to the sheet\'s tone', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const page = fixture.componentInstance as any;
    const tones: Array<[string, string]> = [
      ['Ahead', 'good'], ['On plan', 'good'], ['Done', 'good'], ['Behind', 'warn'],
      ['Plan started', 'muted'], ['Plan updated', 'muted'],
    ];
    for (const [chip, tone] of tones) expect(page.statusTone(chip)).withContext(chip).toBe(tone);
  });

  it('the baby fund sheet says why it is cash', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    await fixture.componentInstance.openUnitDetail(1, 'firstYear');
    const detail = sheetProps().detail()!;
    expect(detail.kind).toBe('fund');
    expect(detail.notes).toContain('Planned as cash at ~4%: money with a date on it stays out of the market.');
  });

  it('tapping the everyday row opens the Everyday sheet with no balance hook', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const page = fixture.componentInstance;
    const everyday = page.kidCards()[0].everyday;
    const row = (fixture.nativeElement as HTMLElement).querySelectorAll('.gp-kcard')[0].querySelector('button.gp-krow.everyday') as HTMLButtonElement;
    expect(row).not.toBeNull();
    expect(row.textContent).toContain(everyday.label);
    expect(row.textContent).toContain('Cash');
    expect(row.textContent).toContain(everyday.subLine);
    expect(row.textContent).toContain(fmtMoney(everyday.monthly));
    row.click();
    await fixture.whenStable();
    const props = sheetProps();
    const detail = props.detail()!;
    expect(props.onBalance).toBeNull();
    expect(props.notes.length).toBe(3);
    expect(detail.kind).toBe('everyday');
    expect(detail.title).toBe('Everyday');
    expect(detail.kidLabel).toBe('Ellie');
    expect(detail.targetLine).toBe('Age 2 · FRED estimate');
    expect(detail.monthly).toBe(everyday.monthly);
    expect(detail.pill).toBe('Cash');
    expect(detail.balance).toBeNull();
    expect(detail.status).toBeNull();
    expect(detail.milestones).toEqual([]);
    expect(detail.notes).toEqual([
      `${everyday.subLine}.`,
      'Steps up on its own at 3, 6, 9, 12 and 15, the ages where the USDA figures change.',
    ]);
    // Only Ellie is at home: Theo is due later and the third kid is not dated.
    expect(detail.sourcesLine).toBe(
      `FRED estimate: USDA 2015 figures for a middle-income family, adjusted to ${kidCostFigures(1).asOfLabel} dollars and to 1 kid at home.`);
    expect(detail.sourcesSheetKids).toBe(1);
  });

  it('the three info cards are gone from a kids plan and still shown on a house plan', async () => {
    const kids = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    expect(kids.componentInstance.view().coversLine).not.toBeNull();
    expect(kids.nativeElement.querySelectorAll('.fd-how').length).toBe(0);
    TestBed.resetTestingModule();

    const house = await create({ id: '7' }, { goals: [houseGoal(7)] });
    expect(house.nativeElement.querySelectorAll('.fd-how').length).toBe(3); // how, loan, why
    expect(text(house, '.fd-how')).toContain('How FRED helps.');
  });

  it('an unborn kid shows the from line in place of an everyday row', async () => {
    const fixture = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    const page = fixture.componentInstance;
    const theo = page.kidCards()[1];
    expect(theo.everyday.state).toBe('unborn');
    const card = (fixture.nativeElement as HTMLElement).querySelectorAll('.gp-kcard')[1];
    expect(card.querySelector('.gp-krow.everyday')).toBeNull();
    expect(card.querySelector('.gp-from')!.textContent!.trim()).toBe(theo.everyday.fromLine!);
    // The Everyday group still heads the line.
    const second = card.querySelector('.gp-group.second') as HTMLElement;
    expect(second.textContent!.trim()).toBe('Everyday');
    // The unpriced line opens nothing.
    await page.openUnitDetail(1, 'everyday');
    expect(modalCtrl.create).not.toHaveBeenCalled();
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

  // ── Balances: serialization and sanitizing ─────────────────────────────────

  it('Update my plan closes the tray, and waits for the balance write that queues, before building its body', async () => {
    reducedMotion = true;
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

    openTray(fixture);
    slideTo(fixture, 12_000);
    expect(updateSpy).not.toHaveBeenCalled(); // the draft waits for the tray to close
    const saving = page.updatePlan();
    expect(page.tray.open()).toBeNull();
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

    void page.updateBalance({ kid: 0, account: 'plan529' }, 300);
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

  it('shows whole dollars, and a tray opened and closed with nothing slid never rewrites a stored balance with cents', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 1_234.56 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    expect(text(fixture, '#gp-bal-current')).toBe('$1,235');
    openTray(fixture);
    click(fixture, '.gp-done');
    expect(fixture.componentInstance.tray.open()).toBeNull();
    await fixture.componentInstance.updateBalance('current', 1_235);
    expect(updateSpy).not.toHaveBeenCalled();
    expect(adjustSpy).not.toHaveBeenCalled();
  });

  it('writes savedYear on a vacation balance edit and records a preview balance in the draft for the POST', async () => {
    reducedMotion = true;
    const goal = vacationGoalFixture(6);
    const fixture = await create({ id: '6' }, { goals: [goal] });
    await fixture.componentInstance.updateBalance('vacation', 1_200);
    const [id, body] = updateSpy.calls.mostRecent().args as [number, GoalWrite];
    expect(id).toBe(6);
    expect((body.config as VacationConfig).savedThisYear).toBe(1_200);
    expect((body.config as VacationConfig).savedYear).toBe(YEAR);
    TestBed.resetTestingModule();

    const preview = await create({ type: 'WEDDING', preview: '1' }, { draft: { form: newDraft('WEDDING'), existing: null } });
    openTray(preview);
    slideTo(preview, 5_000);
    expect(updateSpy).not.toHaveBeenCalled();
    await preview.componentInstance.startPlan();
    expect(preview.componentInstance.tray.open()).toBeNull();
    expect((createSpy.calls.mostRecent().args[0] as GoalWrite).currentBalance).toBe(5_000);
  });

  // ── The Saved so far tray ──────────────────────────────────────────────────

  it('the Saved so far tile is a value button, not an input; tapping it opens the tray under the tiles with the target as its cap', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 8_000, anchorBalance: 8_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('.gp-tile input')).toBeNull();
    expect(text(fixture, '#gp-bal-current')).toBe('$8,000');
    expect(text(fixture, '.gp-tile .fd-tile-label')).toBe('Saved so far');
    expect(fixture.nativeElement.querySelector('.gp-done')).toBeNull();
    expect(fixture.nativeElement.querySelector('.field-tray')).toBeNull();
    // The keyboard stays for the kid rename field, so the directive stays on the content.
    expect(fixture.nativeElement.querySelector('ion-content').hasAttribute('appkeyboardavoid')).toBeTrue();

    openTray(fixture);
    expect(fixture.nativeElement.querySelector('.gp-tile').classList).toContain('active');
    expect(fixture.nativeElement.querySelector('.gp-tile .fd-tile-label').classList).toContain('active');
    expect(fixture.nativeElement.querySelector('#gp-bal-current').getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('.gp-tile .gp-tile-head .gp-done')).not.toBeNull();
    const slot = fixture.nativeElement.querySelector('.gp-plan > .field-tray') as HTMLElement;
    expect(slot).not.toBeNull();
    expect(slot.previousElementSibling).toBe(fixture.nativeElement.querySelector('.gp-tiles'));

    const scale = trayOf(fixture)!.scale();
    expect(scale).toBe(page.balanceScale());
    expect(scale.min).toBe(0);
    expect(scale.max).toBe(80_000);   // 20% of $400,000
    expect(scale.fine).toBe(25);
    const list = detents(scale);
    expect(list[list.length - 1]).toBe(80_000);
    expect(list.length).toBeGreaterThanOrEqual(80);
    expect(list.length).toBeLessThanOrEqual(160);
    expect(Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')).map(e => (e as HTMLElement).textContent)).toEqual(['$0', '$80,000']);
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-label')).toBe('Saved so far');
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-valuetext')).toBe('$8,000');
  });

  it('a stored balance past the target lifts the cap to the next round number above it, so it can be raised and is never pulled down', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 90_000, anchorBalance: 90_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    openTray(fixture);
    const scale = trayOf(fixture)!.scale();
    expect(scale.max).toBe(100_000);   // the target is $80,000; $90,000 sits inside the scale
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-valuenow')).toBe('90000');
    slideTo(fixture, 95_000);
    click(fixture, '.gp-done');
    await fixture.whenStable();
    expect(adjustSpy).toHaveBeenCalledWith(8, 'goal', 'set', 95_000);
  });

  it('a vacation goal\'s tile reads this year and is capped at the year of trips', async () => {
    reducedMotion = true;
    const fixture = await create({ id: '6' }, { goals: [vacationGoalFixture(6)] });
    expect(text(fixture, '.gp-tile .fd-tile-label')).toBe('Saved so far this year');
    expect(fixture.nativeElement.querySelector('#gp-bal-current')).toBeNull();
    openTray(fixture, 'vacation');
    expect(trayOf(fixture)!.scale().max).toBe(6_000);
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-label')).toBe('Saved so far this year');
    fixture.componentInstance.tray.onDragging(true);
    fixture.detectChanges();
    expect(text(fixture, '.gp-tile .fd-tile-label')).toBe('± $25');
    expect(fixture.nativeElement.querySelector('.gp-tile .fd-tile-label').classList).toContain('gp-step');
  });

  it('sliding shows the value at once and writes nothing; Done saves the draft with one set entry and the tile keeps the figure', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 8_000, anchorBalance: 8_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    openTray(fixture);
    slideTo(fixture, 12_000);
    slideTo(fixture, 12_025);
    expect(text(fixture, '#gp-bal-current')).toBe('$12,025');
    expect(adjustSpy).not.toHaveBeenCalled();
    click(fixture, '.gp-done');
    expect(fixture.componentInstance.tray.open()).toBeNull();
    expect(fixture.nativeElement.querySelector('.field-tray')).toBeNull();
    // The write is queued on close and sent on the next turn of the write chain.
    expect(text(fixture, '#gp-bal-current')).toBe('$12,025');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(adjustSpy).toHaveBeenCalledTimes(1);
    expect(adjustSpy).toHaveBeenCalledWith(8, 'goal', 'set', 12_025);
    expect(updateSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.existing()!.currentBalance).toBe(12_025);
    expect(fixture.componentInstance.balanceDraft()).toBeNull();
    expect(text(fixture, '#gp-bal-current')).toBe('$12,025');
    expect(text(fixture, '.gp-band-text')).toContain('Saved $12,025');
  });

  it('two quick Done saves: the tile keeps the newer draft while the first write settles, and shows it once both land', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 10_000, anchorBalance: 10_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    const first = new Subject<{ goal: Goal; capped: boolean }>();
    let calls = 0;
    adjustSpy.and.callFake((id: number, unit: string, kind: LedgerKind, amount: number) => {
      calls++;
      return calls === 1 ? first.asObservable() : of({ goal: { ...goal, currentBalance: amount }, capped: false });
    });

    openTray(fixture);
    slideTo(fixture, 12_000);
    click(fixture, '.gp-done');
    await Promise.resolve();
    await Promise.resolve();
    expect(adjustSpy).toHaveBeenCalledTimes(1);
    openTray(fixture);
    slideTo(fixture, 13_000);
    click(fixture, '.gp-done');
    expect(page.balanceDraft()).toBe(13_000);
    expect(text(fixture, '#gp-bal-current')).toBe('$13,000');

    // The first write lands while the second is still queued behind it: the tile holds $13,000.
    first.next({ goal: { ...goal, currentBalance: 12_000 }, capped: false });
    first.complete();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(adjustSpy).toHaveBeenCalledTimes(2);
    expect(adjustSpy.calls.argsFor(1)).toEqual([8, 'goal', 'set', 13_000]);
    expect(page.existing()!.currentBalance).toBe(13_000);
    expect(page.balanceDraft()).toBeNull();
    expect(text(fixture, '#gp-bal-current')).toBe('$13,000');
  });

  it('a tap outside saves too, and after a failed save the tile shows the stored balance again; a tap on the slider or the tile does not close it', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 8_000, anchorBalance: 8_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    adjustSpy.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    openTray(fixture);
    slideTo(fixture, 20_000);
    press(fixture, '.ps-ends');
    press(fixture, '#gp-bal-current');
    expect(page.tray.open()).toBe('current');
    expect(adjustSpy).not.toHaveBeenCalled();
    press(fixture, '.fd-hero-label');
    expect(page.tray.open()).toBeNull();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(adjustSpy).toHaveBeenCalledTimes(1);
    expect(showToast).toHaveBeenCalledWith('Could not save this goal. Try again.', 'danger');
    expect(page.balanceDraft()).toBeNull();
    expect(text(fixture, '#gp-bal-current')).toBe('$8,000');
  });

  it('tapping the open tile again closes the tray; leaving the page snaps it shut and saves the draft', async () => {
    reducedMotion = true;
    const goal: Goal = { ...houseGoal(8), currentBalance: 8_000, anchorBalance: 8_000, anchorMonthly: 500 };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const page = fixture.componentInstance;
    openTray(fixture);
    click(fixture, '#gp-bal-current');
    expect(page.tray.open()).toBeNull();
    expect(adjustSpy).not.toHaveBeenCalled();

    openTray(fixture);
    slideTo(fixture, 9_000);
    page.ionViewWillLeave();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(fixture.nativeElement.querySelector('.field-tray')).toBeNull();
    await fixture.whenStable();
    expect(adjustSpy).toHaveBeenCalledTimes(1);
    expect(adjustSpy).toHaveBeenCalledWith(8, 'goal', 'set', 9_000);
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

  // ── Saved so far: the pills, the growth line, the band ─────────────────────

  function pills(fixture: ComponentFixture<GoalPage>): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('app-tally-pills button.tp-pill'));
  }

  /** The toast request of the last show() call. */
  function lastToast(): { severity: string; message: string; action?: { label: string; run: () => void } } {
    return show.calls.mostRecent().args[0];
  }

  async function settle(fixture: ComponentFixture<GoalPage>): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('the assumed-growth line renders under Saved so far for an invested goal and not for a cash one', async () => {
    const anchored = new Date(NOW.getFullYear() - 2, NOW.getMonth(), 1);
    const goal: Goal = {
      ...talliedHouse(8, { tally: 10_000 }), anchorBalance: 10_000, anchorMonthly: 500, planAnchoredAt: localIso(anchored),
    };
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const line = fixture.componentInstance.view().growthLine;
    expect(line).toMatch(/^Assuming growth at 7%, worth ~\$[\d,]+ today$/);
    const el = fixture.nativeElement.querySelector('.gp-growth') as HTMLElement;
    expect(el.textContent!.trim()).toBe(line!);
    // Directly under the tile, above the pills.
    expect(el.previousElementSibling).toBe(fixture.nativeElement.querySelector('.gp-tiles'));
    expect(el.nextElementSibling!.tagName.toLowerCase()).toBe('app-tally-pills');
    TestBed.resetTestingModule();

    const cash: Goal = {
      ...goal, type: 'EMERGENCY_FUND', name: 'Rainy day', icon: 'savings',
      config: { monthlyExpenses: 3_000, months: 6, targetYear: YEAR + 2 }, planRateKind: 'cash', planRate: 0.04,
    };
    const plain = await create({ id: '8' }, { goals: [cash] });
    expect(plain.componentInstance.view().growthLine).toBeNull();
    expect(plain.nativeElement.querySelector('.gp-growth')).toBeNull();
    expect(pills(plain).length).toBe(2);
  });

  it('Missed one posts a missed entry for unit goal and shows the toast with Undo', async () => {
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8)] });
    const page = fixture.componentInstance;
    const [missed, doubled] = pills(fixture);
    expect(missed.textContent!.trim()).toBe('removeMissed one−$450');
    expect(doubled.textContent!.trim()).toBe('addDoubled up+$450');

    missed.click();
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledOnceWith(8, 'goal', 'missed');
    expect(updateSpy).not.toHaveBeenCalled();
    expect(page.existing()!.tally!.tally).toBe(450);
    expect(text(fixture, '#gp-bal-current')).toBe('$450');
    expect(show).toHaveBeenCalledOnceWith({
      severity: 'success',
      message: 'Saved so far is now $450.',
      action: { label: 'Undo', run: jasmine.any(Function) },
    });
    expect(showToast).not.toHaveBeenCalled();

    doubled.click();
    await settle(fixture);
    expect(adjustSpy.calls.mostRecent().args).toEqual([8, 'goal', 'extra']);
    expect(lastToast().message).toBe('Saved so far is now $900.');
  });

  it('Undo after Missed one posts extra', async () => {
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8)] });
    const page = fixture.componentInstance;
    pills(fixture)[0].click();
    await settle(fixture);
    expect(page.existing()!.tally!.tally).toBe(450);

    lastToast().action!.run();
    await settle(fixture);
    expect(adjustSpy.calls.count()).toBe(2);
    expect(adjustSpy.calls.mostRecent().args).toEqual([8, 'goal', 'extra', 450]);
    expect(page.existing()!.tally!.tally).toBe(900);
    expect(page.existing()!.tally!.net).toBe(0);
    expect(lastToast().message).toBe('Saved so far is now $900.');
    // Undo's own toast offers no second Undo, so the two never ping-pong.
    expect(lastToast().action).toBeUndefined();
  });

  it('Undo after a cut-short miss adds back only what was taken', async () => {
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8, { tally: 200 })] });
    pills(fixture)[0].click();
    await settle(fixture);
    expect(fixture.componentInstance.existing()!.tally!.tally).toBe(0);

    lastToast().action!.run();
    await settle(fixture);
    expect(adjustSpy.calls.mostRecent().args).toEqual([8, 'goal', 'extra', 200]);
    expect(fixture.componentInstance.existing()!.tally!.tally).toBe(200);
    expect(lastToast().message).toBe('Saved so far is now $200.');
  });

  it('Undo sends the moved amount rounded to cents', async () => {
    // The server takes $450.20 off $900.10 and returns $449.90; 900.10 - 449.90 in floating point is
    // 450.20000000000005, which the server would refuse as more than 2 decimals.
    const goal = talliedHouse(8, { tally: 900.10, nextCreditAmount: 450.20 });
    const after: Goal = { ...goal, currentBalance: 449.90, tally: { ...goal.tally!, tally: 449.90, net: -450.20 } };
    const fixture = await create({ id: '8' }, { goals: [goal], adjustResult: of({ goal: after, capped: false }) });
    pills(fixture)[0].click();
    await settle(fixture);
    expect(lastToast().message).toBe('Saved so far is now $450.');

    lastToast().action!.run();
    await settle(fixture);
    expect(adjustSpy.calls.mostRecent().args).toEqual([8, 'goal', 'extra', 450.2]);
  });

  it('Undo is withheld once creditsUntil has passed', async () => {
    const goal = talliedHouse(8);
    // The server rolled the goal past its last payday: one more tap lands, the next would 409.
    // Net below 0, so the plan reads Behind rather than Done: only creditsUntil says the unit is over.
    const ended: Goal = {
      ...goal, currentBalance: 1_350,
      tally: { ...goal.tally!, tally: 1_350, net: -450, netSince: `${YEAR - 1}-12-15`, creditsUntil: `${YEAR - 1}-12-31` },
    };
    const fixture = await create({ id: '8' }, { goals: [goal], adjustResult: of({ goal: ended, capped: false }) });
    pills(fixture)[1].click();
    await settle(fixture);
    expect(show).toHaveBeenCalledOnceWith({ severity: 'success', message: 'Saved so far is now $1,350.' });
  });

  it('a miss cut short at $0 still took money off: Saved so far is now $0, with Undo', async () => {
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8, { tally: 200 })] });
    pills(fixture)[0].click();
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledOnceWith(8, 'goal', 'missed');
    expect(fixture.componentInstance.existing()!.tally!.tally).toBe(0);
    expect(show).toHaveBeenCalledOnceWith({
      severity: 'success',
      message: 'Saved so far is now $0.',
      action: { label: 'Undo', run: jasmine.any(Function) },
    });
  });

  it('a miss on a tally already at $0 shows the no-more-to-take-off toast without Undo', async () => {
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8, { tally: 0 })] });
    pills(fixture)[0].click();
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledOnceWith(8, 'goal', 'missed');
    expect(fixture.componentInstance.existing()!.tally!.tally).toBe(0);
    expect(show).toHaveBeenCalledOnceWith({ severity: 'info', message: 'Saved so far is at $0; nothing more to take off.' });
    expect(lastToast().action).toBeUndefined();
  });

  it('a Doubled up that reaches the target shows the toast without Undo', async () => {
    // $80,000 is the house's target (20% of $400,000); one more share lands on it.
    const goal: Goal = { ...talliedHouse(8, { tally: 79_550 }), target: 80_000 };
    const reached: Goal = { ...goal, currentBalance: 80_000, tally: { ...goal.tally!, tally: 80_000, net: 450, nextCreditDate: null, nextCreditAmount: 0 } };
    const fixture = await create({ id: '8' }, { goals: [goal], adjustResult: of({ goal: reached, capped: false }) });
    pills(fixture)[1].click();
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledOnceWith(8, 'goal', 'extra');
    expect(show).toHaveBeenCalledOnceWith({ severity: 'success', message: 'Saved so far is now $80,000.' });
    expect(fixture.componentInstance.view().status.kind).toBe('done');
    expect(pills(fixture).length).toBe(0);
  });

  it('a unit that finished under the user refreshes the goal, shows no error toast, and the pills hide', async () => {
    const goal = talliedHouse(8);
    const done: Goal = { ...goal, completed: true };
    const fixture = await create({ id: '8' }, {
      goals: [goal],
      adjustResult: throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'UNIT_FINISHED' } })),
    });
    refreshSpy.and.callFake(() => {
      goals.set([done]);
      return of([done]);
    });
    pills(fixture)[1].click();
    await settle(fixture);
    expect(refreshSpy).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance.existing()).toBe(done);
    expect(showToast).not.toHaveBeenCalled();
    expect(show).not.toHaveBeenCalled();
    expect(trackErrorShown).not.toHaveBeenCalled();
    expect(pills(fixture).length).toBe(0);
  });

  it('closing the Saved so far tray posts a set, not a PUT', async () => {
    reducedMotion = true;
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8)] });
    openTray(fixture);
    slideTo(fixture, 2_000);
    click(fixture, '.gp-done');
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledOnceWith(8, 'goal', 'set', 2_000);
    expect(updateSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.existing()!.tally!.tally).toBe(2_000);
    expect(text(fixture, '#gp-bal-current')).toBe('$2,000');
    // A set is the user's own figure: no toast.
    expect(show).not.toHaveBeenCalled();

    // Nothing moved: nothing is posted.
    openTray(fixture);
    click(fixture, '.gp-done');
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledTimes(1);
  });

  it('band line shows Saved · part · Next · Behind by', async () => {
    const goal = talliedHouse(8, { tally: 900, net: -450, netSince: `${YEAR}-11-15` });
    const fixture = await create({ id: '8' }, { goals: [goal] });
    const t = fixture.componentInstance.view().tallyLine!;
    expect(t.saved).toBe('Saved $900');
    expect(t.next).toBe('Next $450 Jan 1');
    expect(t.net).toBe('Behind by $450 since Nov 15');
    expect(text(fixture, '.gp-band-text')).toBe(`Saved $900 · ${t.part} · Next $450 Jan 1 · Behind by $450 since Nov 15`);
    expect(t.part).toMatch(/^\d+% of your \$[\d.]+K$/);
    expect(text(fixture, '.gp-band .gp-chip')).toBe('Behind');
    TestBed.resetTestingModule();

    // At net 0 the band stops after Next.
    const even = await create({ id: '8' }, { goals: [talliedHouse(8)] });
    expect(text(even, '.gp-band-text')).toBe(`Saved $900 · ${even.componentInstance.view().tallyLine!.part} · Next $450 Jan 1`);
  });

  it('no pills in preview modes', async () => {
    const goal = talliedHouse(4);
    const edit = await create({ id: '4', preview: '1' }, { goals: [goal], draft: { form: { ...formFromGoal(goal), price: 500_000 }, existing: goal } });
    expect(edit.componentInstance.mode()).toBe('preview-edit');
    expect(edit.nativeElement.querySelector('#gp-bal-current')).not.toBeNull();
    expect(edit.nativeElement.querySelector('app-tally-pills')).toBeNull();
    expect(edit.nativeElement.querySelector('.gp-growth')).toBeNull();
    TestBed.resetTestingModule();

    const fresh = await create({ type: 'HOUSE', preview: '1' }, { draft: { form: newDraft('HOUSE'), existing: null } });
    expect(fresh.componentInstance.mode()).toBe('preview-new');
    expect(fresh.nativeElement.querySelector('app-tally-pills')).toBeNull();
  });

  it('no pills on a done goal, an import, a share of 0, or a kids goal', async () => {
    const done = await create({ id: '8' }, { goals: [{ ...talliedHouse(8), completed: true }] });
    expect(done.nativeElement.querySelector('app-tally-pills')).toBeNull();
    TestBed.resetTestingModule();

    const imported = await create({ id: '8' }, { goals: [{ ...talliedHouse(8), clientId: 'legacy-1', anchorMonthly: 0 }] });
    expect(imported.componentInstance.view().status.kind).toBe('imported');
    expect(imported.nativeElement.querySelector('app-tally-pills')).toBeNull();
    TestBed.resetTestingModule();

    const noShare = await create({ id: '8' }, { goals: [talliedHouse(8, { nextCreditAmount: 0, nextCreditDate: null })] });
    expect(pills(noShare).length).toBe(0);
    TestBed.resetTestingModule();

    const kids = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    expect(kids.nativeElement.querySelector('app-tally-pills')).toBeNull();
  });

  it('a vacation goal\'s pills post against the goal unit too', async () => {
    const trips: Goal = { ...vacationGoalFixture(6), tally: tallyOf({ tally: 1_200, creditsUntil: null }) };
    const fixture = await create({ id: '6' }, { goals: [trips] });
    expect(text(fixture, '#gp-bal-vacation')).toBe('$1,200');
    pills(fixture)[1].click();
    await settle(fixture);
    expect(adjustSpy).toHaveBeenCalledOnceWith(6, 'goal', 'extra');
    expect(lastToast().message).toBe('Saved so far is now $1,650.');
  });

  it('Update balance chip no longer exists', async () => {
    const fixture = await create({ id: '8' }, { goals: [talliedHouse(8, { net: -450 })] });
    const page = fixture.componentInstance as unknown as Record<string, unknown>;
    expect(page['onChipTap']).toBeUndefined();
    const chip = fixture.nativeElement.querySelector('.gp-band .gp-chip') as HTMLElement;
    expect(chip.tagName.toLowerCase()).toBe('span');
    expect(chip.classList).not.toContain('stale');
    expect(text(fixture)).not.toContain('Update balance');
    TestBed.resetTestingModule();

    const kids = await create({ id: '5' }, { goals: [sampleFamily(5)] });
    expect((kids.nativeElement.querySelector('.gp-zone .gp-chip') as HTMLElement).tagName.toLowerCase()).toBe('span');
    expect(text(kids)).not.toContain('Update balance');
  });

  it('POST and PUT bodies carry creditsUntil = Dec 31 of the target year for a house', async () => {
    const fresh = await create({ type: 'HOUSE', preview: '1' }, { draft: { form: newDraft('HOUSE'), existing: null } });
    await fresh.componentInstance.startPlan();
    const posted = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(posted.creditsUntil).toBe(`${YEAR + 5}-12-31`);
    expect(posted.target).toBe(80_000);
    expect(posted.anchorMonthly).toBe(fresh.componentInstance.view().investMonthly);
    TestBed.resetTestingModule();

    const goal = houseGoal(4);
    const form = { ...formFromGoal(goal), price: 500_000, targetYear: YEAR + 6 };
    const edit = await create({ id: '4', preview: '1' }, { goals: [goal], draft: { form, existing: goal } });
    await edit.componentInstance.updatePlan();
    const put = updateSpy.calls.mostRecent().args[1] as GoalWrite;
    expect(put.creditsUntil).toBe(`${YEAR + 6}-12-31`);
    expect(put.target).toBe(100_000);
  });

  it('a vacation body carries the year of trips as its target and no creditsUntil; a kids body stamps each account', async () => {
    const trips = await create({ type: 'VACATION', preview: '1' }, { draft: { form: newDraft('VACATION'), existing: null } });
    await trips.componentInstance.startPlan();
    const vacation = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(vacation.creditsUntil).toBeNull();
    expect(vacation.target).toBe(trips.componentInstance.view().vacation!.annual);
    TestBed.resetTestingModule();

    const family = sampleFamily(5);
    const kids = await create({ type: 'KIDS', preview: '1' }, { draft: { form: formFromGoal(family), existing: null } });
    const view = kids.componentInstance.view();
    await kids.componentInstance.startPlan();
    const body = createSpy.calls.mostRecent().args[0] as GoalWrite;
    expect(body.creditsUntil).toBeNull();
    expect(body.target).toBeNull();
    const cfg = body.config as KidsConfig;
    const plan529 = view.kids!.find(a => a.account === 'plan529')!.perKid[0].plan;
    const ellie = cfg.kids[0].accounts;
    expect(ellie.plan529.target).toBe(plan529.milestones.at18);
    expect(ellie.plan529.creditsUntil).toBe(`${plan529.deadline.year}-${String(plan529.deadline.month).padStart(2, '0')}-${new Date(plan529.deadline.year, plan529.deadline.month, 0).getDate()}`);
    expect(ellie.brokerage.target).toBe(view.kids!.find(a => a.account === 'brokerage')!.perKid[0].plan.milestones.at18);
    const fund = view.firstYear!.rows.find(r => r.kidIndex === 1)!.plan;
    expect(cfg.kids[1].accounts.firstYear!.target).toBe(fund.target);
    expect(cfg.kids[1].accounts.firstYear!.creditsUntil).toMatch(/^\d{4}-\d{2}-\d{2}$/);
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
