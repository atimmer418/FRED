import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import { GoalPlanInfoSheetComponent } from './goal-plan-info-sheet.component';
import { InfoNote } from './info-note';

describe('GoalPlanInfoSheetComponent', () => {
  let modal: jasmine.SpyObj<ModalController>;

  const notes: InfoNote[] = [
    { icon: 'auto_awesome', lead: 'FRED helps.', text: 'It sizes the plan for you.' },
    { icon: 'event_repeat', lead: 'The monthly covers.', text: 'Everything in this unit.' },
    { icon: 'trending_up', lead: 'Why invest it.', text: 'Time does the heavy lifting.' },
  ];

  function create(): ComponentFixture<GoalPlanInfoSheetComponent> {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [GoalPlanInfoSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    const fixture = TestBed.createComponent(GoalPlanInfoSheetComponent);
    fixture.componentInstance.notes = notes;
    fixture.detectChanges();
    return fixture;
  }

  it('renders the title, sub line and one card per note with its icon, lead and text', () => {
    const fixture = create();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h3')?.textContent).toBe('How FRED plans this');
    expect(el.querySelector('.pi-sub')?.textContent).toBe('The same three notes for every unit of the family plan.');
    const cards = Array.from(el.querySelectorAll('.fd-how')) as HTMLElement[];
    expect(cards.length).toBe(3);
    notes.forEach((n, i) => {
      expect(cards[i].querySelector('.material-symbols-outlined')?.textContent?.trim()).toBe(n.icon);
      expect(cards[i].querySelector('b')?.textContent).toBe(n.lead);
      expect((cards[i].textContent as string).replace(/\s+/g, ' ')).toContain(`${n.lead} ${n.text}`);
    });
  });

  it('Done dismisses the modal', () => {
    const fixture = create();
    const done = fixture.nativeElement.querySelector('.pi-done') as HTMLButtonElement;
    expect(done.textContent?.trim()).toBe('Done');
    done.click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });
});
