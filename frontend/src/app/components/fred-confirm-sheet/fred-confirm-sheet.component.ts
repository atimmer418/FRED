import { Component, ElementRef, Input, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { IonSpinner, ModalController } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';

/** destructive = the red bordered button; primary = the black CTA. */
export type FredConfirmTone = 'destructive' | 'primary';

export interface FredConfirmSheetOptions {
  title: string;
  body: string;
  confirmLabel: string;
  /** Default "Not now". A destructive sheet says what keeping means, e.g. "Keep investing". */
  cancelLabel?: string;
  tone: FredConfirmTone;
  /** A material symbol name for the chip above the title. Passed from TS, so it must be in subset-icons.mjs DYNAMIC_ICONS. */
  icon?: string;
  /** The button's text while onConfirm runs, e.g. "Closing...". Defaults to confirmLabel. */
  busyLabel?: string;
  /**
   * Runs inside the sheet when the user confirms, with busy on (spinner, both buttons disabled,
   * drag and backdrop off). The sheet dismisses with role 'confirm' once it resolves. When it
   * rejects the sheet stays open with busy off so the user can retry or cancel, and the error goes
   * to onConfirmError (console.error when none is given), never to the caller's await.
   */
  onConfirm?: () => Promise<void>;
  onConfirmError?: (err: unknown) => void;
}

/** The dismiss role when the user confirmed. */
export const FRED_CONFIRM_SHEET_ROLE = 'confirm';

/** The events the sheet drag gesture starts from; swallowed at the host while busy. */
const GESTURE_EVENTS = ['touchstart', 'touchmove', 'pointerdown', 'pointermove'] as const;

/**
 * The FRED confirm sheet: title, body, one confirm button (red or black) and a cancel link.
 * Replaces every Ionic alert and browser confirm. Present with presentConfirmSheet() below, or
 * with BOTTOM_SHEET_MODAL_OPTS and these inputs as componentProps.
 * Confirm: dismiss(undefined, 'confirm'). Cancel, a drag down or a tap outside: no role.
 */
@Component({
  selector: 'app-fred-confirm-sheet',
  templateUrl: './fred-confirm-sheet.component.html',
  styleUrls: ['./fred-confirm-sheet.component.scss'],
  standalone: true,
  imports: [IonSpinner],
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class FredConfirmSheetComponent implements OnInit, OnDestroy {
  @Input({ required: true }) title = '';
  @Input({ required: true }) body = '';
  @Input({ required: true }) confirmLabel = '';
  @Input() cancelLabel = 'Not now';
  @Input() tone: FredConfirmTone = 'primary';
  @Input() icon?: string;
  @Input() busyLabel?: string;
  @Input() onConfirm?: () => Promise<void>;
  @Input() onConfirmError?: (err: unknown) => void;

  /** True while onConfirm runs. */
  readonly busy = signal(false);

  private readonly modalCtrl = inject(ModalController);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private modalEl: HTMLIonModalElement | null = null;

  /**
   * The drag gesture lives on the modal wrapper above this host, so stopping these at the host
   * suspends it (the same trick KeyboardAvoidDirective uses while the keyboard is up). A refused
   * dismiss would have left the sheet animated off-screen but still open.
   */
  private readonly blockGesture = (e: Event): void => {
    if (this.busy()) e.stopPropagation();
  };

  ngOnInit(): void {
    // Ionic copies componentProps with Object.assign, so an explicit undefined lands over the default.
    this.cancelLabel ??= 'Not now';
    this.modalEl = this.host.nativeElement.closest('ion-modal');
    for (const type of GESTURE_EVENTS) {
      this.host.nativeElement.addEventListener(type, this.blockGesture, { passive: true });
    }
  }

  ngOnDestroy(): void {
    for (const type of GESTURE_EVENTS) {
      this.host.nativeElement.removeEventListener(type, this.blockGesture);
    }
  }

  get buttonLabel(): string {
    return this.busy() ? (this.busyLabel ?? this.confirmLabel) : this.confirmLabel;
  }

  async confirm(): Promise<void> {
    if (this.busy()) return;
    if (!this.onConfirm) {
      await this.modalCtrl.dismiss(undefined, FRED_CONFIRM_SHEET_ROLE);
      return;
    }
    this.setBusy(true);
    try {
      await this.onConfirm();
    } catch (err) {
      this.setBusy(false);
      if (this.onConfirmError) this.onConfirmError(err);
      else console.error('FredConfirmSheet: onConfirm failed', err);
      return;
    }
    await this.modalCtrl.dismiss(undefined, FRED_CONFIRM_SHEET_ROLE);
  }

  close(): void {
    if (this.busy()) return;
    void this.modalCtrl.dismiss();
  }

  /** Busy also turns the backdrop tap off; the gesture is handled by blockGesture. */
  private setBusy(on: boolean): void {
    this.busy.set(on);
    if (this.modalEl) this.modalEl.backdropDismiss = !on;
  }
}

/**
 * Opens the sheet and resolves true when the user confirmed (and onConfirm, if given, resolved),
 * false when it was dismissed any other way. One call per decision:
 *
 *   const ok = await presentConfirmSheet(this.modalCtrl, {
 *     tone: 'destructive', icon: 'pause_circle', title: 'Pause investments?', body: '...',
 *     confirmLabel: 'Pause anyway', cancelLabel: 'Keep investing',
 *   });
 *   if (!ok) return;
 */
export async function presentConfirmSheet(
  modalCtrl: ModalController,
  opts: FredConfirmSheetOptions,
): Promise<boolean> {
  const modal = await modalCtrl.create({
    component: FredConfirmSheetComponent,
    componentProps: opts,
    ...BOTTOM_SHEET_MODAL_OPTS,
  });
  await modal.present();
  const { role } = await modal.onDidDismiss();
  return role === FRED_CONFIRM_SHEET_ROLE;
}
