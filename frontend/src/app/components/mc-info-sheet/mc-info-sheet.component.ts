import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';

/**
 * Bottom-sheet content for the Monte Carlo landing, presented via
 * ModalController (cssClass 'mc-bottom-sheet-modal') so it renders at the
 * app root — above the header and tab bar — instead of being contained by
 * the component's inner ion-content (which clips position:fixed on iOS).
 *
 * mode 'assumptions' — market assumptions & methodology disclosure
 * mode 'nudge'       — upgrade nudge (default: Piggy Pro; override with
 *                      nudgeTitle / nudgeBody / nudgeCtaLabel / targetTier)
 *
 * Configurable inputs (all optional — existing callers get identical output):
 *   nudgeTitle      — overrides the default "Go deeper with Piggy Pro" heading
 *   nudgeBody       — overrides the default body paragraph
 *   nudgeCtaLabel   — overrides the default "Upgrade to Pro" button text
 *   targetTier      — passed back in the 'upgrade' dismiss data so callers
 *                     can route to the right upgrade flow; default 'pro'
 *
 * Dismisses with role 'upgrade' and data { targetTier } when the nudge CTA
 * is tapped; the presenter decides what upgrading means.
 */
@Component({
  selector: 'app-mc-info-sheet',
  templateUrl: './mc-info-sheet.component.html',
  styleUrls: ['./mc-info-sheet.component.scss'],
  standalone: true,
  imports: [CommonModule],
  // This root is the sheet's scroller (global.scss .ion-page override).
  // SheetDragDismissDirective provides drag-to-dismiss and defers to
  // scrolling (drag arms only at scrollTop 0).
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class McInfoSheetComponent {
  @Input() mode: 'assumptions' | 'nudge' = 'assumptions';

  // Optional overrides for nudge mode — default values match the existing tab2 copy.
  @Input() nudgeTitle: string = 'Go deeper with Piggy Pro';
  @Input() nudgeBody: string = 'Run conservative and aggressive scenarios, adjust contributions and retirement age on the fly, and see monthly withdrawal estimates in retirement.';
  @Input() nudgeCtaLabel: string = 'Upgrade to Pro';
  @Input() targetTier: string = 'pro';

  constructor(private modalController: ModalController) {}

  close(): void {
    this.modalController.dismiss();
  }

  upgrade(): void {
    this.modalController.dismiss({ targetTier: this.targetTier }, 'upgrade');
  }
}
