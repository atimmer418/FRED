import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import {
  FRED_TEXT_PROMPT_ROLE,
  FredTextPromptOptions,
  FredTextPromptSheetComponent,
  presentTextPrompt,
} from './fred-text-prompt-sheet.component';

describe('FredTextPromptSheetComponent', () => {
  let fixture: ComponentFixture<FredTextPromptSheetComponent>;
  let modal: jasmine.SpyObj<ModalController>;

  function create(opts: Partial<FredTextPromptOptions> = {}): void {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [FredTextPromptSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    fixture = TestBed.createComponent(FredTextPromptSheetComponent);
    Object.assign(fixture.componentInstance, { title: 'Rename goal', label: 'Name', value: 'House', maxlength: 50, ...opts });
    fixture.detectChanges();
  }

  const input = () => fixture.nativeElement.querySelector('input.field-input') as HTMLInputElement;
  const saveBtn = () => fixture.nativeElement.querySelector('.tp-save') as HTMLButtonElement;

  function type(text: string): void {
    input().value = text;
    input().dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  it('renders the title, label, prefilled value, maxlength and the default Save label', () => {
    create();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('h3')!.textContent).toBe('Rename goal');
    const label = el.querySelector('label.field-label') as HTMLLabelElement;
    expect(label.textContent).toBe('Name');
    expect(label.htmlFor).toBe(input().id);
    expect(input().value).toBe('House');
    expect(input().getAttribute('maxlength')).toBe('50');
    expect(saveBtn().textContent).toBe('Save');
  });

  it('takes a custom save label and drops maxlength when none is given', () => {
    create({ saveLabel: 'Rename', maxlength: undefined });
    expect(saveBtn().textContent).toBe('Rename');
    expect(input().hasAttribute('maxlength')).toBeFalse();
  });

  it('treats an explicit undefined value and save label as the defaults', () => {
    // Ionic's attachComponent copies componentProps with Object.assign, so `value: undefined` reaches the input.
    create({ value: undefined, saveLabel: undefined });
    expect(input().value).toBe('');
    expect(saveBtn().textContent).toBe('Save');
    expect(saveBtn().disabled).toBeTrue();
    type('Cabin');
    expect(saveBtn().disabled).toBeFalse();
  });

  it('disables Save while the value is unchanged, empty or only spaces', () => {
    create();
    expect(saveBtn().disabled).toBeTrue();
    type('  House ');
    expect(saveBtn().disabled).toBeTrue();
    type('');
    expect(saveBtn().disabled).toBeTrue();
    type('   ');
    expect(saveBtn().disabled).toBeTrue();
    type('Beach house');
    expect(saveBtn().disabled).toBeFalse();
  });

  it('dismisses with role save and the trimmed value on Save', () => {
    create();
    type('  Beach house ');
    saveBtn().click();
    expect(modal.dismiss).toHaveBeenCalledWith({ value: 'Beach house' }, FRED_TEXT_PROMPT_ROLE);
  });

  it('saves on Enter (form submit) only when Save is allowed', () => {
    create();
    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(modal.dismiss).not.toHaveBeenCalled();

    type('Cabin');
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(modal.dismiss).toHaveBeenCalledWith({ value: 'Cabin' }, FRED_TEXT_PROMPT_ROLE);
  });

  it('dismisses with no role on Cancel', () => {
    create();
    (fixture.nativeElement.querySelector('.gs-cancel') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  it('focuses the field when the sheet lands', () => {
    create();
    // Karma's context has no layout, so focus() cannot land: assert the call, not activeElement.
    const focus = spyOn(input(), 'focus');
    fixture.componentInstance.ionViewDidEnter();
    expect(focus).toHaveBeenCalled();
  });
});

describe('presentTextPrompt', () => {
  function fakeModalCtrl(role: string | undefined, data?: unknown) {
    const modalEl = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role, data }),
    };
    const ctrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    ctrl.create.and.resolveTo(modalEl as unknown as HTMLIonModalElement);
    return { ctrl, modalEl };
  }

  const opts: FredTextPromptOptions = { title: 'Rename goal', label: 'Name', value: 'House', maxlength: 50 };

  it('presents the sheet with the options as componentProps and resolves the saved value', async () => {
    const { ctrl, modalEl } = fakeModalCtrl('save', { value: 'Beach house' });
    expect(await presentTextPrompt(ctrl, opts)).toBe('Beach house');
    const createArgs = ctrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredTextPromptSheetComponent);
    expect(createArgs.componentProps).toBe(opts);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(modalEl.present).toHaveBeenCalled();
  });

  it('resolves null on any other dismissal', async () => {
    expect(await presentTextPrompt(fakeModalCtrl(undefined).ctrl, opts)).toBeNull();
    expect(await presentTextPrompt(fakeModalCtrl('gesture').ctrl, opts)).toBeNull();
    expect(await presentTextPrompt(fakeModalCtrl('backdrop').ctrl, opts)).toBeNull();
  });
});
