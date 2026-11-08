import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import { KidCostSourcesSheetComponent } from './kid-cost-sources-sheet.component';
import { costCardSources } from '../../utils/kid-cost.utils';

describe('KidCostSourcesSheetComponent', () => {
  let modal: jasmine.SpyObj<ModalController>;

  function create(kidsCount: number): ComponentFixture<KidCostSourcesSheetComponent> {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [KidCostSourcesSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    const fixture = TestBed.createComponent(KidCostSourcesSheetComponent);
    fixture.componentInstance.kidsCount = kidsCount;
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<KidCostSourcesSheetComponent>): string {
    return (fixture.nativeElement.textContent as string).replace(/\s+/g, ' ').trim();
  }

  it('lists the cost sources with their years, and the College Board stays off this sheet', () => {
    const fixture = create(3);
    const items = Array.from(fixture.nativeElement.querySelectorAll('.src-list li')) as HTMLElement[];
    const sources = costCardSources();
    expect(sources.length).toBe(3);
    expect(items.length).toBe(sources.length);
    sources.forEach((s, i) => {
      expect(items[i].textContent).toContain(s.name);
      expect(items[i].textContent).toContain(s.year);
    });
    expect(text(fixture)).toContain('Where these numbers come from');
    expect(text(fixture)).toContain('middle-income, two-parent family');
    expect(text(fixture)).not.toContain('College Board');
    expect(text(fixture)).not.toContain('FRED estimate');
  });

  it('labels the four, five and six kid figures as a FRED estimate', () => {
    expect(text(create(4))).toContain('FRED estimate');
    expect(text(create(4))).toContain('four kids');
    expect(text(create(5))).toContain('five kids');
    expect(text(create(6))).toContain('six kids');
    expect(text(create(2))).not.toContain('FRED estimate');
  });

  it('Done dismisses the sheet', () => {
    const fixture = create(1);
    (fixture.nativeElement.querySelector('.src-done') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  it('has no em dash in its copy', () => {
    const fixture = create(5);
    expect(text(fixture)).not.toContain(String.fromCharCode(0x2014));
  });
});
