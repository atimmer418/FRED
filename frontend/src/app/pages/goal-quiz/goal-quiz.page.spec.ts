import { signal } from '@angular/core';
import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';

import { GoalQuizPage } from './goal-quiz.page';
import { KidCostSourcesSheetComponent } from '../../components/kid-cost-sources-sheet/kid-cost-sources-sheet.component';
import { GOAL_PAGE_NOW } from '../goal/goal-clock';
import { GoalDraftService } from '../../services/goal-draft.service';
import { GoalsService } from '../../services/goals.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { defaultFirstYearFund, formFromGoal, formToConfig } from '../goal/goal-form.model';
import { Goal, GoalConfig, GoalType, KidsConfig } from '../../models/goal.model';
import { kidYearBounds } from '../../utils/goal-quiz.utils';

/** 15 Nov 2026: the spec's sample family (born 2024, expected 2028, undated) reads against this clock. */
const NOW = new Date(2026, 10, 15);
const YEAR = NOW.getFullYear();

function goalOf(id: number, type: GoalType, config: GoalConfig, extra: Partial<Goal> = {}): Goal {
  return {
    id, clientId: null, type, name: 'G', icon: 'flag', config,
    currentBalance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0,
    planRate: 0.1, planRateKind: 'market', planAnchoredAt: '2026-01-01',
    completed: false, sortOrder: 0, createdAt: '', updatedAt: '',
    ...extra,
  };
}

function houseGoal(id: number): Goal {
  return goalOf(id, 'HOUSE', {
    price: 400_000, downPaymentPct: 20, targetYear: YEAR + 5, loanRate: 6.5, downPaymentAmount: null,
  }, { name: 'First home', icon: 'home' });
}

function carGoal(id: number): Goal {
  return goalOf(id, 'CAR', {
    price: 32_000, downPaymentPct: 25, targetYear: YEAR + 3, loanRate: 7.5, downPaymentAmount: 6_000,
  }, { name: 'Next car', icon: 'directions_car' });
}

function kidsGoal(id: number, kids: Array<{ birthYear: number | null; birthMonth: number | null }>): Goal {
  const fresh = () => ({ balance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 });
  const config: KidsConfig = {
    brokerageTarget: 100_000,
    plan529Target: 47_800,
    firstYearFund: false,
    giftDays: false,
    kids: kids.map((k, i) => ({
      label: `Kid ${i + 1}`, birthYear: k.birthYear, birthMonth: k.birthMonth,
      accounts: { brokerage: fresh(), plan529: fresh() },
    })),
  };
  return goalOf(id, 'KIDS', config, { name: 'Our kids', icon: 'child_care' });
}

