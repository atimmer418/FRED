import { Location } from '@angular/common';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
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

  beforeEach(() => {
    recoveryService = jasmine.createSpyObj<RecoveryService>('RecoveryService', ['initiateRecovery', 'verifyRecovery']);
    passkeyService = jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startRecoveryRegistration', 'finishRegistration']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    const location = jasmine.createSpyObj<Location>('Location', ['back']);
    const authService = jasmine.createSpyObj<AuthService>('AuthService', ['handleSuccessfulAuthentication']);

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

  it('marks a passkey registration that could not start as danger', async () => {
    spyOn(JwtTokenUtils, 'storeJwtToken');
    recoveryService.verifyRecovery.and.resolveTo({ success: true, message: '', token: 'jwt' });
    passkeyService.startRecoveryRegistration.and.returnValue(throwError(() => new Error('no options')));

    await page.verifyCode();

    expect(JwtTokenUtils.storeJwtToken).toHaveBeenCalledWith('jwt');
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Could not start passkey registration. Try again.', 'danger');
    expect(page.isLoading).toBeFalse();
  });
});
