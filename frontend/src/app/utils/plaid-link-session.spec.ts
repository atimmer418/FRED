import {
  PLAID_LINK_RETURN_KEY,
  PLAID_LINK_TOKEN_KEY,
  PLAID_OAUTH_RETURN_HREF_KEY,
  clearPlaidLinkSession,
  plaidOAuthReturnRoute,
  plaidOAuthStateId,
  readPlaidOAuthReturnHref,
  storePlaidLinkSession,
  storePlaidOAuthReturnHref,
} from './plaid-link-session';

/** The one redirect URI registered for the local environment (R9), as the bank sends the user back. */
const LINK_BANK_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';

describe('plaid-link-session OAuth return helpers', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  it('stores and reads the OAuth return href, and clearPlaidLinkSession removes it with the session', () => {
    expect(readPlaidOAuthReturnHref()).toBeNull();
    storePlaidLinkSession('link-token', 'change-bank');
    storePlaidOAuthReturnHref(LINK_BANK_HREF);

    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBe(LINK_BANK_HREF);
    expect(readPlaidOAuthReturnHref()).toBe(LINK_BANK_HREF);

    clearPlaidLinkSession();

    expect(readPlaidOAuthReturnHref()).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('reads the oauth_state_id, and null when there is none or the href is not a URL', () => {
    expect(plaidOAuthStateId(LINK_BANK_HREF)).toBe('abc123');
    expect(plaidOAuthStateId('https://local.fredvested.com/link-bank')).toBeNull();
    expect(plaidOAuthStateId('not a url')).toBeNull();
  });

  it('routes a change-bank return to /change-bank-account with the whole query string kept', () => {
    storePlaidLinkSession('link-token', 'change-bank');

    expect(plaidOAuthReturnRoute('https://local.fredvested.com/link-bank?oauth_state_id=abc123&x=1'))
      .toBe('/change-bank-account?oauth_state_id=abc123&x=1');
  });

  it('routes an onboarding return back to /link-bank with the query string kept', () => {
    storePlaidLinkSession('link-token', 'onboarding');

    expect(plaidOAuthReturnRoute(LINK_BANK_HREF)).toBe('/link-bank?oauth_state_id=abc123');
  });

  it('is not a return without a stored token, without oauth_state_id, or with an unknown marker', () => {
    expect(plaidOAuthReturnRoute(LINK_BANK_HREF)).toBeNull();

    storePlaidLinkSession('link-token', 'change-bank');
    expect(plaidOAuthReturnRoute('https://local.fredvested.com/link-bank')).toBeNull();

    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'settings');
    expect(plaidOAuthReturnRoute(LINK_BANK_HREF)).toBeNull();
  });
});
