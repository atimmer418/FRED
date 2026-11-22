import { Signal, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ModalController } from '@ionic/angular/standalone';

import { GoalUnitDetailSheetComponent, UnitDetail } from './goal-unit-detail-sheet.component';
import { PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';
import { detents } from '../../utils/plan-amount-scale';
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
    target: 47_800,
    status: null,
    milestones: [
      { label: 'At 18', year: 2042, value: '$48K' },
      { label: 'At 22', year: 2046, value: '$71K' },
      { label: 'At 30', year: 2054, value: '$158K' },
    ],
    notes: [],
    sourcesLine: 'Four years at 2025-26 College Board prices, which tend to rise.',
    sourcesSheetKids: null,
    share: 0,
    growthLine: null,
    ...partial,
  };
}

const nestEgg = detail({
  title: 'Nest egg',
  targetLine: '$120,000 by 2054',
  monthly: 210,
  perPaycheck: 97,
  balance: 1200,
  target: 120_000,
  sourcesLine: 'Your target, grown at the return you chose. At 22 and at 30 assume it stays invested.',
});

type Fixture = ComponentFixture<GoalUnitDetailSheetComponent>;

describe('GoalUnitDetailSheetComponent', () => {
  let modal: jasmine.SpyObj<ModalController>;

  function mount(
    d: Signal<UnitDetail | null>,
    inputs: { onBalance?: ((value: number) => Promise<void>) | null; notes?: InfoNote[]; onTally?: ((kind: 'missed' | 'extra') => Promise<void>) | null } = {},
  ): Fixture {
    modal = jasmine.createSpyObj('ModalController', ['dismiss', 'create']);
    modal.create.and.resolveTo({ present: async () => {} } as unknown as HTMLIonModalElement);
    // Reduced motion: the tray snaps open and shut, so its presence can be asserted without
    // awaiting a collapse. The motion itself is the host's spec.
    if (!jasmine.isSpy(window.matchMedia)) {
      spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);
    }
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [GoalUnitDetailSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    const fixture = TestBed.createComponent(GoalUnitDetailSheetComponent);
    fixture.componentInstance.detail = d;
    if (inputs.onBalance !== undefined) fixture.componentInstance.onBalance = inputs.onBalance;
    if (inputs.notes !== undefined) fixture.componentInstance.notes = inputs.notes;
    if (inputs.onTally !== undefined) fixture.componentInstance.onTally = inputs.onTally;
    fixture.detectChanges();
    return fixture;
  }

  function el(fixture: Fixture, selector: string): HTMLElement {
    return fixture.nativeElement.querySelector(selector) as HTMLElement;
  }

  const balanceBtn = (fixture: Fixture) => el(fixture, '.ud-bal-btn') as HTMLButtonElement | null;
  const trayDone = (fixture: Fixture) => el(fixture, '.ud-tray-done') as HTMLButtonElement | null;
  const slot = (fixture: Fixture) => el(fixture, '.field-tray');
  const tray = (fixture: Fixture) =>
    fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;

  function click(fixture: Fixture, selector: string): void {
    (el(fixture, selector) as HTMLElement).click();
    fixture.detectChanges();
  }

  /** The slider landing on a value: the tray's output, as a finger or a nudge would raise it. */
  function slideTo(fixture: Fixture, value: number): void {
    tray(fixture)!.valueChange.emit(value);
    fixture.detectChanges();
  }

  /** A tap as the host's outside-tap listener sees it: a press and a release on the same element. */
  function press(fixture: Fixture, target: Element): void {
    target.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    target.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
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

  it('shows the Saved so far value as a button (no input) with the Status word; tapping it opens the tray under the tiles', () => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal({ ...nestEgg, balance: 4200, status: { text: 'On plan', tone: 'good' as const } }), { onBalance });
    expect(el(fixture, '.ud-bal input')).toBeNull();
    expect(text(fixture, '.ud-bal-btn')).toBe('$4,200');
    expect(text(fixture, '.ud-bal .fd-tile-label')).toBe('Saved so far');
    expect(text(fixture, '.ud-status')).toBe('On plan');
    expect(el(fixture, '.ud-status').classList).toContain('good');
    expect(slot(fixture)).toBeNull();
    expect(trayDone(fixture)).toBeNull();

    click(fixture, '.ud-bal-btn');
    const sheet = fixture.componentInstance;
    expect(sheet.tray.open()).toBe('balance');
    expect(el(fixture, '.ud-bal').classList).toContain('active');
    expect(el(fixture, '.ud-bal .fd-tile-label').classList).toContain('active');
    expect(balanceBtn(fixture)!.getAttribute('aria-expanded')).toBe('true');
    // Done sits in the tile's label row; the slot is the full-width block right after the tiles.
    expect(el(fixture, '.ud-bal .ud-tile-head .ud-tray-done')).not.toBeNull();
    expect(slot(fixture)).not.toBeNull();
    expect(slot(fixture).previousElementSibling).toBe(el(fixture, '.ud-tiles-2'));
    expect(el(fixture, '.ps').getAttribute('aria-label')).toBe('Saved so far');
    expect(el(fixture, '.ps').getAttribute('aria-valuetext')).toBe('$4,200');
  });

  it('the tray runs from $0 to the unit target in $100 nudges; a unit with no target caps at $50,000', () => {
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }), { onBalance: () => Promise.resolve() });
    click(fixture, '.ud-bal-btn');
    const scale = tray(fixture)!.scale();
    expect(scale.min).toBe(0);
    expect(scale.max).toBe(120_000);
    expect(scale.fine).toBe(100);
    const list = detents(scale);
    expect(list[list.length - 1]).toBe(120_000);
    expect(list.length).toBeGreaterThanOrEqual(80);
    expect(list.length).toBeLessThanOrEqual(160);
    expect(Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')).map(e => (e as HTMLElement).textContent)).toEqual(['$0', '$120,000']);

    const untargeted = mount(signal({ ...nestEgg, balance: 4200, target: null }), { onBalance: () => Promise.resolve() });
    click(untargeted, '.ud-bal-btn');
    expect(tray(untargeted)!.scale().max).toBe(50_000);
  });

  it('a balance already past the target lifts the cap to the next round number above it, so it can still go up', () => {
    const fixture = mount(signal({ ...nestEgg, balance: 130_000 }), { onBalance: () => Promise.resolve() });
    click(fixture, '.ud-bal-btn');
    expect(tray(fixture)!.scale().max).toBe(250_000);
    expect(el(fixture, '.ps').getAttribute('aria-valuenow')).toBe('130000');
    // The draft never moves the cap: sliding under the target leaves the scale alone until a save lands.
    slideTo(fixture, 100_000);
    expect(tray(fixture)!.scale().max).toBe(250_000);
  });

  it('sliding shows the value at once and saves nothing until the tray closes; Done saves the draft as whole dollars', fakeAsync(() => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const d = signal<UnitDetail | null>({ ...nestEgg, balance: 4200 });
    const fixture = mount(d, { onBalance });
    click(fixture, '.ud-bal-btn');
    slideTo(fixture, 5000);
    expect(text(fixture, '.ud-bal-btn')).toBe('$5,000');
    expect(onBalance).not.toHaveBeenCalled();
    slideTo(fixture, 5100);
    click(fixture, '.ud-tray-done');
    expect(onBalance).toHaveBeenCalledOnceWith(5100);
    expect(fixture.componentInstance.tray.open()).toBeNull();
    expect(slot(fixture)).toBeNull();
    // The tile keeps the draft until the save settles and the live detail carries the new balance.
    expect(text(fixture, '.ud-bal-btn')).toBe('$5,100');
    d.set({ ...nestEgg, balance: 5100 });
    flushMicrotasks();
    fixture.detectChanges();
    expect(text(fixture, '.ud-bal-btn')).toBe('$5,100');
  }));

  it('a save that leaves the balance unchanged (it failed) shows the live balance again', fakeAsync(() => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }), { onBalance });
    click(fixture, '.ud-bal-btn');
    slideTo(fixture, 9900);
    click(fixture, '.ud-tray-done');
    flushMicrotasks();
    fixture.detectChanges();
    expect(onBalance).toHaveBeenCalledWith(9900);
    expect(text(fixture, '.ud-bal-btn')).toBe('$4,200');
  }));

  it('a tap outside the tile and the tray closes the tray and saves; a tap on the slider or the tile does not', () => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }), { onBalance });
    click(fixture, '.ud-bal-btn');
    slideTo(fixture, 6000);
    press(fixture, el(fixture, '.ps-ends'));
    press(fixture, balanceBtn(fixture)!);
    expect(fixture.componentInstance.tray.open()).toBe('balance');
    expect(onBalance).not.toHaveBeenCalled();
    press(fixture, el(fixture, '.ud-sub'));
    expect(fixture.componentInstance.tray.open()).toBeNull();
    expect(onBalance).toHaveBeenCalledOnceWith(6000);
  });

  it('the sheet\'s Done closes the tray and saves before dismissing; leaving the sheet saves too', () => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }), { onBalance });
    click(fixture, '.ud-bal-btn');
    slideTo(fixture, 7000);
    click(fixture, '.ud-done');
    expect(onBalance).toHaveBeenCalledOnceWith(7000);
    expect(fixture.componentInstance.tray.open()).toBeNull();
    expect(modal.dismiss).toHaveBeenCalledWith();

    click(fixture, '.ud-bal-btn');
    slideTo(fixture, 8000);
    fixture.componentInstance.ionViewWillLeave();
    fixture.detectChanges();
    expect(onBalance).toHaveBeenCalledWith(8000);
    expect(slot(fixture)).toBeNull();
  });

  it('tapping the open tile again closes the tray; a close with nothing slid saves nothing', () => {
    const onBalance = jasmine.createSpy('onBalance').and.returnValue(Promise.resolve());
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }), { onBalance });
    click(fixture, '.ud-bal-btn');
    click(fixture, '.ud-bal-btn');
    expect(fixture.componentInstance.tray.open()).toBeNull();
    expect(onBalance).not.toHaveBeenCalled();
  });

  it('the label reads the step in force while the finger is down', () => {
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }), { onBalance: () => Promise.resolve() });
    click(fixture, '.ud-bal-btn');
    fixture.componentInstance.tray.onDragging(true);
    fixture.detectChanges();
    expect(text(fixture, '.ud-bal .fd-tile-label')).toMatch(/^± \$\d/);
    expect(el(fixture, '.ud-bal .fd-tile-label').classList).toContain('ud-step');
    fixture.componentInstance.tray.onDragging(false);
    fixture.detectChanges();
    expect(text(fixture, '.ud-bal .fd-tile-label')).toBe('Saved so far');
  });

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
    expect(balanceBtn(fixture)).toBeNull();
    expect(text(fixture, '.ud-bal')).toContain('$20,800');
  });

  it('without an onBalance handler the balance is read-only text', () => {
    const fixture = mount(signal({ ...nestEgg, balance: 4200 }));
    expect(balanceBtn(fixture)).toBeNull();
    expect(text(fixture, '.ud-bal')).toContain('$4,200');
  });

  // ── Missed one / Doubled up ────────────────────────────────────────────────

  const pillButtons = (fixture: Fixture) => Array.from(fixture.nativeElement.querySelectorAll('.tp-pill')) as HTMLButtonElement[];

  it("pills show for a saving unit with the unit's share", () => {
    const fixture = mount(signal({ ...nestEgg, balance: 4200, share: 450 }), { onTally: () => Promise.resolve() });
    const pills = pillButtons(fixture);
    expect(pills.map(p => p.textContent!.replace(/\s+/g, ' ').trim())).toEqual(['removeMissed one−$450', 'addDoubled up+$450']);
    // Under the tiles.
    expect(el(fixture, 'app-tally-pills').previousElementSibling!.className).toContain('ud-tiles');
  });

  it('no pills without a handler, for a finished unit, or at share 0', () => {
    expect(pillButtons(mount(signal({ ...nestEgg, balance: 4200, share: 450 })))).toEqual([]);
    expect(pillButtons(mount(signal({ ...nestEgg, balance: 4200, share: 450, finished: true }), { onTally: () => Promise.resolve() }))).toEqual([]);
    expect(pillButtons(mount(signal({ ...nestEgg, balance: 4200, share: 0 }), { onTally: () => Promise.resolve() }))).toEqual([]);
  });

  it("Missed one calls onTally('missed') and Doubled up calls onTally('extra')", async () => {
    const onTally = jasmine.createSpy('onTally').and.resolveTo();
    const fixture = mount(signal({ ...nestEgg, balance: 4200, share: 450 }), { onTally });
    pillButtons(fixture)[0].click();
    expect(onTally).toHaveBeenCalledOnceWith('missed');
    await fixture.whenStable();
    pillButtons(fixture)[1].click();
    expect(onTally.calls.mostRecent().args).toEqual(['extra']);
  });

  it('the pills are disabled while a tap is in flight', fakeAsync(() => {
    let release!: () => void;
    const onTally = jasmine.createSpy('onTally').and.returnValue(new Promise<void>(r => (release = r)));
    const fixture = mount(signal({ ...nestEgg, balance: 4200, share: 450 }), { onTally });
    pillButtons(fixture)[0].click();
    fixture.detectChanges();
    expect(pillButtons(fixture).every(p => p.disabled)).toBeTrue();
    release();
    flushMicrotasks();
    fixture.detectChanges();
    expect(pillButtons(fixture).every(p => !p.disabled)).toBeTrue();
  }));

  it('Status tile reads Behind by $450 after a missed tap', async () => {
    const d = signal<UnitDetail | null>({ ...nestEgg, balance: 4200, share: 450, status: { text: 'On plan', tone: 'good' } });
    const fixture = mount(d, {
      onTally: async () => d.update(x => ({ ...x!, balance: 3750, status: { text: 'Behind by $450', tone: 'warn' } })),
    });
    pillButtons(fixture)[0].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text(fixture, '.ud-status')).toBe('Behind by $450');
    expect(el(fixture, '.ud-status').classList).toContain('warn');
    expect(text(fixture, '.ud-bal')).toContain('$3,750');
  });

  it('the nest egg sheet shows the Assuming growth line and the baby fund sheet does not', () => {
    const line = 'Assuming growth at 10%, worth ~$4,900 today';
    const nest = mount(signal({ ...nestEgg, balance: 4200, share: 450, growthLine: line }), { onTally: () => Promise.resolve() });
    expect(text(nest, '.ud-growth')).toBe(line);
    // Above the pills, under the Saved so far and Status tiles.
    expect(el(nest, '.ud-growth').nextElementSibling!.tagName.toLowerCase()).toBe('app-tally-pills');
    const fund = mount(signal({ ...nestEgg, title: 'Baby fund' as const, kind: 'fund' as const, balance: 800, share: 200 }), { onTally: () => Promise.resolve() });
    expect(el(fund, '.ud-growth')).toBeNull();
  });

  it('everyday has no pills', () => {
    const everyday = detail({ title: 'Everyday', kind: 'everyday', pill: 'Cash', balance: null, share: 0 });
    expect(pillButtons(mount(signal(everyday), { onTally: () => Promise.resolve() }))).toEqual([]);
  });
});
