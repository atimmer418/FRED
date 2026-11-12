import { Component, ElementRef, Input, OnInit, inject } from '@angular/core';
import { ModalController, ViewDidEnter } from '@ionic/angular/standalone';

import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { tickHaptic } from '../quiz-controls/haptics';

/** The chip, title and one-line status at the top of the sheet. */
export interface FredActionSheetHeader {
  /** A material symbol name. Passed from TS, so it must be in subset-icons.mjs DYNAMIC_ICONS. */
  icon: string;
  title: string;
  subtitle?: string;
}

/** One row. The id comes back to the caller when the row is tapped. */
export interface FredActionSheetAction<Id extends string = string> {
  id: Id;
  /** A material symbol name (see FredActionSheetHeader.icon). */
  icon: string;
  label: string;
  helper?: string;
  /** Destructive rows sit apart in their own red-bordered card under the others. */
  destructive?: boolean;
}

export interface FredActionSheetOptions<Id extends string = string> {
  header: FredActionSheetHeader;
  actions: FredActionSheetAction<Id>[];
  /** Default "Cancel". */
  cancelLabel?: string;
}

/** The dismiss role when a row was tapped; the data is `{ id }`. */
export const FRED_ACTION_SHEET_ROLE = 'action';

let nextTitleId = 0;

/**
 * The FRED action sheet: a header naming what the actions belong to, a card of rows, destructive
 * rows in their own card, a Cancel link. Replaces every Ionic ActionSheet. Present with
 * presentActionSheet() below, or with BOTTOM_SHEET_MODAL_OPTS and these inputs as componentProps.
 * A row: dismiss({ id }, 'action'). Cancel, a drag down or a tap outside: no role.
 */
@Component({
  selector: 'app-fred-action-sheet',
  templateUrl: './fred-action-sheet.component.html',
  styleUrls: ['./fred-action-sheet.component.scss'],
  standalone: true,
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective],
})
export class FredActionSheetComponent implements OnInit, ViewDidEnter {
  @Input({ required: true }) header!: FredActionSheetHeader;
  @Input({ required: true }) actions: FredActionSheetAction[] = [];
  @Input() cancelLabel = 'Cancel';

  /** Unique per sheet so two stacked sheets never share an aria-labelledby target. */
  readonly titleId = `fred-action-sheet-title-${nextTitleId++}`;

  private readonly modalCtrl = inject(ModalController);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  ngOnInit(): void {
    // Ionic copies componentProps with Object.assign, so an explicit undefined lands over the default.
    this.cancelLabel ??= 'Cancel';
  }

  get primaryActions(): FredActionSheetAction[] {
    return this.actions.filter(a => !a.destructive);
  }

  get destructiveActions(): FredActionSheetAction[] {
    return this.actions.filter(a => !!a.destructive);
  }

  /** Menu semantics: the first row takes focus once the sheet has landed. */
  ionViewDidEnter(): void {
    const first = this.host.nativeElement.querySelector<HTMLElement>('[role="menuitem"]');
    first?.focus({ preventScroll: true });
  }

  pick(action: FredActionSheetAction): void {
    void tickHaptic();
    void this.modalCtrl.dismiss({ id: action.id }, FRED_ACTION_SHEET_ROLE);
  }

  close(): void {
    void this.modalCtrl.dismiss();
  }
}

/**
 * Opens the sheet and resolves with the tapped action's id, or null when it was dismissed any
 * other way (Cancel, drag down, tap outside). One call per menu:
 *
 *   const picked = await presentActionSheet(this.modalCtrl, { header, actions });
 *   if (picked === 'rename') ...
 */
export async function presentActionSheet<Id extends string = string>(
  modalCtrl: ModalController,
  opts: FredActionSheetOptions<Id>,
): Promise<Id | null> {
  const modal = await modalCtrl.create({
    component: FredActionSheetComponent,
    componentProps: opts,
    ...BOTTOM_SHEET_MODAL_OPTS,
  });
  await modal.present();
  const { role, data } = await modal.onDidDismiss<{ id?: Id }>();
  return role === FRED_ACTION_SHEET_ROLE && data?.id ? data.id : null;
}
