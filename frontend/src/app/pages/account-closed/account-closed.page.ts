import { Component } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { IonContent, IonFooter } from '@ionic/angular/standalone';

/**
 * Where a device lands when its account cannot come back in.
 *
 * Two versions of the same screen:
 *  - the account is closed (the default);
 *  - `?reason=passkey`: the passkey on this device is no longer linked to an account. The account
 *    may be closed, or its passkeys may have been replaced by account recovery, so this version
 *    also offers recovery.
 *
 * The user is signed out by the time they are here. See AccountClosedService.
 */
@Component({
  selector: 'app-account-closed',
  templateUrl: './account-closed.page.html',
  styleUrls: ['./account-closed.page.scss'],
  standalone: true,
  imports: [IonContent, IonFooter],
})
export class AccountClosedPage {
  readonly passkeyUnknown: boolean;

  constructor(private router: Router, route: ActivatedRoute) {
    this.passkeyUnknown = route.snapshot.queryParamMap.get('reason') === 'passkey';
  }

  startOver(): void {
    this.router.navigateByUrl('/get-started', { replaceUrl: true });
  }

  recover(): void {
    this.router.navigateByUrl('/recovery');
  }
}
