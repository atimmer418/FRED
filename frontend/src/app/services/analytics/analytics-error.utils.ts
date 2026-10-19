import { HttpErrorResponse } from '@angular/common/http';

/**
 * Pulls the HTTP status off whatever a failing call handed us, and nothing else.
 *
 * A status code is bounded and safe. The message beside it is not: server messages are unbounded,
 * and the app's own copy interpolates names, bank names, amounts and transfer ids. Call sites use
 * this so there is no reason to reach into the error object themselves.
 *
 * Returns null when there is no status, including Angular's status 0 for a connection that never
 * reached a server.
 */
export function httpStatusOf(error: unknown): number | null {
  if (error instanceof HttpErrorResponse) {
    return error.status > 0 ? error.status : null;
  }

  const status = (error as { status?: unknown } | null | undefined)?.status;
  return typeof status === 'number' && status > 0 ? status : null;
}

/**
 * True when a failure looks like the network rather than the server: no status at all, or
 * Angular's 0. Call sites use it to pick between the 'network' and 'save_failed'/'load_failed'
 * categories rather than guessing from the message.
 */
export function isNetworkFailure(error: unknown): boolean {
  if (error instanceof HttpErrorResponse) {
    return error.status === 0;
  }
  const status = (error as { status?: unknown } | null | undefined)?.status;
  return status === 0 || status === undefined || status === null;
}
