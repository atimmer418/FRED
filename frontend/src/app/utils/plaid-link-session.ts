/**
 * Plaid OAuth round trip. When a bank uses OAuth, Link leaves the app for the bank's page and the
 * bank sends the user back to the redirect URI with ?oauth_state_id=... in the URL. Link must then
 * be re-created with the SAME link_token it left with, so the token is parked in sessionStorage for
 * the duration of one Link session, with a marker naming the page that started it.
 *
 * Every link token names one redirect URI per environment, and it ends in /link-bank, so a
 * change-bank return lands on /link-bank and is routed on to /change-bank-account. The href it
 * landed on is parked too, because that is the URL Link must be told it was redirected to.
 * Nothing else is stored: no public token, no account data. The href carries only Plaid's
 * one-time oauth_state_id.
 */
export const PLAID_LINK_TOKEN_KEY = 'fred.plaid.linkToken';
export const PLAID_LINK_RETURN_KEY = 'fred.plaid.linkReturn';
export const PLAID_LINK_MODE_KEY = 'fred.plaid.linkMode';
export const PLAID_OAUTH_RETURN_HREF_KEY = 'fred.plaid.oauthReturnHref';

export type PlaidLinkReturnTo = 'onboarding' | 'change-bank';

/**
 * The mode the parked token was created with (spec 2.3). An update-mode token must finish with
 * POST /plaid/update-mode-complete, never an exchange, and the OAuth return URL does not say which.
 */
export type PlaidLinkMode = 'link' | 'update';

export function storePlaidLinkSession(linkToken: string, returnTo: PlaidLinkReturnTo, mode: PlaidLinkMode = 'link'): void {
  try {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, linkToken);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, returnTo);
    sessionStorage.setItem(PLAID_LINK_MODE_KEY, mode);
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

/** The stored mode; anything but an explicit 'update', including nothing stored, is 'link'. */
export function readPlaidLinkMode(): PlaidLinkMode {
  try {
    return sessionStorage.getItem(PLAID_LINK_MODE_KEY) === 'update' ? 'update' : 'link';
  } catch {
    return 'link';
  }
}

/** Parks the full URL the bank's OAuth redirect landed on, for the page that resumes Link. */
export function storePlaidOAuthReturnHref(href: string): void {
  try {
    sessionStorage.setItem(PLAID_OAUTH_RETURN_HREF_KEY, href);
  } catch {
    // Storage blocked: the resuming page falls back to its own URL.
  }
}

export function readPlaidOAuthReturnHref(): string | null {
  try {
    return sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY);
  } catch {
    return null;
  }
}

/** Plaid's oauth_state_id on the given href, or null when it has none or is not a URL. */
export function plaidOAuthStateId(href: string): string | null {
  try {
    return new URL(href).searchParams.get('oauth_state_id');
  } catch {
    return null;
  }
}

/**
 * Where an OAuth return belongs: the page whose Link session is stored, with the redirect's whole
 * query string kept. Null when the href carries no oauth_state_id, no token is stored, or the
 * marker names no page.
 */
export function plaidOAuthReturnRoute(href: string): string | null {
  if (plaidOAuthStateId(href) === null || readPlaidLinkToken() === null) {
    return null;
  }
  const search = new URL(href).search;
  switch (readPlaidLinkReturn()) {
    case 'change-bank':
      return '/change-bank-account' + search;
    case 'onboarding':
      return '/link-bank' + search;
    default:
      return null;
  }
}

export function clearPlaidLinkSession(): void {
  try {
    sessionStorage.removeItem(PLAID_LINK_TOKEN_KEY);
    sessionStorage.removeItem(PLAID_LINK_RETURN_KEY);
    sessionStorage.removeItem(PLAID_LINK_MODE_KEY);
    sessionStorage.removeItem(PLAID_OAUTH_RETURN_HREF_KEY);
  } catch {
    // Nothing to clear.
  }
}
