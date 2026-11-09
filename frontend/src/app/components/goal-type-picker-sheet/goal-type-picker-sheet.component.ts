import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { GoalIcon, GoalType } from '../../models/goal.model';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';

export interface GoalTypeTile {
  type: GoalType;
  icon: GoalIcon;
  title: string;
  line: string;
}

export const GOAL_TYPE_TILES: ReadonlyArray<GoalTypeTile> = [
  { type: 'KIDS', icon: 'child_care', title: 'Kids', line: 'A monthly amount per kid for college, a nest egg and the first year.' },
  { type: 'HOUSE', icon: 'home', title: 'House', line: 'The down payment, planned to the month.' },
  { type: 'EMERGENCY_FUND', icon: 'health_and_safety', title: 'Emergency fund', line: 'Three or six months of expenses, kept in cash.' },
  { type: 'CAR', icon: 'directions_car', title: 'Car', line: 'Down payment or the full price.' },
  { type: 'VACATION', icon: 'flight', title: 'Vacation', line: 'Trips a year and a budget each. FRED works out the monthly amount.' },
  { type: 'WEDDING', icon: 'favorite', title: 'Wedding', line: 'One date, one number, one plan.' },
  { type: 'CUSTOM', icon: 'flag', title: 'Something else', line: 'Any amount by any year.' },
];

/**
 * Goal type picker bottom sheet. Present with BOTTOM_SHEET_MODAL_OPTS.
 * Tap a tile: dismiss(type, 'picked'). Cancel: dismiss() with no role.
 */
@Component({
  selector: 'app-goal-type-picker-sheet',
  templateUrl: './goal-type-picker-sheet.component.html',
  styleUrls: ['./goal-type-picker-sheet.component.scss'],
  standalone: true,
  imports: [CommonModule],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class GoalTypePickerSheetComponent {
  private modalController = inject(ModalController);

  readonly tiles = GOAL_TYPE_TILES;

  pick(type: GoalType): void {
    this.modalController.dismiss(type, 'picked');
  }

  close(): void {
    this.modalController.dismiss();
  }
}
