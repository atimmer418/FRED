import { Component, Input, inject } from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { InfoNote } from './info-note';

/**
 * "How FRED plans this": the three explanatory notes that used to sit on the family plan page,
 * shown behind the info button in each unit's detail sheet. Present with BOTTOM_SHEET_MODAL_OPTS.
 */
@Component({
  selector: 'app-goal-plan-info-sheet',
  standalone: true,
  templateUrl: './goal-plan-info-sheet.component.html',
  styleUrls: ['./goal-plan-info-sheet.component.scss'],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class GoalPlanInfoSheetComponent {
  @Input({ required: true }) notes!: InfoNote[];

  private readonly modalCtrl = inject(ModalController);

  close(): void {
    this.modalCtrl.dismiss();
  }
}
