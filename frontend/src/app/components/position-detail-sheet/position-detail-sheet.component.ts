import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { Position } from '../../services/portfolio.service';

/**
 * Root-level bottom sheet showing position detail.
 * Presented via ModalController with cssClass 'mc-bottom-sheet-modal' so it
 * renders above the header and tab bar instead of being clipped by inner
 * ion-content on iOS.
 */
@Component({
  selector: 'app-position-detail-sheet',
  templateUrl: './position-detail-sheet.component.html',
  styleUrls: ['./position-detail-sheet.component.scss'],
  standalone: true,
  imports: [CommonModule],
  // This root is the sheet's scroller (global.scss .ion-page override).
  // SheetDragDismissDirective provides drag-to-dismiss and defers to
  // scrolling (drag arms only at scrollTop 0).
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class PositionDetailSheetComponent {
  @Input() position!: Position;

  constructor(private modalController: ModalController) {}

  close(): void {
    this.modalController.dismiss();
  }

  formatCurrency(value: number): string {
    const rounded = Math.round(value * 100) / 100;
    const display = rounded === 0 ? 0 : value;
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(display);
  }

  formatPercent(value: number): string {
    const sign = value >= 0 ? '+' : '';
    return `${sign}${value.toFixed(2)}%`;
  }

  formatQuantity(value: number): string {
    if (value == null) return '0';
    return new Intl.NumberFormat('en-US', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 3
    }).format(value);
  }
}
