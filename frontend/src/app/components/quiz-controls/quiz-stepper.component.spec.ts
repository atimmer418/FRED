import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QuizStepperComponent } from './quiz-stepper.component';

describe('QuizStepperComponent', () => {
  let fixture: ComponentFixture<QuizStepperComponent>;
  let emitted: number[];

  function setup(value: number, min = 1, max = 5): void {
    fixture = TestBed.createComponent(QuizStepperComponent);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('min', min);
    fixture.componentRef.setInput('max', max);
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v: number) => emitted.push(v));
    fixture.detectChanges();
  }

  function minus(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button[aria-label="Fewer"]');
  }

  function plus(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button[aria-label="More"]');
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [QuizStepperComponent] }).compileComponents();
  });

  it('disables plus at max and minus at min', () => {
    setup(5);
    expect(plus().disabled).toBeTrue();
    expect(minus().disabled).toBeFalse();

    fixture.componentRef.setInput('value', 1);
    fixture.detectChanges();
    expect(minus().disabled).toBeTrue();
    expect(plus().disabled).toBeFalse();
  });

  it('emits one step up or down and shows the formatted value', () => {
    setup(3);
    fixture.componentRef.setInput('format', (v: number) => `${v} kids`);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.bf-stepper-val').textContent.trim()).toBe('3 kids');

    plus().click();
    minus().click();

    expect(emitted).toEqual([4, 2]);
  });
});
