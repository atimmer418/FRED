import { NgZone } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ModalController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { PasskeyPromptComponent } from './passkey-prompt.component';
import { PasskeyService } from '../../services/passkey.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { NativePasskeyService } from '../../services/native-passkey.service';
import { AccountClosedService } from '../../services/account-closed.service';

/**
 * The lock screen used to be a dead end for a closed account: the passkey on the phone still
 * passes Face ID, the server refuses it, and the screen showed Unlock again, for ever.
 */
describe('PasskeyPromptComponent: a passkey the server no longer has', () => {
  let passkeys: jasmine.SpyObj<PasskeyService>;
  let accountClosed: jasmine.SpyObj<AccountClosedService>;
  let prompt: PasskeyPromptComponent;
  let revealed: jasmine.Spy;

  const settle = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); } };

  function build(lockScreen: boolean, finishError: unknown) {
    passkeys = jasmine.createSpyObj<PasskeyService>('PasskeyService',
      ['startAuthentication', 'startAuthenticationForUser', 'finishAuthentication']);
    const start = of({ sessionId: 's1', requestOptions: JSON.stringify({ publicKey: { challenge: 'abc', rpId: 'fred.test' } }) });
    passkeys.startAuthentication.and.returnValue(start);
    passkeys.startAuthenticationForUser.and.returnValue(start);
    passkeys.finishAuthentication.and.returnValue(throwError(() => finishError));
    accountClosed = jasmine.createSpyObj<AccountClosedService>('AccountClosedService', ['leave']);
    accountClosed.leave.and.resolveTo();
    const native = { isAvailable: true, authenticate: () => Promise.resolve({ id: 'cred' }) } as unknown as NativePasskeyService;

    prompt = new PasskeyPromptComponent(
      jasmine.createSpyObj<ModalController>('ModalController', ['dismiss']),
      passkeys,
      jasmine.createSpyObj<AuthService>('AuthService', ['handleSuccessfulAuthentication'], { userProgress$: of(null) }),
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      native,
      { run: (fn: () => unknown) => fn(), runOutsideAngular: (fn: () => unknown) => fn() } as unknown as NgZone,
      accountClosed,
    );
    prompt.lockScreen = lockScreen;
    prompt.userEmail = 'gone@fredvested.test';
    revealed = spyOn<any>(prompt, 'revealUnlockButton');
    spyOn(console, 'error');
  }

  const notRegistered = () => new HttpErrorResponse({
    status: 401, error: { success: false, code: 'PASSKEY_NOT_REGISTERED' },
  });

  it('on the lock screen, the first time it offers Unlock again (the phone may hold another passkey)', async () => {
    build(true, notRegistered());

    await prompt.authenticate();
    await settle();

    expect(accountClosed.leave).not.toHaveBeenCalled();
    expect(revealed).toHaveBeenCalledTimes(1);
  });

  it('on the lock screen, the second time running it signs the device out instead of trapping the user', async () => {
    build(true, notRegistered());

    await prompt.authenticate();
    await settle();
    prompt.isUnlocking = false; // what revealUnlockButton (stubbed here) does
    await prompt.authenticate();
    await settle();

    expect(accountClosed.leave).toHaveBeenCalledOnceWith('passkey');
    expect(revealed).toHaveBeenCalledTimes(1);
  });

  it('any other refusal on the lock screen still offers Unlock, as before', async () => {
    build(true, new HttpErrorResponse({ status: 401, error: { success: false, message: 'Authentication failed' } }));

    await prompt.authenticate();
    await settle();

    expect(accountClosed.leave).not.toHaveBeenCalled();
    expect(revealed).toHaveBeenCalledTimes(1);
  });

  it('a step-up prompt inside the app never signs the user out for picking the wrong passkey', async () => {
    build(false, notRegistered());

    for (let i = 0; i < 3; i++) {
      prompt.isUnlocking = false;
      await prompt.authenticate();
      await settle();
    }

    expect(accountClosed.leave).not.toHaveBeenCalled();
    expect(revealed).toHaveBeenCalledTimes(3);
  });
});
