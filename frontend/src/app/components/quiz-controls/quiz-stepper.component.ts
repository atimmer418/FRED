import { Component, EventEmitter, Input, Output } from '@angular/core';

/** Minus, a big number, plus. Each button dims at its bound. */
@Component({
  selector: 'app-quiz-stepper',
  standalone: true,
  template: `
    <div class="bf-stepper">
      <button type="button"
              aria-label="Fewer"
              [disabled]="value <= min"
              (click)="valueChange.emit(value - 1)">−</button>
      <div class="bf-stepper-val" aria-live="polite">{{ format ? format(value) : value }}</div>
      <button type="button"
              aria-label="More"
              [disabled]="value >= max"
              (click)="valueChange.emit(value + 1)">+</button>
    </div>
  `,
  styles: [`
    @use '../../../theme/blue-flow' with ($controls-only: true);
    :host { display: block; }
  `],
})
export class QuizStepperComponent {
  @Input() value = 0;
  @Input() min = 0;
  @Input() max = 10;
  @Input() format?: (v: number) => string;

  @Output() valueChange = new EventEmitter<number>();
}
