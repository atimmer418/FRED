import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import {
  FRED_CONFIRM_SHEET_ROLE,
  FredConfirmSheetComponent,
  FredConfirmSheetOptions,
  presentConfirmSheet,
} from './fred-confirm-sheet.component';

/** A promise the test settles by hand. */
function deferred(): { promise: Promise<void>; resolve: () => void; reject: (err: unknown) => void } {
  let resolve!: () => void;
  let reject!: (err: unknown) => void;
  const promise = new Promise<void>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const flush = () => new Promise<void>(r => setTimeout(r, 0));

describe('FredConfirmSheetComponent', () => {
  let fixture: ComponentFixture<FredConfirmSheetComponent>;
  let modal: jasmine.SpyObj<ModalController>;

  function create(opts: Partial<FredConfirmSheetOptions> = {}): void {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    modal.dismiss.and.resolveTo(true);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [FredConfirmSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    fixture = TestBed.createComponent(FredConfirmSheetComponent);
    Object.assign(fixture.componentInstance, {
      title: 'Pause investments?',
      body: 'Your schedule stops until you resume it.',
      confirmLabel: 'Pause anyway',
      tone: 'destructive',
      ...opts,
    });
    fixture.detectChanges();
  }

  const confirmBtn = () => fixture.nativeElement.querySelector('.cs-confirm') as HTMLButtonElement;
  const cancelBtn = () => fixture.nativeElement.querySelector('.gs-cancel') as HTMLButtonElement;

  it('does not fail when the action already closed the sheet itself', async () => {
    // Closing the account signs the device out and clears every sheet, this one included.
    create({ onConfirm: () => Promise.resolve() });
    modal.dismiss.and.rejectWith(new Error('overlay does not exist'));

    await expectAsync(fixture.componentInstance.confirm()).toBeResolved();
  });

  it('renders title, body, labels and the destructive button recipe', () => {
    create({ cancelLabel: 'Keep investing', icon: 'pause_circle' });
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('h3')!.textContent).toBe('Pause investments?');
    expect(el.querySelector('.gs-sub')!.textContent).toBe('Your schedule stops until you resume it.');
    expect(confirmBtn().textContent!.trim()).toBe('Pause anyway');
    expect(confirmBtn().classList).toContain('gs-remove');
    expect(confirmBtn().classList).not.toContain('fd-cta');
    expect(cancelBtn().textContent).toBe('Keep investing');
    const chip = el.querySelector('.cs-chip')!;
    expect(chip.classList).toContain('cs-chip--danger');
    expect(chip.textContent!.trim()).toBe('pause_circle');
  });

  it('renders the primary tone as the black CTA, a blue chip, and "Not now" by default', () => {
    create({ tone: 'primary', icon: 'workspace_premium', confirmLabel: 'Confirm upgrade' });
    expect(confirmBtn().classList).toContain('fd-cta');
    expect(confirmBtn().classList).not.toContain('gs-remove');
    expect(fixture.nativeElement.querySelector('.cs-chip').classList).not.toContain('cs-chip--danger');
    expect(cancelBtn().textContent).toBe('Not now');
  });

  it('falls back to "Not now" when cancelLabel is passed as undefined', () => {
    // Ionic's attachComponent copies componentProps with Object.assign, so the undefined reaches the input.
    create({ cancelLabel: undefined });
    expect(cancelBtn().textContent).toBe('Not now');
  });

  it('shows no chip without an icon', () => {
    create();
    expect(fixture.nativeElement.querySelector('.cs-chip')).toBeNull();
  });

  it('dismisses with role confirm on the button and with no role on cancel', async () => {
    create();
    confirmBtn().click();
    await flush();
    expect(modal.dismiss).toHaveBeenCalledWith(undefined, FRED_CONFIRM_SHEET_ROLE);

    modal.dismiss.calls.reset();
    cancelBtn().click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  it('runs onConfirm with busy on, then dismisses with role confirm when it resolves', async () => {
    const work = deferred();
    const onConfirm = jasmine.createSpy('onConfirm').and.returnValue(work.promise);
    create({ onConfirm, busyLabel: 'Pausing...' });

    confirmBtn().click();
    fixture.detectChanges();
    expect(onConfirm).toHaveBeenCalled();
    expect(fixture.componentInstance.busy()).toBeTrue();
    expect(confirmBtn().disabled).toBeTrue();
    expect(cancelBtn().disabled).toBeTrue();
    expect(confirmBtn().getAttribute('aria-busy')).toBe('true');
    expect(confirmBtn().querySelector('ion-spinner')).not.toBeNull();
    expect(confirmBtn().textContent!.trim()).toBe('Pausing...');
    expect(modal.dismiss).not.toHaveBeenCalled();

    // A second tap while busy does nothing.
    confirmBtn().click();
    cancelBtn().click();
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(modal.dismiss).not.toHaveBeenCalled();

    work.resolve();
    await flush();
    expect(modal.dismiss).toHaveBeenCalledWith(undefined, FRED_CONFIRM_SHEET_ROLE);
  });

  it('keeps the sheet open with busy off when onConfirm rejects, and reports the error', async () => {
    const work = deferred();
    const onConfirmError = jasmine.createSpy('onConfirmError');
    create({ onConfirm: () => work.promise, onConfirmError });

    confirmBtn().click();
    expect(fixture.componentInstance.busy()).toBeTrue();

    const err = new Error('nope');
    work.reject(err);
    await flush();
    fixture.detectChanges();
    expect(fixture.componentInstance.busy()).toBeFalse();
    expect(onConfirmError).toHaveBeenCalledWith(err);
    expect(modal.dismiss).not.toHaveBeenCalled();
    expect(confirmBtn().disabled).toBeFalse();
    expect(cancelBtn().disabled).toBeFalse();
    expect(confirmBtn().querySelector('ion-spinner')).toBeNull();
  });

  it('logs the rejection when no onConfirmError is given', async () => {
    spyOn(console, 'error');
    create({ onConfirm: () => Promise.reject(new Error('boom')) });
    confirmBtn().click();
    await flush();
    expect(console.error).toHaveBeenCalled();
    expect(fixture.componentInstance.busy()).toBeFalse();
    expect(modal.dismiss).not.toHaveBeenCalled();
  });

  // Ionic's gesture arms on touchstart and mousedown, so a mouse drag must be held back as well.
  for (const type of ['pointerdown', 'touchstart', 'mousedown', 'mousemove']) {
    it(`swallows ${type} at the host only while busy`, () => {
      const work = deferred();
      create({ onConfirm: () => work.promise });
      const reached = jasmine.createSpy('reached');
      document.body.addEventListener(type, reached);
      try {
        const target = fixture.nativeElement.querySelector('h3') as HTMLElement;
        target.dispatchEvent(new Event(type, { bubbles: true }));
        expect(reached).toHaveBeenCalledTimes(1);

        confirmBtn().click();
        target.dispatchEvent(new Event(type, { bubbles: true }));
        expect(reached).toHaveBeenCalledTimes(1);
      } finally {
        document.body.removeEventListener(type, reached);
      }
    });
  }
});

describe('presentConfirmSheet', () => {
  function fakeModalCtrl(role: string | undefined) {
    const modalEl = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role }),
    };
    const ctrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    ctrl.create.and.resolveTo(modalEl as unknown as HTMLIonModalElement);
    return { ctrl, modalEl };
  }

  const opts: FredConfirmSheetOptions = {
    tone: 'primary', title: 'Upgrade to Plus?', body: 'More for your money.', confirmLabel: 'Confirm upgrade',
  };

  it('presents the sheet with the options as componentProps and resolves true on confirm', async () => {
    const { ctrl, modalEl } = fakeModalCtrl('confirm');
    expect(await presentConfirmSheet(ctrl, opts)).toBeTrue();
    const createArgs = ctrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredConfirmSheetComponent);
    expect(createArgs.componentProps).toBe(opts);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(modalEl.present).toHaveBeenCalled();
  });

  it('resolves false on any other dismissal', async () => {
    expect(await presentConfirmSheet(fakeModalCtrl(undefined).ctrl, opts)).toBeFalse();
    expect(await presentConfirmSheet(fakeModalCtrl('gesture').ctrl, opts)).toBeFalse();
    expect(await presentConfirmSheet(fakeModalCtrl('backdrop').ctrl, opts)).toBeFalse();
  });
});
