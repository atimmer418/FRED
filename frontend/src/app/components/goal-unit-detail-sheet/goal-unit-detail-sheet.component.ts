import { Component, Input, inject } from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { KidCostSourcesSheetComponent } from '../kid-cost-sources-sheet/kid-cost-sources-sheet.component';
import { UnitPill, fmtMoney } from '../../pages/goal/goal-plan.view-model';

export type UnitTitle = 'Baby fund' | 'College' | 'Nest egg';

/** Everything the sheet shows for one kid unit, built by the plan page. */
export interface UnitDetail {
  kidLabel: string;
  title: UnitTitle;
  /** "$47,800 by 2042", "$20,800 by Jun 2028". */
  targetLine: string;
  pill: UnitPill;
  monthly: number;
  /** Null when the user has no FRED schedule. */
  perPaycheck: number | null;
  finished: boolean;
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
 * figure and the sources, opened by tapping a unit row's label on the plan page. Presented with
 * BOTTOM_SHEET_MODAL_OPTS.
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
  @Input({ required: true }) detail!: UnitDetail;

  private readonly modalCtrl = inject(ModalController);

  readonly fmtMoney = fmtMoney;

  async openSources(): Promise<void> {
    const kidsCount = this.detail.sourcesSheetKids;
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
