import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * A row with an icon, a title, a helper line and a switch. The whole row is
 * the switch, so the tap target is the full width. `tone` picks the glass row
 * on the blue quiz (dark) or a white card row on the plan page (light).
 */
@Component({
  selector: 'app-quiz-switch-row',
  standalone: true,
  template: `
    <button type="button"
            class="bf-row"
            role="switch"
            [class.off]="!on"
            [class.light]="tone === 'light'"
            [attr.aria-checked]="on"
            (click)="onChange.emit(!on)">
      <span class="bf-row-ic"><span class="material-symbols-outlined">{{ icon }}</span></span>
      <span class="bf-row-tx">
        <span class="bf-row-title">{{ title }}</span>
        <span class="bf-row-line" [class.saves]="lineTone === 'saves'">{{ line }}</span>
      </span>
      <span class="bf-switch" [class.on]="on" aria-hidden="true"></span>
    </button>
  `,
  styles: [`
    @use '../../../theme/blue-flow' with ($controls-only: true);
    :host { display: block; }
  `],
})
export class QuizSwitchRowComponent {
  @Input() icon = '';
  @Input() title = '';
  @Input() line = '';
  @Input() on = false;
  @Input() lineTone: 'normal' | 'saves' = 'normal';
  @Input() tone: 'dark' | 'light' = 'dark';

  // The plan names this output onChange; the parents bind (onChange).
  // eslint-disable-next-line @angular-eslint/no-output-on-prefix
  @Output() onChange = new EventEmitter<boolean>();
}
