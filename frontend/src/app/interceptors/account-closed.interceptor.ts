import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { AccountClosedService } from '../services/account-closed.service';

/** What the server puts in a 401 body when the token's account is closed. */
export const ACCOUNT_CLOSED_CODE = 'ACCOUNT_CLOSED';

/**
 * Watches every answer from the FRED server for one thing: a 401 that says the account is closed.
 *
 * That is different from a session that has merely ended, which is still handled by asking for the
 * passkey. For a closed account there is nothing to sign in to, so the device is signed out and
 * shown the closed screen. The error itself is passed on untouched, so callers behave as before.
 */
export const accountClosedInterceptor: HttpInterceptorFn = (req, next) => {
  const injector = inject(Injector);
  return next(req).pipe(
    tap({
      error: (err: unknown) => {
        if (
          err instanceof HttpErrorResponse &&
          err.status === 401 &&
          err.error?.code === ACCOUNT_CLOSED_CODE &&
          req.url.startsWith(environment.backendApiUrl)
        ) {
          void injector.get(AccountClosedService).leave('closed');
        }
      },
    }),
  );
};
