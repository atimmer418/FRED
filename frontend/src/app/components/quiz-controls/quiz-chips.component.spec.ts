import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QuizChipsComponent } from './quiz-chips.component';

describe('QuizChipsComponent', () => {
  let fixture: ComponentFixture<QuizChipsComponent<number>>;
  let emitted: number[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [QuizChipsComponent] }).compileComponents();
    fixture = TestBed.createComponent(QuizChipsComponent<number>);
    fixture.componentRef.setInput('options', [
      { label: '1 day', value: 1 },
      { label: '2 days', value: 2 },
      { label: '3 days', value: 3 },
    ]);
    fixture.componentRef.setInput('value', 2);
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v: number) => emitted.push(v));
    fixture.detectChanges();
  });

  it('marks the selected chip and emits the value of a tapped chip', () => {
    const chips = Array.from(fixture.nativeElement.querySelectorAll('button.bf-chip')) as HTMLButtonElement[];
    expect(chips.map(c => c.textContent!.trim())).toEqual(['1 day', '2 days', '3 days']);
    expect(chips.map(c => c.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'false']);
    expect(chips[1].classList).toContain('on');

    chips[2].click();
    expect(emitted).toEqual([3]);
  });
});
