import { CUSTOM_ELEMENTS_SCHEMA, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { BehaviorSubject, of } from 'rxjs';

import { GoalPage } from './goal.page';
import { AuthService } from '../../services/auth.service';
import { GoalsService } from '../../services/goals.service';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { Goal } from '../../models/goal.model';

function houseGoal(id: number, completed = false): Goal {
  return {
    id, clientId: null, type: 'HOUSE', name: 'First home', icon: 'home',
    config: { price: 400_000, downPaymentPct: 20, targetYear: new Date().getFullYear() + 5 },
    currentBalance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0,
    planRate: 0.07, planRateKind: 'market', planAnchoredAt: '2026-01-01',
    completed, sortOrder: 0, createdAt: '', updatedAt: '',
  };
}

describe('GoalPage (setup step)', () => {
  let navCtrl: jasmine.SpyObj<NavController>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let goals: ReturnType<typeof signal<Goal[]>>;
  let load: jasmine.Spy;
  let progress: BehaviorSubject<{ selectedTier: string } | null>;

  async function create(
    params: Record<string, string>,
    opts: { goals?: Goal[]; loaded?: Goal[]; tier?: string } = {},
  ): Promise<ComponentFixture<GoalPage>> {
    goals = signal<Goal[]>(opts.goals ?? []);
    load = jasmine.createSpy('load').and.callFake(() => {
      if (opts.loaded) goals.set(opts.loaded);
      return of(goals());
    });
    progress = new BehaviorSubject<{ selectedTier: string } | null>({ selectedTier: opts.tier ?? 'plus' });
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo({
      present: async () => {},
      onDidDismiss: async () => ({ role: undefined }),
    } as unknown as HTMLIonModalElement);

    await TestBed.configureTestingModule({
      imports: [GoalPage],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(params) } } },
        { provide: NavController, useValue: navCtrl },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ModalController, useValue: modalCtrl },
        { provide: GoalsService, useValue: { goals, load } },
        { provide: AuthService, useValue: { userProgress$: progress } },
      ],
    })
      // Ionic standalone components need their own providers; render their children as plain DOM.
      .overrideComponent(GoalPage, {
        set: { imports: [CommonModule, FormsModule], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
      })
      .compileComponents();

    const fixture = TestBed.createComponent(GoalPage);
    await fixture.componentInstance.ngOnInit();
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
});
