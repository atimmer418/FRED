import { Component, ElementRef, Input, OnInit, inject, signal } from '@angular/core';
import { ModalController, ViewDidEnter } from '@ionic/angular/standalone';

import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { SheetDragDismissDirective } from '../../directives/sheet-drag-dismiss.directive';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';

export interface FredTextPromptOptions {
  title: string;
  /** The field's label, e.g. "Name". */
  label: string;
  /** The current value, prefilled. Save stays disabled until it changes. */
  value?: string;
  maxlength?: number;
  /** Default "Save". */
  saveLabel?: string;
}

/** The dismiss role when the user saved; the data is `{ value }` (trimmed). */
export const FRED_TEXT_PROMPT_ROLE = 'save';

let nextFieldId = 0;

/**
 * The FRED text prompt sheet: a title, one underlined text field and Save. Replaces every Ionic
 * alert with a text input. The keyboard IS wanted here, so KeyboardAvoidDirective rides as a
 * hostDirective: it lifts the sheet above the keyboard and suspends the drag gesture while the
 * keyboard is up (its sheet mode, see the directive). Present with presentTextPrompt() below, or
 * with BOTTOM_SHEET_MODAL_OPTS and these inputs as componentProps.
 * Save or Enter: dismiss({ value }, 'save'). Cancel, a drag down or a tap outside: no role.
 */
@Component({
  selector: 'app-fred-text-prompt-sheet',
  templateUrl: './fred-text-prompt-sheet.component.html',
  styleUrls: ['./fred-text-prompt-sheet.component.scss'],
  standalone: true,
  host: { class: 'ion-content-scroll-host' },
  hostDirectives: [SheetDragDismissDirective, KeyboardAvoidDirective],
})
export class FredTextPromptSheetComponent implements OnInit, ViewDidEnter {
  @Input({ required: true }) title = '';
  @Input({ required: true }) label = '';
  @Input() value = '';
  @Input() maxlength?: number;
  @Input() saveLabel = 'Save';

  /** What the field holds right now. */
  readonly draft = signal('');

  /** Unique per sheet so the label points at this sheet's field even when sheets stack. */
  readonly fieldId = `fred-text-prompt-${nextFieldId++}`;

  private readonly modalCtrl = inject(ModalController);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  ngOnInit(): void {
    // Ionic copies componentProps with Object.assign, so an explicit undefined lands over the defaults.
    this.value ??= '';
    this.saveLabel ??= 'Save';
    this.draft.set(this.value);
  }

  /** Focus once the sheet has landed, so the keyboard lift starts from the sheet's open position. */
  ionViewDidEnter(): void {
    this.host.nativeElement.querySelector<HTMLInputElement>('input')?.focus();
  }

  onInput(event: Event): void {
    this.draft.set((event.target as HTMLInputElement).value);
  }

  /** Save is off while the field is empty or still says what it said on open. */
  canSave(): boolean {
    const next = this.draft().trim();
    return next.length > 0 && next !== this.value.trim();
  }

  /** The form's submit: the Save button, or Enter on the field. */
  save(event: Event): void {
    event.preventDefault();
    if (!this.canSave()) return;
    void this.modalCtrl.dismiss({ value: this.draft().trim() }, FRED_TEXT_PROMPT_ROLE);
  }

  close(): void {
    void this.modalCtrl.dismiss();
  }
}

/**
 * Opens the sheet and resolves with the trimmed new value on Save, or null when it was dismissed
 * any other way. One call per prompt:
 *
 *   const name = await presentTextPrompt(this.modalCtrl, { title: 'Rename goal', label: 'Name', value: goal.name, maxlength: 50 });
 *   if (name === null) return;
 */
export async function presentTextPrompt(
  modalCtrl: ModalController,
  opts: FredTextPromptOptions,
): Promise<string | null> {
  const modal = await modalCtrl.create({
    component: FredTextPromptSheetComponent,
    componentProps: opts,
    ...BOTTOM_SHEET_MODAL_OPTS,
  });
  await modal.present();
  const { role, data } = await modal.onDidDismiss<{ value?: string }>();
  return role === FRED_TEXT_PROMPT_ROLE && typeof data?.value === 'string' ? data.value : null;
}
