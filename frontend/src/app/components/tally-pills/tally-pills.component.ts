import { Component, input, output } from '@angular/core';
import { fmtMoney } from '../../utils/goal-plan.utils';
import { tickHaptic } from '../quiz-controls/haptics';

/**
 * The two Saved so far corrections under a unit's balance: Missed one takes the share off, Doubled
 * up adds it. `share` is the server's next credit for the unit (never worked out here); at 0 the
 * pills hide. Presentational only: the host posts the entry and shows the toast.
 */
@Component({
  selector: 'app-tally-pills',
  standalone: true,
  templateUrl: './tally-pills.component.html',
  styleUrls: ['./tally-pills.component.scss'],
  host: { '[class.tp-hidden]': 'share() <= 0' },
})
export class TallyPillsComponent {
  readonly share = input.required<number>();
  readonly disabled = input(false);
  readonly missed = output<void>();
  readonly doubled = output<void>();

  readonly fmtMoney = fmtMoney;

  onMissed(): void {
    void tickHaptic();
    this.missed.emit();
  }

  onDoubled(): void {
    void tickHaptic();
    this.doubled.emit();
  }
}
