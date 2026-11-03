import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';

/**
 * Remove-goal confirmation bottom sheet. Present with BOTTOM_SHEET_MODAL_OPTS.
 * "Remove goal": dismiss(undefined, 'remove'). "Keep it": dismiss() with no role.
 */
@Component({
  selector: 'app-goal-remove-confirm-sheet',
  templateUrl: './goal-remove-confirm-sheet.component.html',
  styleUrls: ['./goal-remove-confirm-sheet.component.scss'],
  standalone: true,
  imports: [CommonModule],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class GoalRemoveConfirmSheetComponent {
  /** Name of the goal being removed (used for the destructive button's accessible label). */
  @Input() goalName = '';

  private modalController = inject(ModalController);

  remove(): void {
    this.modalController.dismiss(undefined, 'remove');
  }

  close(): void {
    this.modalController.dismiss();
  }
}
