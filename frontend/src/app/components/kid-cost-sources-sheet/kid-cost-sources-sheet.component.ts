import { Component, Input, inject } from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { costCardSources, kidCostFigures } from '../../utils/kid-cost.utils';

const COUNT_WORDS: Record<number, string> = { 4: 'four', 5: 'five', 6: 'six' };

/**
 * "Where these numbers come from": the sources behind the kid cost figures, opened from the info
 * button on the quiz's cost step. Present with BOTTOM_SHEET_MODAL_OPTS. The four, five and six
 * kid figures are FRED's own extension of the USDA table and are labelled "FRED estimate" here, as
 * FREDdocs/kid-cost-figures.md requires wherever they are shown.
 */
@Component({
  selector: 'app-kid-cost-sources-sheet',
  standalone: true,
  templateUrl: './kid-cost-sources-sheet.component.html',
  styleUrls: ['./kid-cost-sources-sheet.component.scss'],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class KidCostSourcesSheetComponent {
  /** The family size the cost screen showed; four, five or six adds the FRED estimate note. */
  @Input() kidsCount = 1;

  private readonly modalCtrl = inject(ModalController);

  readonly sources = costCardSources();

  get asOfLabel(): string {
    return kidCostFigures(this.kidsCount).asOfLabel;
  }

  /** The FRED estimate note for four, five or six kids; empty for one to three. */
  get estimateNote(): string {
    const word = COUNT_WORDS[Math.min(6, this.kidsCount)];
    if (!word) return '';
    return `The USDA table stops at three kids. The figure for ${word} kids extends it with the same per-kid step the third kid adds.`;
  }

  close(): void {
    this.modalCtrl.dismiss();
  }
}
