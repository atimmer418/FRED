import { Location } from '@angular/common';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { RecoveryPage } from './recovery.page';
import { RecoveryService } from '../../services/recovery.service';
import { PasskeyService } from '../../services/passkey.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';

describe('RecoveryPage toasts', () => {
  let recoveryService: jasmine.SpyObj<RecoveryService>;
  let passkeyService: jasmine.SpyObj<PasskeyService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let page: RecoveryPage;
  let router: jasmine.SpyObj<Router>;
  let authService: jasmine.SpyObj<AuthService>;

  /** Lets the promise chain inside createPasskey run to its end. */
  const settle = async () => { for (let i = 0; i < 5; i++) { await Promise.resolve(); } };

  beforeEach(() => {
    recoveryService = jasmine.createSpyObj<RecoveryService>('RecoveryService', ['initiateRecovery', 'verifyRecovery']);
    passkeyService = jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startRecoveryRegistration', 'finishRegistration']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    const location = jasmine.createSpyObj<Location>('Location', ['back']);
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['handleSuccessfulAuthentication']);

    page = new RecoveryPage(recoveryService, router, toastService, location, passkeyService, authService);
    page.email = 'test@example.com';
    page.otp = '123456';
  });

  it('sends the code with an informational toast, the default look', async () => {
    recoveryService.initiateRecovery.and.resolveTo({ success: true, message: '' });

    await page.sendCode();

    expect(page.step).toBe('otp');
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Recovery code sent. Check your email.');
  });

  it('marks a refused recovery as danger, carrying the server message', async () => {
    recoveryService.initiateRecovery.and.resolveTo({ success: false, message: 'No account for that email.' });

    await page.sendCode();

    expect(page.step).toBe('email');
    expect(toastService.showToast).toHaveBeenCalledOnceWith('No account for that email.', 'danger');
  });

  it('marks a recovery request that threw as danger', async () => {
    recoveryService.initiateRecovery.and.rejectWith(new Error('offline'));

    await page.sendCode();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('An error occurred. Please try again.', 'danger');
    expect(page.isLoading).toBeFalse();
  });

  it('marks a wrong code as danger', async () => {
    recoveryService.verifyRecovery.and.resolveTo({ success: false, message: 'Code expired.' });

    await page.verifyCode();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Code expired.', 'danger');
    expect(page.isLoading).toBeFalse();
  });

  it('marks a verification that threw as danger', async () => {
    recoveryService.verifyRecovery.and.rejectWith(new Error('offline'));

    await page.verifyCode();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Invalid code or error occurred.', 'danger');
  });

  it('shows the server message when a code request is refused on purpose', async () => {
    recoveryService.initiateRecovery.and.rejectWith({ status: 429, error: { success: false, message: 'Too many attempts. Please wait a while and try again.' } });

    await page.sendCode();

    expect(page.step).toBe('email');
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Too many attempts. Please wait a while and try again.', 'danger');
  });

  it('shows the server message for a wrong code', async () => {
    page.step = 'otp';
    recoveryService.verifyRecovery.and.rejectWith({ status: 401, error: { success: false, message: 'That code is wrong or has expired. Request a new one.' } });

    await page.verifyCode();

    expect(page.step).toBe('otp');
    expect(toastService.showToast).toHaveBeenCalledOnceWith('That code is wrong or has expired. Request a new one.', 'danger');
    expect(passkeyService.startRecoveryRegistration).not.toHaveBeenCalled();
  });

  it('does not treat the recovery token as a session: nothing is stored as a sign-in', async () => {
    spyOn(JwtTokenUtils, 'storeJwtToken');
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', recoveryToken: 'rt' });
    passkeyService.startRecoveryRegistration.and.returnValue(throwError(() => new Error('no options')));

    await page.verifyCode();

    expect(JwtTokenUtils.storeJwtToken).not.toHaveBeenCalled();
    expect(passkeyService.startRecoveryRegistration).toHaveBeenCalledOnceWith('rt');
  });

  it('marks a passkey registration that could not start as danger, and keeps the passkey step for a retry', async () => {
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', recoveryToken: 'rt' });
    passkeyService.startRecoveryRegistration.and.returnValue(throwError(() => new Error('no options')));

    await page.verifyCode();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Could not start passkey registration. Try again.', 'danger');
    expect(page.isLoading).toBeFalse();
    expect(page.step).toBe('passkey');
    expect(page.otp).toBe('');

    // The retry button uses the same recovery token; the code is not asked for again.
    page.createPasskey();
    expect(passkeyService.startRecoveryRegistration).toHaveBeenCalledTimes(2);
    expect(recoveryService.verifyRecovery).toHaveBeenCalledTimes(1);
  });

  it('goes back to the start when the recovery token has run out', async () => {
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', recoveryToken: 'rt' });
    passkeyService.startRecoveryRegistration.and.returnValue(throwError(() => ({ status: 401 })));

    await page.verifyCode();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Your recovery session has ended. Please start again.', 'danger');
    expect(page.step).toBe('email');

    // Nothing left to retry with.
    page.createPasskey();
    expect(passkeyService.startRecoveryRegistration).toHaveBeenCalledTimes(1);
  });

  it('says so, and does not start over, when the account already has its new passkey', async () => {
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', recoveryToken: 'rt' });
    passkeyService.startRecoveryRegistration.and.returnValue(throwError(() => ({ status: 403 })));

    await page.verifyCode();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('This account already has a passkey. Go back and sign in with it.', 'danger');
    expect(page.step).toBe('passkey');
  });

  it('sends the recovery token with the new passkey and signs the user in', async () => {
    const credential = { id: 'new-passkey' };
    (page as any).promptForPasskey = jasmine.createSpy('promptForPasskey').and.resolveTo(credential);
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', recoveryToken: 'rt' });
    passkeyService.startRecoveryRegistration.and.returnValue(of({ options: '{"challenge":"abc"}' } as any));
    passkeyService.finishRegistration.and.returnValue(of({ success: true, message: '', jwtToken: 'jwt', userId: 5, email: 'test@example.com' } as any));

    await page.verifyCode();
    await settle();

    expect((page as any).promptForPasskey).toHaveBeenCalledOnceWith({ challenge: 'abc' });
    expect(passkeyService.finishRegistration).toHaveBeenCalledOnceWith({ email: 'test@example.com', credential, recoveryToken: 'rt' });
    expect(authService.handleSuccessfulAuthentication).toHaveBeenCalledOnceWith('jwt', 5, 'test@example.com');
    expect(router.navigate).toHaveBeenCalledOnceWith(['/tabs/tab3']);
    expect(page.isLoading).toBeFalse();

    // The recovery token is used up: there is nothing to register a second passkey with.
    page.createPasskey();
    expect(passkeyService.startRecoveryRegistration).toHaveBeenCalledTimes(1);
  });

  it('lets a cancelled passkey prompt be tried again with the same recovery token', async () => {
    const prompt = jasmine.createSpy('promptForPasskey').and.returnValues(Promise.reject(new Error('cancelled')), Promise.resolve({ id: 'pk' }));
    (page as any).promptForPasskey = prompt;
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', recoveryToken: 'rt' });
    passkeyService.startRecoveryRegistration.and.returnValue(of({ options: '{}' } as any));
    passkeyService.finishRegistration.and.returnValue(of({ success: false, message: 'Passkey registration failed: bad attestation' } as any));

    await page.verifyCode();
    await settle();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Passkey registration was cancelled. Please try again.', 'danger');
    expect(page.step).toBe('passkey');
    expect(page.isLoading).toBeFalse();
    expect(passkeyService.finishRegistration).not.toHaveBeenCalled();

    page.createPasskey();
    await settle();

    expect(passkeyService.startRecoveryRegistration.calls.allArgs()).toEqual([['rt'], ['rt']]);
    expect(passkeyService.finishRegistration).toHaveBeenCalledOnceWith({ email: 'test@example.com', credential: { id: 'pk' }, recoveryToken: 'rt' });
    expect(toastService.showToast).toHaveBeenCalledWith('Passkey registration failed: bad attestation', 'danger');
    expect(page.step).toBe('passkey');
    expect(recoveryService.verifyRecovery).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the code is accepted but no recovery token comes back', async () => {
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: 'Recovered.' });

    await page.verifyCode();

    expect(passkeyService.startRecoveryRegistration).not.toHaveBeenCalled();
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Recovered.', 'danger');
  });
});
