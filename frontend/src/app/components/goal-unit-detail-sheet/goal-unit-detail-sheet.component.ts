import { Component, Input, Signal, effect, inject } from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { KidCostSourcesSheetComponent } from '../kid-cost-sources-sheet/kid-cost-sources-sheet.component';
import { GoalPlanInfoSheetComponent } from '../goal-plan-info-sheet/goal-plan-info-sheet.component';
import { InfoNote } from '../goal-plan-info-sheet/info-note';
import { UnitPill, fmtMoney } from '../../pages/goal/goal-plan.view-model';

export type UnitTitle = 'Baby fund' | 'College' | 'Nest egg' | 'Everyday';

/** Everything the sheet shows for one kid unit, built by the plan page. */
export interface UnitDetail {
  kidLabel: string;
  title: UnitTitle;
  /** A baby fund, a 529 or nest egg account, or the kid's everyday cost line. */
  kind: 'fund' | 'account' | 'everyday';
  /** "$47,800 by 2042", "$20,800 by Jun 2028". */
  targetLine: string;
  pill: UnitPill;
  monthly: number;
  /** Null when the user has no FRED schedule. */
  perPaycheck: number | null;
  finished: boolean;
  /** The unit's "Saved so far" in dollars. Null for the everyday line, which holds no money. */
  balance: number | null;
  /** The unit's Status word and its tone ("On plan", good). Null for the everyday line. */
  status: { text: string; tone: 'good' | 'warn' | 'info' | 'muted' } | null;
  /** Empty for a baby fund; At 18 / At 22 / At 30 for a 529 or nest egg. Years only for a dated, born kid. */
  milestones: Array<{ label: string; year: number | null; value: string }>;
  /** The cash note, the undecided-kid note, the monthly-only reason: whichever apply. */
  notes: string[];
  sourcesLine: string;
  /** The family size to open the kid cost sources sheet for (baby fund figures), else null. */
  sourcesSheetKids: number | null;
}

/**
 * One kid unit, in detail: the 22 and 30 milestones, the cash explanation, the per-paycheck
 * figure, the Saved so far balance, the Status word and the sources, opened by tapping anywhere
 * on a unit row on the plan page. The detail is live (a signal), so a saved balance re-renders in
 * place; the sheet closes itself when the unit goes away. Presented with BOTTOM_SHEET_MODAL_OPTS.
 */
@Component({
  selector: 'app-goal-unit-detail-sheet',
  standalone: true,
  templateUrl: './goal-unit-detail-sheet.component.html',
  styleUrls: ['./goal-unit-detail-sheet.component.scss'],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class GoalUnitDetailSheetComponent {
  /** The unit, live. Null once the unit no longer exists, which closes the sheet. */
  @Input({ required: true }) detail!: Signal<UnitDetail | null>;
  /** Saves a new Saved so far balance. Null leaves the balance read-only. */
  @Input() onBalance: ((value: number) => Promise<void>) | null = null;
  /** The "How FRED plans this" notes behind the info button. Empty hides the button. */
  @Input() notes: InfoNote[] = [];

  private readonly modalCtrl = inject(ModalController);

  readonly fmtMoney = fmtMoney;

  constructor() {
    effect(() => {
      if (this.detail && this.detail() === null) this.modalCtrl.dismiss();
    });
  }

  /** The balance as whole dollars for the field: "4200". */
  wholeDollars(value: number): string {
    return String(Math.round(value));
  }

  /** Whole dollars (cents after a "." dropped), digits only (empty is 0), saved as a non-negative amount. */
  saveBalance(field: HTMLInputElement): void {
    if (!this.onBalance) return;
    const digits = field.value.split('.')[0].replace(/[^0-9]/g, '');
    const value = Math.max(0, Math.round(Number(digits || '0')));
    field.value = String(value);
    // Re-sync to the live balance once the save settles: a failed save (the page toasts and the
    // balance stays) must not leave the rejected number in the field.
    void this.onBalance(value).then(() => {
      const d = this.detail();
      if (d && d.balance !== null) field.value = this.wholeDollars(d.balance);
    });
  }

  async openInfo(): Promise<void> {
    const modal = await this.modalCtrl.create({
      component: GoalPlanInfoSheetComponent,
      componentProps: { notes: this.notes },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  async openSources(): Promise<void> {
    const kidsCount = this.detail()?.sourcesSheetKids ?? null;
    if (kidsCount === null) return;
    const modal = await this.modalCtrl.create({
      component: KidCostSourcesSheetComponent,
      componentProps: { kidsCount },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
  }

  close(): void {
    this.modalCtrl.dismiss();
  }
}
