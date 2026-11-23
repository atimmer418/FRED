import { Component, EventEmitter, Input, Output } from '@angular/core';

export interface QuizPickOption<T> {
  icon: string;
  label: string;
  amount: number;
  value: T;
}

/** Four tappable glass cards in a 2x2 grid; the selected one turns white. */
@Component({
  selector: 'app-quiz-pick-grid',
  standalone: true,
  template: `
    <div class="bf-pick-grid" role="group">
      @for (opt of options; track $index) {
        <button type="button"
                class="bf-pick"
                [class.on]="opt.value === value"
                [attr.aria-pressed]="opt.value === value"
                (click)="valueChange.emit(opt.value)">
          <span class="material-symbols-outlined" aria-hidden="true">{{ opt.icon }}</span>
          <span class="bf-pick-label">{{ opt.label }}</span>
          <span class="bf-pick-amt">{{ dollars(opt.amount) }}</span>
        </button>
      }
    </div>
  `,
  styles: [`
    @use '../../../theme/blue-flow' with ($controls-only: true);
    :host { display: block; }
  `],
})
export class QuizPickGridComponent<T> {
  @Input() options: QuizPickOption<T>[] = [];
  @Input() value?: T;

  @Output() valueChange = new EventEmitter<T>();

  dollars(amount: number): string {
    return '$' + Math.round(amount).toLocaleString('en-US');
  }
}