describe('GoalQuizPage', () => {
  let navCtrl: jasmine.SpyObj<NavController>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let modal: { present: jasmine.Spy; onDidDismiss: jasmine.Spy };
  let goals: ReturnType<typeof signal<Goal[]>>;
  let refresh: jasmine.Spy;
  let draft: GoalDraftService;

  async function create(
    params: Record<string, string>,
    opts: { goals?: Goal[]; loaded?: Goal[]; refreshError?: boolean } = {},
  ): Promise<ComponentFixture<GoalQuizPage>> {
    // Some specs build a second page; the module must be reset between them.
    TestBed.resetTestingModule();
    goals = signal<Goal[]>(opts.goals ?? []);
    refresh = jasmine.createSpy('refresh').and.callFake(() => {
      if (opts.refreshError) return throwError(() => new Error('down'));
      if (opts.loaded) goals.set(opts.loaded);
      return of(goals());
    });
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    navCtrl.navigateForward.and.resolveTo(true);
    navCtrl.navigateBack.and.resolveTo(true);
    modal = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role: undefined }),
    };
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo(modal as unknown as HTMLIonModalElement);

    await TestBed.configureTestingModule({
      imports: [GoalQuizPage],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(params) } } },
        { provide: NavController, useValue: navCtrl },
        { provide: ModalController, useValue: modalCtrl },
        { provide: GoalsService, useValue: { goals, refresh } },
        { provide: RateAssumptionsService, useValue: { annualReturn: signal(0.10), ensureUser: () => {} } },
        { provide: GOAL_PAGE_NOW, useValue: () => NOW },
      ],
    }).compileComponents();

    draft = TestBed.inject(GoalDraftService);
    draft.clear();
    const fixture = TestBed.createComponent(GoalQuizPage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<GoalQuizPage>, selector: string): string {
    return (fixture.nativeElement.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  }

  function onScreen(fixture: ComponentFixture<GoalQuizPage>): HTMLElement {
    return fixture.nativeElement.querySelector('.fscreen.on') as HTMLElement;
  }

  function goTo(fixture: ComponentFixture<GoalQuizPage>, index: number): void {
    fixture.componentInstance.stepIndex.set(index);
    fixture.detectChanges();
  }

  it('starts at the type default form for /goals/new/kids and at formFromGoal for /goals/:id/edit', async () => {
    const fresh = await create({ type: 'KIDS' });
    const page = fresh.componentInstance;
    expect(page.form().type).toBe('KIDS');
    expect(page.form().name).toBe('Our kids');
    expect(page.existing()).toBeNull();
    expect(page.stepIndex()).toBe(0);
    expect(text(fresh, '.fscreen.on .fs-h1')).toBe('How many kids are you planning for?');
    expect(text(fresh, '.fscreen.on .fs-cta')).toBe('Continue');
    expect(fresh.nativeElement.querySelectorAll('.flow-dots .dot').length).toBe(6);

    const goal = houseGoal(3);
    const edit = await create({ id: '3' }, { goals: [goal] });
    expect(edit.componentInstance.existing()).toBe(goal);
    expect(edit.componentInstance.form()).toEqual(formFromGoal(goal));
    expect(refresh).not.toHaveBeenCalled();
  });

  it('refreshes the goals on a cold deep link and leaves when the goal is still missing', async () => {
    const goal = houseGoal(5);
    const warm = await create({ id: '5' }, { loaded: [goal] });
    expect(refresh).toHaveBeenCalled();
    expect(warm.componentInstance.existing()).toBe(goal);

    await create({ id: '9' }, { loaded: [goal] });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');

    await create({ type: 'BOAT' });
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
  });

  it('the Baby fund row is hidden when every kid is born', async () => {
    const born = await create({ id: '1' }, { goals: [kidsGoal(1, [{ birthYear: 2020, birthMonth: 3 }])] });
    goTo(born, 3);
    expect(text(born, '.fscreen.on .fs-h1')).toBe('Choose what to fund');
    const rows = Array.from(onScreen(born).querySelectorAll('app-quiz-switch-row')) as HTMLElement[];
    expect(rows.length).toBe(2);
    expect(rows.map(r => r.textContent)).not.toContain(jasmine.stringContaining('Baby fund'));
  });

  it('a kid expected this year is not yet born: the Baby fund row shows and the fund defaults on (ruling R4)', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.form.update(f => ({
      ...f, kids: { ...f.kids, kids: [{ label: 'Kid 1', birthYear: YEAR, birthMonth: null, undecided: false }] },
    }));
    expect(defaultFirstYearFund(page.form().kids.kids, NOW)).toBeTrue();
    goTo(fixture, 3);
    const rows = Array.from(onScreen(fixture).querySelectorAll('app-quiz-switch-row')) as HTMLElement[];
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Baby fund');
    expect(rows[0].textContent).toContain('$25,700 ready on day one.');
  });

  it('turning College off on step 4 changes the CTA of the new last step to Show my plan with the icon', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    goTo(fixture, 3);
    expect(text(fixture, '.fscreen.on .fs-cta')).toBe('Continue');
    expect(page.steps().length).toBe(6);

    page.setFund('nest', false);
    fixture.detectChanges();
    expect(page.steps().map(s => s.id)).not.toContain('kids_nest');
    expect(page.form().kids.at18Choice).toBe('none');
    expect(text(fixture, '.fscreen.on .fs-cta')).toBe('Continue');

    page.setFund('college', false);
    fixture.detectChanges();
    expect(page.form().kids.collegeChoice).toBe('none');
    expect(page.form().kids.plan529Target).toBeNull();
    expect(page.isLast()).toBeTrue();
    const cta = onScreen(fixture).querySelector('.fs-cta') as HTMLElement;
    expect(cta.textContent).toContain('Show my plan');
    expect(cta.querySelector('.material-symbols-outlined')?.textContent?.trim()).toBe('auto_awesome');
    expect(fixture.nativeElement.querySelectorAll('.flow-dots .dot').length).toBe(4);

    // Off rows read what they save, in green.
    const rows = Array.from(onScreen(fixture).querySelectorAll('app-quiz-switch-row .bf-row-line')) as HTMLElement[];
    const lines = rows.map(r => r.textContent?.trim());
    expect(lines).toContain(jasmine.stringMatching(/^Saves you \$\d+K( to \$\d+K)?\.$/));
    expect(rows.filter(r => r.classList.contains('saves')).length).toBe(2);

    // All three off: the CTA is disabled and the subtitle asks for one.
    page.setFund('baby', false);
    fixture.detectChanges();
    expect((onScreen(fixture).querySelector('.fs-cta') as HTMLButtonElement).disabled).toBeTrue();
    expect(text(fixture, '.fscreen.on .fs-sub')).toBe('Pick at least one.');

    // Back on: the step returns and the college default is restored.
    page.setFund('college', true);
    fixture.detectChanges();
    expect(page.form().kids.collegeChoice).toBe('public_tuition');
    expect(page.form().kids.plan529Target).toBe(47_800);
    expect(page.steps().map(s => s.id)).toContain('kids_college');
  });

  it('the when-step slider past the end marks the kid undecided and labels it Not sure', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.setKidCount(2);
    goTo(fixture, 1);
    const { min, max } = kidYearBounds(NOW);
    const sliders = Array.from(onScreen(fixture).querySelectorAll('input[type="range"]')) as HTMLInputElement[];
    expect(sliders.length).toBe(2);
    expect(sliders[0].min).toBe(String(min));
    expect(sliders[0].max).toBe(String(max + 1));

    sliders[0].value = '2024';
    sliders[0].dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(page.form().kids.kids[0]).toEqual(jasmine.objectContaining({ birthYear: 2024, birthMonth: null, undecided: false }));
    expect(onScreen(fixture).querySelectorAll('.kid-when')[0].textContent?.trim()).toBe('Born 2024');

    sliders[0].value = String(YEAR);
    sliders[0].dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(onScreen(fixture).querySelectorAll('.kid-when')[0].textContent?.trim()).toBe('This year');

    sliders[0].value = String(max + 1);
    sliders[0].dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(page.form().kids.kids[0]).toEqual(jasmine.objectContaining({ birthYear: null, undecided: true }));
    expect(onScreen(fixture).querySelectorAll('.kid-when')[0].textContent?.trim()).toBe('Not sure');
  });

  it('hidden screens are inert, so a stat on the cost screen can never take a tap meant for the stepper under it (2026-11-11)', async () => {
    const fixture = await create({ type: 'KIDS' });
    const screens = Array.from(fixture.nativeElement.querySelectorAll('.fscreen')) as HTMLElement[];
    expect(screens.length).toBeGreaterThan(1);
    expect(screens[0].classList).toContain('on');
    expect(screens[0].hasAttribute('inert')).toBeFalse();
    screens.slice(1).forEach(s => expect(s.hasAttribute('inert')).toBeTrue());
    // The warning lines hang in their own slot under the stepper, so the stepper never moves.
    expect(onScreen(fixture).querySelector('.kids-stepper .kids-cap-slot')).not.toBeNull();
    goTo(fixture, 1);
    const after = Array.from(fixture.nativeElement.querySelectorAll('.fscreen')) as HTMLElement[];
    expect(after[0].hasAttribute('inert')).toBeTrue();
    expect(after[1].hasAttribute('inert')).toBeFalse();
  });

  it('the count step caps at six kids without a line saying so: the disabled plus is the cap (Andy, 2026-11-11)', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.setKidCount(6);
    fixture.detectChanges();
    expect(page.form().kids.kids.length).toBe(6);
    expect(onScreen(fixture).querySelectorAll('.kid-icons .material-symbols-outlined').length).toBe(6);
    expect(fixture.nativeElement.querySelector('.fscreen.on .kids-cap')).toBeNull();
    page.setKidCount(7);
    expect(page.form().kids.kids.length).toBe(6);
    page.setKidCount(2);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fscreen.on .kids-cap')).toBeNull();
  });

  it('the cost screen shows the general stat first and the family monthly after the crossfade; tap flips back', fakeAsync(async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.setKidCount(3);
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    goTo(fixture, 2);
    const statA = onScreen(fixture).querySelector('.stat-a') as HTMLElement;
    const statB = onScreen(fixture).querySelector('.stat-b') as HTMLElement;
    expect(statA.textContent?.replace(/\s+/g, ' ')).toContain('$252K');
    expect(statA.textContent).toContain('From birth to 18');
    expect(statB.textContent?.replace(/\s+/g, ' ')).toContain('$3,510');
    expect(statB.textContent).toContain('/mo');
    expect(statB.textContent).toContain('For all 3 kids');
    expect(statA.classList).toContain('on');
    expect(statB.classList).not.toContain('on');

    tick(2199);
    expect(page.costStat()).toBe('A');
    tick(1);
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    // The two stats crossfade and Continue fades in.
    expect(animate).toHaveBeenCalledTimes(3);
    expect(statB.classList).toContain('on');

    // The flip back crossfades the stats only; Continue stays.
    (onScreen(fixture).querySelector('.xfade') as HTMLElement).click();
    fixture.detectChanges();
    expect(page.costStat()).toBe('A');
    expect(animate).toHaveBeenCalledTimes(5);
    discardPeriodicTasks();
  }));

  it('re-entering the cost step after the flip shows the general stat again: finished fades no longer hold the stats', fakeAsync(async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    goTo(fixture, 2);
    const statA = onScreen(fixture).querySelector('.stat-a') as HTMLElement;
    const statB = onScreen(fixture).querySelector('.stat-b') as HTMLElement;
    const xfade = onScreen(fixture).querySelector('.xfade') as HTMLElement;
    tick(2200);
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    // Land the fades the way 500ms on a device would.
    [statA, statB].forEach(el => el.getAnimations().forEach(a => a.finish()));
    expect(getComputedStyle(statA).opacity).toBe('0');
    expect(getComputedStyle(statB).opacity).toBe('1');

    // Continue, then Back onto the cost step: the hold restarts on the general stat and the
    // stylesheet (the .on class) is what paints, not the finished fill-forwards effects.
    goTo(fixture, 3);
    goTo(fixture, 2);
    expect(page.costStat()).toBe('A');
    expect(statA.classList).toContain('on');
    expect(getComputedStyle(statA).opacity).toBe('1');
    expect(getComputedStyle(statB).opacity).toBe('0');
    expect(statA.getAnimations().length).toBe(0);
    expect(statB.getAnimations().length).toBe(0);

    // The second visit flips again, with a real crossfade.
    tick(2200);
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    expect(statA.getAnimations().length).toBe(1);
    expect(statB.getAnimations().length).toBe(1);

    // Taps replace the running fade instead of stacking a new pair every time.
    xfade.click();
    xfade.click();
    xfade.click();
    expect(statA.getAnimations().length).toBe(1);
    expect(statB.getAnimations().length).toBe(1);
    discardPeriodicTasks();
  }));

  it('the cost step hides Continue until the family monthly shows, and hides it again on a re-entry', fakeAsync(async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    const cta = () => onScreen(fixture).querySelector('.fs-cta') as HTMLButtonElement;
    expect(cta().classList).not.toContain('cta-hidden');

    goTo(fixture, 2);
    expect(cta().classList).toContain('cta-hidden');
    expect(getComputedStyle(cta()).opacity).toBe('0');
    expect(getComputedStyle(cta()).pointerEvents).toBe('none');
    tick(2199);
    fixture.detectChanges();
    expect(cta().classList).toContain('cta-hidden');
    tick(1);
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    expect(cta().classList).not.toContain('cta-hidden');
    expect(cta().getAnimations().length).toBe(1);
    cta().getAnimations().forEach(a => a.finish());
    expect(getComputedStyle(cta()).opacity).toBe('1');

    // A tap back to the general stat keeps it.
    (onScreen(fixture).querySelector('.xfade') as HTMLElement).click();
    fixture.detectChanges();
    expect(page.costStat()).toBe('A');
    expect(cta().classList).not.toContain('cta-hidden');

    // The next step shows it as always; back onto the cost step hides it until the next reveal.
    goTo(fixture, 3);
    expect(cta().classList).not.toContain('cta-hidden');
    goTo(fixture, 2);
    expect(cta().classList).toContain('cta-hidden');
    expect(getComputedStyle(cta()).opacity).toBe('0');
    tick(2200);
    fixture.detectChanges();
    expect(cta().classList).not.toContain('cta-hidden');
    discardPeriodicTasks();
  }));

  it('a tap on the cost stat before the hold ends reveals Continue at once', fakeAsync(async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    goTo(fixture, 2);
    const cta = onScreen(fixture).querySelector('.fs-cta') as HTMLButtonElement;
    tick(500);
    expect(cta.classList).toContain('cta-hidden');
    (onScreen(fixture).querySelector('.xfade') as HTMLElement).click();
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    expect(cta.classList).not.toContain('cta-hidden');
    tick(5000);
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    discardPeriodicTasks();
  }));

  it('under reduced motion Continue appears on the reveal without animate()', fakeAsync(async () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);
    const fixture = await create({ type: 'KIDS' });
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    goTo(fixture, 2);
    const cta = onScreen(fixture).querySelector('.fs-cta') as HTMLButtonElement;
    expect(cta.classList).toContain('cta-hidden');
    tick(2200);
    fixture.detectChanges();
    expect(cta.classList).not.toContain('cta-hidden');
    expect(getComputedStyle(cta).opacity).toBe('1');
    expect(animate).not.toHaveBeenCalled();
    discardPeriodicTasks();
  }));

  it('the cost step swaps the close button for an info button that opens the sources sheet for this family size', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    page.setKidCount(4);
    fixture.detectChanges();
    const closeBtn = () => fixture.nativeElement.querySelector('.flow-top [aria-label="Close"]');
    expect(closeBtn()).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.flow-top .flow-info')).toBeNull();

    goTo(fixture, 2);
    expect(closeBtn()).toBeNull();
    const info = fixture.nativeElement.querySelector('.flow-top .flow-info') as HTMLButtonElement;
    expect(info).not.toBeNull();
    expect(info.querySelector('.material-symbols-outlined')?.textContent?.trim()).toBe('info');

    info.click();
    await fixture.whenStable();
    expect(modalCtrl.create).toHaveBeenCalledWith(jasmine.objectContaining({
      component: KidCostSourcesSheetComponent,
      componentProps: { kidsCount: 4 },
      cssClass: 'mc-bottom-sheet-modal',
    }));
    expect(modal.present).toHaveBeenCalled();

    goTo(fixture, 3);
    expect(closeBtn()).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.flow-top .flow-info')).toBeNull();
  });

  it('the cost screen swaps without animate() under reduced motion and reads the one and two kid hints', fakeAsync(async () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    goTo(fixture, 2);
    expect(text(fixture, '.fscreen.on .stat-a .big-readout')).toBe('$422K');
    expect(text(fixture, '.fscreen.on .stat-a .readout-hint')).toBe('From birth to 18');
    expect(text(fixture, '.fscreen.on .stat-b .big-readout')).toBe('$1,950 /mo');
    expect(text(fixture, '.fscreen.on .stat-b .readout-hint')).toBe('For your kid');
    tick(2200);
    fixture.detectChanges();
    expect(page.costStat()).toBe('B');
    expect(animate).not.toHaveBeenCalled();

    page.setKidCount(2);
    fixture.detectChanges();
    expect(text(fixture, '.fscreen.on .stat-a .big-readout')).toBe('$332K');
    expect(text(fixture, '.fscreen.on .stat-b .readout-hint')).toBe('For both kids');
    page.setKidCount(4);
    fixture.detectChanges();
    expect(text(fixture, '.fscreen.on .stat-a .big-readout')).toBe('$213K');
    page.setKidCount(5);
    fixture.detectChanges();
    expect(text(fixture, '.fscreen.on .stat-a .big-readout')).toBe('$189K');
  }));

  it('college: Skip for now holds in the grid until Continue, then the step is gone; Set my own amount swaps in the slider', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    goTo(fixture, 4);
    expect(text(fixture, '.fscreen.on .fs-h1')).toBe('Saving for college?');
    expect(onScreen(fixture).querySelectorAll('app-quiz-pick-grid .bf-pick').length).toBe(4);
    expect(text(fixture, '.fscreen.on app-quiz-pick-grid .bf-pick.on')).toContain('In-state public');

    page.pickCollege('public_full');
    expect(page.form().kids.collegeChoice).toBe('public_full');
    expect(page.form().kids.plan529Target).toBe(123_960);

    page.pickCollege('none');
    fixture.detectChanges();
    expect(page.form().kids.collegeChoice).toBe('public_full');
    expect(page.collegeGridValue()).toBe('none');
    expect(page.steps().map(s => s.id)).toContain('kids_college');
    page.next();
    fixture.detectChanges();
    expect(page.form().kids.collegeChoice).toBe('none');
    expect(page.steps().map(s => s.id)).toEqual(['kids_count', 'kids_when', 'kids_cost', 'kids_fund', 'kids_nest']);
    expect(page.currentStep().id).toBe('kids_nest');

    page.setFund('college', true);
    goTo(fixture, 4);
    page.toggleCollegeCustom();
    fixture.detectChanges();
    const slider = onScreen(fixture).querySelector('app-quiz-slider');
    expect(slider).not.toBeNull();
    expect(onScreen(fixture).querySelector('app-quiz-pick-grid')).toBeNull();
    page.setCollegeCustom(60_000);
    expect(page.form().kids.collegeChoice).toBe('custom');
    expect(page.form().kids.plan529Target).toBe(60_000);
    expect(text(fixture, '.fscreen.on .college-swap')).toBe('Back to the presets');
  });

  it('college as the last step: Skip for now with the nest egg and Baby fund off shows the kids error and never shows the plan', fakeAsync(async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    goTo(fixture, 3);
    page.setFund('nest', false);
    page.setFund('baby', false);
    fixture.detectChanges();
    expect(page.steps().map(s => s.id)).toEqual(['kids_count', 'kids_when', 'kids_cost', 'kids_fund', 'kids_college']);

    goTo(fixture, 4);
    expect(page.isLast()).toBeTrue();
    page.pickCollege('none');
    fixture.detectChanges();
    page.next();
    fixture.detectChanges();

    expect(page.calcShowing()).toBeFalse();
    expect(draft.draft()).toBeNull();
    expect(page.currentStep().id).toBe('kids_college');
    expect(page.form().kids.collegeChoice).toBe('public_tuition');
    expect(text(fixture, '.fscreen.on .fs-error')).toBe('Enter at least one target for your kids.');

    // Picking a preset clears the error and Show my plan goes through.
    page.pickCollege('private_tuition');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fscreen.on .fs-error')).toBeNull();
    page.next();
    expect(page.calcShowing()).toBeTrue();
    expect(draft.draft()?.form.kids.plan529Target).toBe(180_000);
    tick(2500);
    expect(navCtrl.navigateForward).toHaveBeenCalledWith(['/goals/new', 'KIDS', 'plan']);
    discardPeriodicTasks();
  }));

  it('nest egg: default $100,000, live chip from the flat monthly, and $0 reads as off', async () => {
    const fixture = await create({ type: 'KIDS' });
    const page = fixture.componentInstance;
    goTo(fixture, 5);
    expect(text(fixture, '.fscreen.on .fs-h1')).toBe('How much per kid?');
    expect(page.nestAmount()).toBe(100_000);
    expect(text(fixture, '.fscreen.on .fetch-chip .chip-text')).toMatch(/^~\$\d{1,3}(,\d{3})* a month per kid$/);
    page.setNest(0);
    fixture.detectChanges();
    expect(text(fixture, '.fscreen.on .fetch-chip .chip-text')).toBe('Nothing extra at 18');
    expect(page.steps().map(s => s.id)).toContain('kids_nest');
    page.setNest(250_000);
    expect(page.form().kids.brokerageTarget).toBe(250_000);
    expect(page.form().kids.at18Choice).toBe(250_000);
    page.setNest(120_000);
    expect(page.form().kids.at18Choice).toBe('custom');
  });

  it('Show my plan stores the draft, shows the calc screen for at least 900ms, then navigates to the plan preview route', fakeAsync(async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    expect(page.isLast()).toBeTrue();
    expect(text(fixture, '.fscreen.on .fs-cta')).toContain('Show my plan');
    expect(text(fixture, '.fscreen.on .fs-cta .material-symbols-outlined')).toBe('auto_awesome');

    page.next();
    fixture.detectChanges();
    expect(draft.draft()).toEqual({ form: page.form(), existing: null });
    expect(page.calcShowing()).toBeTrue();
    expect(fixture.nativeElement.querySelector('app-goal-quiz-calc')).not.toBeNull();
    expect(text(fixture, 'app-goal-quiz-calc .calc-title')).toBe("Working out this month's number");

    tick(899);
    expect(navCtrl.navigateForward).not.toHaveBeenCalled();
    tick(1601);
    expect(navCtrl.navigateForward).toHaveBeenCalledWith(['/goals/new', 'HOUSE', 'plan']);
    discardPeriodicTasks();
  }));

  it('Show my plan on an edit navigates to the edit preview route and keeps the existing goal in the draft', fakeAsync(async () => {
    const goal = houseGoal(4);
    const fixture = await create({ id: '4' }, { goals: [goal] });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    page.next();
    fixture.detectChanges();
    expect(draft.draft()?.existing).toBe(goal);
    tick(2500);
    // The preview stacks over the quiz; Update my plan pops back to the saved page beneath (Ruling P4).
    expect(navCtrl.navigateForward).toHaveBeenCalledWith(['/goals', 4, 'edit', 'plan']);
    discardPeriodicTasks();
  }));

  it('back from the preview repaints the last step: the calc screen is gone, the top bar and the CTA are back, and the draft stays', fakeAsync(async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    page.showPlan();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-goal-quiz-calc')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.flow-top').classList).toContain('hidden');
    tick(2500);
    expect(navCtrl.navigateForward).toHaveBeenCalledTimes(1);

    // The preview pops: Ionic re-enters the quiz with the draft still in the service.
    page.ionViewWillEnter();
    fixture.detectChanges();
    expect(page.calcShowing()).toBeFalse();
    expect(page.evicting()).toBeFalse();
    expect(fixture.nativeElement.querySelector('app-goal-quiz-calc')).toBeNull();
    expect(fixture.nativeElement.querySelector('.flow-top').classList).not.toContain('hidden');
    expect(onScreen(fixture)).not.toBeNull();
    expect(text(fixture, '.fscreen.on .fs-h1')).toBe('How much, and when?');
    expect(text(fixture, '.fscreen.on .fs-cta')).toContain('Show my plan');
    expect(draft.draft()).toEqual({ form: page.form(), existing: null });
    page.ionViewDidEnter();
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();

    // A second Show my plan runs the calc screen again and pushes the preview again.
    page.next();
    fixture.detectChanges();
    expect(page.calcShowing()).toBeTrue();
    tick(2500);
    expect(navCtrl.navigateForward).toHaveBeenCalledTimes(2);
    discardPeriodicTasks();
  }));

  it('re-entering while the calc timer still runs cancels the pending push', fakeAsync(async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    page.showPlan();
    tick(100);
    page.ionViewWillEnter();
    tick(5000);
    expect(navCtrl.navigateForward).not.toHaveBeenCalled();
    expect(page.calcShowing()).toBeFalse();
    discardPeriodicTasks();
  }));

  it('revealed after Start created the plan (the draft is gone), the quiz paints nothing and pops itself once the reveal completes', fakeAsync(async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    page.showPlan();
    tick(2500);

    // Start this plan on the preview: POST, replaceUrl to the saved page, clear the draft.
    draft.clear();

    // A swipe back from the saved page starts: ionViewWillEnter fires before the gesture is known
    // to complete, so nothing navigates yet, and no step or top bar is there to tap.
    page.ionViewWillEnter();
    fixture.detectChanges();
    expect(page.evicting()).toBeTrue();
    expect(page.calcShowing()).toBeFalse();
    expect(fixture.nativeElement.querySelector('app-goal-quiz-calc')).toBeNull();
    expect(onScreen(fixture)).toBeNull();
    expect(fixture.nativeElement.querySelector('.flow-top').classList).toContain('hidden');
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();

    // The gesture completes: the quiz pops to my-profile.
    page.ionViewDidEnter();
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    expect(navCtrl.navigateBack).toHaveBeenCalledTimes(1);
    discardPeriodicTasks();
  }));

  it('a cancelled swipe back from the preview, then Start, still evicts the quiz on the next reveal', fakeAsync(async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    page.showPlan();
    tick(2500);

    // The gesture starts (the quiz repaints its last step) and is cancelled: the preview stays on top.
    page.ionViewWillEnter();
    expect(page.calcShowing()).toBeFalse();
    expect(page.evicting()).toBeFalse();

    // Start on the preview clears the draft; a later swipe back from the saved page reveals the quiz.
    draft.clear();
    page.ionViewWillEnter();
    expect(page.evicting()).toBeTrue();
    page.ionViewDidEnter();
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');

    // A draft another quiz set is not this quiz's either.
    const other = await create({ type: 'CAR' });
    const otherPage = other.componentInstance;
    goTo(other, 1);
    otherPage.showPlan();
    tick(2500);
    draft.set({ ...otherPage.form(), price: 1 }, null);
    otherPage.ionViewWillEnter();
    expect(otherPage.evicting()).toBeTrue();
    discardPeriodicTasks();
  }));

  it('a reveal before Show my plan ever ran changes nothing', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    page.ionViewWillEnter();
    page.ionViewDidEnter();
    expect(page.evicting()).toBeFalse();
    expect(navCtrl.navigateBack).not.toHaveBeenCalled();
    expect(onScreen(fixture)).not.toBeNull();
  });

  it('a quiz popped after handing its draft over clears it on destroy', fakeAsync(async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    page.showPlan();
    tick(2500);
    expect(draft.draft()).not.toBeNull();
    fixture.destroy();
    expect(draft.draft()).toBeNull();
    discardPeriodicTasks();
  }));

  it('edit: lowering the count below a stored kid with logged balances warns on the count step; a new quiz never warns', async () => {
    const goal = kidsGoal(5, [{ birthYear: 2022, birthMonth: 3 }, { birthYear: 2024, birthMonth: null }]);
    const config = goal.config as KidsConfig;
    const ben = {
      ...config.kids[1], label: 'Ben', accounts: {
        plan529: { balance: 800, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 },
        brokerage: { balance: 400, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 },
      },
    };
    const fixture = await create({ id: '5' }, { goals: [{ ...goal, config: { ...config, kids: [config.kids[0], ben] } }] });
    const page = fixture.componentInstance;
    expect(page.currentStep().id).toBe('kids_count');
    expect(fixture.nativeElement.querySelector('.fscreen.on .kids-warn')).toBeNull();

    page.setKidCount(1);
    fixture.detectChanges();
    expect(text(fixture, '.fscreen.on .kids-warn')).toBe('Saving removes Ben and the $1,200 you logged for them.');

    // Back up to two: the stored kid is kept, so the warning goes.
    page.setKidCount(2);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fscreen.on .kids-warn')).toBeNull();

    // A dropped kid with nothing logged is not worth a line.
    const quiet = await create({ id: '6' }, { goals: [kidsGoal(6, [{ birthYear: 2022, birthMonth: 3 }, { birthYear: 2024, birthMonth: null }])] });
    quiet.componentInstance.setKidCount(1);
    quiet.detectChanges();
    expect(quiet.nativeElement.querySelector('.fscreen.on .kids-warn')).toBeNull();

    const fresh = await create({ type: 'KIDS' });
    fresh.componentInstance.setKidCount(3);
    fresh.componentInstance.setKidCount(1);
    fresh.detectChanges();
    expect(fresh.componentInstance.droppedKidLines()).toEqual([]);
    expect(fresh.nativeElement.querySelector('.fscreen.on .kids-warn')).toBeNull();
  });

  it('Continue is gated by validateForm: a $0 price shows the error and stays', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    page.setPrice(0);
    page.next();
    fixture.detectChanges();
    expect(page.stepIndex()).toBe(0);
    expect(text(fixture, '.fscreen.on .fs-error')).toBe('Enter an amount greater than $0.');
    page.setPrice(500_000);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fscreen.on .fs-error')).toBeNull();
    page.next();
    expect(page.stepIndex()).toBe(1);
  });

  it('close on a new quiz clears the draft and navigates back to /my-profile', async () => {
    const fixture = await create({ type: 'VACATION' });
    const set = spyOn(draft, 'set').and.callThrough();
    draft.set(fixture.componentInstance.form(), null);
    fixture.componentInstance.close();
    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/my-profile');
    expect(set).toHaveBeenCalledTimes(1);
    expect(draft.draft()).toBeNull();

    // Back on the first step is the same as close; close on an edit returns to the plan page.
    fixture.componentInstance.back();
    expect(navCtrl.navigateBack).toHaveBeenCalledTimes(2);
    const edit = await create({ id: '3' }, { goals: [houseGoal(3)] });
    edit.componentInstance.close();
    expect(navCtrl.navigateBack).toHaveBeenCalledWith(['/goals', 3]);
  });

  it('the quiz root carries ion-page: Ionic\'s iOS page slide moves only ion-content, ion-header or a child .ion-page, so without it the quiz never moved and just vanished at the end of the transition (Andy, 2026-11-18)', async () => {
    const fixture = await create({ type: 'VACATION' });
    const root = fixture.nativeElement.querySelector('.flow-host') as HTMLElement;
    expect(root.classList.contains('ion-page')).toBeTrue();
  });

  /** The two terms lines on the current screen: the down payment FRED plans, then the loan. */
  function termsLines(fixture: ComponentFixture<GoalQuizPage>): string[] {
    return (Array.from(onScreen(fixture).querySelectorAll('.terms-line')) as HTMLElement[])
      .map(el => (el.textContent ?? '').replace(/\s+/g, ' ').trim());
  }

  /** The rate slider (the only app-quiz-slider on the house screen, the second on the car screen). */
  function rateSlider(fixture: ComponentFixture<GoalQuizPage>): HTMLElement {
    return onScreen(fixture).querySelector('app-quiz-slider.rate-slider') as HTMLElement;
  }

  function slide(slider: HTMLElement, value: number, fixture: ComponentFixture<GoalQuizPage>): void {
    const input = slider.querySelector('input.sim-range') as HTMLInputElement;
    input.value = String(value);
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('house terms: chips 5/10/20, default 20, no Full price', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    expect(page.form().price).toBe(650_000);
    goTo(fixture, 1);
    expect(text(fixture, '.fscreen.on .fs-h1')).toBe('How much, and when?');
    expect(text(fixture, '.fscreen.on .fs-sub')).toBe('FRED plans the down payment. The mortgage comes after.');
    const chips = Array.from(onScreen(fixture).querySelectorAll('app-quiz-chips .bf-chip')) as HTMLElement[];
    expect(chips.map(c => c.textContent?.trim())).toEqual(['5%', '10%', '20%']);
    expect(page.downPaymentOptions.map(o => o.value)).toEqual([5, 10, 20]);
    expect(text(fixture, '.fscreen.on app-quiz-chips .bf-chip.on')).toBe('20%');
    expect(onScreen(fixture).textContent).not.toContain('Full price');
    expect(page.form().downPaymentPct).toBe(20);
    expect(page.form().targetYear).toBe(YEAR + 5);
    expect(page.yearBounds()).toEqual({ min: YEAR + 1, max: YEAR + 20 });

    chips[0].click();
    fixture.detectChanges();
    expect(page.form().downPaymentPct).toBe(5);
    expect(termsLines(fixture)[0]).toMatch(/^\$32,500 down · ~\$[\d,]+ a month to get there$/);
  });

  it('house lines read $130K down · ~$X a month to get there and the mortgage line at the default rate', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    // 7.28% (Freddie Mac PMMS) starts the slider at the nearest eighth: 58 / 8 = 7.25%.
    expect(page.form().loanRate).toBe(7.25);
    expect(text(fixture, '.fscreen.on app-quiz-slider.rate-slider .big-readout')).toBe('7.25%');
    // $650,000 × 20% = $130,000 down; the mortgage is the other $520,000 over 360 months.
    expect(termsLines(fixture)).toEqual([
      '$130,000 down · ~$1,644 a month to get there',
      'Then the mortgage: ~$3,547 a month at 7.25% over 30 years, before taxes and insurance.',
    ]);
    expect(page.termsLines()).toEqual(termsLines(fixture) as [string, string]);
  });

  it('moving the mortgage rate slider to 7.125% updates the line and the form', async () => {
    const fixture = await create({ type: 'HOUSE' });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    const slider = rateSlider(fixture);
    const input = slider.querySelector('input.sim-range') as HTMLInputElement;
    expect([input.min, input.max, input.step]).toEqual(['24', '80', '1']);
    expect(input.value).toBe('58');
    // A rate is slid, never typed.
    expect(slider.querySelector('button.big-readout')).toBeNull();

    slide(slider, 57, fixture);
    expect(page.form().loanRate).toBe(7.125);
    expect(text(fixture, '.fscreen.on app-quiz-slider.rate-slider .big-readout')).toBe('7.125%');
    expect(termsLines(fixture)[1]).toBe('Then the mortgage: ~$3,503 a month at 7.125% over 30 years, before taxes and insurance.');
    // The rate never changes what FRED plans: the down payment line stays.
    expect(termsLines(fixture)[0]).toMatch(/^\$130,000 down · /);
  });

  it('car terms: dollar slider default $5,000, lines', async () => {
    const fixture = await create({ type: 'CAR' });
    const page = fixture.componentInstance;
    expect(page.form().price).toBe(35_000);
    goTo(fixture, 1);
    expect(text(fixture, '.fscreen.on .fs-sub')).toBe('FRED plans the down payment. The loan comes after.');
    expect(onScreen(fixture).querySelector('app-quiz-chips')).toBeNull();
    expect(page.form().downPaymentAmount).toBe(5_000);
    const down = onScreen(fixture).querySelector('app-quiz-slider.down-slider') as HTMLElement;
    const downInput = down.querySelector('input.sim-range') as HTMLInputElement;
    expect([downInput.min, downInput.max, downInput.step]).toEqual(['0', '25000', '500']);
    expect(text(fixture, '.fscreen.on app-quiz-slider.down-slider .big-readout')).toBe('$5,000');

    const rate = rateSlider(fixture).querySelector('input.sim-range') as HTMLInputElement;
    expect([rate.min, rate.max, rate.value]).toEqual(['24', '120', '60']);
    // 7.54% (Fed G.19) starts at 60 / 8 = 7.5%.
    expect(page.form().loanRate).toBe(7.5);
    expect(text(fixture, '.fscreen.on app-quiz-slider.rate-slider .big-readout')).toBe('7.5%');
    expect(termsLines(fixture)).toEqual([
      '$5,000 down · ~$116 a month to get there',
      'Then the loan: ~$601 a month at 7.5% over 60 months.',
    ]);

    slide(down, 10_000, fixture);
    expect(page.form().downPaymentAmount).toBe(10_000);
    // $25,000 left to borrow at 7.5% over 60 months.
    expect(termsLines(fixture)[1]).toBe('Then the loan: ~$501 a month at 7.5% over 60 months.');
  });

  it('car down payment clamps to the price and $0 blocks Continue', async () => {
    const fixture = await create({ type: 'CAR' });
    const page = fixture.componentInstance;
    page.setCarDown(10_000);
    page.setPrice(8_000);
    expect(page.form().downPaymentAmount).toBe(8_000);
    goTo(fixture, 1);
    const downInput = onScreen(fixture).querySelector('app-quiz-slider.down-slider input.sim-range') as HTMLInputElement;
    expect(downInput.max).toBe('8000');
    expect(termsLines(fixture)[0]).toMatch(/^\$8,000 down · /);
    expect(termsLines(fixture)[1]).toBe('Then the loan: ~$0 a month at 7.5% over 60 months.');

    const cta = () => onScreen(fixture).querySelector('.fs-cta') as HTMLButtonElement;
    expect(cta().disabled).toBeFalse();
    page.setCarDown(0);
    fixture.detectChanges();
    expect(page.ctaDisabled()).toBeTrue();
    expect(cta().disabled).toBeTrue();
    expect(text(fixture, '.fscreen.on .terms-hint'))
      .toBe('With $0 down there is nothing to save. Set a down payment, or pick Something else.');
    expect(termsLines(fixture)).toEqual([]);
    page.next();
    expect(page.calcShowing()).toBeFalse();
    expect(draft.draft()).toBeNull();

    page.setCarDown(500);
    fixture.detectChanges();
    expect(cta().disabled).toBeFalse();
    expect(fixture.nativeElement.querySelector('.fscreen.on .terms-hint')).toBeNull();
  });

  it('edit: a stored car opens its terms at the stored down payment and rate', async () => {
    const fixture = await create({ id: '7' }, { goals: [carGoal(7)] });
    const page = fixture.componentInstance;
    goTo(fixture, 1);
    expect(page.form().downPaymentAmount).toBe(6_000);
    expect(termsLines(fixture)[1]).toBe('Then the loan: ~$521 a month at 7.5% over 60 months.');
  });

  it('vacation: trips 1..10 with a budget slider and the monthly chip', async () => {
    const fixture = await create({ type: 'VACATION' });
    const page = fixture.componentInstance;
    expect(page.form().tripsPerYear).toBe(2);
    expect(page.form().budgetPerTrip).toBe(5_000);
    expect(text(fixture, '.fscreen.on .fetch-chip .chip-text')).toBe('~$834 a month');
    page.setTrips(11);
    expect(page.form().tripsPerYear).toBe(10);
    page.setTrips(0);
    expect(page.form().tripsPerYear).toBe(1);
  });

  it('emergency: the By chips write targetYear = year + n and the chip reads the target and month', async () => {
    const fixture = await create({ type: 'EMERGENCY_FUND' });
    const page = fixture.componentInstance;
    expect(page.form().targetYear).toBe(YEAR + 2);
    expect(page.form().months).toBe(6);
    expect(page.form().monthlyExpenses).toBe(4_000);
    expect(text(fixture, '.fscreen.on .fetch-chip .chip-text')).toBe(`$24,000 by Dec ${YEAR + 2}`);
    page.setByYears(3);
    expect(page.form().targetYear).toBe(YEAR + 3);
    page.setMonths(3);
    fixture.detectChanges();
    expect(text(fixture, '.fscreen.on .fetch-chip .chip-text')).toBe(`$12,000 by Dec ${YEAR + 3}`);
  });

  it('wedding and custom: one screen each; custom offers the icon chips and keeps the default name', async () => {
    const wedding = await create({ type: 'WEDDING' });
    expect(text(wedding, '.fscreen.on .fs-h1')).toBe('The budget, and when');
    expect(wedding.componentInstance.form().targetAmount).toBe(30_000);
    expect(text(wedding, '.fscreen.on .fetch-chip .chip-text')).toMatch(/^~\$\d{1,3}(,\d{3})* a month$/);

    const custom = await create({ type: 'CUSTOM' });
    const page = custom.componentInstance;
    expect(text(custom, '.fscreen.on .fs-h1')).toBe('How much, and when?');
    expect(page.form().name).toBe('My goal');
    expect(onScreen(custom).querySelectorAll('.icon-chips button').length).toBe(7);
    page.setIcon('savings');
    expect(page.form().icon).toBe('savings');
  });

  it('edit round trip: formFromGoal then formToConfig equals the stored config for KIDS, HOUSE, CAR, VACATION, EMERGENCY_FUND, WEDDING, CUSTOM', () => {
    const goals: Goal[] = [
      kidsGoal(1, [{ birthYear: 2024, birthMonth: null }, { birthYear: 2028, birthMonth: 4 }, { birthYear: null, birthMonth: null }]),
      houseGoal(2),
      carGoal(7),
      goalOf(3, 'VACATION', { tripsPerYear: 3, budgetPerTrip: 2_500, savedThisYear: 400, savedYear: YEAR }),
      goalOf(4, 'EMERGENCY_FUND', { monthlyExpenses: 4_000, months: 3, targetYear: YEAR + 2 }),
      goalOf(5, 'WEDDING', { targetAmount: 30_000, targetYear: YEAR + 3 }),
      goalOf(6, 'CUSTOM', { targetAmount: 12_000, targetYear: YEAR + 4 }),
    ];
    for (const goal of goals) {
      expect(formToConfig(formFromGoal(goal), goal, NOW)).withContext(goal.type).toEqual(goal.config);
    }
  });
});
