import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QuizPickGridComponent, QuizPickOption } from './quiz-pick-grid.component';

describe('QuizPickGridComponent', () => {
  let fixture: ComponentFixture<QuizPickGridComponent<string>>;
  let emitted: string[];

  const OPTIONS: QuizPickOption<string>[] = [
    { icon: 'school', label: 'In-state public', amount: 47_800, value: 'in' },
    { icon: 'travel_explore', label: 'Out-of-state', amount: 123_960, value: 'out' },
    { icon: 'account_balance', label: 'Private', amount: 180_000, value: 'private' },
    { icon: 'do_not_disturb_on', label: 'Skip for now', amount: 0, value: 'skip' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [QuizPickGridComponent] }).compileComponents();
    fixture = TestBed.createComponent(QuizPickGridComponent<string>);
    fixture.componentRef.setInput('options', OPTIONS);
    fixture.componentRef.setInput('value', 'in');
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v: string) => emitted.push(v));
    fixture.detectChanges();
  });

  it('marks the selected option aria-pressed and emits the value of a tapped option', () => {
    const picks = Array.from(fixture.nativeElement.querySelectorAll('button.bf-pick')) as HTMLButtonElement[];
    expect(picks.length).toBe(4);
    expect(picks.map(p => p.getAttribute('aria-pressed'))).toEqual(['true', 'false', 'false', 'false']);
    expect(picks[0].classList).toContain('on');
    expect(picks[1].textContent).toContain('Out-of-state');
    expect(picks[1].textContent).toContain('$123,960');
    expect(picks[3].textContent).toContain('$0');

    picks[2].click();
    expect(emitted).toEqual(['private']);

    fixture.componentRef.setInput('value', 'private');
    fixture.detectChanges();
    expect(picks.map(p => p.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'true', 'false']);
  });
});
