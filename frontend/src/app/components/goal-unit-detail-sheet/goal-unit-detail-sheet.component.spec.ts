import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import { GoalUnitDetailSheetComponent, UnitDetail } from './goal-unit-detail-sheet.component';
import { KidCostSourcesSheetComponent } from '../kid-cost-sources-sheet/kid-cost-sources-sheet.component';

function detail(partial: Partial<UnitDetail> = {}): UnitDetail {
  return {
    kidLabel: 'Ellie',
    title: 'College',
    targetLine: '$47,800 by 2042',
    pill: 'Invested',
    monthly: 104,
    perPaycheck: 48,
    finished: false,
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

describe('GoalUnitDetailSheetComponent', () => {
  let modal: jasmine.SpyObj<ModalController>;

  function create(d: UnitDetail): ComponentFixture<GoalUnitDetailSheetComponent> {
    modal = jasmine.createSpyObj('ModalController', ['dismiss', 'create']);
    modal.create.and.resolveTo({ present: async () => {} } as unknown as HTMLIonModalElement);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [GoalUnitDetailSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    const fixture = TestBed.createComponent(GoalUnitDetailSheetComponent);
    fixture.componentInstance.detail = d;
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<GoalUnitDetailSheetComponent>): string {
    return (fixture.nativeElement.textContent as string).replace(/\s+/g, ' ').trim();
  }

  it('shows the kid, the unit, its pill, the monthly, the per paycheck figure and the 18/22/30 milestones', () => {
    const fixture = create(detail());
    const t = text(fixture);
    expect(t).toContain('Ellie');
    expect(t).toContain('College');
    expect(t).toContain('Invested');
    expect(t).toContain('$47,800 by 2042');
    expect(t).toContain('$104');
    expect(t).toContain('~$48 a paycheck');
    const tiles = Array.from(fixture.nativeElement.querySelectorAll('.ud-ms-tile')).map(el =>
      Array.from((el as HTMLElement).children).map(c => c.textContent!.trim()).join(' '));
    expect(tiles).toEqual(['At 18 2042 $48K', 'At 22 2046 $71K', 'At 30 2054 $158K']);
    expect(fixture.nativeElement.querySelector('.ud-link')).toBeNull();
  });

  it('a baby fund shows its notes, no milestones, a Done state, and opens the sources sheet for the family size', async () => {
    const fixture = create(detail({
      title: 'Baby fund', pill: 'Cash', monthly: 0, perPaycheck: null, finished: true, milestones: [],
      notes: ['Planned as cash at ~4%, since you need it within 3 years.', 'No date yet: this runway ends within 3 years.'],
      sourcesLine: 'FRED estimate: first-year average (USDA) plus birth costs (Peterson-KFF), rounded up.',
      sourcesSheetKids: 4,
    }));
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
    const fixture = create(detail());
    expect(text(fixture)).not.toContain(String.fromCharCode(0x2014));
    (fixture.nativeElement.querySelector('.ud-done') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });
});
