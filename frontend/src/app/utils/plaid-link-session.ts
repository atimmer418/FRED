/**
 * Plaid OAuth round trip. When a bank uses OAuth, Link leaves the app for the bank's page and the
 * universal link brings the user back with ?oauth_state_id=... in the URL. Link must then be
 * re-created with the SAME link_token it left with, so the token is parked in sessionStorage for
 * the duration of one Link session. Nothing else is stored: no public token, no account data.
 */
export const PLAID_LINK_TOKEN_KEY = 'fred.plaid.linkToken';
export const PLAID_LINK_RETURN_KEY = 'fred.plaid.linkReturn';

export type PlaidLinkReturnTo = 'onboarding' | 'change-bank';

export function storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo): void {
  try {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, linkToken);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, returnTo);
  } catch {
    // Storage blocked: the OAuth return starts Link over with a fresh token.
  }
}

export function readPlaidLinkToken(): string | null {
  try {
    return sessionStorage.getItem(PLAID_LINK_TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Which page stored the current token, or null if none is stored. A page must check this before
 * resuming with a stored token: onboarding and change-bank share one sessionStorage slot, so a
 * token left behind by one flow must never be reused by the other.
 */
export function readPlaidLinkReturn(): PlaidLinkReturnTo | null {
  try {
    return sessionStorage.getItem(PLAID_LINK_RETURN_KEY) as PlaidLinkReturnTo | null;
  } catch {
    return null;
  }
}

export function clearPlaidLinkSession(): void {
  try {
    sessionStorage.removeItem(PLAID_LINK_TOKEN_KEY);
    sessionStorage.removeItem(PLAID_LINK_RETURN_KEY);
  } catch {
    // Nothing to clear.
  }
}
