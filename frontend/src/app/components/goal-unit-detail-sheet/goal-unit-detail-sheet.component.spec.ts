import { Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import { GoalUnitDetailSheetComponent, UnitDetail } from './goal-unit-detail-sheet.component';
import { KidCostSourcesSheetComponent } from '../kid-cost-sources-sheet/kid-cost-sources-sheet.component';
import { GoalPlanInfoSheetComponent } from '../goal-plan-info-sheet/goal-plan-info-sheet.component';
import { InfoNote } from '../goal-plan-info-sheet/info-note';

function detail(partial: Partial<UnitDetail> = {}): UnitDetail {
  return {
    kidLabel: 'Ellie',
    title: 'College',
    kind: 'account',
    targetLine: '$47,800 by 2042',
    pill: 'Invested',
    monthly: 104,
    perPaycheck: 48,
    finished: false,
    balance: null,
    status: null,
    milestones: [
      { label: 'At 18', year: 2042, value: '$48K' },
      { label: 'At 22', year: 2046, value: '$71K' },
      { label: 'At 30', year: 2054, value: '$158K' },
    ],
    notes: [],
    sourcesLine: 'Four years at 2025-26 College Board prices, which tend to rise.',
    sourcesSheetKids: null,
    ...partial,
  };
}

const nestEgg = detail({
  title: 'Nest egg',
  targetLine: '$120,000 by 2054',
  monthly: 210,
  perPaycheck: 97,
  balance: 1200,
  sourcesLine: 'Your target, grown at the return you chose. At 22 and at 30 assume it stays invested.',
});

type Fixture = ComponentFixture<GoalUnitDetailSheetComponent>;

describe('GoalUnitDetailSheetComponent', () => {
  let modal: jasmine.SpyObj<ModalController>;

  function mount(
    d: Signal<UnitDetail | null>,
    inputs: { onBalance?: ((value: number) => Promise<void>) | null; notes?: InfoNote[] } = {},
  ): Fixture {
    modal = jasmine.createSpyObj('ModalController', ['dismiss', 'create']);
    modal.create.and.resolveTo({ present: async () => {} } as unknown as HTMLIonModalElement);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [GoalUnitDetailSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    const fixture = TestBed.createComponent(GoalUnitDetailSheetComponent);
    fixture.componentInstance.detail = d;
    if (inputs.onBalance !== undefined) fixture.componentInstance.onBalance = inputs.onBalance;
    if (inputs.notes !== undefined) fixture.componentInstance.notes = inputs.notes;
    fixture.detectChanges();
    return fixture;
  }

  function el(fixture: Fixture, selector: string): HTMLElement {
    return fixture.nativeElement.querySelector(selector) as HTMLElement;
  }

  function input(fixture: Fixture, selector: string): HTMLInputElement {
    return el(fixture, selector) as HTMLInputElement;
  }

  function blur(fixture: Fixture, selector: string, value: string): void {
    const field = input(fixture, selector);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  }

  function text(fixture: Fixture, selector?: string): string {
    const root = selector ? el(fixture, selector) : fixture.nativeElement;
    return (root.textContent as string).replace(/\s+/g, ' ').trim();
  }

  it('shows the kid, the unit, its pill, the monthly, the per paycheck figure and the 18/22/30 milestones', () => {
    const fixture = mount(signal(detail()));
    const t = text(fixture);
    expect(t).toContain('Ellie');
    expect(t).toContain('College');
    expect(t).toContain('Invested');
    expect(t).toContain('$47,800 by 2042');
    expect(t).toContain('$104');
    expect(t).toContain('~$48 a paycheck');
    const tiles = Array.from(fixture.nativeElement.querySelectorAll('.ud-ms-tile')).map(e =>
      Array.from((e as HTMLElement).children).map(c => c.textContent!.trim()).join(' '));
    expect(tiles).toEqual(['At 18 2042 $48K', 'At 22 2046 $71K', 'At 30 2054 $158K']);
    expect(fixture.nativeElement.querySelector('.ud-link')).toBeNull();
  });

  it('a baby fund shows its notes, no milestones, a Done state, and opens the sources sheet for the family size', async () => {
    const fixture = mount(signal(detail({
      title: 'Baby fund', kind: 'fund', pill: 'Cash', monthly: 0, perPaycheck: null, finished: true, milestones: [],
      notes: ['Planned as cash at ~4%, since you need it within 3 years.', 'No date yet: this runway ends within 3 years.'],
      sourcesLine: 'FRED estimate: first-year average (USDA) plus birth costs (Peterson-KFF), rounded up.',
      sourcesSheetKids: 4,
    })));
    const t = text(fixture);
    expect(t).toContain('Baby fund');
    expect(t).toContain('Cash');
    expect(t).toContain('Done');
    expect(t).not.toContain('a paycheck');
    expect(fixture.nativeElement.querySelectorAll('.ud-ms-tile').length).toBe(0);
    expect(fixture.nativeElement.querySelectorAll('.ud-note').length).toBe(2);
    expect(t).toContain('No date yet: this runway ends within 3 years.');

    (fixture.nativeElement.querySelector('.ud-link') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(modal.create).toHaveBeenCalledTimes(1);
    const args = modal.create.calls.mostRecent().args[0];
    expect(args.component).toBe(KidCostSourcesSheetComponent);
    expect(args.componentProps).toEqual({ kidsCount: 4 });
  });

  it('Done dismisses the sheet and the copy has no em dash', () => {
    const fixture = mount(signal(detail({ balance: 4200, status: { text: 'On plan', tone: 'good' } })), {
      onBalance: () => Promise.resolve(),
      notes: [{ icon: 'savings', lead: 'Invested.', text: 'Long horizons stay in the market.' }],
    });
    expect(text(fixture)).not.toContain(String.fromCharCode(0x2014));
    (fixture.nativeElement.querySelector('.ud-done') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  it('shows the Saved so far field and the Status word, and saves the balance on blur', fakeAsync(() => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal({ ...nestEgg, balance: 4200, status: { text: 'On plan', tone: 'good' as const } }), { onBalance });
    expect(input(fixture, '.ud-bal input').value).toBe('4200');
    expect(input(fixture, '.ud-bal input').getAttribute('aria-label')).toBe('Saved so far');
    expect(input(fixture, '.ud-bal input').getAttribute('inputmode')).toBe('numeric');
    expect(text(fixture, '.ud-status')).toBe('On plan');
    expect(el(fixture, '.ud-status').classList).toContain('good');
    blur(fixture, '.ud-bal input', '5,000');
    flushMicrotasks();
    expect(onBalance).toHaveBeenCalledWith(5000);
  }));

  it('an emptied Saved so far field saves 0', fakeAsync(() => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal(nestEgg), { onBalance });
    blur(fixture, '.ud-bal input', '');
    flushMicrotasks();
    expect(onBalance).toHaveBeenCalledWith(0);
  }));

  it('each status tone sets its class', () => {
    const d = signal<UnitDetail | null>({ ...nestEgg, status: { text: 'Behind', tone: 'warn' } });
    const fixture = mount(d);
    expect(el(fixture, '.ud-status').classList).toContain('warn');
    d.set({ ...nestEgg, status: { text: 'Started', tone: 'info' } });
    fixture.detectChanges();
    expect(el(fixture, '.ud-status').classList).toContain('info');
    expect(el(fixture, '.ud-status').classList).not.toContain('warn');
    d.set({ ...nestEgg, status: { text: 'Done', tone: 'muted' } });
    fixture.detectChanges();
    expect(el(fixture, '.ud-status').classList).toContain('muted');
  });

  it('re-renders when the live detail changes', () => {
    const d = signal<UnitDetail | null>(nestEgg);
    const fixture = mount(d);
    d.set({ ...nestEgg, monthly: 600 });
    fixture.detectChanges();
    expect(text(fixture, '.ud-tiles .ud-tile-val')).toContain('$600');
  });

  it('closes itself when the live detail turns null', () => {
    const d = signal<UnitDetail | null>(nestEgg);
    const fixture = mount(d);
    expect(modal.dismiss).not.toHaveBeenCalled();
    d.set(null);
    fixture.detectChanges();
    expect(modal.dismiss).toHaveBeenCalled();
  });

  it('the Everyday variant has no balance, no status and no milestones, and keeps the sources link', async () => {
    const fixture = mount(signal(detail({
      title: 'Everyday', kind: 'everyday', pill: 'Cash', balance: null, status: null, milestones: [],
      targetLine: 'Age 2 to 5', monthly: 1540, perPaycheck: 711,
      sourcesLine: 'USDA estimate of what a kid costs a month, by age.', sourcesSheetKids: 2,
    })), { onBalance: () => Promise.resolve() });
    expect(text(fixture)).toContain('Everyday');
    expect(text(fixture, '.ud-tiles .ud-tile-val')).toContain('$1,540');
    expect(fixture.nativeElement.querySelector('.ud-bal')).toBeNull();
    expect(fixture.nativeElement.querySelector('.ud-status')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.ud-ms-tile').length).toBe(0);
    (fixture.nativeElement.querySelector('.ud-link') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(modal.create.calls.mostRecent().args[0].component).toBe(KidCostSourcesSheetComponent);
    expect(modal.create.calls.mostRecent().args[0].componentProps).toEqual({ kidsCount: 2 });
  });

  it('shows the info button only when notes are passed, and opens the info sheet with them', async () => {
    expect(el(mount(signal(nestEgg)), '.ud-info')).toBeNull();

    const notes: InfoNote[] = [{ icon: 'savings', lead: 'Invested.', text: 'Long horizons stay in the market.' }];
    const fixture = mount(signal(nestEgg), { notes });
    const button = el(fixture, '.ud-info') as HTMLButtonElement;
    expect(button).not.toBeNull();
    expect(button.querySelector('.material-symbols-outlined')!.textContent!.trim()).toBe('info');
    button.click();
    await fixture.whenStable();
    expect(modal.create).toHaveBeenCalledTimes(1);
    const args = modal.create.calls.mostRecent().args[0];
    expect(args.component).toBe(GoalPlanInfoSheetComponent);
    expect(args.componentProps).toEqual({ notes });
  });

  it('a finished fund shows the balance read-only', () => {
    const fixture = mount(signal(detail({
      title: 'Baby fund', kind: 'fund', pill: 'Cash', finished: true, milestones: [], perPaycheck: null, balance: 20800,
      status: { text: 'Done', tone: 'muted' },
    })), { onBalance: () => Promise.resolve() });
    expect(el(fixture, '.ud-bal')).not.toBeNull();
    expect(el(fixture, '.ud-bal input')).toBeNull();
    expect(text(fixture, '.ud-bal')).toContain('$20,800');
  });

  it('without an onBalance handler the balance is read-only text', () => {
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }));
    expect(el(fixture, '.ud-bal input')).toBeNull();
    expect(text(fixture, '.ud-bal')).toContain('$4,200');
  });
});
