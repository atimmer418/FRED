import { Injectable, Injector } from '@angular/core';
import { Router } from '@angular/router';
import { ModalController } from '@ionic/angular/standalone';
import { AuthService } from './auth.service';
import { AppLockService } from './app-lock.service';
import { KeychainSyncService } from './keychain-sync.service';

/**
 * Why the device is being signed out for good:
 *  - 'closed': the account is closed (this device closed it, or the server said so).
 *  - 'passkey': the lock screen offered a passkey the server no longer has. The account may be
 *    closed, or its passkeys may have been replaced by account recovery.
 */
export type SignedOutReason = 'closed' | 'passkey';

/** The route a signed-out device lands on. AppComponent leaves a user on it alone. */
export const ACCOUNT_CLOSED_ROUTE = '/account-closed';

/**
 * The one way out of the app for an account that cannot come back in.
 *
 * A closed account used to be left half signed in: storage was cleared but the app still believed
 * it was logged in and fully set up, so the next resume sent it back to the tabs, and a second
 * device sat on a lock screen that could never be passed. Everything that ends an account's time
 * on a device now comes through here, so memory, storage, the Keychain email, any open lock
 * screen and the route are all dealt with together.
 */
@Injectable({ providedIn: 'root' })
export class AccountClosedService {
  private leaving: Promise<void> | null = null;

  // Resolved lazily: AuthService needs HttpClient, and this service is reached from an HTTP
  // interceptor, so asking for it in the constructor would be a circular dependency.
  constructor(private injector: Injector) {}

  /**
   * True from the start of a sign-out until the closed screen is showing. AppComponent reads it so
   * its own "signed out, go to Get Started" rule does not get there first.
   */
  get isLeaving(): boolean {
    return this.leaving !== null;
  }

  /** Signs this device out and shows the closed screen. Calls made while one is running join it. */
  leave(reason: SignedOutReason): Promise<void> {
    if (!this.leaving) {
      this.leaving = this.run(reason).finally(() => { this.leaving = null; });
    }
    return this.leaving;
  }

  private async run(reason: SignedOutReason): Promise<void> {
    const auth = this.injector.get(AuthService);
    const appLock = this.injector.get(AppLockService);
    const router = this.injector.get(Router);

    // First, and before storage is wiped: logout() resets the analytics identity (which needs its
    // own storage entries to still be there) and tells every in-memory store the user is gone.
    auth.logout();

    // Everything up to the first `await` runs before the failed request's own error handler does.
    // That handler rebuilds "progress" from storage, so storage must already be clean by then,
    // or a signed-out device would be left holding "fully set up" and be routed into the tabs.
    try {
      if (reason === 'closed') {
        // The account is gone, so nothing of it should stay on the device.
        localStorage.clear();
        sessionStorage.clear();
      } else {
        // The account may still exist (recovery is offered), so the device is not wiped. Only the
        // saved onboarding position goes: with nobody signed in, it would send the next launch
        // into the middle of the app.
        auth.clearLocalStorageProgressFlags();
      }
    } catch {
      // Storage can be unavailable; the in-memory sign-out above still holds.
    }

    if (reason === 'closed') {
      // The email in the Keychain would otherwise make every later launch look for a passkey
      // that cannot work.
      try {
        await this.injector.get(KeychainSyncService).clearAccountEmail();
      } catch {
        // Not worth blocking the exit for.
      }
    }

    await this.dismissOverlays();
    appLock.hideAllCovers();
    const url = reason === 'passkey' ? `${ACCOUNT_CLOSED_ROUTE}?reason=passkey` : ACCOUNT_CLOSED_ROUTE;
    try {
      await router.navigateByUrl(url, { replaceUrl: true });
    } catch {
      // A failed navigation still leaves the user signed out; the next launch lands on Get Started.
    }
  }

  /** Closes the lock screen and any sheet above the page, so the closed screen is not hidden. */
  private async dismissOverlays(): Promise<void> {
    const modals = this.injector.get(ModalController);
    for (let i = 0; i < 5; i++) {
      try {
        const top = await modals.getTop();
        if (!top) return;
        await top.dismiss({ signedOut: true });
      } catch {
        return;
      }
    }
  }
}
