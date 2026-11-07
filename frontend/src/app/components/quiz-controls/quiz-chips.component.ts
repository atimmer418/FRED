import { Component, EventEmitter, Input, Output } from '@angular/core';

export interface QuizChipOption<T> {
  label: string;
  value: T;
}

/** A segmented row of chips; one is selected. */
@Component({
  selector: 'app-quiz-chips',
  standalone: true,
  template: `
    <div class="bf-chips" role="group">
      @for (opt of options; track $index) {
        <button type="button"
                class="bf-chip"
                [class.on]="opt.value === value"
                [attr.aria-pressed]="opt.value === value"
                (click)="valueChange.emit(opt.value)">{{ opt.label }}</button>
      }
    </div>
  `,
  styles: [`
    @use '../../../theme/blue-flow' with ($controls-only: true);
    :host { display: block; }
  `],
})
export class QuizChipsComponent<T> {
  @Input() options: QuizChipOption<T>[] = [];
  @Input() value?: T;

  @Output() valueChange = new EventEmitter<T>();
}
