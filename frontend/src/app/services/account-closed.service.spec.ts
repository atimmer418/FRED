import { Injector } from '@angular/core';
import { Router } from '@angular/router';
import { ModalController } from '@ionic/angular/standalone';
import { AccountClosedService } from './account-closed.service';
import { AuthService } from './auth.service';
import { AppLockService } from './app-lock.service';
import { KeychainSyncService } from './keychain-sync.service';

describe('AccountClosedService', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let appLock: jasmine.SpyObj<AppLockService>;
  let router: jasmine.SpyObj<Router>;
  let keychain: jasmine.SpyObj<KeychainSyncService>;
  let modals: jasmine.SpyObj<ModalController>;
  let service: AccountClosedService;
  let order: string[];

  beforeEach(() => {
    order = [];
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['logout', 'clearLocalStorageProgressFlags']);
    auth.logout.and.callFake(() => { order.push('logout'); });
    appLock = jasmine.createSpyObj<AppLockService>('AppLockService', ['hideAllCovers']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    router.navigateByUrl.and.callFake(async () => { order.push('navigate'); return true; });
    keychain = jasmine.createSpyObj<KeychainSyncService>('KeychainSyncService', ['clearAccountEmail']);
    keychain.clearAccountEmail.and.callFake(async () => { order.push('keychain'); return true; });
    modals = jasmine.createSpyObj<ModalController>('ModalController', ['getTop']);
    modals.getTop.and.resolveTo(undefined);

    const injector = {
      get: (token: unknown) => {
        if (token === AuthService) return auth;
        if (token === AppLockService) return appLock;
        if (token === Router) return router;
        if (token === KeychainSyncService) return keychain;
        if (token === ModalController) return modals;
        throw new Error('unexpected token');
      },
    } as unknown as Injector;
    service = new AccountClosedService(injector);

    spyOn(Storage.prototype, 'clear').and.callFake(() => { order.push('storage'); });
  });

  it('a closed account is signed out in memory first, then wiped from the device, then shown the closed screen', async () => {
    await service.leave('closed');

    // logout() must come before the wipe: it resets the analytics identity, which needs storage.
    expect(order).toEqual(['logout', 'storage', 'storage', 'keychain', 'navigate']);
    expect(appLock.hideAllCovers).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/account-closed', { replaceUrl: true });
  });

  it('wipes storage before it waits for anything, so the failed request cannot rebuild "set up" progress from it', () => {
    // No await: only what has happened by the time leave() first yields.
    void service.leave('closed');

    // The Keychain call is the first thing waited for; storage is already clean when it starts.
    expect(order).toEqual(['logout', 'storage', 'storage', 'keychain']);
  });

  it('says it is leaving until the closed screen is showing', async () => {
    expect(service.isLeaving).toBeFalse();
    const leaving = service.leave('closed');
    expect(service.isLeaving).toBeTrue();
    await leaving;
    expect(service.isLeaving).toBeFalse();
  });

  it('an unknown passkey signs the device out but does not wipe it, and offers recovery', async () => {
    await service.leave('passkey');

    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(keychain.clearAccountEmail).not.toHaveBeenCalled();
    expect(Storage.prototype.clear).not.toHaveBeenCalled();
    // The saved onboarding position goes, or the next launch would route a signed-out user in.
    expect(auth.clearLocalStorageProgressFlags).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/account-closed?reason=passkey', { replaceUrl: true });
  });

  it('closes the lock screen and any sheet above the page before showing the closed screen', async () => {
    const lockScreen = { dismiss: jasmine.createSpy('dismiss').and.callFake(async () => { order.push('dismiss'); return true; }) };
    const sheet = { dismiss: jasmine.createSpy('dismiss').and.callFake(async () => { order.push('dismiss'); return true; }) };
    modals.getTop.and.returnValues(
      Promise.resolve(sheet as unknown as HTMLIonModalElement),
      Promise.resolve(lockScreen as unknown as HTMLIonModalElement),
      Promise.resolve(undefined),
    );

    await service.leave('closed');

    expect(sheet.dismiss).toHaveBeenCalledOnceWith({ signedOut: true });
    expect(lockScreen.dismiss).toHaveBeenCalledOnceWith({ signedOut: true });
    expect(order.indexOf('navigate')).toBeGreaterThan(order.lastIndexOf('dismiss'));
  });

  it('several refused requests at once sign out only once', async () => {
    await Promise.all([service.leave('closed'), service.leave('closed'), service.leave('closed')]);

    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
  });

  it('still reaches the closed screen when the Keychain or storage fails', async () => {
    keychain.clearAccountEmail.and.rejectWith(new Error('keychain unavailable'));
    (Storage.prototype.clear as jasmine.Spy).and.throwError('storage unavailable');

    await service.leave('closed');

    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/account-closed', { replaceUrl: true });
  });
});
